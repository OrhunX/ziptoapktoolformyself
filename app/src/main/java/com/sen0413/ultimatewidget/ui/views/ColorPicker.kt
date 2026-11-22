package com.sen0413.ultimatewidget.ui.views

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.label

/**
 * A color picker: a palette of ready-made colors, a saturation/brightness square, a hue bar
 * and (optionally) a transparency bar, with a live preview.
 */
object ColorPicker {
    private val PALETTE = intArrayOf(
        0xFF6B4FD8.toInt(), 0xFFC4A8FF.toInt(), 0xFFFF9F6E.toInt(), 0xFFFF6B8A.toInt(),
        0xFFFFC857.toInt(), 0xFF7BE0A6.toInt(), 0xFF4FC3F7.toInt(), 0xFF2E7DFF.toInt(),
        0xFF15131D.toInt(), 0xFF3A2E5C.toInt(), 0xFF8D6E63.toInt(), 0xFFF4F1EC.toInt(),
    )

    fun show(context: Context, title: CharSequence, initial: Int, withAlpha: Boolean, onPicked: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        var alpha = if (withAlpha) Color.alpha(initial) else 255

        val preview = View(context)
        val square = SatValSquare(context)
        val hue = HueBar(context)
        val alphaBar = AlphaBar(context)

        fun current(): Int = Color.HSVToColor(alpha, hsv)
        fun sync() {
            square.hue = hsv[0]
            square.sat = hsv[1]
            square.value = hsv[2]
            hue.hue = hsv[0]
            alphaBar.color = Color.HSVToColor(hsv)
            alphaBar.alphaValue = alpha / 255f
            preview.background = GradientDrawable().apply {
                cornerRadius = context.dp(14f)
                setColor(current())
                setStroke(context.dp(1), Ink.STROKE)
            }
        }
        square.onChange = { s, v -> hsv[1] = s; hsv[2] = v; sync() }
        hue.onChange = { h -> hsv[0] = h; sync() }
        alphaBar.onChange = { a -> alpha = (a * 255).toInt(); sync() }

        val swatches = GridLayout(context).apply {
            columnCount = 6
            PALETTE.forEach { color ->
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(context.dp(1), 0x40FFFFFF)
                    }
                    contentDescription = String.format("#%06X", 0xFFFFFF and color)
                    setOnClickListener {
                        Color.colorToHSV(color, hsv)
                        sync()
                    }
                }, GridLayout.LayoutParams().apply {
                    width = context.dp(36)
                    height = context.dp(36)
                    setMargins(context.dp(5), context.dp(5), context.dp(5), context.dp(5))
                })
            }
        }

        val body = context.column {
            setPadding(dp(22), dp(12), dp(22), 0)
            add(swatches, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(square, height = dp(170), top = dp(12))
            add(hue, height = dp(28), top = dp(12))
            if (withAlpha) {
                add(context.label(context.getString(R.string.color_transparency), TextRole.CAPTION, Ink.MIST), top = dp(12))
                add(alphaBar, height = dp(28), top = dp(6))
            }
            add(preview, height = dp(40), top = dp(14))
        }
        sync()

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(android.widget.ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.color_apply) { _, _ -> onPicked(current()) }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    /** Small round swatch showing a chosen color, for buttons. */
    fun swatch(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(context.dp(2), 0x66FFFFFF)
    }

    // ---------------------------------------------------------------- views

    private class SatValSquare(context: Context) : View(context) {
        var hue = 0f
            set(value) { field = value; invalidate() }
        var sat = 1f
            set(value) { field = value; invalidate() }
        var value = 1f
            set(v) { field = v; invalidate() }
        var onChange: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            color = Color.WHITE
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            val horizontal = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
            val vertical = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            paint.shader = ComposeShader(vertical, horizontal, PorterDuff.Mode.MULTIPLY)
            val r = context.dp(14f)
            canvas.drawRoundRect(rect, r, r, paint)
            canvas.drawCircle(sat * width, (1f - value) * height, context.dp(9f), knob)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val s = (event.x / width).coerceIn(0f, 1f)
            val v = 1f - (event.y / height).coerceIn(0f, 1f)
            onChange?.invoke(s, v)
            return true
        }
    }

    private class HueBar(context: Context) : View(context) {
        var hue = 0f
            set(value) { field = value; invalidate() }
        var onChange: ((Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            canvas.drawCircle(hue / 360f * width, height / 2f, r - context.dp(2f), knob)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            onChange?.invoke((event.x / width).coerceIn(0f, 1f) * 359.9f)
            return true
        }
    }

    private class AlphaBar(context: Context) : View(context) {
        var color = Color.WHITE
            set(value) { field = value; invalidate() }
        var alphaValue = 1f
            set(value) { field = value; invalidate() }
        var onChange: ((Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val checker = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x33FFFFFF }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            this.color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, checker)
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f, color and 0x00FFFFFF, color or 0xFF000000.toInt(), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            canvas.drawCircle(alphaValue * width, height / 2f, r - context.dp(2f), knob)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            onChange?.invoke((event.x / width).coerceIn(0f, 1f))
            return true
        }
    }
}
