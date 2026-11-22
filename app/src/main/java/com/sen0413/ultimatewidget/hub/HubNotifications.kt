package com.sen0413.ultimatewidget.hub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.sen0413.ultimatewidget.R

/**
 * The one notification the hub posts itself: "the next song plays in Spotify, tap to
 * continue". Android does not let an app in the background open another app, but a tap on a
 * notification is a moment in which it may, so a cross-app playlist never silently stalls.
 */
object HubNotifications {
    private const val CHANNEL_ID = "playlist"
    private const val CONTINUE_ID = 4131
    private const val REQ_CONTINUE = 7_100

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Returns false when notifications are not allowed. */
    fun showContinue(context: Context, appLabel: String, title: String, launch: Intent): Boolean {
        if (!canPost(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context, manager)
        val tap = PendingIntent.getActivity(
            context, REQ_CONTINUE, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_playlist)
            .setContentTitle(context.getString(R.string.continue_title, appLabel))
            .setContentText(context.getString(R.string.continue_body, title))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(CONTINUE_ID, notification)
        return true
    }

    fun cancelContinue(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CONTINUE_ID)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.continue_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                setSound(null, null)
            },
        )
    }
}
