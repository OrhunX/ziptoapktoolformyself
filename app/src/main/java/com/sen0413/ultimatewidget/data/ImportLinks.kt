package com.sen0413.ultimatewidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an imported playlist came from, so it can be updated later: the platform, its id
 * there, when it was last updated, the songs that came from it, and the songs the user
 * removed from it (an update never brings those back).
 */
object ImportLinks {
    private const val PREFS = "import_links"
    private const val KEY_AUTO = "auto_update"
    private const val KEY_LAST_AUTO = "last_auto_update"

    data class Link(
        /** "SPOTIFY" or "YOUTUBE_MUSIC" (PlaylistImporter.Source names). */
        val source: String,
        val remoteId: String,
        val lastSyncMs: Long,
        /** TrackMatcher keys of the songs the platform had at the last update. */
        val importedKeys: Set<String>,
        /** Keys of songs from the platform that the user removed here. */
        val removedKeys: Set<String> = emptySet(),
    )

    fun get(context: Context, playlistId: Long): Link? {
        val raw = prefs(context).getString(key(playlistId), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Link(
                source = o.getString("source"),
                remoteId = o.getString("id"),
                lastSyncMs = o.optLong("last"),
                importedKeys = o.optJSONArray("imported").toSet(),
                removedKeys = o.optJSONArray("removed").toSet(),
            )
        }.getOrNull()
    }

    fun put(context: Context, playlistId: Long, link: Link) {
        val o = JSONObject()
            .put("source", link.source)
            .put("id", link.remoteId)
            .put("last", link.lastSyncMs)
            .put("imported", JSONArray(link.importedKeys.toList()))
            .put("removed", JSONArray(link.removedKeys.toList()))
        prefs(context).edit().putString(key(playlistId), o.toString()).apply()
    }

    fun remove(context: Context, playlistId: Long) {
        prefs(context).edit().remove(key(playlistId)).apply()
    }

    /** Ids of every playlist that can be updated. */
    fun linkedIds(context: Context): List<Long> =
        prefs(context).all.keys.mapNotNull { it.removePrefix("p").takeIf { k -> it.startsWith("p") }?.toLongOrNull() }

    fun isAutoUpdate(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun lastAutoUpdate(context: Context): Long = prefs(context).getLong(KEY_LAST_AUTO, 0L)

    fun setLastAutoUpdate(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_LAST_AUTO, ms).apply()
    }

    private fun key(playlistId: Long) = "p$playlistId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun JSONArray?.toSet(): Set<String> =
        if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }.toSet()
}
