package com.sen0413.ultimatewidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodeTest {
    private val playlist = SharedPlaylist(
        name = "Road trip",
        description = "Sunday drive",
        color = 0xFF6B4FD8.toInt(),
        emoji = "🚗",
        from = "Orhun",
        entries = listOf(
            SharedEntry("com.spotify.music", "Spotify", "Night Train", "Demo Band", 210_000, "spotify:track:abc123"),
            SharedEntry(null, "On this phone", "Blue Hours", "Demo Band", 47_000, null),
            SharedEntry("com.google.android.apps.youtube.music", "YouTube Music", "Şarkı ğüşıöç", "Sanatçı", 0, null),
        ),
    )

    @Test
    fun aCodeWithoutPinRoundTrips() {
        val code = ShareCode.encode(playlist)
        assertTrue(code.startsWith(ShareCode.PREFIX))
        assertEquals(ShareCode.Result.Ok(playlist), ShareCode.decode(code))
    }

    @Test
    fun aPinIsRequiredAndAWrongPinIsRejected() {
        val code = ShareCode.encode(playlist, pin = "4130")
        assertEquals(ShareCode.Result.NeedsPin, ShareCode.decode(code))
        assertEquals(ShareCode.Result.WrongPin, ShareCode.decode(code, pin = "0000"))
        assertEquals(ShareCode.Result.Ok(playlist), ShareCode.decode(code, pin = "4130"))
    }

    @Test
    fun theCodeIsFoundInsideAMessageOrALink() {
        val code = ShareCode.encode(playlist)
        assertEquals(ShareCode.Result.Ok(playlist), ShareCode.decode("Listen to this!\n$code\nOpen it in the app"))
        assertEquals(ShareCode.Result.Ok(playlist), ShareCode.decode(ShareCode.DEEP_LINK + code))
    }

    @Test
    fun garbageAndTamperedCodesAreInvalid() {
        assertEquals(ShareCode.Result.Invalid, ShareCode.decode("hello"))
        val code = ShareCode.encode(playlist)
        val tampered = code.dropLast(3) + if (code.endsWith("AAA")) "BBB" else "AAA"
        assertEquals(ShareCode.Result.Invalid, ShareCode.decode(tampered))
    }
}
