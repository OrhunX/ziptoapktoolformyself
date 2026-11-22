package com.sen0413.ultimatewidget.hub

import android.content.Context
import android.media.AudioManager

/**
 * Owns the audio session id of our own player. Generating it up front lets the
 * visualizer attach to the player's session before playback even starts.
 */
object HubAudio {
    @Volatile
    private var localSessionId: Int = 0

    fun localSessionId(context: Context): Int {
        if (localSessionId == 0) {
            synchronized(this) {
                if (localSessionId == 0) {
                    val audioManager = context.getSystemService(AudioManager::class.java)
                    localSessionId = audioManager.generateAudioSessionId()
                }
            }
        }
        return localSessionId
    }
}
