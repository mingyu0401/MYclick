package com.myclick.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.myclick.core.Config
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 独立 SurfaceView + Choreographer 驱动的渲染循环。
 *
 * - Surface 用 PixelFormat.TRANSLUCENT + setZOrderOnTop，保证透明且能进入
 *   SurfaceFlinger 合成链，从而被 MediaProjection / DisplayCapture 录进视频。
 * - 逻辑坐标单位 = dp；Surface 物理分辨率 = 逻辑尺寸 × dpr，dpr = min(density, 2)
 *   （可关闭上限）。canvas.scale(dpr,dpr) 之后所有 BASpark 尺寸都是规格里的原始数值。
 * - 无活动粒子时立刻停 frame callback 并清屏，不空转、不占 GPU。
 * - dt 钳制在 50ms 内，避免后台切回时一次性补帧爆炸。
 */
class EffectSurfaceView(
    context: Context,
    private val density: Float
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private val engine = EffectEngine()
    private val holderCallback = holder
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    @Volatile
    private var running = false
    private var surfaceReady = false
    private var lastFrameNanos = 0L
    private var dpr = 1f
    private var speed = 1f
    private var dprCapEnabled = true
    private var lastFixedW = 0
    private var lastFixedH = 0

    init {
        setZOrderOnTop(true)
        holderCallback.setFormat(PixelFormat.TRANSLUCENT)
        holderCallback.addCallback(this)
        isClickable = false
        isFocusable = false
        alpha = 1f
    }

    fun applyConfig(config: Config, uiMode: Int) {
        engine.update(config, uiMode)
        speed = engine.render.speed
        dprCapEnabled = config.dprCapEnabled
        post { applyFixedSize() }
    }

    /** xDp / yDp 已是 dp 坐标（由 OverlayController 从 MotionEvent.rawX/density 换算） */
    fun emit(xDp: Float, yDp: Float) {
        post {
            engine.spawn(xDp, yDp)
            if (!running) startLoop()
        }
    }

    fun reset() {
        post {
            running = false
            engine.clear()
            clearSurface()
        }
    }

    // ---------------- SurfaceHolder.Callback ----------------

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        applyFixedSize()
        clearSurface()
        // Surface 晚于第一次点击才创建时，别把已经生成的粒子丢掉
        if (engine.hasActive) startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFixedSize()
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        running = false
        engine.clear()
    }

    private fun applyFixedSize() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val newDpr = if (dprCapEnabled) min(density, 2f) else density
        dpr = if (newDpr <= 0f) 1f else newDpr
        val fw = ((w / density) * dpr).roundToInt().coerceAtLeast(1)
        val fh = ((h / density) * dpr).roundToInt().coerceAtLeast(1)
        if (fw == lastFixedW && fh == lastFixedH) return
        lastFixedW = fw
        lastFixedH = fh
        try {
            holderCallback.setFixedSize(fw, fh)
        } catch (t: Throwable) {
            // 某些 ROM 不允许在 surfaceChanged 中改尺寸，忽略即可（退化为 1:1）
        }
    }

    // ---------------- 渲染循环 ----------------

    private fun startLoop() {
        if (running || !surfaceReady) return
        running = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dtMs = if (lastFrameNanos == 0L) {
            FRAME_MS
        } else {
            (frameTimeNanos - lastFrameNanos) / 1_000_000f
        }
        lastFrameNanos = frameTimeNanos
        val clamped = dtMs.coerceIn(0f, MAX_DT_MS)
        val frameDt = (clamped / FRAME_MS) * speed
        engine.advance(frameDt)
        renderFrame()
        if (engine.hasActive) {
            choreographer.postFrameCallback(this)
        } else {
            running = false
            clearSurface()
        }
    }

    private fun renderFrame() {
        var canvas: Canvas? = null
        try {
            canvas = holderCallback.lockCanvas() ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.save()
            canvas.scale(dpr, dpr)
            engine.draw(canvas)
            canvas.restore()
        } catch (t: Throwable) {
            // 忽略单帧异常，下一帧继续
        } finally {
            if (canvas != null) {
                try {
                    holderCallback.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    // surface 已销毁
                }
            }
        }
    }

    private fun clearSurface() {
        var canvas: Canvas? = null
        try {
            canvas = holderCallback.lockCanvas() ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (t: Throwable) {
            // ignore
        } finally {
            if (canvas != null) {
                try {
                    holderCallback.unlockCanvasAndPost(canvas)
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        engine.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FRAME_MS = 1000f / 60f
        const val MAX_DT_MS = 50f
    }
}
