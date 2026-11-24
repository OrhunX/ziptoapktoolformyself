package com.sen0413.ultimatewidget.hub

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sen0413.ultimatewidget.data.ArtStore
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.ImportLinks
import com.sen0413.ultimatewidget.data.TrackMatcher
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
     * and the songs' covers, and remembers where it came from so it can be updated later.
     * Returns (playlist id, songs added).
     */
    suspend fun save(context: Context, store: PlaylistStore, source: Source, remoteId: String, fetched: Fetched): Pair<Long, Int> {
        val drafts = drafts(context, source, fetched.songs)
        val id = store.createWithEntries(fetched.name, fetched.description, drafts)
        ImportLinks.put(
            context, id,
            ImportLinks.Link(source.name, remoteId, System.currentTimeMillis(), drafts.map { TrackMatcher.key(it.title, it.artist) }.toSet()),
        )
        fetched.imageUrl?.let { url ->
            download(url)?.let { bitmap ->
                val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val path = store.saveCover(id, jpeg)
                store.byId(id)?.let { store.updateMeta(id, it.meta.copy(coverPath = path)) }
            }
        }
        saveSongCovers(context, fetched.songs)
        return id to drafts.size
    }

    private fun drafts(context: Context, source: Source, songs: List<Song>): List<EntryDraft> {
        val pkg = packageOf(source)
        val label = ExternalSnapshots.appLabel(context, pkg)
        return songs.map { EntryDraft(pkg, label, it.title, it.artist, null, it.durationMs, it.mediaId) }
    }

    private fun packageOf(source: Source) = if (source == Source.SPOTIFY) KnownApps.SPOTIFY else KnownApps.YOUTUBE_MUSIC

    /** Song covers, so the playlist looks complete before anything is played. */
    private suspend fun saveSongCovers(context: Context, songs: List<Song>) {
        for (song in songs.take(300)) {
            val url = song.imageUrl ?: continue
            if (ArtStore.has(context, song.title, song.artist)) continue
            val bitmap = download(url) ?: continue
            ArtStore.save(context, song.title, song.artist, square(bitmap))
        }
    }

    // ---------------------------------------------------------------- updating

    data class SyncResult(val name: String, val added: Int, val removed: Int)

    fun sourceOf(context: Context, playlistId: Long): Source? =
        ImportLinks.get(context, playlistId)?.let { link -> Source.values().firstOrNull { it.name == link.source } }

    /**
     * Brings an imported playlist up to date with its platform:
     *  - songs added there are added here (with covers), in the platform's order;
     *  - songs removed there are removed here;
     *  - songs the user added here by hand stay, after the platform's songs;
     *  - songs from the platform that the user removed here are not brought back.
     * Throws [NeedsSpotifyApproval] / [NeedsYouTubeSignIn] like the import does.
     */
    suspend fun sync(context: Context, store: PlaylistStore, playlistId: Long): SyncResult {
        val link = ImportLinks.get(context, playlistId) ?: throw IllegalStateException("not imported")
        val source = Source.values().first { it.name == link.source }
        val playlist = store.byId(playlistId) ?: throw IllegalStateException("gone")
        val fetched = if (source == Source.SPOTIFY) spotifyPlaylist(context, link.remoteId) else youTubePlaylist(context, link.remoteId)

        val key = { title: String, artist: String -> TrackMatcher.key(title, artist) }
        val current = playlist.entries
        val currentKeys = current.map { key(it.title, it.artist) }.toSet()
        // Songs from the platform that are no longer here: the user removed them.
        // (A song the user put back by hand is here again, so it is not "removed".)
        val userRemoved = (link.removedKeys + link.importedKeys) - currentKeys
        val sourceSongs = fetched.songs.distinctBy { key(it.title, it.artist) }
        val sourceKeys = sourceSongs.map { key(it.title, it.artist) }.toSet()
        val byKey = current.associateBy { key(it.title, it.artist) }

        val wanted = sourceSongs.filter { key(it.title, it.artist) !in userRemoved }
        val mediaIds = HashMap<Long, String>()
        val newSongs = ArrayList<Song>()
        for (song in wanted) {
            val existing = byKey[key(song.title, song.artist)]
            if (existing == null) {
                newSongs += song
            } else if (existing.mediaId.isNullOrBlank() && !song.mediaId.isNullOrBlank()) {
                mediaIds[existing.id] = song.mediaId
            }
        }
        val newDrafts = drafts(context, source, newSongs)
        // The platform's order, each new song where it stands there.
        val order = ArrayList<Any>()
        var draftIndex = 0
        for (song in wanted) {
            val existing = byKey[key(song.title, song.artist)]
            if (existing != null) order += existing.id else order += newDrafts[draftIndex++]
        }
        // The user's own additions (not from the platform) stay, after the platform's songs.
        val removedFromSource = current.filter { val k = key(it.title, it.artist); k in link.importedKeys && k !in sourceKeys }
        for (e in current) {
            val k = key(e.title, e.artist)
            if (k !in link.importedKeys && k !in sourceKeys) order += e.id
        }
        store.syncEntries(playlistId, order, mediaIds)
        ImportLinks.put(
            context, playlistId,
            link.copy(lastSyncMs = System.currentTimeMillis(), importedKeys = sourceKeys, removedKeys = userRemoved intersect sourceKeys),
        )
        saveSongCovers(context, newSongs)
        return SyncResult(playlist.name, newDrafts.size, removedFromSource.size)
    }

    /** Updates every imported playlist; returns the results of those that worked. */
    suspend fun syncAll(context: Context, store: PlaylistStore): List<SyncResult> {
        val out = ArrayList<SyncResult>()
        for (id in ImportLinks.linkedIds(context)) {
            if (store.byId(id) == null) {
                ImportLinks.remove(context, id)
                continue
            }
            runCatching { sync(context, store, id) }.onSuccess { out += it }
                .onFailure { Log.i(TAG, "update of playlist $id failed: ${it.javaClass.simpleName}") }
        }
        return out
    }

    /** Once a day, when turned on in Settings: updates imported playlists quietly in the background. */
    suspend fun autoSync(context: Context, store: PlaylistStore) {
        if (!ImportLinks.isAutoUpdate(context)) return
        val now = System.currentTimeMillis()
        if (now - ImportLinks.lastAutoUpdate(context) < 24 * 60 * 60 * 1000L) return
        if (ImportLinks.linkedIds(context).isEmpty()) return
        ImportLinks.setLastAutoUpdate(context, now)
        syncAll(context, store)
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
