package com.sen0413.ultimatewidget.hub

import android.util.Log
import com.sen0413.ultimatewidget.data.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube Music helpers that need no login.
 *
 * YouTube Music has no official API for apps like ours, so this uses the same unofficial
 * "InnerTube" search the YouTube Music website uses (as ytmusicapi does) to find a song's
 * video id, and youtube.com's watch_videos link to turn several ids into one temporary
 * playlist. Both can break whenever YouTube changes them; every caller falls back to
 * YouTube Music's own search screen when they return null.
 */
object YouTubeLink {
    private const val TAG = "YouTubeLink"
    private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20241120.01.00"
    /** ytmusicapi's "songs" filter. */
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    const val MAX_QUEUE = 50

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isVideoId(id: String?): Boolean = id != null && ID.matches(id)

    /** The video id of the song on YouTube Music, or null when nothing matches its title. */
    suspend fun findVideoId(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return@withContext null
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")))
            .put("query", query)
            .put("params", SONGS_FILTER)
        val conn = URL(SEARCH_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val songs = ArrayList<Pair<String, String>>() // (title, videoId)
            collectSongs(json, songs)
            // Only a row that really is the song (never just the first result).
            songs.firstOrNull { TrackMatcher.sameTitle(title, it.first) }?.second
        } catch (t: Throwable) {
            Log.i(TAG, "search failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * One temporary playlist ("TL…") holding [ids] in order, made by youtube.com's watch_videos
     * link (it answers with a redirect to watch?v=…&list=TL…). Null when YouTube did not make one.
     */
    suspend fun temporaryPlaylist(ids: List<String>): String? = withContext(Dispatchers.IO) {
        val clean = ids.filter(::isVideoId).distinct().take(MAX_QUEUE)
        if (clean.size < 2) return@withContext null
        val url = URL("https://www.youtube.com/watch_videos?video_ids=" + URLEncoder.encode(clean.joinToString(","), "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            if (conn.responseCode !in 300..399) return@withContext null
            val location = conn.getHeaderField("Location") ?: return@withContext null
            Regex("[?&]list=([A-Za-z0-9_-]+)").find(location)?.groupValues?.get(1)
        } catch (t: Throwable) {
            Log.i(TAG, "watch_videos failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Finds song rows (musicResponsiveListItemRenderer) anywhere in a search response. */
    private fun collectSongs(node: Any?, out: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> {
                val row = node.optJSONObject("musicResponsiveListItemRenderer")
                if (row != null) {
                    val id = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf(::isVideoId)
                        ?: findVideoId(row)
                    val title = row.optJSONArray("flexColumns")?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    if (id != null && !title.isNullOrBlank()) out += title to id
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) collectSongs(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectSongs(node.opt(i), out)
        }
    }

    /** The first watchEndpoint.videoId inside [node]. */
    private fun findVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(::isVideoId)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findVideoId(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findVideoId(node.opt(i))?.let { return it }
        }
        return null
    }
}
