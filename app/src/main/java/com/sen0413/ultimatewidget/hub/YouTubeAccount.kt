package com.sen0413.ultimatewidget.hub

import android.content.Context
import android.webkit.CookieManager
import java.security.MessageDigest

/**
 * The user's YouTube Music sign-in, for private playlists. The sign-in itself happens on
 * Google's own page in YouTubeLoginActivity; afterwards we keep the site's cookies and sign
 * requests the way the YouTube Music website does (the SAPISIDHASH header, as ytmusicapi does).
 *
 * The embedded player shares the same cookies, so it plays as the signed-in user too.
 */
object YouTubeAccount {
    private const val PREFS = "yt_account"
    private const val KEY_COOKIES = "cookies"
    const val ORIGIN = "https://music.youtube.com"
    private val SAPISID = Regex("(?:^|;\\s*)SAPISID=([^;]+)")
    private val SECURE_SAPISID = Regex("(?:^|;\\s*)__Secure-3PAPISID=([^;]+)")

    fun isSignedIn(context: Context): Boolean = cookies(context)?.let(::sapisid) != null

    private fun cookies(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIES, null)

    /** After the sign-in page: true when YouTube Music's cookies are there and were kept. */
    fun saveFromWebView(context: Context): Boolean {
        val manager = CookieManager.getInstance()
        val cookie = manager.getCookie(ORIGIN) ?: return false
        if (sapisid(cookie) == null) return false
        manager.flush()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_COOKIES, cookie).apply()
        return true
    }

    fun signOut(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COOKIES).apply()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    /** Headers that make an InnerTube request run as the signed-in user, or empty. */
    fun authHeaders(context: Context): Map<String, String> {
        val cookie = cookies(context) ?: return emptyMap()
        val id = sapisid(cookie) ?: return emptyMap()
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$ts $id $ORIGIN".toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        return mapOf(
            "Cookie" to cookie,
            "Authorization" to "SAPISIDHASH ${ts}_$hash",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
        )
    }

    private fun sapisid(cookie: String): String? =
        SAPISID.find(cookie)?.groupValues?.get(1) ?: SECURE_SAPISID.find(cookie)?.groupValues?.get(1)
}
