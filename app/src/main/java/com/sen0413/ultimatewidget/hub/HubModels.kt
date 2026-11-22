package com.sen0413.ultimatewidget.hub

import android.graphics.Bitmap
import androidx.annotation.DrawableRes

/** Where the music currently comes from. */
enum class SourceKind { LOCAL, EXTERNAL }

/** What the visualizer can show right now. It never fakes movement. */
enum class VisualizerStatus { IDLE, NEEDS_PERMISSION, RUNNING, NO_AUDIO }

object KnownApps {
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val YOUTUBE = "com.google.android.youtube"
}

data class TrackInfo(
    val title: String,
    val artist: String,
    val mediaId: String? = null,
    @DrawableRes val coverRes: Int = 0,
    val art: Bitmap? = null,
    val durationMs: Long = 0L,
    val mediaUri: String? = null,
)

data class QueueEntry(
    val title: String,
    val subtitle: String,
    val sourceLabel: String,
    /** Position to pass back to HubController.skipTo(). */
    val index: Int,
    val queueId: Long = index.toLong(),
    /** Package of the app that plays this entry; null for local files. */
    val sourcePackage: String? = null,
)

/** An immutable snapshot of one source: our own player or another app's MediaSession. */
data class SourceSnapshot(
    val kind: SourceKind,
    val appLabel: String,
    val packageName: String?,
    val track: TrackInfo?,
    val isPlaying: Boolean,
    val positionMs: Long = 0L,
    /** SystemClock.elapsedRealtime() at which [positionMs] was measured; 0 if unknown. */
    val positionAtElapsedMs: Long = 0L,
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueIndex: Int = -1,
) {
    /** Playback position right now, extrapolated while playing. */
    fun positionAt(nowElapsedMs: Long): Long {
        if (!isPlaying || positionAtElapsedMs <= 0L) return positionMs
        return positionMs + (nowElapsedMs - positionAtElapsedMs)
    }
}

/** A hub playlist that is being played across apps. */
data class PlaylistSession(
    val playlistId: Long,
    val name: String,
    val index: Int,
    val queue: List<QueueEntry>,
)

/** The single source of truth every surface renders: widget, app screens, editor preview. */
data class HubState(
    val local: SourceSnapshot,
    val external: SourceSnapshot?,
    val active: SourceKind,
    val visualizer: VisualizerStatus,
    val notificationAccess: Boolean,
    val session: PlaylistSession? = null,
    val isFavorite: Boolean = false,
) {
    val current: SourceSnapshot
        get() = if (active == SourceKind.EXTERNAL && external != null) external else local

    /** What "Up next" shows: the hub playlist while one is playing, else the app's own queue. */
    val queue: List<QueueEntry> get() = session?.queue ?: current.queue
    val queueIndex: Int get() = session?.index ?: current.currentQueueIndex
}
