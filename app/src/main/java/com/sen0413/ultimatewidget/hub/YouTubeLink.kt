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
    private const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    const val CLIENT_VERSION = "1.20241120.01.00"
    /** ytmusicapi's "songs" filter. */
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
    const val MAX_QUEUE = 50

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isVideoId(id: String?): Boolean = id != null && ID.matches(id)

    /** ytmusicapi's "videos" filter: covers and fan uploads are often only videos, not songs. */
    private const val VIDEOS_FILTER = "EgWKAQIQAWoMEA4QChADEAQQCRAF"

    /** Words that say nothing about who made a version. */
    private val NOISE = setOf("topic", "official", "vevo", "music", "the", "and", "feat", "channel", "audio", "video", "lyrics")

    private class Row(val title: String, val details: String, val id: String)

    /**
     * The video id of exactly this song on YouTube Music, or null. The title must match and,
     * when we know who made it, so must the artist (or the version named in the title, e.g.
     * "(Kasane Teto cover)"): otherwise a cover would turn into the original. Songs are
     * searched first, then videos (where covers and fan uploads live), then everything.
     */
    suspend fun findVideoId(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return@withContext null
        val wanted = versionWords(title, artist)
        for (filter in listOf(SONGS_FILTER, VIDEOS_FILTER, null)) {
            val rows = search(query, filter) ?: continue
            val candidates = rows.filter { TrackMatcher.sameTitle(title, it.title) }
            if (candidates.isEmpty()) continue
            if (wanted.isEmpty()) return@withContext candidates.first().id
            // The row naming the most of the artist / version words; exact titles win ties.
            val best = candidates.maxByOrNull { row ->
                val text = words(row.title + " " + row.details)
                wanted.count { it in text } * 10 + if (TrackMatcher.normalize(row.title) == TrackMatcher.normalize(title)) 1 else 0
            }!!
            if (wanted.any { it in words(best.title + " " + best.details) }) return@withContext best.id
        }
        null
    }

    /**
     * Whether video [id] is this song by this artist (its title and channel on YouTube Music).
     * Null when YouTube could not be asked.
     */
    suspend fun videoMatches(id: String, title: String, artist: String): Boolean? = withContext(Dispatchers.IO) {
        val wanted = versionWords(title, artist)
        if (wanted.isEmpty()) return@withContext true
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")))
            .put("videoId", id)
        val conn = URL(NEXT_URL).openConnection() as HttpURLConnection
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
            val video = findPanelVideo(json, id) ?: return@withContext null
            fun runs(key: String): String {
                val r = video.optJSONObject(key)?.optJSONArray("runs") ?: return ""
                return (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() }
            }
            val videoTitle = runs("title")
            if (!TrackMatcher.sameTitle(title, videoTitle)) return@withContext false
            val text = words(videoTitle + " " + runs("longBylineText") + " " + runs("shortBylineText"))
            wanted.any { it in text }
        } catch (t: Throwable) {
            Log.i(TAG, "next failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun findPanelVideo(node: Any?, id: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistPanelVideoRenderer")?.let { if (it.optString("videoId") == id) return it }
                val keys = node.keys()
                while (keys.hasNext()) findPanelVideo(node.opt(keys.next()), id)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findPanelVideo(node.opt(i), id)?.let { return it }
        }
        return null
    }

    /** The artist's words plus the words that decorate the title, like "kasane teto cover". */
    private fun versionWords(title: String, artist: String): Set<String> {
        val base = words(TrackMatcher.normalize(title))
        val extra = words(title).filter { it !in base }
        return (words(artist) + extra).filter { it.length >= 3 && it !in NOISE }.toSet()
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotBlank() }.toSet()

    private fun search(query: String, filter: String?): List<Row>? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")))
            .put("query", query)
        if (filter != null) body.put("params", filter)
        val conn = URL(SEARCH_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            ArrayList<Row>().also { collectSongs(json, it) }
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
    private fun collectSongs(node: Any?, out: MutableList<Row>) {
        when (node) {
            is JSONObject -> {
                val row = node.optJSONObject("musicResponsiveListItemRenderer")
                if (row != null) {
                    val id = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf(::isVideoId)
                        ?: findVideoId(row)
                    val cols = row.optJSONArray("flexColumns")
                    fun col(i: Int): String {
                        val runs = cols?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
                        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
                    }
                    val title = col(0)
                    // Artist / channel, album, views...: who made this version.
                    val details = (1 until (cols?.length() ?: 1)).joinToString(" ") { col(it) }
                    if (id != null && title.isNotBlank()) out += Row(title, details, id)
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
