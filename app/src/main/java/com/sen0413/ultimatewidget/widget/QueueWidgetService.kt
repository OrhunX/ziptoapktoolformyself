package com.sen0413.ultimatewidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.KnownApps
import com.sen0413.ultimatewidget.hub.QueueEntry

/** Feeds the "Up next" ListView of the 4×4 widget, like an adapter in another process. */
class QueueWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return QueueFactory(applicationContext, appWidgetId)
    }
}

private class QueueFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<QueueEntry> = emptyList()
    private var currentIndex = -1

    override fun onCreate() = Unit

    /** Runs on a binder thread; reading the StateFlow value is thread-safe. */
    override fun onDataSetChanged() {
        val state = HubController.state.value
        items = state.queue
        currentIndex = state.queueIndex
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_queue_item)
        val entry = items.getOrNull(position) ?: return views
        val colors = WidgetRenderer.colorsFor(appWidgetId)
        val isCurrent = entry.index == currentIndex

        views.setTextViewText(R.id.q_title, entry.title)
        views.setTextColor(R.id.q_title, if (isCurrent) colors.accent else colors.onBackground)
        views.setTextViewText(R.id.q_source, entry.sourceLabel)
        views.setTextColor(R.id.q_source, colors.onBackgroundMuted)
        views.setInt(R.id.q_dot, "setColorFilter", ContextCompat.getColor(context, dotColor(entry.sourcePackage)))
        views.setContentDescription(R.id.q_root, "${entry.title}, ${entry.sourceLabel}")
        views.setOnClickFillInIntent(
            R.id.q_root,
            Intent().putExtra(HubWidgetProvider.EXTRA_QUEUE_INDEX, entry.index),
        )
        return views
    }

    private fun dotColor(packageName: String?): Int = when (packageName) {
        null -> R.color.chip_local
        KnownApps.SPOTIFY -> R.color.chip_spotify
        KnownApps.YOUTUBE_MUSIC, KnownApps.YOUTUBE -> R.color.chip_youtube
        else -> R.color.chip_other
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.queueId ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
