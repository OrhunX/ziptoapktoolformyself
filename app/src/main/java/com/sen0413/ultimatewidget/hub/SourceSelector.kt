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
 * once the requested title has been seen ("armed"), a different title means the app moved on.
 */
class ExternalTrackWatch(
    private val expectedTitle: String,
    private val startedAtMs: Long,
    private val graceMs: Long = 12_000L,
) {
    private var armedTitle: String? = null

    enum class Result { WAITING, PLAYING_EXPECTED, MOVED_ON }

    fun onMetadata(title: String?, isPlaying: Boolean, nowMs: Long): Result {
        if (title.isNullOrBlank()) return if (armedTitle == null) Result.WAITING else Result.PLAYING_EXPECTED
        val armed = armedTitle
        if (armed == null) {
            if (!isPlaying) return Result.WAITING
            val matches = TrackMatcher.sameTitle(expectedTitle, title)
            // If the app played something else (search miss), accept it after a grace period
            // so the playlist never stalls on one entry.
            if (matches || nowMs - startedAtMs > graceMs) {
                armedTitle = title
                return Result.PLAYING_EXPECTED
            }
            return Result.WAITING
        }
        return if (title == armed) Result.PLAYING_EXPECTED else Result.MOVED_ON
    }
}
