package com.myclick.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.WindowManager
import com.myclick.core.Config
import com.myclick.fx.EffectEngine
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * shell 进程里的覆盖层渲染器。
 *
 * **为什么不用 WindowManager**：ColorOS 对 `TYPE_APPLICATION_OVERLAY` 窗口会弹
 * 「正在当前页面悬浮显示」提示；而 `app_process` 起的进程没有在 AMS 注册，
 * `IWindowManager.openSession()` 会直接抛 `Unknown pid / uid`，连窗口都建不出来
 * （真机 Android 16 / ColorOS 17 实测）。
 *
 * **为什么 SurfaceControl 可以**：它绕开窗口系统，直接向 SurfaceFlinger 要一个
 * root 图层。shell 身份持有 `ACCESS_SURFACE_FLINGER`，建层、设 z、show 全部通过；
 * 因为没有窗口、没有 AppOps 操作，系统不会提示任何「悬浮窗」信息。
 *
 * 坐标系：buffer 用屏幕物理像素，canvas 按 density 缩放，于是绘制单位仍是 dp，
 * 和 App 内 [com.myclick.fx.EffectSurfaceView] 完全一致（连粒子尺寸都不用改）。
 */
class ShellFxRenderer(context: Context) {

    private val engine = EffectEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var surfaceControl: SurfaceControl? = null
    private var surface: Surface? = null

    private var bufW = 0
    private var bufH = 0
    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    /** buffer 相对 dp 的倍率（= min(density, 2)，可关闭上限） */
    private var dpr = 1f

    /**
     * 是否允许「小 buffer + setScale 放大」。
     *
     * 实测 `SurfaceControl.setScale` 的缩放锚点不是左上角（把 buffer 缩到 1584×727
     * 再放大 1.75 倍后，点击点画的圆整体偏到别处），会直接导致特效错位，
     * 所以默认关闭、只用 1:1 的全屏 buffer。要重新启用请先解决锚点补偿。
     */
    private var matrixSupported = false

    private var config: Config = Config.default()
    private var uiMode = 0

    /** 是否正在录屏（由 [ShellRecorderState] 写入） */
    @Volatile
    private var recording = false

    private var running = false
    private var shown = false

    private var lastFrameNanos = 0L
    private var speed = 1f
    private var frameCount = 0

    /** 单帧绘制耗时累计（微秒），空闲时打印，用来判断卡顿是否来自这里 */
    private var frameCostUs = 0L

    /**
     * 渲染节拍用 Handler 而不是 Choreographer。
     *
     * app_process 起的进程没有走 ActivityThread 的正常绑定流程，Choreographer 的
     * vsync 回调在这里收不到（真机实测：postFrameCallback 永远不回调，图层一片空白）。
     * 固定 16ms 的 Handler 节拍不依赖 vsync，效果一样且更可控。
     */
    private val frameRunnable = Runnable { tick() }

    // ---------------- 对外入口（都必须跑在主线程） ----------------

    fun applyConfig(cfg: Config, uMode: Int) {
        config = cfg
        uiMode = uMode
        engine.update(cfg, uMode)
        speed = engine.render.speed
        if (!cfg.enabled) {
            engine.clear()
            hideSoon()
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        if (!on && !config.globalEffect && config.gateMode != Config.GATE_ALWAYS) {
            // 停录后立刻收尾，避免残留粒子在桌面上飘
            engine.clear()
            hideSoon()
        }
    }

    fun emit(xPx: Float, yPx: Float) {
        if (!config.enabled) {
            println("FX tap dropped: disabled")
            return
        }
        if (!config.globalEffect && config.gateMode != Config.GATE_ALWAYS && !recording) {
            println("FX tap dropped: idle")
            return
        }
        if (!ensureSurface()) {
            println("FX tap dropped: no surface")
            return
        }
        val d = density.coerceAtLeast(0.5f)
        engine.spawn(xPx / d, yPx / d)
        println("FX tap $xPx,$yPx -> ${xPx / d},${yPx / d} dpr=$d active=${engine.hasActive}")
        if (!running) {
            frameCount = 0
            startLoop()
        }
    }

    fun release() {
        running = false
        engine.clear()
        releaseSurface()
    }

    // ---------------- 图层生命周期 ----------------

    /** 确保图层存在且尺寸与当前屏幕一致；旋转 / 分辨率变化时会重建 */
    private fun ensureSurface(): Boolean {
        val metrics = currentMetrics() ?: return false
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val d = metrics.density
        if (w <= 0 || h <= 0) return false
        val nd = if (config.dprCapEnabled) min(d, 2f) else d
        // 不支持 setMatrix 时 buffer 只能按物理像素，别把 nd 当目标值反复重建
        val target = if (matrixSupported) nd else d
        if (surface != null && w == screenW && h == screenH && abs(target - dpr) < 0.01f) return true
        rebuild(w, h, d, nd)
        return surface != null
    }

    private fun currentMetrics(): DisplayMetrics? {
        val display = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: runCatching { windowManager?.defaultDisplay }.getOrNull()
            ?: return null
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        return m
    }

    /**
     * 建图层。
     *
     * buffer 尺寸 = 屏幕 dp × dpr（默认上限 2x），而不是屏幕物理像素 ——
     * 后者在 1272×2772 / 3.5dpi 的机器上一帧就是 13MB，每帧都要软件 CLEAR + 上传，
     * 点击后那 0.4 秒会明显抢 CPU，录屏时更严重（表现成「点完点一下要卡一会儿才恢复」）。
     * 用 dpr 后 buffer 小 3 倍多，再通过 setMatrix 放大回全屏。
     */
    private fun rebuild(w: Int, h: Int, d: Float, nd: Float) {
        releaseSurface()
        val useScale = matrixSupported && nd < d - 0.01f
        val bw = if (useScale) (w / d * nd).roundToInt().coerceAtLeast(1) else w
        val bh = if (useScale) (h / d * nd).roundToInt().coerceAtLeast(1) else h
        try {
            val c = SurfaceControl.Builder()
                .setName(LAYER_NAME)
                .setBufferSize(bw, bh)
                .setFormat(PixelFormat.TRANSLUCENT)
                .build()
            val s = Surface(c)
            val tx = SurfaceControl.Transaction()
                .setLayer(c, LAYER_Z)
                .setVisibility(c, false)
            // setLayerStack 是隐藏 API，反射调用；失败就退化成默认（挂在所有 display 上）
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(tx, c, Display.DEFAULT_DISPLAY)
            }
            if (useScale) {
                val k = d / nd
                val err = runCatching {
                    SurfaceControl.Transaction::class.java
                        .getMethod(
                            "setScale",
                            SurfaceControl::class.java,
                            Float::class.javaPrimitiveType,
                            Float::class.javaPrimitiveType
                        )
                        .invoke(tx, c, k, k)
                }.exceptionOrNull()
                if (err != null) {
                    // 某些版本没有这个方法：放弃缩放，退回全屏 buffer
                    val names = runCatching {
                        SurfaceControl.Transaction::class.java.methods
                            .filter { it.name.contains("atrix") || it.name.contains("cale") }
                            .joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }
                    }.getOrDefault("?")
                    println("FX setScale 失败: $err；候选=$names")
                    matrixSupported = false
                    runCatching { tx.close() }
                    runCatching { s.release() }
                    runCatching { c.release() }
                    rebuild(w, h, d, d)
                    return
                }
            }
            tx.apply()
            surfaceControl = c
            surface = s
            screenW = w
            screenH = h
            bufW = bw
            bufH = bh
            density = d
            dpr = if (useScale) nd else d
            shown = false
            clearBuffer()
            println("FX surface screen=${w}x$h buffer=${bw}x$bh dpr=$dpr")
        } catch (t: Throwable) {
            println("FX rebuild failed: $t")
            surfaceControl = null
            surface = null
        }
    }

    private fun releaseSurface() {
        runCatching { surfaceControl?.let { newTxn().setVisibility(it, false).apply() } }
        runCatching { surface?.release() }
        runCatching { surfaceControl?.release() }
        surface = null
        surfaceControl = null
        shown = false
        bufW = 0
        bufH = 0
    }

    private fun newTxn() = SurfaceControl.Transaction()

    private fun showLayer() {
        val c = surfaceControl ?: return
        if (shown) return
        runCatching { newTxn().setVisibility(c, true).apply() }
        shown = true
    }

    private fun hideLayer() {
        val c = surfaceControl ?: return
        if (!shown) return
        runCatching { newTxn().setVisibility(c, false).apply() }
        shown = false
    }

    private fun hideSoon() {
        if (!running) {
            clearBuffer()
            hideLayer()
        }
    }

    // ---------------- 渲染循环 ----------------

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        showLayer()
        handler.post(frameRunnable)
    }

    private fun tick() {
        if (!running) return
        val now = System.nanoTime()
        val dtMs = if (lastFrameNanos == 0L) FRAME_MS else (now - lastFrameNanos) / 1_000_000f
        lastFrameNanos = now
        val clamped = dtMs.coerceIn(0f, MAX_DT_MS)
        engine.advance((clamped / FRAME_MS) * speed)
        val t0 = System.nanoTime()
        renderFrame()
        frameCostUs += (System.nanoTime() - t0) / 1000
        frameCount++
        if (!engine.hasActive) {
            running = false
            clearBuffer()
            hideLayer()
            val avg = if (frameCount > 0) frameCostUs / frameCount else 0
            println("FX idle after $frameCount frames, draw avg=${avg}us buffer=${bufW}x$bufH")
            frameCostUs = 0
            return
        }
        handler.postDelayed(frameRunnable, FRAME_MS.toLong())
    }

    private fun renderFrame() {
        val s = surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null) ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.save()
            canvas.scale(dpr, dpr)
            engine.draw(canvas)
            canvas.restore()
        } catch (t: Throwable) {
            // 单帧失败忽略，下一帧继续
        } finally {
            if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun clearBuffer() {
        val s = surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null) ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (t: Throwable) {
            // ignore
        } finally {
            if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    /** 供录制状态轮询线程切回主线程用 */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    private companion object {
        const val LAYER_NAME = "MYclick-ShellFx"

        /** 覆盖在应用之上、SystemUI 之下的一个足够高的 z（实测 2e6 可盖住桌面与全屏应用） */
        const val LAYER_Z = 2_000_000
        const val FRAME_MS = 1000f / 60f
        const val MAX_DT_MS = 50f
    }
}
