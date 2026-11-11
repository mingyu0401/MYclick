package com.myclick.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.myclick.core.Config
import com.myclick.core.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import java.util.Random

/**
 * BASpark 风格点击特效引擎（Canvas 2D，坐标单位 = dp，Density-independent）。
 *
 * 规格逐条对应：
 *  1) 扩散填充圆：0 → 26px*SCALE，ease-out-cubic 1-(1-p)^3，16 帧 @60fps ≈ 266ms；
 *     填充 alpha 0.4 → 0 线性淡出。
 *  2) 双旋转光弧：每段 1.1π，随机起始偏移 / 随机角速度 [0,0.08,0.1] rad/帧 / 随机扩散
 *     速率 [0,1,1.5,2]*SCALE；半径 = 圆半径 + 扩散速率*帧数；描边白→淡蓝(或粉)，
 *     23 帧 ≈ 383ms；线宽 weight(t)=min(2-|4(t-0.5)|,1) 映射 0.4→3.3，整体乘 (1-p)，
 *     尾部 *1.15；分 10 段绘制；alpha = 1.1-0.3p。
 *  3) 三角火花 ×4：随机方向，初速 4.8~6.8*SCALE，每帧 ×0.9 摩擦，大小 4~7*SCALE，
 *     自旋 ±0.14 rad/帧，等腰三角形，alpha 每帧 -0.032，归零移除。
 *
 * 性能：所有粒子预分配 + swap-remove，渲染循环 0 分配；Paint / Path 复用；
 * 不做任何 Canvas.save/restore 矩阵栈操作。
 */
class EffectEngine {

    class RenderConfig(
        val scale: Float,
        val speed: Float,
        val dark: Boolean,
        val palette: Palette
    ) {
        companion object {
            fun from(config: Config, uiMode: Int) = RenderConfig(
                scale = config.scale.coerceIn(0.2f, 6f),
                speed = config.speed.coerceIn(0.25f, 4f),
                dark = config.isDark(uiMode),
                palette = config.palette(config.isDark(uiMode))
            )
        }
    }

    @Volatile
    var render: RenderConfig = RenderConfig.from(Config.default(), 0)
        private set

    private val random = Random(System.nanoTime())
    private val ripples = Array(MAX_RIPPLES) { Ripple() }
    private val sparks = Array(MAX_SPARKS) { Spark() }
    private var rippleCount = 0
    private var sparkCount = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val triangle = Path()

    fun update(config: Config, uiMode: Int) {
        render = RenderConfig.from(config, uiMode)
    }

    val hasActive: Boolean get() = rippleCount > 0 || sparkCount > 0

    fun clear() {
        rippleCount = 0
        sparkCount = 0
    }

    /** 在 dp 坐标系下触发一次点击。 */
    fun spawn(xDp: Float, yDp: Float) {
        val s = render.scale
        val r = if (rippleCount < MAX_RIPPLES) ripples[rippleCount++] else ripples[0]
        r.reset(xDp, yDp, s, random)
        var k = 0
        while (k < SPARKS_PER_TAP) {
            val sp = if (sparkCount < MAX_SPARKS) sparks[sparkCount++] else sparks[0]
            sp.reset(xDp, yDp, s, random)
            k++
        }
    }

    /** frameDt：以 60fps 为一帧的时间步进，已在外层按 speed 缩放、按 50ms 钳制。 */
    fun advance(frameDt: Float) {
        if (frameDt <= 0f) return
        val friction = FRICTION.pow(frameDt)
        val fade = SPARK_FADE * frameDt

        var i = 0
        while (i < rippleCount) {
            val r = ripples[i]
            r.frame += frameDt
            if (r.frame >= ARC_FRAMES) {
                removeRipple(i)
            } else {
                i++
            }
        }

        var j = 0
        while (j < sparkCount) {
            val s = sparks[j]
            s.x += s.vx * frameDt
            s.y += s.vy * frameDt
            s.vx *= friction
            s.vy *= friction
            s.rot += s.spin * frameDt
            s.alpha -= fade
            if (s.alpha <= 0f) {
                removeSpark(j)
            } else {
                j++
            }
        }
    }

    fun draw(canvas: Canvas) {
        val rc = render
        val s = rc.scale
        val p = rc.palette
        val arcStartR = (p.arcStart shr 16) and 0xFF
        val arcStartG = (p.arcStart shr 8) and 0xFF
        val arcStartB = p.arcStart and 0xFF
        val arcEndR = (p.arcEnd shr 16) and 0xFF
        val arcEndG = (p.arcEnd shr 8) and 0xFF
        val arcEndB = p.arcEnd and 0xFF
        val sparkR = (p.spark shr 16) and 0xFF
        val sparkG = (p.spark shr 8) and 0xFF
        val sparkB = p.spark and 0xFF

        // ---- 1 + 2：扩散圆 + 双旋转光弧 ----
        var i = 0
        while (i < rippleCount) {
            val r = ripples[i]

            // 圆：16 帧 ease-out-cubic
            val circleFrame = if (r.frame < CIRCLE_FRAMES) r.frame else CIRCLE_FRAMES
            val cp = circleFrame / CIRCLE_FRAMES
            val inv = 1f - cp
            val ease = 1f - inv * inv * inv
            val circleR = BASE_RADIUS * s * ease
            val fillAlpha = (0.4f * (1f - cp) * 255f).toInt().coerceIn(0, 255)
            if (fillAlpha > 0 && circleR > 0f) {
                fillPaint.setARGB(fillAlpha, p.fillR, p.fillG, p.fillB)
                canvas.drawCircle(r.x, r.y, circleR, fillPaint)
            }

            // 光弧：23 帧
            val ap = if (r.frame < ARC_FRAMES) r.frame / ARC_FRAMES else 1f
            val cr = (arcStartR + (arcEndR - arcStartR) * ap).toInt()
            val cg = (arcStartG + (arcEndG - arcStartG) * ap).toInt()
            val cb = (arcStartB + (arcEndB - arcStartB) * ap).toInt()
            val arcAlpha = (min(1.1f - 0.3f * ap, 1f) * 255f).toInt().coerceIn(0, 255)
            val widthMultiplier = 1f - ap

            var arcIndex = 0
            while (arcIndex < 2) {
                val startRad: Float
                val spread: Float
                if (arcIndex == 0) {
                    startRad = r.a0Start + r.a0Rot * r.frame
                    spread = r.a0Spread
                } else {
                    startRad = r.a1Start + r.a1Rot * r.frame
                    spread = r.a1Spread
                }
                val arcR = circleR + spread * s * r.frame
                if (arcR > 0.5f && arcAlpha > 0 && widthMultiplier > 0f) {
                    arcPaint.setARGB(arcAlpha, cr, cg, cb)
                    var seg = 0
                    while (seg < ARC_SEGMENTS) {
                        val t0 = seg.toFloat() / ARC_SEGMENTS
                        val t1 = (seg + 1).toFloat() / ARC_SEGMENTS
                        val tm = (t0 + t1) * 0.5f
                        val w = weight(tm)
                        var strokeWidth = (MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * w) * widthMultiplier
                        if (t1 > 0.8f) strokeWidth *= TAIL_BOOST
                        arcPaint.strokeWidth = strokeWidth
                        val startDeg = ((startRad + ARC_SPAN * t0) * RAD_TO_DEG).toFloat()
                        val sweepDeg = (ARC_SPAN * (t1 - t0) * RAD_TO_DEG).toFloat()
                        canvas.drawArc(
                            r.x - arcR, r.y - arcR, r.x + arcR, r.y + arcR,
                            startDeg, sweepDeg, false, arcPaint
                        )
                        seg++
                    }
                }
                arcIndex++
            }
            i++
        }

        // ---- 3：三角火花 ----
        var j = 0
        while (j < sparkCount) {
            val sp = sparks[j]
            val a = (sp.alpha * 255f).toInt().coerceIn(0, 255)
            if (a > 0) {
                val size = sp.size
                val c = cos(sp.rot.toDouble()).toFloat()
                val sn = sin(sp.rot.toDouble()).toFloat()
                // 顶角 (0, -size)，底边两点 (±size*0.58, size*0.6)
                val apexX = sp.x + size * sn
                val apexY = sp.y - size * c
                val lbx = -size * TRI_HALF_BASE
                val lby = size * TRI_BASE_Y
                val rbx = size * TRI_HALF_BASE
                val rby = size * TRI_BASE_Y
                val leftX = sp.x + lbx * c - lby * sn
                val leftY = sp.y + lbx * sn + lby * c
                val rightX = sp.x + rbx * c - rby * sn
                val rightY = sp.y + rbx * sn + rby * c
                triangle.reset()
                triangle.moveTo(apexX, apexY)
                triangle.lineTo(leftX, leftY)
                triangle.lineTo(rightX, rightY)
                triangle.close()
                sparkPaint.setARGB(a, sparkR, sparkG, sparkB)
                canvas.drawPath(triangle, sparkPaint)
            }
            j++
        }
    }

    private fun removeRipple(index: Int) {
        val last = --rippleCount
        if (index != last) {
            val tmp = ripples[index]
            ripples[index] = ripples[last]
            ripples[last] = tmp
        }
    }

    private fun removeSpark(index: Int) {
        val last = --sparkCount
        if (index != last) {
            val tmp = sparks[index]
            sparks[index] = sparks[last]
            sparks[last] = tmp
        }
    }

    /** weight(t)=min(2-|4(t-0.5)|,1)，钳制到 [0,1] */
    private fun weight(t: Float): Float {
        val v = 2f - abs(4f * (t - 0.5f))
        return min(v, 1f).coerceAtLeast(0f)
    }

    private class Ripple {
        var x = 0f
        var y = 0f
        var frame = 0f
        var a0Start = 0f
        var a0Rot = 0f
        var a0Spread = 0f
        var a1Start = 0f
        var a1Rot = 0f
        var a1Spread = 0f

        fun reset(x: Float, y: Float, scale: Float, rnd: Random) {
            this.x = x
            this.y = y
            frame = 0f
            a0Start = rnd.nextFloat() * TWO_PI
            a1Start = rnd.nextFloat() * TWO_PI
            a0Rot = ROT_SPEEDS[rnd.nextInt(ROT_SPEEDS.size)]
            a1Rot = ROT_SPEEDS[rnd.nextInt(ROT_SPEEDS.size)]
            a0Spread = SPREAD_RATES[rnd.nextInt(SPREAD_RATES.size)]
            a1Spread = SPREAD_RATES[rnd.nextInt(SPREAD_RATES.size)]
        }
    }

    private class Spark {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var size = 0f
        var rot = 0f
        var spin = 0f
        var alpha = 0f

        fun reset(x: Float, y: Float, scale: Float, rnd: Random) {
            val angle = rnd.nextFloat() * TWO_PI
            val speed = (4.8f + rnd.nextFloat() * 2f) * scale
            this.x = x
            this.y = y
            vx = cos(angle.toDouble()).toFloat() * speed
            vy = sin(angle.toDouble()).toFloat() * speed
            size = (4f + rnd.nextFloat() * 3f) * scale
            rot = rnd.nextFloat() * TWO_PI
            spin = (rnd.nextFloat() * 0.28f - 0.14f)
            alpha = 1f
        }
    }

    companion object {
        const val CIRCLE_FRAMES = 16f          // 266ms @60fps
        const val ARC_FRAMES = 23f             // 383ms @60fps
        const val BASE_RADIUS = 26f
        const val ARC_SEGMENTS = 10
        val ARC_SPAN: Float = 1.1f * Math.PI.toFloat()
        const val MIN_WIDTH = 0.4f
        const val MAX_WIDTH = 3.3f
        const val TAIL_BOOST = 1.15f
        const val FRICTION = 0.9f
        const val SPARK_FADE = 0.032f
        const val SPARKS_PER_TAP = 4
        const val TRI_HALF_BASE = 0.58f
        const val TRI_BASE_Y = 0.6f
        val TWO_PI: Float = (Math.PI * 2.0).toFloat()
        val RAD_TO_DEG: Float = (180.0 / Math.PI).toFloat()

        val ROT_SPEEDS = floatArrayOf(0f, 0.08f, 0.1f)
        val SPREAD_RATES = floatArrayOf(0f, 1f, 1.5f, 2f)

        private const val MAX_RIPPLES = 24
        private const val MAX_SPARKS = 128
    }
}
