package com.sen0413.ultimatewidget.widget

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Widgets and the lock-screen card are drawn here, off the main thread: building RemoteViews,
 * decoding covers and picking colors takes long enough to make the app stutter when a song
 * starts. Work posted here runs in order, one task at a time.
 */
object RenderThread {
    private val thread = HandlerThread("hub-render", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    fun post(block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("RenderThread", "Render task failed", t)
            }
        }
    }
}
