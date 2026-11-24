package com.sen0413.ultimatewidget.hub

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sen0413.ultimatewidget.data.ArtStore
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.PlaylistMeta
import com.sen0413.ultimatewidget.data.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Brings playlists in from Spotify and YouTube Music.
 *
 * Spotify: the official Web API with the user's approval (playlist-read scopes). Since
 * Spotify's February 2026 rules, apps in development mode only get the songs of playlists the
 * user owns or collaborates on; for anyone else's public playlist the songs are read from
 * Spotify's public embed page instead (unofficial).
 *
 * YouTube Music: the unofficial InnerTube browse API the website uses. Public and unlisted
 * playlists work without signing in; private playlists and "Liked music" need the user's
 * sign-in (YouTubeAccount).
 */
object PlaylistImporter {
    private const val TAG = "PlaylistImporter"
    private const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val MAX_SONGS = 1_000
    private const val MAX_PAGES = 25

    enum class Source { SPOTIFY, YOUTUBE_MUSIC }

    /** One of the user's playlists, to choose from. */
    data class Remote(val source: Source, val id: String, val name: String, val count: Int, val imageUrl: String?)

    data class Song(val title: String, val artist: String, val durationMs: Long, val mediaId: String?, val imageUrl: String?)

    data class Fetched(val name: String, val description: String, val imageUrl: String?, val songs: List<Song>)

    /** Spotify said the playlist exists but did not give its songs (or no approval yet). */
    class NeedsSpotifyApproval : Exception()

    /** YouTube Music returned nothing without signing in (a private playlist). */
    class NeedsYouTubeSignIn : Exception()

    // ---------------------------------------------------------------- links

    private val SPOTIFY_LINK = Regex("(?:open\\.spotify\\.com/(?:[a-z-]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]{10,})")
    private val YT_LIST = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val YT_BARE = Regex("^(?:VL)?((?:PL|OLAK5uy_|RD|LM|LL|FL)[A-Za-z0-9_-]*)$")

    /** (source, playlist id) from a pasted link, or null. */
    fun parseLink(text: String): Pair<Source, String>? {
        val t = text.trim()
        SPOTIFY_LINK.find(t)?.let { return Source.SPOTIFY to it.groupValues[1] }
        if ("youtube.com" in t || "youtu.be" in t) {
            YT_LIST.find(t)?.let { return Source.YOUTUBE_MUSIC to it.groupValues[1] }
        }
        YT_BARE.find(t)?.let { return Source.YOUTUBE_MUSIC to it.groupValues[1] }
        return null
    }

    // ---------------------------------------------------------------- Spotify

    suspend fun spotifyMine(context: Context): List<Remote> = withContext(Dispatchers.IO) {
        val token = SpotifyLink.playlistToken(context) ?: throw NeedsSpotifyApproval()
        val out = ArrayList<Remote>()
        var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (url != null && pages++ < 6) {
            val json = spotifyGet(token, url) ?: break
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val id = p.optString("id").ifBlank { null } ?: continue
                val count = (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total") ?: 0
                out += Remote(Source.SPOTIFY, id, p.optString("name"), count, firstImage(p.optJSONArray("images")))
            }
            url = json.optString("next").takeIf { it.startsWith("https://") }
        }
        out
    }

    suspend fun spotifyPlaylist(context: Context, id: String): Fetched = withContext(Dispatchers.IO) {
        val token = SpotifyLink.playlistToken(context)
        if (token != null) {
            val meta = spotifyGet(token, "https://api.spotify.com/v1/playlists/$id?fields=name,description,images")
            val songs = ArrayList<Song>()
            var url: String? = "https://api.spotify.com/v1/playlists/$id/items?limit=50"
            var pages = 0
            while (url != null && pages++ < MAX_PAGES && songs.size < MAX_SONGS) {
                val json = spotifyGet(token, url) ?: break
                val items = json.optJSONArray("items") ?: break
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i) ?: continue
                    val t = row.optJSONObject("item") ?: row.optJSONObject("track") ?: continue
                    if (t.optString("type", "track") != "track") continue
                    val title = t.optString("name").ifBlank { null } ?: continue
                    val artists = t.optJSONArray("artists")
                    val artist = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                        .joinToString(", ")
                    val uri = t.optString("uri").takeIf { it.startsWith("spotify:track:") }
                    songs += Song(title, artist, t.optLong("duration_ms"), uri, firstImage(t.optJSONObject("album")?.optJSONArray("images")))
                }
                url = json.optString("next").takeIf { it.startsWith("https://") }
            }
            if (songs.isNotEmpty()) {
                return@withContext Fetched(
                    meta?.optString("name").orEmpty().ifBlank { "Spotify playlist" },
                    meta?.optString("description").orEmpty().let(::stripHtml),
                    firstImage(meta?.optJSONArray("images")),
                    songs,
                )
            }
        }
        // Someone else's playlist (or not approved yet): Spotify's public embed page lists its songs.
        spotifyEmbed(id) ?: throw NeedsSpotifyApproval()
    }

    private fun spotifyEmbed(id: String): Fetched? {
        val html = httpGet("https://open.spotify.com/embed/playlist/$id", emptyMap()) ?: return null
        val start = html.indexOf("id=\"__NEXT_DATA__\"")
        if (start < 0) return null
        val open = html.indexOf('>', start) + 1
        val close = html.indexOf("</script>", open)
        if (open <= 0 || close < 0) return null
        val json = runCatching { JSONObject(html.substring(open, close)) }.getOrNull() ?: return null
        val entity = findObjectWith(json, "trackList") ?: return null
        val list = entity.optJSONArray("trackList") ?: return null
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.let { s ->
            (0 until s.length()).mapNotNull { s.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url")
        }
        val songs = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifBlank { null } ?: return@mapNotNull null
            Song(
                title = title,
                artist = t.optString("subtitle").replace(' ', ' '),
                durationMs = t.optLong("duration"),
                mediaId = t.optString("uri").takeIf { it.startsWith("spotify:track:") },
                imageUrl = null,
            )
        }
        if (songs.isEmpty()) return null
        return Fetched(entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Spotify playlist" }, "", cover, songs)
    }

    private fun spotifyGet(token: String, url: String): JSONObject? =
        httpGet(url, mapOf("Authorization" to "Bearer $token"))?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun firstImage(images: JSONArray?): String? {
        if (images == null || images.length() == 0) return null
        // The image closest to 640 px (Spotify lists them largest first).
        return (0 until images.length()).mapNotNull { images.optJSONObject(it) }
            .minByOrNull { kotlin.math.abs(it.optInt("width", 640) - 640) }?.optString("url")?.ifBlank { null }
    }

    // ---------------------------------------------------------------- YouTube Music

    suspend fun youTubeMine(context: Context): List<Remote> = withContext(Dispatchers.IO) {
        val headers = YouTubeAccount.authHeaders(context)
        if (headers.isEmpty()) throw NeedsYouTubeSignIn()
        val json = browse(JSONObject().put("browseId", "FEmusic_liked_playlists"), headers) ?: return@withContext emptyList()
        val out = ArrayList<Remote>()
        collect(json, "musicTwoRowItemRenderer") { item ->
            val browseId = findString(item.optJSONObject("navigationEndpoint"), "browseId") ?: return@collect
            if (!browseId.startsWith("VL")) return@collect
            val title = runsText(item.optJSONObject("title")).ifBlank { return@collect }
            out += Remote(Source.YOUTUBE_MUSIC, browseId.removePrefix("VL"), title, 0, lastThumbnail(item))
        }
        out.distinctBy { it.id }
    }

    suspend fun youTubePlaylist(context: Context, listId: String): Fetched = withContext(Dispatchers.IO) {
        val headers = YouTubeAccount.authHeaders(context)
        val id = listId.removePrefix("VL")
        val first = browse(JSONObject().put("browseId", "VL$id"), headers)
        val shelf = first?.let { findObjectWith(it, "musicPlaylistShelfRenderer")?.optJSONObject("musicPlaylistShelfRenderer") }
            ?: first?.let { findObjectWith(it, "musicShelfRenderer")?.optJSONObject("musicShelfRenderer") }
        val songs = LinkedHashMap<String, Song>()
        if (shelf != null) addRows(shelf, songs)
        var token = shelf?.let(::continuationToken)
        var pages = 0
        while (token != null && pages++ < MAX_PAGES && songs.size < MAX_SONGS) {
            val next = browse(JSONObject().put("continuation", token), headers) ?: break
            val before = songs.size
            addRows(next, songs)
            if (songs.size == before) break
            token = continuationToken(next)
        }
        if (songs.isEmpty()) {
            if (headers.isEmpty()) throw NeedsYouTubeSignIn()
            throw IllegalStateException("empty")
        }
        val header = first?.let { json ->
            listOf("musicResponsiveHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer", "musicDetailHeaderRenderer")
                .firstNotNullOfOrNull { key -> findObjectWith(json, key)?.optJSONObject(key) }
        }
        val name = header?.let { h -> runsText(h.optJSONObject("title")).ifBlank { findTitle(h) } }.orEmpty()
            .ifBlank { if (id == "LM") "Liked music" else "YouTube Music playlist" }
        val description = header?.let { h ->
            findObjectWith(h, "description")?.optJSONObject("description")?.let { d ->
                runsText(d).ifBlank { findTitle(d) }
            }
        }.orEmpty()
        Fetched(name, description, header?.let(::lastThumbnail), songs.values.toList())
    }

    private fun addRows(node: Any, out: MutableMap<String, Song>) {
        collect(node, "musicResponsiveListItemRenderer") { row ->
            val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf(YouTubeLink::isVideoId)
                ?: findString(row.optJSONObject("overlay"), "videoId")?.takeIf(YouTubeLink::isVideoId)
                ?: return@collect // unavailable song
            val cols = row.optJSONArray("flexColumns") ?: return@collect
            fun col(i: Int) = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val title = runsText(col(0)).ifBlank { return@collect }
            val artist = runsText(col(1)).split(" • ").firstOrNull().orEmpty().trim()
            val durationText = row.optJSONArray("fixedColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
                ?.let { runsText(it).ifBlank { it.optString("simpleText") } }.orEmpty()
            out.putIfAbsent(videoId, Song(title, artist, parseDuration(durationText), videoId, lastThumbnail(row)))
        }
    }

    private fun continuationToken(node: Any): String? =
        findObjectWith(node, "continuationCommand")?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
            ?: findObjectWith(node, "nextContinuationData")?.optJSONObject("nextContinuationData")?.optString("continuation")?.ifBlank { null }

    private fun browse(extra: JSONObject, headers: Map<String, String>): JSONObject? {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", YouTubeLink.CLIENT_VERSION)
            .put("hl", "en")))
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            body.put(k, extra.get(k))
        }
        val conn = URL(BROWSE_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", YouTubeAccount.ORIGIN)
            conn.setRequestProperty("Referer", "${YouTubeAccount.ORIGIN}/")
            conn.setRequestProperty("User-Agent", YouTubeLink.USER_AGENT)
            if (headers.isEmpty()) conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) {
                Log.i(TAG, "browse: HTTP ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.i(TAG, "browse failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- saving

    /**
     * Stores [fetched] as a new hub playlist (songs played by [source]'s app), with its cover
     * and the songs' covers. Returns (playlist id, songs added).
     */
    suspend fun save(context: Context, store: PlaylistStore, source: Source, fetched: Fetched): Pair<Long, Int> {
        val pkg = if (source == Source.SPOTIFY) KnownApps.SPOTIFY else KnownApps.YOUTUBE_MUSIC
        val label = ExternalSnapshots.appLabel(context, pkg)
        val drafts = fetched.songs.map { EntryDraft(pkg, label, it.title, it.artist, null, it.durationMs, it.mediaId) }
        val id = store.createWithEntries(fetched.name, fetched.description, drafts)
        fetched.imageUrl?.let { url ->
            download(url)?.let { bitmap ->
                val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val path = store.saveCover(id, jpeg)
                store.byId(id)?.let { store.updateMeta(id, it.meta.copy(coverPath = path)) }
            }
        }
        // Song covers, so the playlist looks complete before anything is played.
        for (song in fetched.songs.take(300)) {
            val url = song.imageUrl ?: continue
            if (ArtStore.has(context, song.title, song.artist)) continue
            val bitmap = download(url) ?: continue
            ArtStore.save(context, song.title, song.artist, square(bitmap))
        }
        return id to drafts.size
    }

    private suspend fun download(url: String): Bitmap? = SpotifyLink.downloadImage(url)

    private fun square(src: Bitmap): Bitmap {
        if (src.width == src.height) return src
        // YouTube video thumbnails: 16:9 (sometimes letterboxed in 4:3); the cover is the middle square.
        val contentHeight = minOf(src.height, src.width * 9 / 16)
        val side = minOf(contentHeight, src.width)
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    // ---------------------------------------------------------------- helpers

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", YouTubeLink.USER_AGENT)
            conn.setRequestProperty("Accept-Language", "en")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode != 200) {
                Log.i(TAG, "GET ${url.substringBefore('?')}: HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "GET failed: ${t.javaClass.simpleName}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#x27;", "'").replace("&quot;", "\"").trim()

    private fun parseDuration(text: String): Long {
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty() || parts.size > 3) return 0L
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000L
    }

    private fun runsText(node: JSONObject?): String {
        val runs = node?.optJSONArray("runs") ?: return node?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun findTitle(node: JSONObject): String = findObjectWith(node, "runs")?.let { runsText(it) }.orEmpty()

    private fun lastThumbnail(node: JSONObject): String? {
        val holder = findObjectWith(node, "thumbnails") ?: return null
        val list = holder.optJSONArray("thumbnails") ?: return null
        if (list.length() == 0) return null
        val url = list.optJSONObject(list.length() - 1)?.optString("url")?.ifBlank { null } ?: return null
        // lh3 covers come small; ask for a 544 px version.
        return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    }

    /** Calls [block] for every object stored under [key], anywhere in [node]. */
    private fun collect(node: Any?, key: String, block: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let {
                    block(it)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), key, block)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, block)
        }
    }

    /** The first object (depth first) that has [key]. */
    private fun findObjectWith(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node
                val keys = node.keys()
                while (keys.hasNext()) findObjectWith(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findObjectWith(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun findString(node: Any?, key: String): String? {
        val holder = findObjectWith(node, key) ?: return null
        return holder.optString(key).ifBlank { null }
    }
}
