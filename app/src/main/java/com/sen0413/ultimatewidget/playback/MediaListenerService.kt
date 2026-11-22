package com.sen0413.ultimatewidget.playback

import android.service.notification.NotificationListenerService
import com.sen0413.ultimatewidget.hub.HubController

/**
 * Exists only so the user can grant notification access, which Android requires before an
 * app may read and control other apps' MediaSessions. Notification content is never read.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        HubController.onNotificationAccessChanged(true)
    }

    override fun onListenerDisconnected() {
        HubController.onNotificationAccessChanged(false)
    }
}
