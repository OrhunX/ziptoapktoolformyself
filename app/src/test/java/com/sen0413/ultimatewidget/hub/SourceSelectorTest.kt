package com.sen0413.ultimatewidget.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSelectorTest {
    private fun select(current: SourceKind, hasExternal: Boolean, local: Boolean, external: Boolean) =
        SourceSelector.select(current, hasExternal, local, external)

    @Test
    fun withoutAnExternalSessionTheHubIsAlwaysLocal() {
        assertEquals(SourceKind.LOCAL, select(SourceKind.EXTERNAL, hasExternal = false, local = false, external = false))
    }

    @Test
    fun followsWhicheverSourceIsPlaying() {
        assertEquals(SourceKind.EXTERNAL, select(SourceKind.LOCAL, hasExternal = true, local = false, external = true))
        assertEquals(SourceKind.LOCAL, select(SourceKind.EXTERNAL, hasExternal = true, local = true, external = false))
    }

    @Test
    fun keepsTheUsersChoiceWhenBothOrNeitherPlay() {
        assertEquals(SourceKind.EXTERNAL, select(SourceKind.EXTERNAL, hasExternal = true, local = false, external = false))
        assertEquals(SourceKind.LOCAL, select(SourceKind.LOCAL, hasExternal = true, local = true, external = true))
    }
}

class ExternalTrackWatchTest {
    private val waiting = ExternalTrackWatch.Result.WAITING
    private val playing = ExternalTrackWatch.Result.PLAYING_EXPECTED
    private val movedOn = ExternalTrackWatch.Result.MOVED_ON

    @Test
    fun waitsWhileTheAppStillShowsThePreviousSong() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(waiting, watch.onMetadata("Old Song", isPlaying = true, nowMs = 500))
        assertEquals(waiting, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 800))
    }

    @Test
    fun armsOnTheRequestedSongThenDetectsTheNextOne() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours (Remastered)", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        // Played through to the end, then the app went on to its next song.
        assertEquals(movedOn, watch.onMetadata("Something Else", isPlaying = true, nowMs = 201_500))
    }

    @Test
    fun aTitleChangeWhilePausedNeverStartsTheNextSong() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 30_000, positionMs = 29_000, durationMs = 200_000))
        // Minutes later the paused app refreshes its session with another title.
        assertEquals(playing, watch.onMetadata("Something Else", isPlaying = false, nowMs = 330_000))
    }

    @Test
    fun anotherSongBeforeOursEndedIsTheUsersChoice() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(ExternalTrackWatch.Result.USER_CHANGED, watch.onMetadata("Picked In Spotify", isPlaying = true, nowMs = 60_000))
    }

    @Test
    fun anotherSongAfterAPauseIsTheUsersChoice() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000))
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 60_000))
        assertEquals(ExternalTrackWatch.Result.USER_CHANGED, watch.onMetadata("Something Else", isPlaying = true, nowMs = 200_000))
    }

    @Test
    fun neverAcceptsADifferentSongAndReportsOverdueAfterTheGracePeriod() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0, graceMs = 10_000)
        assertEquals(waiting, watch.onMetadata("Last Song The App Had Open", isPlaying = true, nowMs = 5_000))
        assertEquals(false, watch.isOverdue(nowMs = 5_000))
        assertEquals(waiting, watch.onMetadata("Last Song The App Had Open", isPlaying = true, nowMs = 11_000))
        assertEquals(true, watch.isOverdue(nowMs = 11_000))
    }

    @Test
    fun aSongStoppedAtItsEndCountsAsFinished() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 60_000, positionMs = 60_000, durationMs = 200_000))
        assertEquals(movedOn, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 201_000, positionMs = 199_000, durationMs = 200_000))
    }

    @Test
    fun spotifysBufferingMomentBetweenSongsStillCountsAsFinished() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        // At the end Spotify reports "not playing" for a moment, then its next song playing.
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 199_500, positionMs = 196_000, durationMs = 200_000))
        assertEquals(movedOn, watch.onMetadata("Autoplay Pick", isPlaying = false, nowMs = 200_200))
    }

    @Test
    fun aNextTitleShownPausedAfterTheEndCountsAsFinished() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(movedOn, watch.onMetadata("Next In Context", isPlaying = false, nowMs = 202_000))
    }

    @Test
    fun aSongResetToTheStartAfterItsEndCountsAsFinished() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        // Autoplay off: Spotify stops and jumps back to 0:00.
        assertEquals(movedOn, watch.onMetadata("Blue Hours", isPlaying = false, nowMs = 201_000, positionMs = 0, durationMs = 200_000))
    }

    @Test
    fun theAppsOwnRepeatStartingTheSongAgainCountsAsFinished() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(movedOn, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 201_500, positionMs = 500, durationMs = 200_000))
    }

    @Test
    fun seekingBackMidSongIsNotTheEnd() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000, positionMs = 0, durationMs = 200_000))
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 60_000, positionMs = 1_000, durationMs = 200_000))
    }

    @Test
    fun missingMetadataIsNotTreatedAsANewSong() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000))
        assertEquals(playing, watch.onMetadata(null, isPlaying = true, nowMs = 2_000))
    }
}
