package com.myclick.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.max

/**
 * 主题切换的圆形过渡层：旧界面快照只在圆外（浅 → 深）或圆内（深 → 浅）可见，圆心是主题按钮。
 *
 *  浅 → 深：圆从按钮处扩散到全屏，圆内露出已经换好的深色界面
 *  深 → 浅：圆从全屏收缩回按钮，圆外露出已经换好的浅色界面
 *
 * 节奏与网页版的深浅色切换一致：480ms，三次方缓出。
 */
internal class ThemeRevealView(
    context: Context,
    private val reveal: ThemeTransition.Reveal,
) : View(context) {

    private val snapshot = reveal.snapshot
    private val cx = reveal.cx
    private val cy = reveal.cy
    // 圆要能盖住最远的那个屏幕角：圆心到四个角里最远的距离
    private val maxRadius = hypot(
        max(cx, snapshot.width - cx).toDouble(),
        max(cy, snapshot.height - cy).toDouble(),
    ).toFloat()
    private var radius = if (reveal.toDark) reveal.startRadius else maxRadius
    private val dst = Rect()

    // 浅 → 深：圆内挖空，只留圆外的旧界面；深 → 浅：只保留圆内的旧界面
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(if (reveal.toDark) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.DST_IN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dst.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(snapshot, null, dst, null)
        canvas.drawCircle(cx, cy, radius, maskPaint)
        canvas.restoreToCount(layer)
    }

    /** 挂到窗口上开始播放；播完自动摘掉并回收快照 */
    fun play() {
        val from = if (reveal.toDark) reveal.startRadius else maxRadius
        val to = if (reveal.toDark) maxRadius else reveal.startRadius
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                radius = from + (to - from) * it.animatedFraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (this@ThemeRevealView.parent as? ViewGroup)?.removeView(this@ThemeRevealView)
                    snapshot.recycle()
                }
            })
            start()
        }
    }

    private companion object {
        const val DURATION_MS = 480L
    }
}
