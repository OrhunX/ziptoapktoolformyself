package com.sen0413.ultimatewidget.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.ui.MainActivity

/**
 * Keeps the app alive while a YouTube song plays inside it (YouTubeEmbed): a media-playback
 * foreground service with a quiet notification naming the song.
 */
class YouTubeEmbedService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val notification = build(this, title)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.i(TAG, "Not in the foreground: ${t.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "YouTubeEmbedService"
        private const val CHANNEL_ID = "in_app_youtube"
        private const val NOTIFICATION_ID = 4132
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            runCatching {
                context.startForegroundService(Intent(context, YouTubeEmbedService::class.java).putExtra(EXTRA_TITLE, title))
            }.onFailure { Log.i(TAG, "Could not start: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, YouTubeEmbedService::class.java)) }
        }

        private fun build(context: Context, title: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.embed_channel), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 7_200,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(context.getString(R.string.embed_playing))
                .setContentText(title)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        }
    }
}
