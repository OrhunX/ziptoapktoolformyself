package com.sen0413.ultimatewidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Album art for local tracks. Real art comes from MediaStore; tracks without art get a
 * deterministic generative cover, so every song has its own colors for the widget theme.
 * Bitmaps are capped at [SIZE] px because widgets have a bitmap memory budget.
 */
object ArtLoader {
    private const val SIZE = 400
    private val cache = LruCache<String, Bitmap>(24)

    fun cached(key: String): Bitmap? = cache.get(key)

    /** Loads (or generates) art for [artUri]; [seed] picks the generative colors. */
    suspend fun load(context: Context, artUri: String?, seed: String): Bitmap {
        val key = artUri ?: "gen:$seed"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            artUri?.let { decode(context, it) } ?: generate(seed)
        }
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(context: Context, uri: String): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && parsed.scheme == "content") {
            context.contentResolver.loadThumbnail(parsed, Size(SIZE, SIZE), null)
        } else {
            context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    /** Two hues derived from the text, a diagonal gradient and a few soft bands. */
    fun generate(seed: String): Bitmap {
        val hash = seed.hashCode()
        val hueA = abs(hash % 360).toFloat()
        val hueB = (hueA + 40f + abs((hash / 360) % 80)) % 360f
        val top = Color.HSVToColor(floatArrayOf(hueA, 0.55f, 0.95f))
        val bottom = Color.HSVToColor(floatArrayOf(hueB, 0.75f, 0.35f))

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = SIZE / 28f
        val rings = 3 + abs(hash % 3)
        for (i in 0 until rings) {
            paint.color = Color.argb(46 - i * 8, 255, 255, 255)
            val radius = SIZE * (0.22f + i * 0.16f)
            canvas.drawCircle(SIZE * 0.3f, SIZE * 0.72f, radius, paint)
        }
        return bitmap
    }
}
