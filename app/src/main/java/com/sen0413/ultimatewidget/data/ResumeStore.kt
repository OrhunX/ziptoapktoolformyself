package com.sen0413.ultimatewidget.data

import android.content.Context

/**
 * The last song (and the hub playlist it came from), kept on disk so the widget still shows
 * it after the app was closed, the process was killed or the phone restarted, and so "play"
 * can continue from there.
 */
object ResumeStore {
    private const val PREFS = "resume"

    data class Last(
        val title: String,
        val artist: String,
        /** null = a file on the phone. */
        val packageName: String?,
        val appLabel: String,
        val mediaId: String?,
        val mediaUri: String?,
        val durationMs: Long,
        val positionMs: Long,
        /** The hub playlist(s) being played, or empty. */
        val playlistIds: List<Long>,
        val sessionName: String,
        /** PlaylistEntry.id of the song within that playlist, or -1. */
        val entryId: Long,
        /** Position of the song in our player's queue (library playback), or -1. */
        val libraryIndex: Int,
    )

    fun save(context: Context, last: Last) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", last.title)
            .putString("artist", last.artist)
            .putString("pkg", last.packageName)
            .putString("label", last.appLabel)
            .putString("mediaId", last.mediaId)
            .putString("mediaUri", last.mediaUri)
            .putLong("duration", last.durationMs)
            .putLong("position", last.positionMs)
            .putString("playlists", last.playlistIds.joinToString(","))
            .putString("session", last.sessionName)
            .putLong("entry", last.entryId)
            .putInt("libraryIndex", last.libraryIndex)
            .apply()
    }

    fun load(context: Context): Last? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = p.getString("title", null)?.takeIf { it.isNotBlank() } ?: return null
        return Last(
            title = title,
            artist = p.getString("artist", "").orEmpty(),
            packageName = p.getString("pkg", null),
            appLabel = p.getString("label", "").orEmpty(),
            mediaId = p.getString("mediaId", null),
            mediaUri = p.getString("mediaUri", null),
            durationMs = p.getLong("duration", 0L),
            positionMs = p.getLong("position", 0L),
            playlistIds = p.getString("playlists", "").orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() },
            sessionName = p.getString("session", "").orEmpty(),
            entryId = p.getLong("entry", -1L),
            libraryIndex = p.getInt("libraryIndex", -1),
        )
    }
}
