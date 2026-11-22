package com.sen0413.ultimatewidget.data

/**
 * Decides whether two titles from different apps are the same song. Platforms decorate
 * titles differently ("Song (Remastered 2011)", "Song - Live", "Song (feat. X)"), so titles
 * are normalized before they are compared. Pure Kotlin, unit-tested.
 */
object TrackMatcher {
    private val brackets = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val featuring = Regex("""\b(feat|ft|featuring)\b\.?.*$""")
    private val suffix = Regex("""\s-\s.*\b(remaster(ed)?|version|edit|mix|live|mono|stereo|bonus)\b.*$""")
    private val notWord = Regex("""[^\p{L}\p{Nd}]+""")

    fun normalize(text: String): String = text
        .lowercase()
        .replace(brackets, " ")
        .replace(suffix, " ")
        .replace(featuring, " ")
        .replace(notWord, " ")
        .trim()

    /** A stable identity for "this song by this artist", used to avoid duplicates. */
    fun key(title: String, artist: String): String {
        val firstArtist = artist.split(",", "&", " x ", ";").firstOrNull().orEmpty()
        return normalize(title) + "|" + normalize(firstArtist)
    }

    /** True when [actual] is the song we asked for; tolerant of decorations on either side. */
    fun sameTitle(expected: String, actual: String): Boolean {
        val a = normalize(expected)
        val b = normalize(actual)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        return shorter.length >= 4 && longer.contains(shorter)
    }
}
