package com.myclick.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
 * **为什么要用一堆小图层而不是一张全屏图层**：一次点击的可见范围只有点击点周围
 * 约 90dp（弧最远到 ~58dp，火花飞出去 ~60dp），但全屏 buffer 在 1272×2772 上
 * 每帧要清 13MB、上传 13MB，SurfaceFlinger 还要整块合成一遍；录屏时那块虚拟屏
 * 再合成一次，等于「点一下之后 0.4 秒里系统带宽被吃掉一大块」—— 表现就是
 * **点完马上滑动会发涩**。改成每帧只提交 ~600×600（约 1.4MB）后合成面积降到
 * 十分之一。
 *
 * **为什么用 setPosition 而不是缩小 buffer 再 setScale**：`setScale` 的缩放锚点
 * 不是左上角（实测把 buffer 缩到 1584×727 再放大 1.75 倍后，圆整体偏到别处），
 * 而 `setPosition` 是明确的左上角偏移，位置精确。
 *
 * 坐标系：每个 slot 的 buffer 是屏幕物理像素的一块矩形，canvas 按 density 缩放，
 * 于是绘制单位仍是 dp，和 App 内 [com.myclick.fx.EffectSurfaceView] 完全一致。
 */
class ShellFxRenderer(context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * 图层槽位。一次点击占一个槽，独立引擎、独立 buffer、独立位置。
     *
     * 做成「多槽」而不是「单图层跟着最新点击移动」：连点时前一次点击的粒子还在飞，
     * 移动图层会让它们画到错误的位置。
     */
    private inner class Slot(val id: Int) {
        val engine = EffectEngine()
        var control: SurfaceControl? = null
        var surface: Surface? = null

        /** buffer 边长（物理像素，正方形） */
        var size = 0
        var originX = 0
        var originY = 0
        var shown = false

        /** 这个槽当前是否有粒子在播 */
        var active = false

        /** 最近一次使用的时间戳，槽不够用时踢掉最老的 */
        var lastUsed = 0L
    }

    private val slots = Array(SLOT_COUNT) { Slot(it) }

    private var screenW = 0
    private var screenH = 0
    private var density = 1f

    private var config: Config = Config.default()
    private var uiMode = 0

    /** 是否正在录屏（由 [ShellRecorderState] 写入） */
    @Volatile
    private var recording = false

    private var running = false
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
        speed = EffectEngine.RenderConfig.from(cfg, uMode).speed
        for (s in slots) s.engine.update(cfg, uMode)
        if (!cfg.enabled) {
            clearAll()
            hideIdle()
        }
    }

    fun setRecording(on: Boolean) {
        recording = on
        if (!on && !config.globalEffect && config.gateMode != Config.GATE_ALWAYS) {
            // 停录后立刻收尾，避免残留粒子在桌面上飘
            clearAll()
            hideIdle()
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
        if (!ensureLayout()) {
            println("FX tap dropped: no metrics")
            return
        }
        val radius = radiusPx()
        val size = (radius * 2).coerceAtMost(maxOf(screenW, screenH))
        val ox = (xPx - size / 2f).roundToInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
        val oy = (yPx - size / 2f).roundToInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
        val slot = pickSlot()
        if (!ensureLayer(slot, size, ox, oy)) {
            println("FX tap dropped: no layer")
            return
        }
        slot.engine.update(config, uiMode)
        val d = density.coerceAtLeast(0.5f)
        slot.engine.spawn(xPx / d, yPx / d)
        slot.active = true
        slot.lastUsed = System.nanoTime()
        println(
            "FX tap ${xPx.roundToInt()},${yPx.roundToInt()} slot=${slot.id} " +
                "layer=${size}x$size @$ox,$oy active=${slot.engine.hasActive}"
        )
        if (!running) {
            frameCount = 0
            frameCostUs = 0
            startLoop()
        }
    }

    fun release() {
        running = false
        for (s in slots) {
            s.engine.clear()
            releaseSlot(s)
        }
    }

    // ---------------- 屏幕尺寸 ----------------

    /** 屏幕尺寸 / 密度变了（旋转、分辨率切换）就整体重建 */
    private fun ensureLayout(): Boolean {
        val metrics = currentMetrics() ?: return false
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w <= 0 || h <= 0) return false
        if (w == screenW && h == screenH && density == metrics.density) return true
        // 尺寸变化：旧图层的 buffer 与位置全部作废
        running = false
        for (s in slots) {
            s.engine.clear()
            releaseSlot(s)
        }
        screenW = w
        screenH = h
        density = metrics.density
        println("FX layout ${w}x$h d=$density")
        return true
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
     * 一次点击需要的可见半径（物理像素）。
     *
     * 引擎里可见部分的最远距离：弧半径最大约 `26*scale + 2*scale*16`（再往后描边宽度
     * 趋零、看不见），火花 20 帧后 6.8*scale 的初速衰减到位移 ~60*scale。合计取
     * [EFFECT_RADIUS_DP] dp 留余量 —— 裁掉的只是几乎透明的最外圈。
     */
    private fun radiusPx(): Int {
        val s = config.scale.coerceIn(0.2f, 6f)
        val r = (EFFECT_RADIUS_DP * s * density).roundToInt()
        val cap = maxOf(screenW, screenH) / 2
        return r.coerceIn(64, cap.coerceAtLeast(64))
    }

    // ---------------- 图层生命周期 ----------------

    /** 优先空闲槽；都在播就踢最老的（连点超过 [SLOT_COUNT] 次时） */
    private fun pickSlot(): Slot {
        val idle = slots.firstOrNull { !it.active }
        if (idle != null) return idle
        return slots.minBy { it.lastUsed }
    }

    private fun ensureLayer(slot: Slot, size: Int, ox: Int, oy: Int): Boolean {
        if (slot.control == null || slot.size != size) {
            releaseSlot(slot)
            if (!buildLayer(slot, size)) return false
        }
        if (!slot.shown) {
            runCatching { newTxn().setVisibility(slot.control!!, true).apply() }
            slot.shown = true
        }
        if (slot.originX != ox || slot.originY != oy) {
            runCatching { newTxn().setPosition(slot.control!!, ox.toFloat(), oy.toFloat()).apply() }
            slot.originX = ox
            slot.originY = oy
        }
        // 不必在这里清屏：上一轮播完时 tick 已经 clear 过，且首帧自己会先 CLEAR 整块。
        // 每次点击多锁一次 canvas 是纯浪费（实测占单次点击渲染开销的 1/4）。
        return true
    }

    private fun buildLayer(slot: Slot, size: Int): Boolean {
        try {
            val c = SurfaceControl.Builder()
                .setName("$LAYER_NAME-${slot.id}")
                .setBufferSize(size, size)
                .setFormat(PixelFormat.TRANSLUCENT)
                .build()
            val s = Surface(c)
            val tx = newTxn()
                .setLayer(c, LAYER_Z + slot.id)
                .setVisibility(c, false)
                .setPosition(c, 0f, 0f)
            // setLayerStack 是 @hide，反射调用（进程入口已解 hidden API 限制）
            runCatching {
                SurfaceControl.Transaction::class.java
                    .getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                    .invoke(tx, c, Display.DEFAULT_DISPLAY)
            }
            tx.apply()
            slot.control = c
            slot.surface = s
            slot.size = size
            slot.originX = 0
            slot.originY = 0
            slot.shown = false
            // 新 buffer 内容未定义，先清一遍，避免首次显示闪出脏像素
            clearSlot(slot)
            return true
        } catch (t: Throwable) {
            println("FX build layer failed: $t")
            return false
        }
    }

    private fun releaseSlot(slot: Slot) {
        runCatching { slot.control?.let { newTxn().setVisibility(it, false).apply() } }
        runCatching { slot.surface?.release() }
        runCatching { slot.control?.release() }
        slot.surface = null
        slot.control = null
        slot.shown = false
        slot.size = 0
        slot.active = false
    }

    private fun clearAll() {
        for (s in slots) {
            s.engine.clear()
            s.active = false
            clearSlot(s)
        }
    }

    private fun hideIdle() {
        if (running) return
        for (s in slots) {
            if (!s.shown) continue
            runCatching {
                s.control?.let { newTxn().setVisibility(it, false).apply() }
            }
            s.shown = false
        }
    }

    private fun newTxn() = SurfaceControl.Transaction()

    // ---------------- 渲染循环 ----------------

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        handler.post(frameRunnable)
    }

    private fun tick() {
        if (!running) return
        val now = System.nanoTime()
        val dtMs = if (lastFrameNanos == 0L) FRAME_MS else (now - lastFrameNanos) / 1_000_000f
        lastFrameNanos = now
        val step = (dtMs.coerceIn(0f, MAX_DT_MS) / FRAME_MS) * speed

        var any = false
        val t0 = System.nanoTime()
        for (s in slots) {
            if (!s.active) continue
            s.engine.advance(step)
            if (s.engine.hasActive) {
                renderSlot(s)
                any = true
            } else {
                // 这一槽播完了：立刻收掉，别让它参与合成
                s.active = false
                clearSlot(s)
                hideSlot(s)
            }
        }
        frameCostUs += (System.nanoTime() - t0) / 1000
        frameCount++

        if (!any) {
            running = false
            val avg = if (frameCount > 0) frameCostUs / frameCount else 0
            println("FX idle after $frameCount frames, draw avg=${avg}us")
            frameCostUs = 0
            return
        }
        handler.postDelayed(frameRunnable, FRAME_MS.toLong())
    }

    private fun renderSlot(slot: Slot) {
        val s = slot.surface ?: return
        var canvas: Canvas? = null
        try {
            canvas = s.lockCanvas(null) ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.save()
            canvas.scale(density, density)
            // 粒子坐标是屏幕绝对 dp，减掉本槽原点后落到 buffer 内
            canvas.translate(-slot.originX / density, -slot.originY / density)
            slot.engine.draw(canvas)
            canvas.restore()
        } catch (t: Throwable) {
            // 单帧失败忽略，下一帧继续
        } finally {
            if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun clearSlot(slot: Slot) {
        val s = slot.surface ?: return
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

    private fun hideSlot(slot: Slot) {
        if (!slot.shown) return
        runCatching {
            slot.control?.let { newTxn().setVisibility(it, false).apply() }
        }
        slot.shown = false
    }

    /** 供录制状态轮询线程切回主线程用 */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    private companion object {
        const val LAYER_NAME = "MYclick-ShellFx"

        /** 覆盖在应用之上、SystemUI 之下的一个足够高的 z（实测 2e6 可盖住桌面与全屏应用） */
        const val LAYER_Z = 2_000_000

        /**
         * 一次点击需要的可见半径（dp，scale=1 时）。
         *
         * 实测可见内容集中在 60dp 内，90dp 已能完整覆盖弧与火花；再大只是白白增加
         * buffer 与合成面积。
         */
        const val EFFECT_RADIUS_DP = 90f

        /**
         * 同时可播的点击数量；超过就复用最老的槽。
         *
         * 一次特效约 0.4 秒，人手连点最快约 10 次/秒，所以 5 个槽足够覆盖；每个槽只
         * 是一个 630×630 的小图层，5 个加起来（约 2M 像素）仍比原来一张全屏图层
         * （3.5M 像素）少，多开几个不会让合成变重。
         */
        const val SLOT_COUNT = 5

        const val FRAME_MS = 1000f / 60f
        const val MAX_DT_MS = 50f
    }
}
