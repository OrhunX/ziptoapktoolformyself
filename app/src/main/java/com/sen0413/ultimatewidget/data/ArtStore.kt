package com.sen0413.ultimatewidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Song covers kept on the phone, so playlists show them even when the song is not playing.
 *
 * A cover is stored once per song (normalized title + artist, see [TrackMatcher.key]), so the
 * same song in several playlists and Favorites shares one small JPEG in files/art. Covers come
 * from the app that played the song, from the audio file itself, or from Spotify's Web API.
 */
object ArtStore {
    private const val TAG = "ArtStore"
    private const val PX = 320
    private const val QUALITY = 85

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saving: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val _version = MutableStateFlow(0)

    /** Goes up whenever a new cover is saved; screens refresh their covers on it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun has(context: Context, title: String, artist: String): Boolean =
        title.isNotBlank() && file(context, TrackMatcher.key(title, artist)).exists()

    /**
     * Saves [bitmap] as the cover of the song (in the background). An existing cover is kept
     * unless [replace] is true.
     */
    fun save(context: Context, title: String, artist: String, bitmap: Bitmap, replace: Boolean = false) {
        if (title.isBlank() || bitmap.isRecycled) return
        val key = TrackMatcher.key(title, artist)
        val app = context.applicationContext
        if (!saving.add(key)) return
        io.launch {
            try {
                val target = file(app, key)
                if (target.exists() && !replace) return@launch
                val scaled = scale(bitmap)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                if (!tmp.renameTo(target)) tmp.delete()
                memory.put(key, scaled)
                missing.remove(key)
                _version.update { it + 1 }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not save a cover", t)
            } finally {
                saving.remove(key)
            }
        }
    }

    /** The stored cover of the song, or null. */
    suspend fun load(context: Context, title: String, artist: String): Bitmap? {
        if (title.isBlank()) return null
        val key = TrackMatcher.key(title, artist)
        memory.get(key)?.let { return it }
        if (key in missing) return null
        return withContext(Dispatchers.IO) {
            val f = file(context, key)
            val bitmap = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
            if (bitmap != null) memory.put(key, bitmap) else missing.add(key)
            bitmap
        }
    }

    private fun scale(source: Bitmap): Bitmap {
        val bitmap = if (android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) return bitmap
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val square = if (bitmap.width == bitmap.height) bitmap else Bitmap.createBitmap(bitmap, left, top, side, side)
        return if (side <= PX) square else Bitmap.createScaledBitmap(square, PX, PX, true)
    }

    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, "art")
        if (!dir.exists()) dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(dir, "$name.jpg")
    }
}
