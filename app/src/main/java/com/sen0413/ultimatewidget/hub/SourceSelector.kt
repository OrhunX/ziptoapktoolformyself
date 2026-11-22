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
 *  - once armed, a different title, or the same song stopped at its very end, means done;
 *  - [isOverdue] tells the hub that the requested song never started, so it can retry or skip.
 */
class ExternalTrackWatch(
    private val expectedTitle: String,
    private val startedAtMs: Long,
    private val graceMs: Long = 15_000L,
) {
    private var armedTitle: String? = null

    enum class Result { WAITING, PLAYING_EXPECTED, MOVED_ON }

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
                return Result.PLAYING_EXPECTED
            }
            return Result.WAITING
        }
        if (title.isNullOrBlank()) return Result.PLAYING_EXPECTED
        if (title != armed) return Result.MOVED_ON
        // Some apps stop on the last second instead of moving to another song.
        if (!isPlaying && durationMs > 0L && positionMs >= durationMs - END_SLACK_MS) return Result.MOVED_ON
        return Result.PLAYING_EXPECTED
    }

    /** True when the requested song has not started within the grace period. */
    fun isOverdue(nowMs: Long): Boolean = armedTitle == null && nowMs - startedAtMs > graceMs

    companion object {
        const val END_SLACK_MS = 2_500L
    }
}
