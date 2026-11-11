package com.myclick.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/** 主卡右侧的装饰：把 BASpark 的「扩散圆 + 光弧 + 三角火花」抽象成静态图形。 */
class HeroDecorView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F9A86C")
        alpha = 42
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        alpha = 225
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 220
    }
    private val triangle = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.56f
        val cy = height * 0.52f
        val r = min(width, height) * 0.36f

        canvas.drawCircle(cx, cy, r * 0.62f, fillPaint)
        for (i in 0..2) {
            ringPaint.alpha = 62 - i * 16
            canvas.drawCircle(cx, cy, r * (0.72f + i * 0.20f), ringPaint)
        }

        val bounds = r * 1.02f
        canvas.drawArc(cx - bounds, cy - bounds, cx + bounds, cy + bounds,
            206f, 96f, false, arcPaint)
        canvas.drawArc(cx - bounds, cy - bounds, cx + bounds, cy + bounds,
            26f, 96f, false, arcPaint)

        drawArrow(canvas, cx, cy, bounds, 206f)
        drawArrow(canvas, cx, cy, bounds, 26f + 96f)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float, degrees: Float) {
        val rad = Math.toRadians(degrees.toDouble())
        val x = cx + (r * Math.cos(rad)).toFloat()
        val y = cy + (r * Math.sin(rad)).toFloat()
        val size = r * 0.16f
        val tangent = Math.toRadians((degrees + 90).toDouble())
        val tx = Math.cos(tangent).toFloat()
        val ty = Math.sin(tangent).toFloat()
        triangle.reset()
        triangle.moveTo(x + tx * size, y + ty * size)
        triangle.lineTo(x - ty * size * 0.55f, y + tx * size * 0.55f)
        triangle.lineTo(x + ty * size * 0.55f, y - tx * size * 0.55f)
        triangle.close()
        canvas.drawPath(triangle, sparkPaint)
    }
}
