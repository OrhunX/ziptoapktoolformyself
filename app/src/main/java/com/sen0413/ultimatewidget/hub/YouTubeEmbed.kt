package com.sen0413.ultimatewidget.hub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread

/**
 * Plays YouTube songs inside our own app with YouTube's official embedded player (the IFrame
 * player API) in an invisible WebView, so a playlist's YouTube Music songs play without
 * switching to the YouTube Music app.
 *
 * The WebView lives in a tiny transparent window when "display over other apps" is allowed
 * (the most reliable host while the app is in the background), otherwise unattached. It
 * always reports itself as visible, so playback continues with the screen off.
 *
 * Songs whose owner turned off embedding report an error; HubController then plays them
 * through the YouTube Music app instead and remembers them here.
 *
 * Note: background playback of embedded YouTube is outside YouTube's terms of service; this
 * is for the course project only.
 */
@MainThread
object YouTubeEmbed {
    private const val TAG = "YouTubeEmbed"
    private const val PREFS = "yt_embed"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BLOCKED = "blocked"
    private const val SIZE_PX = 200

    /** IFrame player states. */
    const val STATE_UNSTARTED = -1
    const val STATE_ENDED = 0
    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2
    const val STATE_BUFFERING = 3

    /** Errors after which the song can never play embedded (removed, embedding turned off). */
    private val PERMANENT_ERRORS = setOf(100, 101, 150)

    /** (state, positionMs, durationMs) for the song loaded last. */
    var onUpdate: ((Int, Long, Long) -> Unit)? = null
    var onError: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var overlay = false
    private var apiReady = false
    private var pending: Pair<String, Int>? = null
    private var seq = 0
    private var blocked: MutableSet<String>? = null

    // ---------------------------------------------------------------- settings

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isBlocked(context: Context, videoId: String): Boolean = videoId in blockedSet(context)

    fun isPermanentError(code: Int) = code in PERMANENT_ERRORS

    fun block(context: Context, videoId: String) {
        val set = blockedSet(context)
        if (!set.add(videoId)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_BLOCKED, HashSet(set)).apply()
    }

    private fun blockedSet(context: Context): MutableSet<String> = blocked ?: HashSet(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet(),
    ).also { blocked = it }

    // ---------------------------------------------------------------- playback

    /** Loads [videoId] and plays it from the beginning. */
    fun load(context: Context, videoId: String) {
        val token = ++seq
        val view = webView ?: create(context.applicationContext)
        if (apiReady) {
            view.evaluateJavascript("load('$videoId',$token)", null)
        } else {
            pending = videoId to token
        }
    }

    fun play() = js("play()")
    fun pause() = js("pause()")
    fun seekTo(positionMs: Long) = js("seek(${positionMs.coerceAtLeast(0L)})")

    /** Stops and frees the player (the WebView and its window). */
    fun stop() {
        seq++
        pending = null
        val view = webView ?: return
        webView = null
        apiReady = false
        runCatching { view.evaluateJavascript("stop()", null) }
        if (overlay) {
            runCatching { view.context.getSystemService(WindowManager::class.java).removeView(view) }
            overlay = false
        }
        main.postDelayed({ runCatching { view.destroy() } }, 300)
    }

    val isActive: Boolean get() = webView != null

    private fun js(code: String) {
        if (apiReady) webView?.evaluateJavascript(code, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context): WebView {
        val view = object : WebView(app) {
            // Playback must not stop when the app goes to the background or the screen turns off.
            override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(View.VISIBLE)
            override fun onVisibilityChanged(changedView: View, visibility: Int) = super.onVisibilityChanged(changedView, View.VISIBLE)
        }
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        view.webChromeClient = WebChromeClient()
        view.webViewClient = WebViewClient()
        view.addJavascriptInterface(Bridge(), "Bridge")
        webView = view
        apiReady = false

        overlay = Settings.canDrawOverlays(app) && runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                SIZE_PX, SIZE_PX, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 0f // invisible; touches pass through
            }
            app.getSystemService(WindowManager::class.java).addView(view, params)
        }.onFailure { Log.i(TAG, "No overlay host: ${it.javaClass.simpleName}") }.isSuccess
        if (!overlay) {
            // Unattached: give the page a size anyway (the player needs one).
            view.measure(
                View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SIZE_PX, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, SIZE_PX, SIZE_PX)
        }
        view.onResume()
        view.resumeTimers()
        // YouTube requires embedders to identify themselves; apps use https://<package name>.
        val origin = "https://${app.packageName}"
        view.loadDataWithBaseURL(origin, page(origin), "text/html", "utf-8", null)
        return view
    }

    private class Bridge {
        @JavascriptInterface
        fun onApiReady() = main.post {
            apiReady = true
            pending?.let { (id, token) ->
                pending = null
                webView?.evaluateJavascript("load('$id',$token)", null)
            }
        }.let { }

        @JavascriptInterface
        fun onState(token: Int, state: Int, positionMs: Long, durationMs: Long) = main.post {
            if (token == seq) onUpdate?.invoke(state, positionMs, durationMs)
        }.let { }

        @JavascriptInterface
        fun onError(token: Int, code: Int) = main.post {
            if (token == seq) {
                Log.i(TAG, "Embedded player error $code")
                onError?.invoke(code)
            }
        }.let { }
    }

    private fun page(origin: String) = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:transparent;width:100%;height:100%;overflow:hidden}#p{width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
var player = null, token = 0, ticker = null;
function pos(){ try { return Math.floor(player.getCurrentTime() * 1000); } catch (e) { return 0; } }
function dur(){ try { return Math.floor(player.getDuration() * 1000); } catch (e) { return 0; } }
function report(state){ Bridge.onState(token, state, pos(), dur()); }
function onYouTubeIframeAPIReady(){ Bridge.onApiReady(); }
function load(id, t){
  token = t;
  if (player && player.loadVideoById) { player.loadVideoById({videoId: id, startSeconds: 0}); return; }
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: id,
    playerVars: { autoplay: 1, controls: 0, playsinline: 1, rel: 0, fs: 0, disablekb: 1,
                  iv_load_policy: 3, origin: '$origin', widget_referrer: '$origin' },
    events: {
      onReady: function(e){ e.target.playVideo(); },
      onStateChange: function(e){ report(e.data); },
      onError: function(e){ Bridge.onError(token, e.data); }
    }
  });
  if (!ticker) ticker = setInterval(function(){
    if (player && player.getPlayerState) { var s = player.getPlayerState(); if (s === 1) report(s); }
  }, 1000);
}
function play(){ if (player && player.playVideo) player.playVideo(); }
function pause(){ if (player && player.pauseVideo) player.pauseVideo(); }
function seek(ms){ if (player && player.seekTo) player.seekTo(ms / 1000, true); }
function stop(){ if (player && player.stopVideo) player.stopVideo(); }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
""".trimIndent()
}
