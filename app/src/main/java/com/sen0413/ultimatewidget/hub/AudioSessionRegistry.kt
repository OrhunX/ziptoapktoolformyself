package com.sen0413.ultimatewidget.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Many players announce their audio session for equalizers with
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION. When they do, the visualizer attaches to that
 * exact session instead of the device-dependent output mix (session 0).
 */
object AudioSessionRegistry {
    private val sessions = ConcurrentHashMap<String, Int>()

    fun sessionFor(packageName: String?): Int? = packageName?.let { sessions[it] }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    if (sessionId != 0) sessions[pkg] = sessionId
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> sessions.remove(pkg)
            }
            HubController.onAnnouncedSessionsChanged()
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Sent by other apps, so the receiver has to be exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}
