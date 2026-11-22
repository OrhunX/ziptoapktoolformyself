package com.sen0413.ultimatewidget.data

import androidx.annotation.DrawableRes

/** A song our own player can play: a bundled demo track or an audio file on the phone. */
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: String,
    @DrawableRes val coverRes: Int = 0,
    val artUri: String? = null,
)

/**
 * One row of a hub playlist. [sourcePackage] is null for a local file; otherwise it is the
 * app that plays the song (Spotify, YouTube Music, ...), which the hub asks to play it.
 * [mediaId] is that app's own id for the song (e.g. "spotify:track:..."), when we saw it,
 * so the hub can ask for exactly this song instead of searching.
 */
data class PlaylistEntry(
    val id: Long,
    val sourcePackage: String?,
    val sourceLabel: String,
    val title: String,
    val artist: String,
    val mediaUri: String?,
    val durationMs: Long,
    val mediaId: String? = null,
) {
    val isLocal: Boolean get() = sourcePackage == null

    /** A local song from someone else's code that is not on this phone. */
    val isMissing: Boolean get() = isLocal && mediaUri == null

    fun toDraft() = EntryDraft(sourcePackage, sourceLabel, title, artist, mediaUri, durationMs, mediaId)
}

/** What we know about a song before it is stored (no row id yet). */
data class EntryDraft(
    val sourcePackage: String?,
    val sourceLabel: String,
    val title: String,
    val artist: String,
    val mediaUri: String?,
    val durationMs: Long,
    val mediaId: String? = null,
)

enum class RepeatMode { OFF, ALL, ONE }

/** How the playlist list is ordered (pinned playlists always come first). */
enum class PlaylistSort { MANUAL, NAME, RECENT, MOST_PLAYED }

/** Everything a user can customize about a playlist, apart from its songs. */
data class PlaylistMeta(
    val name: String,
    val description: String = "",
    /** 0 = take the color from the covers. */
    val color: Int = 0,
    /** Shown on a plain color when set and no photo is chosen. */
    val emoji: String = "",
    /** A cover photo copied into the app's storage, or null for the automatic collage. */
    val coverPath: String? = null,
    val pinned: Boolean = false,
    val defaultShuffle: Boolean = false,
    val defaultRepeat: RepeatMode = RepeatMode.ALL,
)

data class Playlist(
    val id: Long,
    val name: String,
    val entries: List<PlaylistEntry>,
    val description: String = "",
    val color: Int = 0,
    val emoji: String = "",
    val coverPath: String? = null,
    val pinned: Boolean = false,
    val defaultShuffle: Boolean = false,
    val defaultRepeat: RepeatMode = RepeatMode.ALL,
    val isFavorites: Boolean = false,
    val createdAt: Long = 0L,
    val position: Int = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
) {
    val totalDurationMs: Long get() = entries.sumOf { it.durationMs }

    /** Apps the songs come from: null stands for files on the phone. */
    val sources: List<String?> get() = entries.map { it.sourcePackage }.distinct()

    val meta: PlaylistMeta
        get() = PlaylistMeta(name, description, color, emoji, coverPath, pinned, defaultShuffle, defaultRepeat)
}
