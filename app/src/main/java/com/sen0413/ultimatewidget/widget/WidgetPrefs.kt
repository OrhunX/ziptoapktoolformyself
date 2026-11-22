package com.sen0413.ultimatewidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/** 1×1 MINI, 2×1 SMALL, 2×2 SQUARE, 4×1 SLIM, 4×2 MEDIUM, 4×4 LARGE. */
enum class SizeClass {
    MINI, SMALL, SQUARE, SLIM, MEDIUM, LARGE;

    companion object {
        /** Picks a layout from the size the launcher reports (dp). Works on every API level. */
        fun fromOptions(options: Bundle?, fallback: SizeClass = MEDIUM): SizeClass {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            if (width == 0 || height == 0) return fallback
            return when {
                width < 100 && height < 110 -> MINI
                height < 90 -> if (width >= 230) SLIM else SMALL
                width < 200 -> SQUARE
                height >= 200 -> LARGE
                else -> MEDIUM
            }
        }
    }
}

/**
 * GLASS: a translucent pane with a light edge; the wallpaper shows through.
 * CUSTOM: the user's own background color (with transparency) and bar color.
 */
enum class ThemeMode { COVER, DYNAMIC, GLASS, CUSTOM, DARK, LIGHT }

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
    /** ARGB; the alpha is the widget's transparency. Used by [ThemeMode.CUSTOM]. */
    val customBackground: Int = 0xE63A2E5C.toInt(),
    val customAccent: Int = 0xFFFF9F6E.toInt(),
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
            ?: SizeClass.fromOptions(AppWidgetManager.getInstance(context).getAppWidgetOptions(id), defaultSize(context, id))
    }

    /** The size the widget was picked at in the launcher's list (until the launcher reports it). */
    fun defaultSize(context: Context, id: Int): SizeClass {
        val provider = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className }.getOrNull()
        return when (provider) {
            MiniWidgetProvider::class.java.name -> SizeClass.MINI
            SmallWidgetProvider::class.java.name -> SizeClass.SMALL
            SquareWidgetProvider::class.java.name -> SizeClass.SQUARE
            SlimWidgetProvider::class.java.name -> SizeClass.SLIM
            LargeWidgetProvider::class.java.name -> SizeClass.LARGE
            else -> SizeClass.MEDIUM
        }
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
            customBackground = p.getInt("cbg_$id", 0xE63A2E5C.toInt()),
            customAccent = p.getInt("cacc_$id", 0xFFFF9F6E.toInt()),
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
            putInt("cbg_$id", config.customBackground)
            putInt("cacc_$id", config.customAccent)
        }
    }

    fun remove(context: Context, id: Int) {
        sizeCache.remove(id)
        configCache.remove(id)
        prefs(context).edit {
            listOf("size_", "theme_", "style_", "video_", "source_", "fav_", "cbg_", "cacc_").forEach { remove(it + id) }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
