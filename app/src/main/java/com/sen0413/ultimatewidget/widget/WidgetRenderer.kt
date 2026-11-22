package com.sen0413.ultimatewidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.hub.KnownApps
import com.sen0413.ultimatewidget.hub.SourceSnapshot
import com.sen0413.ultimatewidget.hub.VisualizerStatus
import com.sen0413.ultimatewidget.ui.MainActivity
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds RemoteViews from [HubState]. The widget only renders state; every decision lives in
 * the hub. Updates are always as small as possible:
 *  - first render of a widget or a size change: full update,
 *  - state changes: partial update,
 *  - visualizer frames: partial update containing only the bar heights.
 */
object WidgetRenderer {
    private val BAR_IDS = intArrayOf(
        R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6, R.id.bar7,
        R.id.bar8, R.id.bar9, R.id.bar10, R.id.bar11, R.id.bar12, R.id.bar13, R.id.bar14, R.id.bar15,
    )
    private const val MIN_BAR_DP = 3f
    private const val DOT_DP = 5f

    private const val REQ_OPEN = 9_001
    private const val REQ_MIC = 9_002
    private const val REQ_VIDEO = 20_000
    private const val REQ_QUEUE = 40_000

    private val initialized: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val colorsById = ConcurrentHashMap<Int, WidgetColors>()

    @Volatile
    private var cachedIds: IntArray? = null

    fun colorsFor(appWidgetId: Int): WidgetColors = colorsById[appWidgetId] ?: WidgetTheme.DARK

    fun widgetIds(context: Context): IntArray = cachedIds ?: AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, HubWidgetProvider::class.java))
        .also { cachedIds = it }

    fun invalidateIds() {
        cachedIds = null
    }

    fun forget(appWidgetId: Int) {
        initialized.remove(appWidgetId)
        colorsById.remove(appWidgetId)
    }

    fun renderAll(context: Context, state: HubState) {
        widgetIds(context).forEach { render(context, it, state) }
    }

    fun render(context: Context, appWidgetId: Int, state: HubState, forceFull: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val views = build(context, appWidgetId, state)
        if (initialized.add(appWidgetId) || forceFull) {
            manager.updateAppWidget(appWidgetId, views)
        } else {
            manager.partiallyUpdateAppWidget(appWidgetId, views)
        }
    }

    /** Visualizer frame: a partial update with nothing but bar heights. */
    fun pushBars(context: Context, bars: FloatArray) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in widgetIds(context)) {
            if (id !in initialized) continue
            val style = WidgetPrefs.config(context, id).style
            if (style == VisualizerStyle.OFF) continue
            val size = WidgetPrefs.size(context, id)
            val views = RemoteViews(context.packageName, layoutFor(size))
            applyBarHeights(views, size, style, bars)
            manager.partiallyUpdateAppWidget(id, views)
        }
    }

    @Suppress("DEPRECATION") // still the way to refresh a RemoteViewsService-backed list
    fun notifyQueueChanged(context: Context) {
        val ids = widgetIds(context).filter { WidgetPrefs.size(context, it) == SizeClass.LARGE }
        if (ids.isNotEmpty()) {
            AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(ids.toIntArray(), R.id.queue)
        }
    }

    fun build(
        context: Context,
        appWidgetId: Int,
        state: HubState,
        size: SizeClass = WidgetPrefs.size(context, appWidgetId),
        config: WidgetConfig = WidgetPrefs.config(context, appWidgetId),
        bars: FloatArray = VisualizerEngine.bars.value,
        preview: Boolean = false,
    ): RemoteViews {
        val source = state.current
        val track = source.track
        val colors = WidgetTheme.colors(context, config.theme, source)
        if (!preview) colorsById[appWidgetId] = colors

        val views = RemoteViews(context.packageName, layoutFor(size))
        if (config.theme == ThemeMode.GLASS) {
            // White tint keeps the pane's own alpha: a translucent fill with a brighter edge.
            views.setImageViewResource(R.id.bg, R.drawable.widget_bg_glass)
            views.setInt(R.id.bg, "setColorFilter", android.graphics.Color.WHITE)
        } else {
            views.setImageViewResource(R.id.bg, R.drawable.widget_bg)
            views.setInt(R.id.bg, "setColorFilter", colors.background)
        }

        val title = track?.title ?: context.getString(R.string.nothing_playing)
        views.setTextViewText(R.id.title, title)
        views.setTextColor(R.id.title, colors.onBackground)

        // Play / pause exists in every size.
        views.setInt(R.id.play_circle, "setColorFilter", colors.onBackground)
        views.setImageViewResource(R.id.btn_play, if (source.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        views.setInt(R.id.btn_play, "setColorFilter", colors.background)
        views.setContentDescription(
            R.id.play_box,
            context.getString(if (source.isPlaying) R.string.cd_pause else R.string.cd_play, title),
        )
        views.setOnClickPendingIntent(R.id.play_box, actionIntent(context, HubWidgetProvider.ACTION_PLAY_PAUSE, appWidgetId))

        applyVisualizer(context, views, size, config, bars, colors, state.visualizer)

        if (size != SizeClass.SMALL) {
            views.setTextViewText(R.id.artist, track?.artist.orEmpty())
            views.setTextColor(R.id.artist, colors.onBackgroundMuted)
            applyCover(views, source)
            views.setOnClickPendingIntent(R.id.cover, openAppIntent(context))
            applySourceChip(context, views, source, config, appWidgetId)
            applyVideoChip(context, views, source, config, appWidgetId)
            applyFavorite(context, views, state.isFavorite, config, colors, appWidgetId)
            applyNavigation(context, views, size, colors, appWidgetId)

            val now = SystemClock.elapsedRealtime()
            val measuredAt = if (source.positionAtElapsedMs > 0) source.positionAtElapsedMs else now
            views.setChronometer(R.id.elapsed, measuredAt - source.positionMs, null, source.isPlaying)
            views.setTextColor(R.id.elapsed, colors.onBackgroundMuted)
        }

        if (size == SizeClass.LARGE) applyQueue(context, views, appWidgetId, colors)
        return views
    }

    // ---------------------------------------------------------------- parts

    private fun applyVisualizer(
        context: Context,
        views: RemoteViews,
        size: SizeClass,
        config: WidgetConfig,
        bars: FloatArray,
        colors: WidgetColors,
        status: VisualizerStatus,
    ) {
        if (config.style == VisualizerStyle.OFF) {
            views.setViewVisibility(R.id.bars, View.GONE)
            views.setViewVisibility(R.id.vis_hint, View.GONE)
            return
        }
        views.setViewVisibility(R.id.bars, View.VISIBLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val centered = config.style == VisualizerStyle.MIRROR || config.style == VisualizerStyle.CENTER
            views.setInt(R.id.bars, "setGravity", if (centered) Gravity.CENTER_VERTICAL else Gravity.BOTTOM)
            val count = barCount(size)
            val shape = if (config.style == VisualizerStyle.LED) R.drawable.bar_led else R.drawable.bar_shape
            for (i in 0 until count) {
                views.setImageViewResource(BAR_IDS[i], shape)
                val color = if (config.style == VisualizerStyle.RAINBOW) rainbow(colors.accent, i, count) else colors.accent
                views.setInt(BAR_IDS[i], "setColorFilter", color)
            }
        }
        applyBarHeights(views, size, config.style, bars)

        val hint = when (status) {
            VisualizerStatus.NEEDS_PERMISSION -> R.string.vis_needs_permission
            VisualizerStatus.NO_AUDIO -> R.string.vis_no_audio
            else -> 0
        }
        if (hint == 0) {
            views.setViewVisibility(R.id.vis_hint, View.GONE)
        } else {
            views.setViewVisibility(R.id.vis_hint, View.VISIBLE)
            views.setTextViewText(R.id.vis_hint, context.getString(hint))
            views.setTextColor(R.id.vis_hint, colors.onBackground)
            views.setOnClickPendingIntent(
                R.id.vis_hint,
                openAppIntent(context, requestMic = status == VisualizerStatus.NEEDS_PERMISSION),
            )
        }
    }

    private fun applyBarHeights(views: RemoteViews, size: SizeClass, style: VisualizerStyle, bars: FloatArray) {
        val count = barCount(size)
        val values = if (style == VisualizerStyle.CENTER) centerOut(bars, count) else resample(bars, count)
        val maxDp = maxBarDp(size)
        val dots = style == VisualizerStyle.DOTS
        for (i in 0 until count) {
            val v = values[i].coerceIn(0f, 1f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Bars grow from the baseline; dots keep their size and ride on a bottom margin.
                val height = if (dots) DOT_DP else MIN_BAR_DP + v * (maxDp - MIN_BAR_DP)
                val lift = if (dots) v * (maxDp - DOT_DP) else 0f
                views.setViewLayoutHeight(BAR_IDS[i], height, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(BAR_IDS[i], RemoteViews.MARGIN_BOTTOM, lift, TypedValue.COMPLEX_UNIT_DIP)
            } else {
                views.setProgressBar(BAR_IDS[i], 1000, (40 + v * 960).toInt(), false)
            }
        }
    }

    private fun applyCover(views: RemoteViews, source: SourceSnapshot) {
        val track = source.track
        when {
            track?.art != null -> views.setImageViewBitmap(R.id.cover, track.art)
            track != null && track.coverRes != 0 -> views.setImageViewResource(R.id.cover, track.coverRes)
            else -> views.setImageViewResource(R.id.cover, R.drawable.cover_placeholder)
        }
    }

    private fun applySourceChip(
        context: Context,
        views: RemoteViews,
        source: SourceSnapshot,
        config: WidgetConfig,
        appWidgetId: Int,
    ) {
        views.setViewVisibility(R.id.source_chip, if (config.showSource) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.source_chip, source.appLabel)
        views.setInt(R.id.source_chip, "setBackgroundResource", chipBackground(source.packageName))
        views.setContentDescription(R.id.source_chip, context.getString(R.string.cd_source, source.appLabel))
        views.setOnClickPendingIntent(
            R.id.source_chip,
            actionIntent(context, HubWidgetProvider.ACTION_SWITCH_SOURCE, appWidgetId),
        )
    }

    private fun applyVideoChip(
        context: Context,
        views: RemoteViews,
        source: SourceSnapshot,
        config: WidgetConfig,
        appWidgetId: Int,
    ) {
        val intent = if (config.showVideo) VideoLinks.intentFor(context, source) else null
        if (intent == null) {
            views.setViewVisibility(R.id.video_chip, View.GONE)
            return
        }
        views.setViewVisibility(R.id.video_chip, View.VISIBLE)
        // Activities must be started directly from the widget click (background-start rules).
        views.setOnClickPendingIntent(
            R.id.video_chip,
            PendingIntent.getActivity(
                context, REQ_VIDEO + appWidgetId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    private fun applyFavorite(
        context: Context,
        views: RemoteViews,
        isFavorite: Boolean,
        config: WidgetConfig,
        colors: WidgetColors,
        appWidgetId: Int,
    ) {
        if (!config.showFavorite) {
            views.setViewVisibility(R.id.btn_fav, View.GONE)
            return
        }
        views.setViewVisibility(R.id.btn_fav, View.VISIBLE)
        views.setImageViewResource(R.id.btn_fav, if (isFavorite) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        views.setInt(R.id.btn_fav, "setColorFilter", if (isFavorite) colors.accent else colors.onBackground)
        views.setContentDescription(
            R.id.btn_fav,
            context.getString(if (isFavorite) R.string.cd_favorite_remove else R.string.cd_favorite_add),
        )
        views.setOnClickPendingIntent(R.id.btn_fav, actionIntent(context, HubWidgetProvider.ACTION_FAVORITE, appWidgetId))
    }

    private fun applyNavigation(
        context: Context,
        views: RemoteViews,
        size: SizeClass,
        colors: WidgetColors,
        appWidgetId: Int,
    ) {
        val next = Triple(R.id.btn_next, HubWidgetProvider.ACTION_NEXT, R.string.cd_next)
        val previous = Triple(R.id.btn_prev, HubWidgetProvider.ACTION_PREVIOUS, R.string.cd_previous)
        // 4×2 has room for play + next only; 4×4 shows all three.
        val buttons = if (size == SizeClass.LARGE) listOf(previous, next) else listOf(next)
        for ((viewId, action, description) in buttons) {
            views.setInt(viewId, "setColorFilter", colors.onBackground)
            views.setContentDescription(viewId, context.getString(description))
            views.setOnClickPendingIntent(viewId, actionIntent(context, action, appWidgetId))
        }
    }

    @Suppress("DEPRECATION") // setRemoteAdapter(Intent) keeps the RemoteViewsService the assignment asks for
    private fun applyQueue(context: Context, views: RemoteViews, appWidgetId: Int, colors: WidgetColors) {
        val serviceIntent = Intent(context, QueueWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) // unique per widget
        }
        views.setRemoteAdapter(R.id.queue, serviceIntent)
        views.setEmptyView(R.id.queue, R.id.queue_empty)
        views.setTextColor(R.id.queue_label, colors.accent)
        views.setTextColor(R.id.queue_empty, colors.onBackgroundMuted)

        val template = Intent(context, HubWidgetProvider::class.java)
            .setAction(HubWidgetProvider.ACTION_QUEUE_ITEM)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Collection templates must be mutable so each row can fill in its index.
        views.setPendingIntentTemplate(
            R.id.queue,
            PendingIntent.getBroadcast(
                context, REQ_QUEUE + appWidgetId, template,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun actionIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, HubWidgetProvider::class.java)
            .setAction(action)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, action.hashCode() * 31 + appWidgetId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppIntent(context: Context, requestMic: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_MIC, requestMic)
        return PendingIntent.getActivity(
            context, if (requestMic) REQ_MIC else REQ_OPEN, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun chipBackground(packageName: String?): Int = when (packageName) {
        null -> R.drawable.chip_local
        KnownApps.SPOTIFY -> R.drawable.chip_spotify
        KnownApps.YOUTUBE_MUSIC, KnownApps.YOUTUBE -> R.drawable.chip_youtube
        else -> R.drawable.chip_other
    }

    @LayoutRes
    private fun layoutFor(size: SizeClass): Int = when (size) {
        SizeClass.SMALL -> R.layout.widget_small
        SizeClass.MEDIUM -> R.layout.widget_medium
        SizeClass.LARGE -> R.layout.widget_large
    }

    private fun barCount(size: SizeClass): Int = if (size == SizeClass.SMALL) 10 else 16

    private fun maxBarDp(size: SizeClass): Float = when (size) {
        SizeClass.SMALL -> 18f
        SizeClass.MEDIUM -> 24f
        SizeClass.LARGE -> 48f
    }

    /** Bass in the middle, treble at both edges: a symmetric, "speaker cone" look. */
    private fun centerOut(bars: FloatArray, count: Int): FloatArray {
        val half = resample(bars, (count + 1) / 2)
        val middle = (count - 1) / 2f
        return FloatArray(count) { i ->
            val distance = kotlin.math.abs(i - middle)
            half[(distance - 0.5f).coerceAtLeast(0f).toInt().coerceAtMost(half.lastIndex)]
        }
    }

    /** One color per bar, turning around the color wheel from the album accent. */
    private fun rainbow(accent: Int, index: Int, count: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + 220f * index / (count - 1).coerceAtLeast(1)) % 360f
        hsv[1] = hsv[1].coerceIn(0.55f, 0.85f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Averages [bars] into [count] buckets so every size can show its own number of bars. */
    private fun resample(bars: FloatArray, count: Int): FloatArray {
        if (bars.size == count || bars.isEmpty()) return if (bars.isEmpty()) FloatArray(count) else bars
        return FloatArray(count) { i ->
            val start = i * bars.size / count
            val end = maxOf(start + 1, (i + 1) * bars.size / count)
            var sum = 0f
            for (j in start until end) sum += bars[j]
            sum / (end - start)
        }
    }
}
