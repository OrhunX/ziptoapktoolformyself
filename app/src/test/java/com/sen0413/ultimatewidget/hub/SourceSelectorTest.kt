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
        assertEquals(playing, watch.onMetadata("Blue Hours (Remastered)", isPlaying = true, nowMs = 1_000))
        assertEquals(playing, watch.onMetadata("Blue Hours (Remastered)", isPlaying = false, nowMs = 60_000))
        assertEquals(movedOn, watch.onMetadata("Something Else", isPlaying = true, nowMs = 200_000))
    }

    @Test
    fun acceptsASearchMissAfterTheGracePeriodSoThePlaylistNeverStalls() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0, graceMs = 10_000)
        assertEquals(waiting, watch.onMetadata("Close Enough Radio", isPlaying = true, nowMs = 5_000))
        assertEquals(playing, watch.onMetadata("Close Enough Radio", isPlaying = true, nowMs = 11_000))
        assertEquals(movedOn, watch.onMetadata("Next Radio Song", isPlaying = true, nowMs = 180_000))
    }

    @Test
    fun missingMetadataIsNotTreatedAsANewSong() {
        val watch = ExternalTrackWatch("Blue Hours", startedAtMs = 0)
        assertEquals(playing, watch.onMetadata("Blue Hours", isPlaying = true, nowMs = 1_000))
        assertEquals(playing, watch.onMetadata(null, isPlaying = true, nowMs = 2_000))
    }
}
