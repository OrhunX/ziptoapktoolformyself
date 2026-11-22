package com.sen0413.ultimatewidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.sen0413.ultimatewidget.hub.HubController
import kotlinx.coroutines.launch

/**
 * Widget lifecycle and button handling. Buttons are broadcasts to this provider; the work is
 * delegated to the hub inside goAsync() so it never blocks the broadcast or the main thread.
 */
class HubWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRenderer.invalidateIds()
        val state = HubController.state.value
        appWidgetIds.forEach { WidgetRenderer.render(context, it, state, forceFull = true) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetPrefs.setSize(context, appWidgetId, SizeClass.fromOptions(newOptions))
        WidgetRenderer.render(context, appWidgetId, HubController.state.value, forceFull = true)
        WidgetRenderer.notifyQueueChanged(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WidgetPrefs.remove(context, it)
            WidgetRenderer.forget(it)
        }
        WidgetRenderer.invalidateIds()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in HUB_ACTIONS) return

        // A tap is a user-visible moment: the visualizer service may (re)start now.
        HubController.onWidgetInteraction()
        val pending = goAsync()
        HubController.scope.launch {
            try {
                when (action) {
                    ACTION_PLAY_PAUSE -> HubController.playPause()
                    ACTION_NEXT -> HubController.next()
                    ACTION_PREVIOUS -> HubController.previous()
                    ACTION_SWITCH_SOURCE -> HubController.switchSource()
                    ACTION_FAVORITE -> HubController.toggleFavorite()
                    ACTION_SHUFFLE -> HubController.toggleShuffle()
                    ACTION_REPEAT -> HubController.cycleRepeat()
                    ACTION_QUEUE_ITEM -> HubController.skipTo(intent.getIntExtra(EXTRA_QUEUE_INDEX, -1))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Widget action $action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "HubWidget"
        const val ACTION_PLAY_PAUSE = "com.sen0413.ultimatewidget.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.sen0413.ultimatewidget.action.NEXT"
        const val ACTION_PREVIOUS = "com.sen0413.ultimatewidget.action.PREVIOUS"
        const val ACTION_SWITCH_SOURCE = "com.sen0413.ultimatewidget.action.SWITCH_SOURCE"
        const val ACTION_QUEUE_ITEM = "com.sen0413.ultimatewidget.action.QUEUE_ITEM"
        const val ACTION_FAVORITE = "com.sen0413.ultimatewidget.action.FAVORITE"
        const val ACTION_SHUFFLE = "com.sen0413.ultimatewidget.action.SHUFFLE"
        const val ACTION_REPEAT = "com.sen0413.ultimatewidget.action.REPEAT"
        const val EXTRA_QUEUE_INDEX = "com.sen0413.ultimatewidget.extra.QUEUE_INDEX"

        private val HUB_ACTIONS = setOf(
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_SWITCH_SOURCE, ACTION_QUEUE_ITEM, ACTION_FAVORITE,
            ACTION_SHUFFLE, ACTION_REPEAT,
        )
    }
}
