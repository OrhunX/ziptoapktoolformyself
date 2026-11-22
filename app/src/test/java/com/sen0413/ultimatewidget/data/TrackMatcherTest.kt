package com.sen0413.ultimatewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    @Test
    fun normalizeStripsDecorations() {
        assertEquals("night train", TrackMatcher.normalize("Night Train (Remastered 2011)"))
        assertEquals("night train", TrackMatcher.normalize("Night Train - Live at Wembley"))
        assertEquals("night train", TrackMatcher.normalize("NIGHT TRAIN feat. Someone"))
        assertEquals("night train", TrackMatcher.normalize("Night Train [Official Video]"))
    }

    @Test
    fun normalizeKeepsNonLatinLetters() {
        assertEquals("gülümse çocuk", TrackMatcher.normalize("Gülümse, Çocuk!"))
    }

    @Test
    fun sameSongOnDifferentPlatformsHasOneKey() {
        val spotify = TrackMatcher.key("Blue Hours - Remastered", "Demo Band")
        val youtube = TrackMatcher.key("Blue Hours (Official Video)", "Demo Band, Guest")
        assertEquals(spotify, youtube)
    }

    @Test
    fun differentArtistsAreDifferentSongs() {
        assertFalse(TrackMatcher.key("Home", "Artist A") == TrackMatcher.key("Home", "Artist B"))
    }

    @Test
    fun sameTitleToleratesExtraWords() {
        assertTrue(TrackMatcher.sameTitle("Last Ferry", "Last Ferry (Live)"))
        assertTrue(TrackMatcher.sameTitle("Last Ferry", "Demo Band - Last Ferry"))
        assertFalse(TrackMatcher.sameTitle("Last Ferry", "Night Train"))
    }

    @Test
    fun emptyTitlesNeverMatch() {
        assertFalse(TrackMatcher.sameTitle("", "Anything"))
        assertFalse(TrackMatcher.sameTitle("(Intro)", "(Intro)"))
    }
}
