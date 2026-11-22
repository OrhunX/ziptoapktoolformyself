package com.sen0413.ultimatewidget.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The in-app visualizer. The audio engine delivers about 20 spectrum frames a second; this
 * view eases toward each new frame on every display frame, so the motion stays fluid at
 * 60–120 Hz while every bar is still real audio data. Bass kicks from the engine add a short
 * pulse (brighter, a little bigger) so the picture moves with the beat.
 *
 * Styles around the artwork: RING, BURST, BLOB. Styles in the strip below the title: BARS,
 * MIRROR, WAVE, PEAKS, LED, SPECTRUM, PARTICLES.
 */
class VisualizerView(context: Context) : View(context) {
    enum class Style(val aroundArtwork: Boolean) {
        RING(true), BURST(true), BLOB(true),
        BARS(false), MIRROR(false), WAVE(false), PEAKS(false), LED(false), SPECTRUM(false), PARTICLES(false),
    }

    var style: Style = Style.BARS
        set(value) {
            field = value
            particles.clear()
            invalidate()
        }

    /** For the artwork styles: radius of the artwork the visualizer surrounds, in px. */
    var ringInnerRadius: Float = 0f

    private var target = FloatArray(0)
    private var shown = FloatArray(0)
    private var peaks = FloatArray(0)
    private var lastFrameAt = 0L
    private var gradient: Shader? = null
    @ColorInt private var colorTop = Ink.EMBER
    @ColorInt private var colorBottom = Ink.LAVENDER

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float)
    private val particles = ArrayList<Particle>()
    private val random = Random(7)

    fun setColors(@ColorInt top: Int, @ColorInt bottom: Int) {
        if (top == colorTop && bottom == colorBottom) return
        colorTop = top
        colorBottom = bottom
        gradient = null
        invalidate()
    }

    /** New spectrum frame (values 0..1). The array is not kept. */
    fun setBars(bars: FloatArray) {
        if (target.size != bars.size) {
            target = bars.copyOf()
            shown = FloatArray(bars.size)
            peaks = FloatArray(bars.size)
        } else {
            bars.copyInto(target)
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradient = null
        particles.clear()
    }

    /** 0..1, high right after a bass kick, gone after about half a second. */
    private fun beatPulse(now: Long): Float {
        val since = now - VisualizerEngine.lastBeatAt
        return if (since in 0..600) exp(-since / 170f) else 0f
    }

    override fun onDraw(canvas: Canvas) {
        val count = shown.size
        if (count == 0 || width == 0 || height == 0) return

        // Ease toward the newest frame, independent of the display's refresh rate.
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else ((now - lastFrameAt) / 1000f).coerceIn(0f, 0.1f)
        lastFrameAt = now
        val ease = 1f - exp(-dt * 24f)
        var moving = false
        for (i in 0 until count) {
            val delta = target[i] - shown[i]
            if (delta > 0.002f || delta < -0.002f) {
                shown[i] += delta * ease
                moving = true
            } else {
                shown[i] = target[i]
            }
        }
        val pulse = beatPulse(now)
        if (pulse > 0.01f) moving = true

        val shader = gradient ?: LinearGradient(
            0f, 0f, 0f, height.toFloat(), colorTop, colorBottom, Shader.TileMode.CLAMP,
        ).also { gradient = it }
        paint.shader = shader
        stroke.shader = shader

        when (style) {
            Style.BARS -> drawBars(canvas, mirror = false, pulse)
            Style.MIRROR -> drawBars(canvas, mirror = true, pulse)
            Style.WAVE -> drawWave(canvas, pulse)
            Style.RING -> drawRing(canvas, pulse)
            Style.BURST -> drawBurst(canvas, now, pulse)
            Style.BLOB -> drawBlob(canvas, now, pulse)
            Style.PEAKS -> moving = drawPeaks(canvas, dt, pulse) || moving
            Style.LED -> drawLed(canvas, pulse)
            Style.SPECTRUM -> drawSpectrum(canvas, pulse)
            Style.PARTICLES -> moving = drawParticles(canvas, dt, pulse) || moving
        }
        if (moving) postInvalidateOnAnimation() else lastFrameAt = 0L
    }

    private fun value(i: Int, pulse: Float): Float = (shown[i] * (1f + 0.18f * pulse)).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- strip styles

    private fun drawBars(canvas: Canvas, mirror: Boolean, pulse: Float) {
        val count = shown.size
        val gap = dp(5f)
        val barWidth = (width - gap * (count - 1)) / count
        val radius = min(barWidth / 2f, dp(6f))
        val minHeight = dp(4f)
        for (i in 0 until count) {
            val h = minHeight + value(i, pulse) * (height - minHeight)
            val left = i * (barWidth + gap)
            val top = if (mirror) (height - h) / 2f else height - h
            canvas.drawRoundRect(left, top, left + barWidth, top + h, radius, radius, paint)
        }
    }

    /** Bars with caps that jump to every peak and then fall slowly, like a studio meter. */
    private fun drawPeaks(canvas: Canvas, dt: Float, pulse: Float): Boolean {
        val count = shown.size
        val gap = dp(5f)
        val barWidth = (width - gap * (count - 1)) / count
        val capHeight = dp(3f)
        val minHeight = dp(3f)
        val usable = height - capHeight - dp(3f)
        var falling = false
        for (i in 0 until count) {
            val v = value(i, pulse)
            peaks[i] = if (v >= peaks[i]) v else max(v, peaks[i] - dt * 0.55f)
            if (peaks[i] > v + 0.005f) falling = true
            val h = minHeight + v * (usable - minHeight)
            val left = i * (barWidth + gap)
            paint.alpha = 190
            canvas.drawRoundRect(left, height - h, left + barWidth, height.toFloat(), dp(2f), dp(2f), paint)
            paint.alpha = 255
            val capTop = height - (minHeight + peaks[i] * (usable - minHeight)) - capHeight - dp(3f)
            canvas.drawRoundRect(left, capTop, left + barWidth, capTop + capHeight, dp(1.5f), dp(1.5f), paint)
        }
        return falling
    }

    /** Stacked blocks like an LED meter; unlit blocks stay faintly visible. */
    private fun drawLed(canvas: Canvas, pulse: Float) {
        val count = shown.size
        val gap = dp(4f)
        val barWidth = (width - gap * (count - 1)) / count
        val block = dp(4f)
        val blockGap = dp(2f)
        val rows = ((height + blockGap) / (block + blockGap)).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val lit = (value(i, pulse) * rows + 0.35f).toInt().coerceAtLeast(1)
            val left = i * (barWidth + gap)
            for (r in 0 until rows) {
                val bottom = height - r * (block + blockGap)
                paint.alpha = if (r < lit) 255 else 38
                canvas.drawRoundRect(left, bottom - block, left + barWidth, bottom, dp(1f), dp(1f), paint)
            }
        }
        paint.alpha = 255
    }

    /** A filled spectrum rising from the bottom with a bright outline. */
    private fun drawSpectrum(canvas: Canvas, pulse: Float) {
        val count = shown.size
        val step = width.toFloat() / (count - 1).coerceAtLeast(1)
        val top = dp(3f)
        path.reset()
        path.moveTo(0f, height.toFloat())
        for (i in 0 until count) {
            val x = i * step
            val y = height - value(i, pulse) * (height - top)
            if (i == 0) path.lineTo(x, y) else curveTo(x, y, (i - 1) * step, height - value(i - 1, pulse) * (height - top))
        }
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        paint.alpha = (90 + 60 * pulse).toInt()
        canvas.drawPath(path, paint)
        paint.alpha = 255
        path.reset()
        for (i in 0 until count) {
            val x = i * step
            val y = height - value(i, pulse) * (height - top)
            if (i == 0) path.moveTo(x, y) else curveTo(x, y, (i - 1) * step, height - value(i - 1, pulse) * (height - top))
        }
        stroke.strokeWidth = dp(2.5f)
        canvas.drawPath(path, stroke)
    }

    /** Sparks that rise from each band, more and faster when it is loud. */
    private fun drawParticles(canvas: Canvas, dt: Float, pulse: Float): Boolean {
        val count = shown.size
        val bandWidth = width.toFloat() / count
        for (i in 0 until count) {
            val v = shown[i]
            val chance = (v * v * 14f + pulse * 6f) * dt
            if (v > 0.18f && random.nextFloat() < chance && particles.size < MAX_PARTICLES) {
                particles.add(
                    Particle(
                        x = (i + random.nextFloat()) * bandWidth,
                        y = height.toFloat(),
                        vx = (random.nextFloat() - 0.5f) * dp(24f),
                        vy = -dp(40f + 150f * v + 80f * pulse),
                        life = 1f,
                        size = dp(1.5f + 2.5f * random.nextFloat()),
                    ),
                )
            }
        }
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt * 1.1f
            if (p.life <= 0f || p.y < -p.size) {
                iterator.remove()
                continue
            }
            paint.alpha = (255 * p.life).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
        paint.alpha = 255
        // A thin live baseline so the style still reads when it is quiet.
        stroke.strokeWidth = dp(2f)
        path.reset()
        for (i in 0 until count) {
            val x = (i + 0.5f) * bandWidth
            val y = height - dp(2f) - shown[i] * dp(10f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, stroke)
        return particles.isNotEmpty()
    }

    private fun drawWave(canvas: Canvas, pulse: Float) {
        val count = shown.size
        val mid = height / 2f
        val amplitude = height / 2f - dp(3f)
        val step = width.toFloat() / (count - 1).coerceAtLeast(1)

        // Filled, mirrored body.
        path.reset()
        path.moveTo(0f, mid)
        for (i in 0 until count) {
            val x = i * step
            val y = mid - value(i, pulse) * amplitude
            if (i == 0) path.lineTo(x, y) else curveTo(x, y, (i - 1) * step, mid - value(i - 1, pulse) * amplitude)
        }
        for (i in count - 1 downTo 0) {
            val x = i * step
            val y = mid + value(i, pulse) * amplitude
            if (i == count - 1) path.lineTo(x, y) else curveTo(x, y, (i + 1) * step, mid + value(i + 1, pulse) * amplitude)
        }
        path.close()
        paint.alpha = 70
        canvas.drawPath(path, paint)
        paint.alpha = 255

        // Bright top line.
        path.reset()
        for (i in 0 until count) {
            val x = i * step
            val y = mid - value(i, pulse) * amplitude
            if (i == 0) path.moveTo(x, y) else curveTo(x, y, (i - 1) * step, mid - value(i - 1, pulse) * amplitude)
        }
        stroke.strokeWidth = dp(3f)
        canvas.drawPath(path, stroke)
    }

    /** Smooth segment from the previous point to (x, y) with horizontal tangents. */
    private fun curveTo(x: Float, y: Float, prevX: Float, prevY: Float) {
        val cx = (prevX + x) / 2f
        path.cubicTo(cx, prevY, cx, y, x, y)
    }

    // ---------------------------------------------------------------- artwork styles

    private fun innerRadius(outer: Float): Float =
        if (ringInnerRadius > 0f) ringInnerRadius + dp(10f) else outer * 0.6f

    /** Band for ray [i] of [rays]: bass at the bottom, mirrored left and right, treble at the top. */
    private fun bandFor(i: Int, rays: Int): Int {
        val count = shown.size
        val half = rays / 2
        val k = if (i < half) i else rays - 1 - i
        return (k * count / half).coerceIn(0, count - 1)
    }

    private fun drawRing(canvas: Canvas, pulse: Float) {
        val count = shown.size
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = innerRadius(outer)
        val reach = (outer - inner).coerceAtLeast(dp(8f))
        val rays = count * 2
        stroke.strokeWidth = dp(4f)
        for (i in 0 until rays) {
            val v = value(bandFor(i, rays), pulse)
            val angle = (Math.PI / 2.0 + (i + 0.5) * (2.0 * Math.PI / rays)).toFloat()
            val length = dp(3f) + v * (reach - dp(3f))
            val sx = cx + cos(angle) * inner
            val sy = cy + sin(angle) * inner
            val ex = cx + cos(angle) * (inner + length)
            val ey = cy + sin(angle) * (inner + length)
            stroke.alpha = (110 + 145 * v).toInt()
            canvas.drawLine(sx, sy, ex, ey, stroke)
        }
        stroke.alpha = 255
    }

    /** Many thin rays with a soft glow that slowly turn and flare on every kick. */
    private fun drawBurst(canvas: Canvas, now: Long, pulse: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = innerRadius(outer) - dp(4f)
        val reach = (outer - inner).coerceAtLeast(dp(8f))
        val rays = 72
        val spin = (now % 60_000L) / 60_000f * (2f * Math.PI.toFloat())

        // Glow halo that swells with the beat.
        val glowRadius = inner + reach * (0.35f + 0.4f * pulse)
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius.coerceAtLeast(1f),
            intArrayOf(ColorUtils.setAlphaComponent(colorTop, (70 + 90 * pulse).toInt()), ColorUtils.setAlphaComponent(colorTop, 0)),
            floatArrayOf(inner / glowRadius.coerceAtLeast(1f), 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        stroke.strokeWidth = dp(2.5f)
        for (i in 0 until rays) {
            val v = value(bandFor(i, rays), pulse)
            val angle = spin + (Math.PI / 2.0 + (i + 0.5) * (2.0 * Math.PI / rays)).toFloat()
            val length = dp(2f) + v * reach
            val sx = cx + cos(angle) * inner
            val sy = cy + sin(angle) * inner
            val ex = cx + cos(angle) * (inner + length)
            val ey = cy + sin(angle) * (inner + length)
            stroke.alpha = (90 + 165 * v).toInt()
            canvas.drawLine(sx, sy, ex, ey, stroke)
        }
        stroke.alpha = 255
    }

    /** A liquid outline around the artwork whose bumps follow the spectrum. */
    private fun drawBlob(canvas: Canvas, now: Long, pulse: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = innerRadius(outer) - dp(6f)
        val reach = (outer - inner).coerceAtLeast(dp(8f)) * 0.9f
        val points = 48
        val wobble = (now % 20_000L) / 20_000f * (2f * Math.PI.toFloat())
        val xs = FloatArray(points)
        val ys = FloatArray(points)
        for (i in 0 until points) {
            val v = value(bandFor(i, points), pulse)
            val angle = (Math.PI / 2.0 + i * (2.0 * Math.PI / points)).toFloat()
            val breathe = 0.06f * sin(wobble + i * 0.5f)
            val r = inner + reach * (0.12f + 0.85f * v + breathe)
            xs[i] = cx + cos(angle) * r
            ys[i] = cy + sin(angle) * r
        }
        path.reset()
        // Closed Catmull-Rom curve through the points.
        path.moveTo(xs[0], ys[0])
        for (i in 0 until points) {
            val p0 = (i - 1 + points) % points
            val p1 = i
            val p2 = (i + 1) % points
            val p3 = (i + 2) % points
            val c1x = xs[p1] + (xs[p2] - xs[p0]) / 6f
            val c1y = ys[p1] + (ys[p2] - ys[p0]) / 6f
            val c2x = xs[p2] - (xs[p3] - xs[p1]) / 6f
            val c2y = ys[p2] - (ys[p3] - ys[p1]) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, xs[p2], ys[p2])
        }
        path.close()
        paint.alpha = (70 + 70 * pulse).toInt()
        canvas.drawPath(path, paint)
        paint.alpha = 255
        stroke.strokeWidth = dp(3f)
        canvas.drawPath(path, stroke)
    }

    /** Average of the lowest bands: drives the artwork pulse and the ambient background. */
    fun bassEnergy(): Float {
        if (shown.isEmpty()) return 0f
        val n = min(3, shown.size)
        var sum = 0f
        for (i in 0 until n) sum += shown[i]
        return sum / n
    }

    companion object {
        private const val MAX_PARTICLES = 140

        /** Two readable accents from one base color. */
        fun accentPair(@ColorInt base: Int): Pair<Int, Int> =
            ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.35f) to base
    }
}
