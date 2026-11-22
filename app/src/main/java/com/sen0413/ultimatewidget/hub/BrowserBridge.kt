package com.sen0413.ultimatewidget.hub

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * Wakes a music app that has no live session, without opening its screen: connects to the
 * app's MediaBrowserService (the door Android Auto and Assistant use) and hands back a
 * controller for its session. Apps may refuse callers they do not know; then [connect]
 * reports null and the hub falls back to its "tap to continue" notification.
 */
class BrowserBridge(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val browsers = HashMap<String, MediaBrowser>()

    fun connect(packageName: String, timeoutMs: Long = 4_000L, onResult: (MediaController?) -> Unit) {
        browsers[packageName]?.let { existing ->
            if (existing.isConnected) {
                onResult(runCatching { MediaController(context, existing.sessionToken) }.getOrNull())
                return
            }
            runCatching { existing.disconnect() }
            browsers.remove(packageName)
        }
        val service = findService(packageName)
        if (service == null) {
            onResult(null)
            return
        }
        var done = false
        fun finish(controller: MediaController?) {
            if (done) return
            done = true
            onResult(controller)
        }
        lateinit var browser: MediaBrowser
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                finish(runCatching { MediaController(context, browser.sessionToken) }.getOrNull())
            }

            override fun onConnectionFailed() {
                browsers.remove(packageName)
                finish(null)
            }

            override fun onConnectionSuspended() {
                browsers.remove(packageName)
            }
        }
        browser = MediaBrowser(context, service, callback, null)
        browsers[packageName] = browser
        try {
            browser.connect()
        } catch (t: Throwable) {
            Log.i(TAG, "Browser connect to $packageName refused: ${t.javaClass.simpleName}")
            browsers.remove(packageName)
            finish(null)
            return
        }
        handler.postDelayed({ finish(null) }, timeoutMs)
    }

    fun disconnectAll() {
        browsers.values.forEach { runCatching { it.disconnect() } }
        browsers.clear()
    }

    private fun findService(packageName: String): ComponentName? {
        val intent = Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName)
        val info = runCatching { context.packageManager.queryIntentServices(intent, 0) }.getOrNull()?.firstOrNull()
        return info?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private companion object {
        const val TAG = "BrowserBridge"
    }
}
