package com.sen0413.ultimatewidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.sen0413.ultimatewidget.hub.SourceSnapshot

data class WidgetColors(
    @ColorInt val background: Int,
    @ColorInt val onBackground: Int,
    @ColorInt val onBackgroundMuted: Int,
    @ColorInt val accent: Int,
)

/**
 * Theme colors for the widget: from the album cover (Palette), from the wallpaper
 * (Material You, Android 12+), or fixed dark/light. Text always meets 4.5:1 contrast.
 */
object WidgetTheme {
    private const val PAPER = 0xFFF4F1EC.toInt()
    private const val NIGHT = 0xFF15131D.toInt()

    val DARK = WidgetColors(NIGHT, PAPER, 0xFFC9C3D9.toInt(), 0xFFC4A8FF.toInt())
    private val LIGHT = WidgetColors(PAPER, NIGHT, 0xFF4D4860.toInt(), 0xFF6B4FD8.toInt())
    private val DEFAULT_COVER = WidgetColors(0xFF3A2E5C.toInt(), PAPER, 0xFFC9C3D9.toInt(), 0xFFFF9F6E.toInt())

    private val cache = LruCache<String, WidgetColors>(24)

    fun colors(context: Context, mode: ThemeMode, source: SourceSnapshot): WidgetColors = when (mode) {
        ThemeMode.DARK -> DARK
        ThemeMode.LIGHT -> LIGHT
        ThemeMode.DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamic(context) else DARK
        ThemeMode.COVER -> fromCover(context, source)
        ThemeMode.GLASS -> glass(context, source)
    }

    /** Glass: light text on a translucent pane; the accent still comes from the cover. */
    private fun glass(context: Context, source: SourceSnapshot): WidgetColors {
        val accent = fromCover(context, source).accent
        val brightAccent = if (android.graphics.Color.luminance(accent) < 0.25f) ColorUtils.blendARGB(accent, PAPER, 0.45f) else accent
        return WidgetColors(NIGHT, PAPER, 0xFFE4E0EC.toInt(), brightAccent)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dynamic(context: Context): WidgetColors = readable(
        background = context.getColor(android.R.color.system_accent1_800),
        accent = context.getColor(android.R.color.system_accent2_200),
    )

    private fun fromCover(context: Context, source: SourceSnapshot): WidgetColors {
        val track = source.track ?: return DEFAULT_COVER
        val key = listOf(source.packageName, track.mediaId, track.title, track.coverRes,
            System.identityHashCode(track.art)).joinToString("|")
        cache.get(key)?.let { return it }

        val bitmap = track.art ?: track.coverRes.takeIf { it != 0 }?.let { decodeSmall(context, it) }
        val result = bitmap?.let { bmp ->
            val palette = Palette.from(bmp).maximumColorCount(16).generate()
            readable(
                background = palette.getDarkVibrantColor(palette.getDarkMutedColor(DEFAULT_COVER.background)),
                accent = palette.getVibrantColor(palette.getLightVibrantColor(DEFAULT_COVER.accent)),
            )
        } ?: DEFAULT_COVER
        cache.put(key, result)
        return result
    }

    /** Picks light or dark text, whichever contrasts more with the background (target 4.5:1). */
    private fun readable(@ColorInt background: Int, @ColorInt accent: Int): WidgetColors {
        val bg = ColorUtils.setAlphaComponent(background, 255)
        val lightContrast = ColorUtils.calculateContrast(PAPER, bg)
        val darkContrast = ColorUtils.calculateContrast(NIGHT, bg)
        val on = if (lightContrast >= darkContrast) PAPER else NIGHT
        val muted = ColorUtils.blendARGB(on, bg, 0.25f)
        val opaqueAccent = ColorUtils.setAlphaComponent(accent, 255)
        val safeAccent = if (ColorUtils.calculateContrast(opaqueAccent, bg) >= 3.0) opaqueAccent else on
        return WidgetColors(bg, on, muted, safeAccent)
    }

    private fun decodeSmall(context: Context, @DrawableRes resId: Int): Bitmap? =
        BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply { inSampleSize = 4 })
}
