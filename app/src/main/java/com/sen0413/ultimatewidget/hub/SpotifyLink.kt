package com.sen0413.ultimatewidget.hub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.sen0413.ultimatewidget.data.TrackMatcher
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify's official SDK (App Remote + Auth).
 *
 * Spotify ignores "play this song" requests that arrive through Android's shared media
 * session from apps it does not know, and simply resumes whatever it had paused. App Remote
 * talks to the Spotify app directly instead: play("spotify:track:...") starts exactly that
 * song, from the beginning, without bringing Spotify to the screen.
 *
 * The Auth library is used once, from our own screen, to let the user approve the app; the
 * short-lived token it returns is also used to look up Spotify ids for saved songs that do
 * not have one yet (Web API search), so they can be played exactly later on.
 *
 * Everything here is optional: when Spotify is missing, not approved, or a call fails, the
 * hub falls back to the shared media session.
 */
@MainThread
object SpotifyLink {
    private const val TAG = "SpotifyLink"
    const val CLIENT_ID = "2bd9780d81154855b3a7ba4d5fe5c77b"
    const val REDIRECT_URI = "ultimatewidget://spotify-callback"
    const val REQUEST_AUTH = 43
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val AUTO_AUTH_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private const val PREFS = "spotify"
    private const val KEY_AUTHORIZED = "authorized"
    private const val KEY_TOKEN = "token"
    private const val KEY_TOKEN_EXPIRY = "token_expiry"
    private const val KEY_LAST_AUTO_AUTH = "last_auto_auth"

    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private val waiters = mutableListOf<(SpotifyAppRemote?) -> Unit>()

    /** False for accounts that cannot pick a song (Spotify Free); then the old method is used. */
    var canPlayOnDemand = true
        private set

    /** Every player change in Spotify (song, pause, position), while connected. */
    var onPlayerState: ((PlayerState) -> Unit)? = null

    /** Called after the user approved the app (token available). */
    var onAuthorized: (() -> Unit)? = null

    fun isInstalled(context: Context): Boolean = runCatching { SpotifyAppRemote.isSpotifyInstalled(context) }.getOrDefault(false)

    fun isAuthorized(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTHORIZED, false)

    val isConnected: Boolean get() = remote?.isConnected == true

    /** "spotify:track:..." (or an episode): an id App Remote can play directly. */
    fun isPlayableId(id: String?): Boolean =
        id != null && (id.startsWith("spotify:track:") || id.startsWith("spotify:episode:"))

    // ---------------------------------------------------------------- App Remote

    fun connect(context: Context, cb: (SpotifyAppRemote?) -> Unit) {
        val r = remote
        if (r != null && r.isConnected) {
            cb(r)
            return
        }
        waiters += cb
        if (connecting) return
        connecting = true
        val app = context.applicationContext
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            // Never show Spotify's approval screen from the background; approval happens
            // once, from our Settings screen (or the first time the app is opened).
            .showAuthView(false)
            .build()
        val timeout = Runnable { finishConnect(null) }
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        try {
            SpotifyAppRemote.connect(app, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    handler.removeCallbacks(timeout)
                    remote = appRemote
                    prefs(app).edit().putBoolean(KEY_AUTHORIZED, true).apply()
                    subscribe(appRemote)
                    finishConnect(appRemote)
                }

                override fun onFailure(error: Throwable) {
                    handler.removeCallbacks(timeout)
                    Log.i(TAG, "App Remote connection failed: ${error.javaClass.simpleName}: ${error.message}")
                    if (error.javaClass.simpleName == "UserNotAuthorizedException") {
                        prefs(app).edit().putBoolean(KEY_AUTHORIZED, false).apply()
                    }
                    remote = null
                    finishConnect(null)
                }
            })
        } catch (t: Throwable) {
            handler.removeCallbacks(timeout)
            Log.i(TAG, "App Remote unavailable: ${t.javaClass.simpleName}")
            finishConnect(null)
        }
    }

    private fun finishConnect(result: SpotifyAppRemote?) {
        if (!connecting) return
        connecting = false
        val list = waiters.toList()
        waiters.clear()
        list.forEach { runCatching { it(result) } }
    }

    private fun subscribe(r: SpotifyAppRemote) {
        runCatching {
            r.playerApi.subscribeToPlayerState().setEventCallback { state -> onPlayerState?.invoke(state) }
            r.userApi.subscribeToCapabilities().setEventCallback { caps -> canPlayOnDemand = caps.canPlayOnDemand }
        }
    }

    /**
     * Plays [uri] from the beginning. [onDone] gets false when App Remote cannot do it
     * (not approved, Spotify not reachable, a Free account), so the caller can fall back.
     */
    fun play(context: Context, uri: String, onDone: (Boolean) -> Unit) {
        if (!isAuthorized(context) || !isInstalled(context)) {
            onDone(false)
            return
        }
        connect(context) { r ->
            if (r == null || !canPlayOnDemand) {
                onDone(false)
                return@connect
            }
            var answered = false
            fun answer(ok: Boolean) {
                if (answered) return
                answered = true
                onDone(ok)
            }
            try {
                r.playerApi.play(uri)
                    .setResultCallback { answer(true) }
                    .setErrorCallback { e ->
                        Log.i(TAG, "play failed: ${e.message}")
                        answer(false)
                    }
            } catch (t: Throwable) {
                answer(false)
            }
        }
    }

    fun seekToStart() {
        runCatching { remote?.takeIf { it.isConnected }?.playerApi?.seekTo(0L) }
    }

    fun disconnect() {
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
    }

    // ---------------------------------------------------------------- approval (Auth library)

    /** Opens Spotify's approval screen (a quick hand-off to the Spotify app once approved). */
    fun authorize(activity: Activity) {
        val request = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(arrayOf("app-remote-control"))
            .build()
        prefs(activity).edit().putLong(KEY_LAST_AUTO_AUTH, System.currentTimeMillis()).apply()
        runCatching { AuthorizationClient.openLoginActivity(activity, REQUEST_AUTH, request) }
            .onFailure { Log.i(TAG, "Could not open Spotify login: ${it.javaClass.simpleName}") }
    }

    /**
     * Approval silently refreshed when our app is opened: only when the user approved before,
     * there are songs still missing their Spotify id, and not more than twice a day.
     */
    fun shouldRefreshToken(context: Context): Boolean {
        if (!isInstalled(context) || validToken(context) != null) return false
        val last = prefs(context).getLong(KEY_LAST_AUTO_AUTH, 0L)
        return System.currentTimeMillis() - last > AUTO_AUTH_INTERVAL_MS
    }

    /** First run: ask once, when the user has Spotify songs in a playlist but never approved. */
    fun shouldAskFirstTime(context: Context): Boolean =
        isInstalled(context) && !isAuthorized(context) && prefs(context).getLong(KEY_LAST_AUTO_AUTH, 0L) == 0L

    /** Returns true when the user approved. */
    fun onAuthResult(context: Context, resultCode: Int, data: Intent?): Boolean {
        val response = runCatching { AuthorizationClient.getResponse(resultCode, data) }.getOrNull() ?: return false
        return when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                val expiry = System.currentTimeMillis() + response.expiresIn * 1000L - 60_000L
                prefs(context).edit()
                    .putBoolean(KEY_AUTHORIZED, true)
                    .putString(KEY_TOKEN, response.accessToken)
                    .putLong(KEY_TOKEN_EXPIRY, expiry)
                    .apply()
                connect(context) { }
                onAuthorized?.invoke()
                true
            }
            else -> {
                Log.i(TAG, "Spotify approval: ${response.type} ${response.error}")
                false
            }
        }
    }

    fun validToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(KEY_TOKEN, null) ?: return null
        return if (System.currentTimeMillis() < p.getLong(KEY_TOKEN_EXPIRY, 0L)) token else null
    }

    private fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRY).apply()
    }

    // ---------------------------------------------------------------- Web API lookup

    /**
     * Finds the Spotify id of a song by title and artist. Null when there is no good match,
     * or the token is missing or expired.
     */
    suspend fun findTrackUri(context: Context, title: String, artist: String): String? {
        val token = validToken(context) ?: return null
        return withContext(Dispatchers.IO) {
            val strict = buildString {
                append("track:").append(title)
                if (artist.isNotBlank()) append(" artist:").append(artist)
            }
            search(context, token, strict, title) ?: search(context, token, "$title $artist".trim(), title)
        }
    }

    private fun search(context: Context, token: String, query: String, title: String): String? {
        val url = URL(
            "https://api.spotify.com/v1/search?type=track&limit=5&q=" + URLEncoder.encode(query, "UTF-8"),
        )
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items")
                    // The first result whose name is really the song (never a cover of something else).
                    (0 until (items?.length() ?: 0)).asSequence()
                        .map { items!!.getJSONObject(it) }
                        .firstOrNull { TrackMatcher.sameTitle(title, it.optString("name")) }
                        ?.optString("uri")?.ifBlank { null }
                }
                401 -> {
                    handler.post { clearToken(context) }
                    null
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.i(TAG, "search failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
