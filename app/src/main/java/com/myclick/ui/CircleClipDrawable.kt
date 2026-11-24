package com.myclick.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.min

/**
 * 把内部 drawable 裁成圆形再画。
 *
 * 之前用 `ImageView.clipToOutline` + `setOval` 做圆形图标：轮廓裁切要等 outline
 * 计算完成，Activity 重建后的最初几帧还没算好，就会露出 `ic_launcher` 的方形深色底
 * —— 切主题时表现为「MYclick 右边的图标闪一下方框」。这里改成 draw() 里每帧自己裁，
 * 与布局/outline 的时机无关。
 *
 * 另外支持 [setHolding]：圆形裁切固定不动，只让内部图形绕圆心旋转。
 */
internal class CircleClipDrawable(private val inner: Drawable) : Drawable() {

    private val path = Path()

    private var angle = 0f        // 当前旋转角（度）
    private var speed = 0f        // 当前角速度（度/秒）
    private var holding = false   // 按住中：向最高速加速；否则向 0 减速
    private var spinning = false  // 逐帧回调是否在跑
    private var lastFrame = 0L

    private val frame = Runnable {
        val now = SystemClock.uptimeMillis()
        val dt = min(now - lastFrame, MAX_STEP_MS) / 1000f
        lastFrame = now
        // 指数逼近目标速度：按住时逐渐加速，松手后逐渐减速，两段都没有突变
        val target = if (holding) MAX_SPEED else 0f
        val tau = if (holding) SPEED_UP_TAU_S else SLOW_DOWN_TAU_S
        speed += (target - speed) * (1f - exp(-dt / tau))
        angle = (angle + speed * dt) % 360f
        invalidateSelf()
        if (holding || speed > MIN_SPEED) {
            scheduleSelf(frame, now)
        } else {
            speed = 0f
            spinning = false
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(b.width(), b.height()) / 2f
        val save = canvas.save()
        path.reset()
        path.addCircle(b.exactCenterX(), b.exactCenterY(), r, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.rotate(angle, b.exactCenterX(), b.exactCenterY())
        inner.setBounds(b)
        inner.draw(canvas)
        canvas.restoreToCount(save)
    }

    /** 长按期间传 true 开始加速；松手传 false，之后缓慢减速直至停下 */
    fun setHolding(on: Boolean) {
        holding = on
        if (on && !spinning) {
            spinning = true
            lastFrame = SystemClock.uptimeMillis()
            scheduleSelf(frame, lastFrame)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        inner.bounds = bounds
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val MAX_SPEED = 900f          // 按住时的最高角速度（约 2.5 圈/秒）
        const val SPEED_UP_TAU_S = 0.6f     // 加速时间常数：约 1.5 秒接近最高速
        const val SLOW_DOWN_TAU_S = 1f      // 减速时间常数：松手后约 7 秒慢慢停下
        const val MIN_SPEED = 1f            // 低于此角速度视为停止
        const val MAX_STEP_MS = 50L         // 单帧最长积分步长，防止切后台再回来时跳角度
    }
}
