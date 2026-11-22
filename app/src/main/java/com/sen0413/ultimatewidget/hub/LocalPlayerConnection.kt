package com.sen0413.ultimatewidget.hub

import android.content.ComponentName
import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sen0413.ultimatewidget.playback.PlaybackService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A Media3 MediaController connected to our PlaybackService. Every command to our own
 * player goes through the MediaSession, exactly like the notification and lock screen.
 * Must be used from the main thread.
 */
class LocalPlayerConnection(private val context: Context) {
    private val mutex = Mutex()
    private var future: ListenableFuture<MediaController>? = null

    @MainThread
    suspend fun controller(): MediaController = mutex.withLock {
        val existing = future
            ?.takeIf { it.isDone && !it.isCancelled }
            ?.let { runCatching { it.get() }.getOrNull() }
        if (existing != null && existing.isConnected) return existing

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val next = MediaController.Builder(context, token).buildAsync()
        future = next
        next.await()
    }

    @MainThread
    suspend fun withController(block: (MediaController) -> Unit) {
        block(controller())
    }
}

/** Suspends until the future completes; listener runs inline on the completing thread. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (t: Throwable) {
                continuation.resumeWithException(t.cause ?: t)
            }
        },
        { runnable -> runnable.run() },
    )
    continuation.invokeOnCancellation { cancel(false) }
}
