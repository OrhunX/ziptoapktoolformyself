package com.sen0413.ultimatewidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sen0413.ultimatewidget.hub.KnownApps
import com.sen0413.ultimatewidget.hub.SourceKind
import com.sen0413.ultimatewidget.hub.SourceSnapshot

/**
 * Where the "Video" chip goes. Spotify songs open in the Spotify app, which shows the music
 * video when Spotify has one; playback controls stay on our widget through Spotify's
 * MediaSession. Other songs open a YouTube search for the official video.
 *
 * The production design resolves the exact video with the YouTube Data API and caches it by
 * ISRC; this demo uses a search link so it runs without an API key.
 */
object VideoLinks {
    fun intentFor(context: Context, source: SourceSnapshot): Intent? {
        if (source.kind != SourceKind.EXTERNAL) return null // demo tracks have no video
        val track = source.track ?: return null
        val pkg = source.packageName ?: return null

        if (pkg == KnownApps.SPOTIFY) {
            val mediaId = track.mediaId
            val direct = if (mediaId != null && mediaId.startsWith("spotify:")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(mediaId)).setPackage(KnownApps.SPOTIFY)
            } else {
                null
            }
            val launch = direct?.takeIf { it.resolveActivity(context.packageManager) != null }
                ?: context.packageManager.getLaunchIntentForPackage(KnownApps.SPOTIFY)
            return launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val query = listOf(track.artist, track.title, "official video").filter { it.isNotBlank() }.joinToString(" ")
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
