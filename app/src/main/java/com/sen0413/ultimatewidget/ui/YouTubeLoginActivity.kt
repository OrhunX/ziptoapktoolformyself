package com.sen0413.ultimatewidget.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sen0413.ultimatewidget.hub.YouTubeAccount

/**
 * Google's own sign-in page for YouTube Music. The password is typed into Google's page only;
 * we keep nothing but the cookies YouTube Music sets afterwards (YouTubeAccount).
 */
class YouTubeLoginActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.fitsSystemWindows = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Google refuses sign-in in app web views that say so in their user agent.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(YouTubeAccount.ORIGIN) && YouTubeAccount.saveFromWebView(this@YouTubeLoginActivity)) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        setContentView(web)
        web.loadUrl(SIGN_IN_URL)
    }

    companion object {
        private const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    }
}
