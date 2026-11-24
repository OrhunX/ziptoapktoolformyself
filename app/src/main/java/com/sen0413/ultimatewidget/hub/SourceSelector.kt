package com.sen0413.ultimatewidget.hub

import com.sen0413.ultimatewidget.data.TrackMatcher

/**
 * Which source the hub shows and controls. Pure functions, unit-tested.
 *
 * Rule: follow whichever source is actually playing; when both or neither play, keep what
 * the user was looking at; never point at an external app that has no session.
 */
object SourceSelector {
    fun select(
        current: SourceKind,
        hasExternal: Boolean,
        localPlaying: Boolean,
        externalPlaying: Boolean,
    ): SourceKind = when {
        !hasExternal -> SourceKind.LOCAL
        localPlaying && !externalPlaying -> SourceKind.LOCAL
        externalPlaying && !localPlaying -> SourceKind.EXTERNAL
        else -> current
    }
}

/**
 * Detects that an external app finished the song the hub asked for, so a hub playlist can
 * move on. Other apps do not tell us "the song ended"; we watch their metadata instead:
 *  - the watch arms only when the requested title is actually playing (a different song,
 *    such as the one the app last had open, is never accepted as the requested one);
 *  - it remembers the furthest point the song reached while playing. Once that is within
 *    [NATURAL_END_MS] of the end, the song is over: whatever comes next (another title, even
 *    after a short "buffering" or "paused" moment between songs as Spotify reports it, the app
 *    stopping on its last seconds, or the same song starting again) counts as finished;
 *  - before that point, a title change while nothing plays is ignored (apps refresh their
 *    session on their own while paused; that must never start the next song), and another
 *    song playing is the user's own choice ([Result.USER_CHANGED]);
 *  - [isOverdue] tells the hub that the requested song never started, so it can retry or skip.
 */
class ExternalTrackWatch(
    private val expectedTitle: String,
    private val startedAtMs: Long,
    private val graceMs: Long = 15_000L,
) {
    private var armedTitle: String? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var lastAtMs = 0L
    private var lastPlaying = false
    private var lastPlayingAtMs = 0L
    /** The furthest position the song reached while playing. */
    private var reachedMs = 0L

    enum class Result { WAITING, PLAYING_EXPECTED, MOVED_ON, USER_CHANGED }

    val isArmed: Boolean get() = armedTitle != null

    fun matches(title: String?): Boolean = !title.isNullOrBlank() && TrackMatcher.sameTitle(expectedTitle, title)

    fun onMetadata(
        title: String?,
        isPlaying: Boolean,
        nowMs: Long,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ): Result {
        val armed = armedTitle
        if (armed == null) {
            if (isPlaying && matches(title)) {
                armedTitle = title
                remember(isPlaying, nowMs, positionMs, durationMs)
                return Result.PLAYING_EXPECTED
            }
            return Result.WAITING
        }
        if (title.isNullOrBlank()) return Result.PLAYING_EXPECTED
        // How far it got: it kept playing from the last report until now.
        if (lastPlaying) reachedMs = maxOf(reachedMs, lastPositionMs + (nowMs - lastAtMs).coerceAtLeast(0L))
        val nearEnd = reachedNearEnd(nowMs)
        if (title != armed) {
            if (nearEnd) return Result.MOVED_ON
            // Paused mid-song: the app only refreshed its session. Never start the next song.
            if (!isPlaying) return Result.PLAYING_EXPECTED
            return Result.USER_CHANGED
        }
        val restarted = nearEnd && positionMs < RESTART_MS
        remember(isPlaying, nowMs, positionMs, durationMs)
        // Stopped on its last seconds, or reset to the start after reaching the end (stopped, or
        // looping by the app's own repeat mode).
        if (!isPlaying && durationMs > 0L && positionMs >= durationMs - END_SLACK_MS) return Result.MOVED_ON
        if (restarted) return Result.MOVED_ON
        return Result.PLAYING_EXPECTED
    }

    private fun remember(isPlaying: Boolean, nowMs: Long, positionMs: Long, durationMs: Long) {
        lastPlaying = isPlaying
        lastAtMs = nowMs
        lastPositionMs = positionMs
        if (isPlaying) {
            lastPlayingAtMs = nowMs
            reachedMs = maxOf(reachedMs, positionMs)
        }
        if (durationMs > 0L) lastDurationMs = durationMs
    }

    private fun reachedNearEnd(nowMs: Long): Boolean {
        if (lastDurationMs <= 0L) {
            // Length unknown: only a change that follows playback directly counts.
            return lastPlayingAtMs > 0L && nowMs - lastPlayingAtMs <= UNKNOWN_LENGTH_GAP_MS
        }
        return reachedMs >= lastDurationMs - NATURAL_END_MS
    }

    /** True when the requested song has not started within the grace period. */
    fun isOverdue(nowMs: Long): Boolean = armedTitle == null && nowMs - startedAtMs > graceMs

    companion object {
        const val END_SLACK_MS = 2_500L
        const val NATURAL_END_MS = 10_000L
        const val RESTART_MS = 5_000L
        const val UNKNOWN_LENGTH_GAP_MS = 5_000L
    }
}
