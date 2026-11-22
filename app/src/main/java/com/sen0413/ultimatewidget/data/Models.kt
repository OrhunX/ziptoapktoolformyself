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
 */
data class PlaylistEntry(
    val id: Long,
    val sourcePackage: String?,
    val sourceLabel: String,
    val title: String,
    val artist: String,
    val mediaUri: String?,
    val durationMs: Long,
) {
    val isLocal: Boolean get() = sourcePackage == null
}

/** What we know about a song before it is stored (no row id yet). */
data class EntryDraft(
    val sourcePackage: String?,
    val sourceLabel: String,
    val title: String,
    val artist: String,
    val mediaUri: String?,
    val durationMs: Long,
)

data class Playlist(
    val id: Long,
    val name: String,
    val entries: List<PlaylistEntry>,
)
