package com.sen0413.ultimatewidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

enum class SizeClass {
    SMALL, MEDIUM, LARGE;

    companion object {
        /** Picks a layout from the size the launcher reports (dp). Works on every API level. */
        fun fromOptions(options: Bundle?): SizeClass {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            if (width == 0 || height == 0) return MEDIUM
            return when {
                width >= 200 && height >= 200 -> LARGE
                width >= 200 && height >= 90 -> MEDIUM
                else -> SMALL
            }
        }
    }
}

/** GLASS: a translucent pane with a light edge; the wallpaper shows through. */
enum class ThemeMode { COVER, DYNAMIC, GLASS, DARK, LIGHT }

/**
 * DOTS, LED and RAINBOW need API 31 (per-view sizes and tints in RemoteViews); older
 * versions draw them as BARS. CENTER puts the bass in the middle and works everywhere.
 */
enum class VisualizerStyle { BARS, MIRROR, DOTS, LED, CENTER, RAINBOW, OFF }

data class WidgetConfig(
    val theme: ThemeMode = ThemeMode.COVER,
    val style: VisualizerStyle = VisualizerStyle.BARS,
    val showVideo: Boolean = true,
    val showSource: Boolean = true,
    val showFavorite: Boolean = true,
)

/**
 * Per-widget settings keyed by appWidgetId, so two widgets can look completely different.
 * Values are cached in memory because visualizer frames read them up to 20 times a second.
 */
object WidgetPrefs {
    private const val FILE = "widget_prefs"
    private val sizeCache = ConcurrentHashMap<Int, SizeClass>()
    private val configCache = ConcurrentHashMap<Int, WidgetConfig>()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun size(context: Context, id: Int): SizeClass = sizeCache.getOrPut(id) {
        prefs(context).getString("size_$id", null)?.let { enumOrNull<SizeClass>(it) }
            ?: SizeClass.fromOptions(AppWidgetManager.getInstance(context).getAppWidgetOptions(id))
    }

    fun setSize(context: Context, id: Int, size: SizeClass) {
        sizeCache[id] = size
        prefs(context).edit { putString("size_$id", size.name) }
    }

    fun config(context: Context, id: Int): WidgetConfig = configCache.getOrPut(id) {
        val p = prefs(context)
        WidgetConfig(
            theme = p.getString("theme_$id", null)?.let { enumOrNull<ThemeMode>(it) } ?: ThemeMode.COVER,
            style = p.getString("style_$id", null)?.let { enumOrNull<VisualizerStyle>(it) } ?: VisualizerStyle.BARS,
            showVideo = p.getBoolean("video_$id", true),
            showSource = p.getBoolean("source_$id", true),
            showFavorite = p.getBoolean("fav_$id", true),
        )
    }

    fun setConfig(context: Context, id: Int, config: WidgetConfig) {
        configCache[id] = config
        prefs(context).edit {
            putString("theme_$id", config.theme.name)
            putString("style_$id", config.style.name)
            putBoolean("video_$id", config.showVideo)
            putBoolean("source_$id", config.showSource)
            putBoolean("fav_$id", config.showFavorite)
        }
    }

    fun remove(context: Context, id: Int) {
        sizeCache.remove(id)
        configCache.remove(id)
        prefs(context).edit {
            listOf("size_", "theme_", "style_", "video_", "source_", "fav_").forEach { remove(it + id) }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
