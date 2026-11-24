package com.myclick.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 把内部 drawable 裁成圆形再画。
 *
 * 之前用 `ImageView.clipToOutline` + `setOval` 做圆形图标：轮廓裁切要等 outline
 * 计算完成，Activity 重建后的最初几帧还没算好，就会露出 `ic_launcher` 的方形深色底
 * —— 切主题时表现为「MYclick 右边的图标闪一下方框」。这里改成 draw() 里每帧自己裁，
 * 与布局/outline 的时机无关。
 */
internal class CircleClipDrawable(private val inner: Drawable) : Drawable() {

    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = min(b.width(), b.height()) / 2f
        val save = canvas.save()
        path.reset()
        path.addCircle(b.exactCenterX(), b.exactCenterY(), r, Path.Direction.CW)
        canvas.clipPath(path)
        inner.setBounds(b)
        inner.draw(canvas)
        canvas.restoreToCount(save)
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
}
