package com.sen0413.ultimatewidget.hub

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.sen0413.ultimatewidget.playback.MediaListenerService
import android.media.session.MediaController as PlatformController

/**
 * Watches other apps' MediaSessions (Spotify, YouTube Music, ...). Android only allows this
 * once the user has granted notification access to [MediaListenerService].
 */
class ExternalSessionWatcher(
    private val context: Context,
    private val onControllerChanged: (PlatformController?) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)
    private var listening = false

    /** While a hub playlist plays an entry in a specific app, keep showing that app. */
    var preferredPackage: String? = null
        set(value) {
            field = value
            if (listening) choose(activeSessions())
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        choose(controllers.orEmpty())
    }

    /** Returns false while notification access is missing. */
    fun start(): Boolean {
        if (listening) return true
        return try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            listening = true
            choose(manager.getActiveSessions(listenerComponent))
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification access not granted yet")
            false
        }
    }

    fun stop() {
        if (!listening) return
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        listening = false
        onControllerChanged(null)
    }

    /** The live session of [packageName], or null if that app has none right now. */
    fun controllerFor(packageName: String): PlatformController? =
        activeSessions().firstOrNull { it.packageName == packageName }

    /** Every other app's live session (never our own), for pausing them all at once. */
    fun otherSessions(): List<PlatformController> =
        activeSessions().filter { it.packageName != context.packageName }

    private fun activeSessions(): List<PlatformController> = try {
        manager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun choose(controllers: List<PlatformController>) {
        val others = controllers.filter { it.packageName != context.packageName }
        val chosen = others.firstOrNull { it.packageName == preferredPackage }
            ?: others.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: others.firstOrNull()
        onControllerChanged(chosen)
    }

    private companion object {
        const val TAG = "ExternalSessions"
    }
}
