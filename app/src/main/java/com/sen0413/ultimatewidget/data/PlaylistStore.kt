package com.sen0413.ultimatewidget.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hub playlists in SQLite. A playlist row never stores a platform id as its identity: an
 * entry is "this title by this artist, played by this app", which is what lets one playlist
 * mix Spotify, YouTube Music and local files.
 *
 * Reads are served from [playlists] (memory); every write runs on Dispatchers.IO and then
 * republishes the whole list, which is cheap at playlist scale and keeps one source of truth.
 *
 * Schema v2 (v1.2) adds playlist customization (description, color, emoji, cover photo,
 * pinning, default shuffle/repeat, manual order, play count) and each song's media id.
 */
class PlaylistStore(context: Context) {
    private val appContext = context.applicationContext
    private val helper = Helper(appContext)
    private val favoritesName = context.getString(com.sen0413.ultimatewidget.R.string.playlist_favorites)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    @Volatile
    private var favoriteKeys: Set<String> = emptySet()

    suspend fun load() = withContext(Dispatchers.IO) { publish() }

    fun byId(id: Long): Playlist? = _playlists.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- playlists

    suspend fun create(name: String, meta: PlaylistMeta? = null): Long = withContext(Dispatchers.IO) {
        val id = insertPlaylist(name.trim().ifEmpty { "Playlist" })
        if (meta != null) writeMeta(id, meta.copy(name = name.trim().ifEmpty { "Playlist" }))
        publish()
        id
    }

    /** Favorites can be customized but never deleted. */
    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(T_PLAYLISTS, "id = ? AND is_favorites = 0", arrayOf(playlistId.toString()))
        coverFile(playlistId).delete()
        publish()
    }

    suspend fun updateMeta(playlistId: Long, meta: PlaylistMeta) = withContext(Dispatchers.IO) {
        writeMeta(playlistId, meta)
        publish()
    }

    /** Stores a chosen cover photo (already decoded and scaled) and returns its path. */
    suspend fun saveCover(playlistId: Long, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        val file = coverFile(playlistId)
        file.parentFile?.mkdirs()
        file.writeBytes(jpeg)
        file.absolutePath
    }

    suspend fun recordPlay(playlistIds: Collection<Long>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (id in playlistIds) {
            helper.writableDatabase.execSQL(
                "UPDATE $T_PLAYLISTS SET play_count = play_count + 1, last_played = ? WHERE id = ?",
                arrayOf<Any>(now, id),
            )
        }
        publish()
    }

    /** Manual order of the playlist list (drag and drop): [orderedIds] in their new order. */
    suspend fun setPlaylistOrder(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { i, id ->
                db.update(T_PLAYLISTS, ContentValues().apply { put("position", i) }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        publish()
    }

    /** A new playlist with the same songs and look; the original stays as it is. */
    suspend fun copy(sourceId: Long, name: String): Long = withContext(Dispatchers.IO) {
        val source = readPlaylist(sourceId) ?: return@withContext -1L
        val id = insertPlaylist(name)
        writeMeta(id, source.meta.copy(name = name, pinned = false, coverPath = null))
        source.coverPath?.let { path ->
            val copied = runCatching { File(path).copyTo(coverFile(id), overwrite = true) }.isSuccess
            if (copied) writeCoverPath(id, coverFile(id).absolutePath)
        }
        source.entries.forEach { insertEntry(id, it.toDraft()) }
        publish()
        id
    }

    /** Adds the songs of [sourceId] to the end of [targetId], skipping songs already there. Returns how many. */
    suspend fun appendInto(sourceId: Long, targetId: Long): Int = withContext(Dispatchers.IO) {
        val source = readPlaylist(sourceId) ?: return@withContext 0
        var added = 0
        source.entries.forEach { if (insertEntry(targetId, it.toDraft())) added++ }
        publish()
        added
    }

    /** A new playlist with the songs of all [sourceIds] in random order, without duplicates. */
    suspend fun createMixed(name: String, sourceIds: List<Long>): Long = withContext(Dispatchers.IO) {
        val drafts = sourceIds.mapNotNull { readPlaylist(it) }.flatMap { it.entries }
            .distinctBy { TrackMatcher.key(it.title, it.artist) }.shuffled().map { it.toDraft() }
        val id = insertPlaylist(name)
        drafts.forEach { insertEntry(id, it) }
        publish()
        id
    }

    /** A new playlist with these songs (imported from another app). Returns its id. */
    suspend fun createWithEntries(name: String, description: String, entries: List<EntryDraft>): Long = withContext(Dispatchers.IO) {
        val id = insertPlaylist(name)
        writeMeta(id, PlaylistMeta(name, description))
        // One transaction, duplicates skipped in memory (playlists can have hundreds of songs).
        val db = helper.writableDatabase
        val seen = HashSet<String>()
        db.beginTransaction()
        try {
            for (draft in entries) {
                if (!seen.add(TrackMatcher.key(draft.title, draft.artist))) continue
                db.insertOrThrow(T_ENTRIES, null, ContentValues().apply {
                    put("playlist_id", id)
                    put("position", seen.size - 1)
                    put("source_pkg", draft.sourcePackage)
                    put("source_label", draft.sourceLabel)
                    put("title", draft.title)
                    put("artist", draft.artist)
                    put("media_uri", draft.mediaUri)
                    put("duration", draft.durationMs)
                    put("media_id", draft.mediaId)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        publish()
        id
    }

    /** A playlist from a share code. Returns its id. */
    suspend fun importShared(shared: SharedPlaylist, entries: List<EntryDraft>): Long = withContext(Dispatchers.IO) {
        val id = insertPlaylist(shared.name)
        writeMeta(id, PlaylistMeta(shared.name, shared.description, shared.color, shared.emoji))
        entries.forEach { insertEntry(id, it) }
        publish()
        id
    }

    // ---------------------------------------------------------------- songs

    /** Returns false when the same song is already in the playlist. */
    suspend fun addEntry(playlistId: Long, draft: EntryDraft): Boolean = withContext(Dispatchers.IO) {
        val added = insertEntry(playlistId, draft)
        if (added) publish()
        added
    }

    /** Removes a song and returns it with its position, so the UI can offer undo. */
    suspend fun removeEntry(entryId: Long): Pair<PlaylistEntry, Int>? = withContext(Dispatchers.IO) {
        val playlistId = playlistOf(entryId) ?: return@withContext null
        val entries = queryEntries(playlistId)
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return@withContext null
        helper.writableDatabase.delete(T_ENTRIES, "id = ?", arrayOf(entryId.toString()))
        rewritePositions(playlistId, entries.filter { it.id != entryId }.map { it.id })
        publish()
        entries[index] to index
    }

    /** Puts a removed song back where it was. */
    suspend fun restoreEntry(playlistId: Long, entry: PlaylistEntry, index: Int) = withContext(Dispatchers.IO) {
        if (!insertEntry(playlistId, entry.toDraft())) return@withContext
        val entries = queryEntries(playlistId)
        val restored = entries.last()
        val ids = entries.dropLast(1).map { it.id }.toMutableList()
        ids.add(index.coerceIn(0, ids.size), restored.id)
        rewritePositions(playlistId, ids)
        publish()
    }

    /** Drag and drop inside a playlist. */
    suspend fun moveEntry(playlistId: Long, from: Int, to: Int) = withContext(Dispatchers.IO) {
        val ids = queryEntries(playlistId).map { it.id }.toMutableList()
        if (from !in ids.indices) return@withContext
        val id = ids.removeAt(from)
        ids.add(to.coerceIn(0, ids.size), id)
        rewritePositions(playlistId, ids)
        publish()
    }

    /** Forgets the stored app id of these songs (a wrong guess; looked up again later). */
    suspend fun clearMediaIds(entryIds: Collection<Long>) = withContext(Dispatchers.IO) {
        if (entryIds.isEmpty()) return@withContext
        for (id in entryIds) {
            helper.writableDatabase.update(
                T_ENTRIES, ContentValues().apply { putNull("media_id") }, "id = ?", arrayOf(id.toString()),
            )
        }
        publish()
    }

    /** A song that could not be found here will be played by [pkg] from now on. */
    suspend fun assignSource(entryIds: Collection<Long>, pkg: String, label: String) = withContext(Dispatchers.IO) {
        for (id in entryIds) {
            helper.writableDatabase.update(
                T_ENTRIES,
                ContentValues().apply {
                    put("source_pkg", pkg)
                    put("source_label", label)
                    putNull("media_uri")
                    putNull("media_id")
                },
                "id = ?", arrayOf(id.toString()),
            )
        }
        publish()
    }

    /** Remembers the app's own id for songs (e.g. "spotify:track:..."), so they play exactly. */
    suspend fun setMediaIds(ids: Map<Long, String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((entryId, mediaId) in ids) {
                db.update(T_ENTRIES, ContentValues().apply { put("media_id", mediaId) }, "id = ?", arrayOf(entryId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        publish()
    }

    /** Memory lookup, safe to call from the main thread on every state change. */
    fun isFavorite(title: String, artist: String): Boolean = TrackMatcher.key(title, artist) in favoriteKeys

    /** Adds the song to Favorites, or removes it if it is already there. Returns the new state. */
    suspend fun toggleFavorite(draft: EntryDraft): Boolean = withContext(Dispatchers.IO) {
        val favorites = favoritesId()
        val key = TrackMatcher.key(draft.title, draft.artist)
        val existing = queryEntries(favorites).firstOrNull { TrackMatcher.key(it.title, it.artist) == key }
        if (existing != null) {
            helper.writableDatabase.delete(T_ENTRIES, "id = ?", arrayOf(existing.id.toString()))
        } else {
            insertEntry(favorites, draft)
        }
        publish()
        existing == null
    }

    // ---------------------------------------------------------------- internals (IO thread)

    private fun coverFile(playlistId: Long) = File(appContext.filesDir, "covers/playlist_$playlistId.jpg")

    private fun favoritesId(): Long {
        helper.readableDatabase.query(T_PLAYLISTS, arrayOf("id"), "is_favorites = 1", null, null, null, null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return insertPlaylist(favoritesName, favorites = true)
    }

    private fun insertPlaylist(name: String, favorites: Boolean = false): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("created", System.currentTimeMillis())
            put("is_favorites", if (favorites) 1 else 0)
            put("position", Int.MAX_VALUE / 2) // new playlists go to the end of the manual order
        }
        return helper.writableDatabase.insertOrThrow(T_PLAYLISTS, null, values)
    }

    private fun writeMeta(playlistId: Long, meta: PlaylistMeta) {
        val values = ContentValues().apply {
            put("name", meta.name.trim().ifEmpty { "Playlist" })
            put("description", meta.description.trim())
            put("color", meta.color)
            put("emoji", meta.emoji)
            if (meta.coverPath == null) putNull("cover_path") else put("cover_path", meta.coverPath)
            put("pinned", if (meta.pinned) 1 else 0)
            put("default_shuffle", if (meta.defaultShuffle) 1 else 0)
            put("default_repeat", meta.defaultRepeat.name)
        }
        helper.writableDatabase.update(T_PLAYLISTS, values, "id = ?", arrayOf(playlistId.toString()))
        if (meta.coverPath == null) coverFile(playlistId).delete()
    }

    private fun writeCoverPath(playlistId: Long, path: String) {
        helper.writableDatabase.update(
            T_PLAYLISTS, ContentValues().apply { put("cover_path", path) }, "id = ?", arrayOf(playlistId.toString()),
        )
    }

    private fun insertEntry(playlistId: Long, draft: EntryDraft): Boolean {
        val key = TrackMatcher.key(draft.title, draft.artist)
        val current = queryEntries(playlistId)
        if (current.any { TrackMatcher.key(it.title, it.artist) == key }) return false
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("position", current.size)
            put("source_pkg", draft.sourcePackage)
            put("source_label", draft.sourceLabel)
            put("title", draft.title)
            put("artist", draft.artist)
            put("media_uri", draft.mediaUri)
            put("duration", draft.durationMs)
            put("media_id", draft.mediaId)
        }
        helper.writableDatabase.insertOrThrow(T_ENTRIES, null, values)
        return true
    }

    private fun rewritePositions(playlistId: Long, orderedIds: List<Long>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { i, id ->
                db.update(T_ENTRIES, ContentValues().apply { put("position", i) }, "id = ? AND playlist_id = ?",
                    arrayOf(id.toString(), playlistId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun playlistOf(entryId: Long): Long? =
        helper.readableDatabase.query(T_ENTRIES, arrayOf("playlist_id"), "id = ?", arrayOf(entryId.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun queryEntries(playlistId: Long): List<PlaylistEntry> {
        val result = ArrayList<PlaylistEntry>()
        helper.readableDatabase.query(
            T_ENTRIES,
            arrayOf("id", "source_pkg", "source_label", "title", "artist", "media_uri", "duration", "media_id"),
            "playlist_id = ?", arrayOf(playlistId.toString()), null, null, "position ASC, id ASC",
        ).use { c ->
            while (c.moveToNext()) {
                result += PlaylistEntry(
                    id = c.getLong(0),
                    sourcePackage = c.stringOrNull(1),
                    sourceLabel = c.getString(2).orEmpty(),
                    title = c.getString(3).orEmpty(),
                    artist = c.getString(4).orEmpty(),
                    mediaUri = c.stringOrNull(5),
                    durationMs = c.getLong(6),
                    mediaId = c.stringOrNull(7),
                )
            }
        }
        return result
    }

    private fun readPlaylist(id: Long): Playlist? =
        helper.readableDatabase.query(T_PLAYLISTS, PLAYLIST_COLUMNS, "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) playlistFrom(c) else null }

    private fun playlistFrom(c: Cursor): Playlist {
        val id = c.getLong(0)
        return Playlist(
            id = id,
            name = c.getString(1).orEmpty(),
            entries = queryEntries(id),
            isFavorites = c.getInt(2) == 1,
            createdAt = c.getLong(3),
            description = c.stringOrNull(4).orEmpty(),
            color = c.getInt(5),
            emoji = c.stringOrNull(6).orEmpty(),
            coverPath = c.stringOrNull(7)?.takeIf { File(it).exists() },
            pinned = c.getInt(8) == 1,
            defaultShuffle = c.getInt(9) == 1,
            defaultRepeat = runCatching { RepeatMode.valueOf(c.getString(10)) }.getOrDefault(RepeatMode.ALL),
            position = c.getInt(11),
            playCount = c.getInt(12),
            lastPlayedAt = c.getLong(13),
        )
    }

    private fun publish() {
        val lists = ArrayList<Playlist>()
        helper.readableDatabase.query(T_PLAYLISTS, PLAYLIST_COLUMNS, null, null, null, null, "is_favorites DESC, created ASC")
            .use { c -> while (c.moveToNext()) lists += playlistFrom(c) }
        favoriteKeys = lists.firstOrNull { it.isFavorites }?.entries?.map { TrackMatcher.key(it.title, it.artist) }?.toSet().orEmpty()
        _playlists.value = lists
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private class Helper(context: Context) : SQLiteOpenHelper(context, "hub.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE $T_PLAYLISTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    created INTEGER NOT NULL,
                    is_favorites INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL(
                """CREATE TABLE $T_ENTRIES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    playlist_id INTEGER NOT NULL REFERENCES $T_PLAYLISTS(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    source_pkg TEXT,
                    source_label TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    media_uri TEXT,
                    duration INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.execSQL("CREATE INDEX idx_entries_playlist ON $T_ENTRIES(playlist_id, position)")
            upgradeToV2(db)
        }

        /** Migrations keep every existing playlist; each step only adds columns. */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) upgradeToV2(db)
        }

        private fun upgradeToV2(db: SQLiteDatabase) {
            listOf(
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN description TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN color INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN emoji TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN cover_path TEXT",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN default_shuffle INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN default_repeat TEXT NOT NULL DEFAULT 'ALL'",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN position INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN play_count INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_PLAYLISTS ADD COLUMN last_played INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE $T_ENTRIES ADD COLUMN media_id TEXT",
            ).forEach(db::execSQL)
            // Existing playlists keep their creation order as their manual order.
            db.execSQL("UPDATE $T_PLAYLISTS SET position = id")
        }
    }

    private companion object {
        const val T_PLAYLISTS = "playlists"
        const val T_ENTRIES = "entries"
        val PLAYLIST_COLUMNS = arrayOf(
            "id", "name", "is_favorites", "created", "description", "color", "emoji", "cover_path",
            "pinned", "default_shuffle", "default_repeat", "position", "play_count", "last_played",
        )
    }
}
