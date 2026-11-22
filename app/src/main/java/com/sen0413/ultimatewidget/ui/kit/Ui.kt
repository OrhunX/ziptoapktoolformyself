package com.sen0413.ultimatewidget.ui.kit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.KnownApps

/**
 * The app's small design system: colors, type and a few view builders. Screens are built in
 * Kotlin with these helpers, so the look is defined in exactly one place.
 */
object Ink {
    const val NIGHT = 0xFF0E0C14.toInt()
    // Card fills on the ambient background; brighter in the Glass appearance (see Appearance).
    var SURFACE = 0x14FFFFFF
        private set
    var SURFACE_STRONG = 0x24FFFFFF
        private set
    var STROKE = 0x1FFFFFFF
        private set

    internal fun useGlass(glass: Boolean) {
        SURFACE = if (glass) 0x2BFFFFFF else 0x14FFFFFF
        SURFACE_STRONG = if (glass) 0x40FFFFFF else 0x24FFFFFF
        STROKE = if (glass) 0x52FFFFFF else 0x1FFFFFFF
    }
    const val PAPER = 0xFFF4F1EC.toInt()
    const val MIST = 0xFFC9C3D9.toInt()
    const val FAINT = 0x99C9C3D9.toInt()
    const val LAVENDER = 0xFFC4A8FF.toInt()
    const val VIOLET = 0xFF6B4FD8.toInt()
    const val EMBER = 0xFFFF9F6E.toInt()

    @ColorInt
    fun source(packageName: String?): Int = when (packageName) {
        null -> 0xFFFFC857.toInt()
        KnownApps.SPOTIFY -> 0xFF1ED760.toInt()
        KnownApps.YOUTUBE_MUSIC, KnownApps.YOUTUBE -> 0xFFFF7A7A.toInt()
        else -> 0xFF8FB8FF.toInt()
    }
}

/**
 * App-wide look: "Night" (default) or "Glass", an iOS-like frosted look in which the backdrop
 * is blurred (Android 12+) and cards become brighter, translucent panes with a light edge.
 * Read once per activity in onCreate; changing it recreates the activity.
 */
object Appearance {
    private const val PREFS = "appearance"
    private const val KEY_GLASS = "glass"

    var glass: Boolean = false
        private set

    fun load(context: Context) {
        glass = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GLASS, false)
        Ink.useGlass(glass)
    }

    fun setGlass(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GLASS, value).apply()
        load(context)
    }
}

/** Space Grotesk for display, DM Sans for text; falls back to the system font if missing. */
object Type {
    private var display: Typeface? = null
    private var displayMedium: Typeface? = null
    private var text: Typeface? = null
    private var textBold: Typeface? = null

    private fun load(context: Context, id: Int, fallback: Typeface): Typeface =
        runCatching { context.resources.getFont(id) }.getOrDefault(fallback)

    fun display(c: Context): Typeface =
        display ?: load(c, R.font.space_grotesk_bold, Typeface.DEFAULT_BOLD).also { display = it }

    fun displayMedium(c: Context): Typeface =
        displayMedium ?: load(c, R.font.space_grotesk_medium, Typeface.DEFAULT).also { displayMedium = it }

    fun text(c: Context): Typeface =
        text ?: load(c, R.font.dm_sans_regular, Typeface.DEFAULT).also { text = it }

    fun textBold(c: Context): Typeface =
        textBold ?: load(c, R.font.dm_sans_semibold, Typeface.DEFAULT_BOLD).also { textBold = it }
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dp(value: Int): Int = dp(value.toFloat()).toInt()
fun View.dp(value: Int): Int = context.dp(value)
fun View.dp(value: Float): Float = context.dp(value)

fun rounded(@ColorInt fill: Int, radius: Float, @ColorInt stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

/** Background plus a ripple that is clipped to the same rounded shape. */
fun pressable(@ColorInt fill: Int, radius: Float, @ColorInt stroke: Int = 0, strokeWidth: Int = 0): Drawable {
    val content = rounded(fill, radius, stroke, strokeWidth)
    val mask = rounded(Color.WHITE, radius)
    return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
}

enum class TextRole { DISPLAY, TITLE, HEADING, BODY, LABEL, CAPTION }

fun Context.label(
    text: CharSequence = "",
    role: TextRole = TextRole.BODY,
    @ColorInt color: Int = Ink.PAPER,
    maxLines: Int = 0,
): TextView = TextView(this).apply {
    this.text = text
    setTextColor(color)
    includeFontPadding = false
    when (role) {
        TextRole.DISPLAY -> { typeface = Type.display(context); textSize = 30f; letterSpacing = -0.01f }
        TextRole.TITLE -> { typeface = Type.display(context); textSize = 22f }
        TextRole.HEADING -> { typeface = Type.displayMedium(context); textSize = 16f }
        TextRole.BODY -> { typeface = Type.text(context); textSize = 15f; setLineSpacing(0f, 1.25f) }
        TextRole.LABEL -> { typeface = Type.textBold(context); textSize = 13f }
        TextRole.CAPTION -> { typeface = Type.text(context); textSize = 12.5f; setLineSpacing(0f, 1.2f) }
    }
    if (maxLines > 0) {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** A text button with a filled or outlined pill background; always at least 48dp tall. */
fun Context.pill(text: CharSequence, filled: Boolean = true, onClick: () -> Unit): TextView =
    label(text, TextRole.LABEL, if (filled) Ink.NIGHT else Ink.PAPER).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        background = if (filled) {
            pressable(Ink.LAVENDER, dp(24f))
        } else {
            pressable(Ink.SURFACE, dp(24f), Ink.STROKE, dp(1))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** A small selectable chip. */
fun Context.chip(text: CharSequence, onClick: () -> Unit): TextView =
    label(text, TextRole.LABEL, Ink.PAPER).apply {
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        setChipSelected(false)
    }

fun TextView.setChipSelected(selected: Boolean) {
    isSelected = selected
    setTextColor(if (selected) Ink.NIGHT else Ink.PAPER)
    background = if (selected) {
        pressable(Ink.PAPER, dp(20f))
    } else {
        pressable(Ink.SURFACE, dp(20f), Ink.STROKE, dp(1))
    }
}

/** An icon button with a 48dp touch target and a spoken description. */
fun Context.iconButton(
    @DrawableRes icon: Int,
    description: CharSequence,
    @ColorInt tint: Int = Ink.PAPER,
    sizeDp: Int = 48,
    onClick: () -> Unit,
): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(tint)
    contentDescription = description
    val pad = dp((sizeDp - 24) / 2)
    setPadding(pad, pad, pad, pad)
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, dp(sizeDp / 2f)))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.column(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        block()
    }

fun Context.row(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

/** A translucent "glass" card that sits on the ambient background. */
fun Context.card(block: LinearLayout.() -> Unit = {}): LinearLayout = column {
    background = rounded(Ink.SURFACE, dp(24f), Ink.STROKE, dp(1))
    setPadding(dp(20), dp(18), dp(20), dp(18))
    block()
}

fun LinearLayout.add(
    view: View,
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f,
    top: Int = 0,
    start: Int = 0,
    gravity: Int = Gravity.NO_GRAVITY,
): View {
    val params = LinearLayout.LayoutParams(width, height, weight)
    params.topMargin = top
    params.marginStart = start
    if (gravity != Gravity.NO_GRAVITY) params.gravity = gravity
    addView(view, params)
    return view
}

/** A small filled circle in the color of a music source. */
fun Context.sourceDot(packageName: String?, sizeDp: Int = 10): View = View(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Ink.source(packageName))
    }
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
}
