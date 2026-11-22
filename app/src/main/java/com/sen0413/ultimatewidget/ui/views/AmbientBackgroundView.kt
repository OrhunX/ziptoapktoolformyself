package com.sen0413.ultimatewidget.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.sen0413.ultimatewidget.ui.kit.Appearance
import com.sen0413.ultimatewidget.ui.kit.Ink
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The living backdrop behind every screen: three soft color fields that take their colors
 * from the album cover, drift slowly and swell with the bass. It only animates while music
 * plays (and never when the user has disabled animations), so an idle app costs nothing.
 */
class AmbientBackgroundView(context: Context) : View(context) {

    private class Blob(
        val phaseX: Float,
        val phaseY: Float,
        val speed: Float,
        val size: Float,
        @ColorInt var color: Int,
    ) {
        @ColorInt var target: Int = color
        var shader: Shader? = null
        @ColorInt var shaderColor: Int = 0
    }

    private val blobs = arrayOf(
        Blob(0.0f, 1.3f, 0.21f, 0.95f, Ink.VIOLET),
        Blob(2.1f, 0.2f, 0.16f, 0.80f, Ink.EMBER),
        Blob(4.0f, 3.1f, 0.12f, 1.05f, 0xFF2E2450.toInt()),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val startedAt = SystemClock.uptimeMillis()
    private var frozenTime = 0f
    private var energy = 0f

    /** True while music plays: the fields drift and react. */
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 0..1 bass energy from the visualizer. */
    var targetEnergy: Float = 0f

    /** Glass appearance: vivid fields under a strong blur, the frosted pane behind the cards. */
    private val glass = Appearance.glass
    private val fieldAlpha = if (glass) 185 else 120

    init {
        if (glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
        }
    }

    private var lastCover = Triple(Ink.VIOLET, Ink.EMBER, 0xFF2E2450.toInt())

    /** Colors from the album cover; replaced by the user's own color when one is chosen in Setup. */
    fun setColors(@ColorInt primary: Int, @ColorInt secondary: Int, @ColorInt deep: Int) {
        lastCover = Triple(primary, secondary, deep)
        val custom = Appearance.backdrop
        if (custom != 0) {
            blobs[0].target = custom
            blobs[1].target = ColorUtils.blendARGB(custom, 0xFFFFFFFF.toInt(), 0.35f)
            blobs[2].target = ColorUtils.blendARGB(custom, 0xFF000000.toInt(), 0.55f)
        } else {
            blobs[0].target = primary
            blobs[1].target = secondary
            blobs[2].target = deep
        }
        invalidate()
    }

    /** Re-applies after the backdrop setting changed. */
    fun refreshColors() = setColors(lastCover.first, lastCover.second, lastCover.third)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ink.NIGHT)
        val animate = playing && ValueAnimator.areAnimatorsEnabled()
        val time = if (animate) (SystemClock.uptimeMillis() - startedAt) / 1000f else frozenTime
        frozenTime = time
        energy += ((if (animate) targetEnergy else 0f) - energy) * 0.12f

        val w = width.toFloat()
        val h = height.toFloat()
        val base = min(w, h)
        var settling = false
        for (blob in blobs) {
            if (blob.color != blob.target) {
                blob.color = ColorUtils.blendARGB(blob.color, blob.target, 0.06f)
                if (closeEnough(blob.color, blob.target)) blob.color = blob.target else settling = true
            }
            if (blob.shader == null || blob.shaderColor != blob.color) {
                blob.shaderColor = blob.color
                blob.shader = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(ColorUtils.setAlphaComponent(blob.color, fieldAlpha), ColorUtils.setAlphaComponent(blob.color, 0)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            val cx = w * (0.5f + 0.42f * sin(time * blob.speed + blob.phaseX))
            val cy = h * (0.42f + 0.36f * cos(time * blob.speed * 0.8f + blob.phaseY))
            val radius = base * blob.size * (1f + (if (glass) 0.16f else 0.10f) * energy)
            paint.shader = blob.shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(radius, radius)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        if (animate || settling) postInvalidateOnAnimation()
    }

    private fun closeEnough(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a) - Color.red(b)) < 3 &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) < 3 &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 3
}
