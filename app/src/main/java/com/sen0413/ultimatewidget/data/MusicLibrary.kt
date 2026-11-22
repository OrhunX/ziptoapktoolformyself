package com.sen0413.ultimatewidget.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Everything our own player can play: the phone's audio files (MediaStore). */
class MusicLibrary(context: Context) {
    private val app = context.applicationContext

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** Re-reads MediaStore. Safe to call often; does nothing without the permission. */
    suspend fun refresh() {
        if (!hasPermission()) {
            _tracks.value = emptyList()
            return
        }
        _tracks.value = withContext(Dispatchers.IO) { queryDevice() }
    }

    private fun queryDevice(): List<LocalTrack> {
        val result = ArrayList<LocalTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        try {
            app.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val unknown = app.getString(R.string.unknown_artist)
                while (c.moveToNext() && result.size < MAX_TRACKS) {
                    val id = c.getLong(0)
                    val duration = c.getLong(3)
                    if (duration in 1 until MIN_DURATION_MS) continue // skip ringtones and clips
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val artist = c.getString(2)?.takeUnless { it == MediaStore.UNKNOWN_STRING } ?: unknown
                    result += LocalTrack(
                        id = "device:$id",
                        title = c.getString(1) ?: app.getString(R.string.unknown_title),
                        artist = artist,
                        durationMs = duration,
                        uri = uri,
                        artUri = uri, // the track's own content uri; ArtLoader asks MediaStore for its thumbnail
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore query failed", t)
        }
        return result
    }

    private companion object {
        const val TAG = "MusicLibrary"
        const val MAX_TRACKS = 2_000
        const val MIN_DURATION_MS = 20_000L
    }
}
