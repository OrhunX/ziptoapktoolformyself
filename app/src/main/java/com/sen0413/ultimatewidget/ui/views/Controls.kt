package com.sen0413.ultimatewidget.ui.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.annotation.ColorInt
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.dp

/** The main transport button: a disc whose icon morphs between play and pause. */
class PlayPauseView(context: Context) : View(context) {
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.PAPER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.NIGHT }
    private val path = Path()
    private var progress = 0f // 0 = play triangle, 1 = pause bars
    private var animator: ValueAnimator? = null

    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    fun setColors(@ColorInt discColor: Int, @ColorInt iconColor: Int) {
        disc.color = discColor
        icon.color = iconColor
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)
        canvas.drawCircle(cx, cy, radius * (if (isPressed) 0.94f else 1f), disc)

        val size = radius * 0.72f           // icon box
        val left = cx - size / 2f
        val right = cx + size / 2f
        val top = cy - size / 2f
        val bottom = cy + size / 2f
        val p = progress
        val nudge = size * 0.08f * (1f - p)  // the triangle looks centered when shifted right
        val barWidth = size * 0.32f

        // Left half: triangle's back half -> left bar.
        path.reset()
        path.moveTo(left + nudge, top)
        path.lineTo(lerp(cx + nudge, left + barWidth, p), lerp(top + size / 4f, top, p))
        path.lineTo(lerp(cx + nudge, left + barWidth, p), lerp(bottom - size / 4f, bottom, p))
        path.lineTo(left + nudge, bottom)
        path.close()
        canvas.drawPath(path, icon)

        // Right half: triangle's tip -> right bar.
        path.reset()
        path.moveTo(lerp(cx + nudge, right - barWidth, p), lerp(top + size / 4f, top, p))
        path.lineTo(right + nudge, lerp(cy, top, p))
        path.lineTo(right + nudge, lerp(cy, bottom, p))
        path.lineTo(lerp(cx + nudge, right - barWidth, p), lerp(bottom - size / 4f, bottom, p))
        path.close()
        canvas.drawPath(path, icon)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** A slim seek bar: tap or drag to scrub, the thumb appears only while touched. */
class ScrubberView(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.PAPER }
    private var dragging = false
    private var dragFraction = 0f

    /** 0..1 playback progress; ignored while the user is dragging. */
    var fraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            if (!dragging) invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null
    var onPreview: ((Float?) -> Unit)? = null

    fun setColor(@ColorInt color: Int) {
        fill.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val thickness = dp(if (dragging) 6f else 4f)
        val radius = thickness / 2f
        val shown = if (dragging) dragFraction else fraction
        canvas.drawRoundRect(0f, cy - radius, width.toFloat(), cy + radius, radius, radius, track)
        canvas.drawRoundRect(0f, cy - radius, width * shown, cy + radius, radius, radius, fill)
        if (dragging) canvas.drawCircle((width * shown).coerceIn(dp(8f), width - dp(8f)), cy, dp(8f), fill)
    }

    @SuppressLint("ClickableViewAccessibility") // seeking is also offered by the system media controls
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragFraction = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                onPreview?.invoke(dragFraction)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                fraction = dragFraction
                onPreview?.invoke(null)
                onSeek?.invoke(dragFraction)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onPreview?.invoke(null)
                invalidate()
            }
        }
        return true
    }
}

/** Album art with rounded corners and a soft shadow. */
class ArtworkView(context: Context) : ImageView(context) {
    var cornerRadius: Float = dp(28f)
        set(value) {
            field = value
            invalidateOutline()
        }

    init {
        scaleType = ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }
}
