package com.sen0413.ultimatewidget.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The in-app visualizer. The audio engine delivers about 20 spectrum frames a second; this
 * view eases toward each new frame on every display frame, so the motion stays fluid at
 * 60–120 Hz while every bar is still real audio data.
 */
class VisualizerView(context: Context) : View(context) {
    enum class Style { BARS, MIRROR, WAVE, RING }

    var style: Style = Style.BARS
        set(value) {
            field = value
            invalidate()
        }

    /** For [Style.RING]: radius of the artwork the ring surrounds, in px. */
    var ringInnerRadius: Float = 0f

    private var target = FloatArray(0)
    private var shown = FloatArray(0)
    private var lastFrameAt = 0L
    private var gradient: Shader? = null
    @ColorInt private var colorTop = Ink.EMBER
    @ColorInt private var colorBottom = Ink.LAVENDER

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

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
        } else {
            bars.copyInto(target)
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradient = null
    }

    override fun onDraw(canvas: Canvas) {
        val count = shown.size
        if (count == 0 || width == 0 || height == 0) return

        // Ease toward the newest frame, independent of the display's refresh rate.
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0.016f else ((now - lastFrameAt) / 1000f).coerceIn(0f, 0.1f)
        lastFrameAt = now
        val ease = 1f - exp(-dt * 16f)
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

        val shader = gradient ?: LinearGradient(
            0f, 0f, 0f, height.toFloat(), colorTop, colorBottom, Shader.TileMode.CLAMP,
        ).also { gradient = it }
        paint.shader = shader
        stroke.shader = shader

        when (style) {
            Style.BARS -> drawBars(canvas, mirror = false)
            Style.MIRROR -> drawBars(canvas, mirror = true)
            Style.WAVE -> drawWave(canvas)
            Style.RING -> drawRing(canvas)
        }
        if (moving) postInvalidateOnAnimation() else lastFrameAt = 0L
    }

    private fun drawBars(canvas: Canvas, mirror: Boolean) {
        val count = shown.size
        val gap = dp(5f)
        val barWidth = (width - gap * (count - 1)) / count
        val radius = min(barWidth / 2f, dp(6f))
        val minHeight = dp(4f)
        for (i in 0 until count) {
            val h = minHeight + shown[i].coerceIn(0f, 1f) * (height - minHeight)
            val left = i * (barWidth + gap)
            val top = if (mirror) (height - h) / 2f else height - h
            canvas.drawRoundRect(left, top, left + barWidth, top + h, radius, radius, paint)
        }
    }

    private fun drawWave(canvas: Canvas) {
        val count = shown.size
        val mid = height / 2f
        val amplitude = height / 2f - dp(3f)
        val step = width.toFloat() / (count - 1).coerceAtLeast(1)

        // Filled, mirrored body.
        path.reset()
        path.moveTo(0f, mid)
        for (i in 0 until count) {
            val x = i * step
            val y = mid - shown[i].coerceIn(0f, 1f) * amplitude
            if (i == 0) path.lineTo(x, y) else curveTo(x, y, (i - 1) * step, mid - shown[i - 1].coerceIn(0f, 1f) * amplitude)
        }
        for (i in count - 1 downTo 0) {
            val x = i * step
            val y = mid + shown[i].coerceIn(0f, 1f) * amplitude
            if (i == count - 1) path.lineTo(x, y) else curveTo(x, y, (i + 1) * step, mid + shown[i + 1].coerceIn(0f, 1f) * amplitude)
        }
        path.close()
        paint.alpha = 70
        canvas.drawPath(path, paint)
        paint.alpha = 255

        // Bright top line.
        path.reset()
        for (i in 0 until count) {
            val x = i * step
            val y = mid - shown[i].coerceIn(0f, 1f) * amplitude
            if (i == 0) path.moveTo(x, y) else curveTo(x, y, (i - 1) * step, mid - shown[i - 1].coerceIn(0f, 1f) * amplitude)
        }
        stroke.strokeWidth = dp(3f)
        canvas.drawPath(path, stroke)
    }

    /** Smooth segment from the previous point to (x, y) with horizontal tangents. */
    private fun curveTo(x: Float, y: Float, prevX: Float, prevY: Float) {
        val cx = (prevX + x) / 2f
        path.cubicTo(cx, prevY, cx, y, x, y)
    }

    private fun drawRing(canvas: Canvas) {
        val count = shown.size
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = if (ringInnerRadius > 0f) ringInnerRadius + dp(10f) else outer * 0.6f
        val reach = (outer - inner).coerceAtLeast(dp(8f))
        val rays = count * 2
        stroke.strokeWidth = dp(4f)
        for (i in 0 until rays) {
            // Low frequencies at the bottom, mirrored left and right, treble meeting at the top.
            val band = if (i < count) i else rays - 1 - i
            val v = shown[band].coerceIn(0f, 1f)
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

    /** Average of the lowest bands: drives the artwork pulse and the ambient background. */
    fun bassEnergy(): Float {
        if (shown.isEmpty()) return 0f
        val n = min(3, shown.size)
        var sum = 0f
        for (i in 0 until n) sum += shown[i]
        return sum / n
    }

    companion object {
        /** Two readable accents from one base color. */
        fun accentPair(@ColorInt base: Int): Pair<Int, Int> =
            ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.35f) to base
    }
}
