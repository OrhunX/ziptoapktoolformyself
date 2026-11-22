package com.sen0413.ultimatewidget.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Hub playlists in SQLite. A playlist row never stores a platform id as its identity: an
 * entry is "this title by this artist, played by this app", which is what lets one playlist
 * mix Spotify, YouTube Music and local files.
 *
 * Reads are served from [playlists] (memory); every write runs on Dispatchers.IO and then
 * republishes the whole list, which is cheap at playlist scale and keeps one source of truth.
 */
class PlaylistStore(context: Context) {
    private val helper = Helper(context.applicationContext)
    private val favoritesName = context.getString(com.sen0413.ultimatewidget.R.string.playlist_favorites)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    @Volatile
    private var favoriteKeys: Set<String> = emptySet()

    suspend fun load() = withContext(Dispatchers.IO) { publish() }

    suspend fun create(name: String): Long = withContext(Dispatchers.IO) {
        val id = insertPlaylist(name.trim().ifEmpty { "Playlist" })
        publish()
        id
    }

    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(T_PLAYLISTS, "id = ?", arrayOf(playlistId.toString()))
        publish()
    }

    /** Returns false when the same song is already in the playlist. */
    suspend fun addEntry(playlistId: Long, draft: EntryDraft): Boolean = withContext(Dispatchers.IO) {
        val added = insertEntry(playlistId, draft)
        if (added) publish()
        added
    }

    suspend fun removeEntry(entryId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(T_ENTRIES, "id = ?", arrayOf(entryId.toString()))
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
        }
        return helper.writableDatabase.insertOrThrow(T_PLAYLISTS, null, values)
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
        }
        helper.writableDatabase.insertOrThrow(T_ENTRIES, null, values)
        return true
    }

    private fun queryEntries(playlistId: Long): List<PlaylistEntry> {
        val result = ArrayList<PlaylistEntry>()
        helper.readableDatabase.query(
            T_ENTRIES,
            arrayOf("id", "source_pkg", "source_label", "title", "artist", "media_uri", "duration"),
            "playlist_id = ?", arrayOf(playlistId.toString()), null, null, "position ASC, id ASC",
        ).use { c ->
            while (c.moveToNext()) {
                result += PlaylistEntry(
                    id = c.getLong(0),
                    sourcePackage = if (c.isNull(1)) null else c.getString(1),
                    sourceLabel = c.getString(2).orEmpty(),
                    title = c.getString(3).orEmpty(),
                    artist = c.getString(4).orEmpty(),
                    mediaUri = if (c.isNull(5)) null else c.getString(5),
                    durationMs = c.getLong(6),
                )
            }
        }
        return result
    }

    private fun publish() {
        val lists = ArrayList<Playlist>()
        var favorites: Playlist? = null
        helper.readableDatabase.query(
            T_PLAYLISTS, arrayOf("id", "name", "is_favorites"), null, null, null, null,
            "is_favorites DESC, created ASC",
        ).use { c ->
            while (c.moveToNext()) {
                val playlist = Playlist(c.getLong(0), c.getString(1).orEmpty(), queryEntries(c.getLong(0)))
                if (c.getInt(2) == 1) favorites = playlist
                lists += playlist
            }
        }
        favoriteKeys = favorites?.entries?.map { TrackMatcher.key(it.title, it.artist) }?.toSet().orEmpty()
        _playlists.value = lists
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "hub.db", null, 1) {
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val T_PLAYLISTS = "playlists"
        const val T_ENTRIES = "entries"
    }
}
