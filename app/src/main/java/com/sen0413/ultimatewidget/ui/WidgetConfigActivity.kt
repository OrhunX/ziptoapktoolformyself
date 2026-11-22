package com.sen0413.ultimatewidget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.chip
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.rounded
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.kit.setChipSelected
import com.sen0413.ultimatewidget.widget.SizeClass
import com.sen0413.ultimatewidget.widget.ThemeMode
import com.sen0413.ultimatewidget.widget.VisualizerStyle
import com.sen0413.ultimatewidget.widget.WidgetConfig
import com.sen0413.ultimatewidget.widget.WidgetPrefs
import com.sen0413.ultimatewidget.widget.WidgetRenderer

/**
 * The widget editor: opens when the widget is added (android:configure) and again from a
 * long-press (widgetFeatures="reconfigurable"). The preview is the real widget layout,
 * inflated in-app with RemoteViews.apply(), so what you see is exactly what you get.
 */
class WidgetConfigActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var config = WidgetConfig()
    private lateinit var preview: FrameLayout

    private val themeChips = LinkedHashMap<ThemeMode, TextView>()
    private val styleChips = LinkedHashMap<VisualizerStyle, TextView>()
    private lateinit var videoChip: TextView
    private lateinit var sourceChip: TextView
    private lateinit var favoriteChip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.sen0413.ultimatewidget.ui.kit.Appearance.load(this)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out, the launcher must not add the widget.
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        config = WidgetPrefs.config(this, appWidgetId)
        setContentView(buildContent())
        sync()
    }

    private fun buildContent(): View {
        preview = FrameLayout(this).apply {
            // A wallpaper-like gradient, so the Glass theme shows what it really looks like.
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3A2E5C.toInt(), 0xFF6B4FD8.toInt(), 0xFFFF9F6E.toInt()),
            ).apply { cornerRadius = dp(28f) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val body = column {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            add(label(getString(R.string.editor_title), TextRole.DISPLAY))
            add(label(getString(R.string.editor_subtitle), TextRole.BODY, Ink.MIST), top = dp(4))
            add(preview, height = dp(164), top = dp(18))

            add(label(getString(R.string.editor_theme), TextRole.HEADING), top = dp(22))
            add(scrollRow {
                ThemeMode.values().forEachIndexed { i, mode ->
                    val chip = chip(getString(themeLabel(mode))) { update(config.copy(theme = mode)) }
                    themeChips[mode] = chip
                    add(chip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = if (i > 0) dp(8) else 0)
                }
            }, top = dp(10))

            add(label(getString(R.string.editor_visualizer), TextRole.HEADING), top = dp(20))
            add(scrollRow {
                VisualizerStyle.values().forEachIndexed { i, style ->
                    val chip = chip(getString(styleLabel(style))) { update(config.copy(style = style)) }
                    styleChips[style] = chip
                    add(chip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = if (i > 0) dp(8) else 0)
                }
            }, top = dp(10))

            videoChip = chip(getString(R.string.editor_show_video)) { update(config.copy(showVideo = !config.showVideo)) }
            sourceChip = chip(getString(R.string.editor_show_source)) { update(config.copy(showSource = !config.showSource)) }
            favoriteChip = chip(getString(R.string.editor_show_favorite)) {
                update(config.copy(showFavorite = !config.showFavorite))
            }
            add(scrollRow {
                add(sourceChip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                add(videoChip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
                add(favoriteChip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
            }, top = dp(20))

            add(row {
                add(pill(getString(R.string.editor_cancel), filled = false) { finish() }, ViewGroup.LayoutParams.WRAP_CONTENT)
                add(pill(getString(R.string.editor_save)) { save() }, 0, weight = 1f, start = dp(12))
            }, top = dp(28))
        }
        return ScrollView(this).apply {
            setBackgroundColor(Ink.NIGHT)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, topInset(insets), 0, bottomInset(insets))
                insets
            }
        }
    }

    private fun scrollRow(block: android.widget.LinearLayout.() -> Unit): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row(block))
        }

    private fun update(next: WidgetConfig) {
        config = next
        sync()
    }

    /** Reflects [config] in the chips and re-inflates the real widget layout as the preview. */
    private fun sync() {
        themeChips.forEach { (mode, chip) -> chip.setChipSelected(mode == config.theme) }
        styleChips.forEach { (style, chip) -> chip.setChipSelected(style == config.style) }
        videoChip.setChipSelected(config.showVideo)
        sourceChip.setChipSelected(config.showSource)
        favoriteChip.setChipSelected(config.showFavorite)

        val views = WidgetRenderer.build(
            context = this,
            appWidgetId = appWidgetId,
            state = HubController.state.value,
            size = SizeClass.MEDIUM,
            config = config,
            bars = SAMPLE_BARS,
            preview = true,
        )
        preview.removeAllViews()
        runCatching { preview.addView(views.apply(this, preview)) }
    }

    private fun save() {
        WidgetPrefs.setConfig(this, appWidgetId, config)
        WidgetRenderer.render(this, appWidgetId, HubController.state.value, forceFull = true)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun themeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.COVER -> R.string.theme_cover
        ThemeMode.DYNAMIC -> R.string.theme_dynamic
        ThemeMode.GLASS -> R.string.theme_glass
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.LIGHT -> R.string.theme_light
    }

    private fun styleLabel(style: VisualizerStyle): Int = when (style) {
        VisualizerStyle.BARS -> R.string.style_bars
        VisualizerStyle.MIRROR -> R.string.style_mirror
        VisualizerStyle.DOTS -> R.string.style_dots
        VisualizerStyle.LED -> R.string.style_led
        VisualizerStyle.CENTER -> R.string.style_center
        VisualizerStyle.RAINBOW -> R.string.style_rainbow
        VisualizerStyle.OFF -> R.string.style_off
    }

    @Suppress("DEPRECATION")
    private fun topInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).top
        } else {
            insets.systemWindowInsetTop
        }

    @Suppress("DEPRECATION")
    private fun bottomInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            insets.systemWindowInsetBottom
        }

    private companion object {
        val SAMPLE_BARS = floatArrayOf(
            0.35f, 0.6f, 0.45f, 0.8f, 0.55f, 0.9f, 0.4f, 0.7f, 0.5f, 0.75f, 0.42f, 0.62f, 0.3f, 0.5f, 0.25f, 0.4f,
        )
    }
}
