package com.sen0413.ultimatewidget.visualizer

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
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.MainActivity

/**
 * Keeps real-audio analysis alive for the home-screen widget.
 *
 * RECORD_AUDIO is a while-in-use permission: without a foreground service of type
 * "microphone", Android stops serving audio data once the app leaves the screen. The service
 * is started only from user-visible moments (the app on screen, a tap on the widget) and it
 * never records: the Visualizer API hands us a spectrum that is reduced to bar heights in
 * memory.
 *
 * It is started with startService() and promotes itself with startForeground(), so a refusal
 * by the system (for example a start from the background) is a caught exception instead of
 * a crash; the widget then shows its "tap to open" hint.
 */
class VisualizerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promote()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        HubController.onVisualizerServiceReady()
        return START_NOT_STICKY
    }

    private fun promote(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        // SecurityException / ForegroundServiceStartNotAllowedException when not eligible.
        Log.i(TAG, "Foreground promotion refused: ${t.javaClass.simpleName}")
        false
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vis_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_equalizer)
            .setContentTitle(getString(R.string.vis_notification_title))
            .setContentText(getString(R.string.vis_notification_body))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VisualizerService"
        private const val CHANNEL_ID = "visualizer"
        private const val NOTIFICATION_ID = 4130

        @Volatile
        var running: Boolean = false
            private set

        /** Best effort: returns false if Android refuses to start the service right now. */
        fun start(context: Context): Boolean {
            if (running) return true
            if (!VisualizerEngine.hasPermission(context)) return false
            return try {
                context.startService(Intent(context, VisualizerService::class.java))
                true
            } catch (t: Throwable) {
                // IllegalStateException when the app is in the background.
                Log.i(TAG, "Service start refused: ${t.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: Context) {
            if (!running) return
            runCatching { context.stopService(Intent(context, VisualizerService::class.java)) }
        }
    }
}
