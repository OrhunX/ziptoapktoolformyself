package com.sen0413.ultimatewidget.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.sen0413.ultimatewidget.data.ArtLoader
import com.sen0413.ultimatewidget.data.ArtStore
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.PlaylistEntry
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.Type
import com.sen0413.ultimatewidget.ui.kit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A playlist's cover: the user's photo, an emoji on the playlist color, or by default a
 * 2×2 collage of the first four songs' covers (stored covers, the files' own art, or
 * generated art for songs whose cover is not known yet). Reports the cover's main color so cards and headers can take it on.
 */
class PlaylistCover(context: Context) : View(context) {
    var cornerRadius: Float = dp(18f)
    var onColor: ((Int) -> Unit)? = null

    private var key: Any? = null
    private var tiles: List<Bitmap> = emptyList()
    private var photo: Bitmap? = null
    private var emoji: String = ""
    private var fill: Int = Ink.VIOLET
    private var job: Job? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()

    fun bind(playlist: Playlist) {
        val newKey = listOf(
            playlist.id, playlist.coverPath, playlist.emoji, playlist.color,
            playlist.entries.take(4).map { it.id }, ArtStore.version.value,
        )
        if (newKey == key) return
        key = newKey
        job?.cancel()
        emoji = playlist.emoji
        fill = if (playlist.color != 0) playlist.color else Ink.VIOLET
        photo = null
        tiles = emptyList()
        invalidate()

        val path = playlist.coverPath
        val entries = playlist.entries.take(4)
        job = HubController.scope.launch {
            val loadedPhoto = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it) } }
            val loadedTiles = if (loadedPhoto == null && emoji.isEmpty()) entries.map { thumbnail(artFor(context, it), TILE_PX) } else emptyList()
            photo = loadedPhoto
            tiles = loadedTiles
            invalidate()
            val source = loadedPhoto ?: loadedTiles.firstOrNull()
            val color = when {
                playlist.color != 0 -> playlist.color
                source != null -> withContext(Dispatchers.Default) {
                    val palette = Palette.from(source).maximumColorCount(12).generate()
                    palette.getVibrantColor(palette.getDarkVibrantColor(Ink.VIOLET))
                }
                else -> Ink.VIOLET
            }
            onColor?.invoke(color)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        val p = photo
        when {
            p != null -> drawCropped(canvas, p, rect)
            emoji.isNotEmpty() || tiles.isEmpty() -> {
                paint.color = fill
                canvas.drawRect(rect, paint)
                paint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 40)
                canvas.drawCircle(w * 0.85f, h * 0.15f, w * 0.45f, paint)
                val glyph = emoji.ifEmpty { "♪" }
                textPaint.textSize = h * (if (emoji.isEmpty()) 0.42f else 0.5f)
                textPaint.color = Ink.PAPER
                textPaint.typeface = Type.display(context)
                val y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(glyph, w / 2f, y, textPaint)
            }
            tiles.size < 4 -> drawCropped(canvas, tiles.first(), rect)
            else -> {
                val half = w / 2f
                val halfH = h / 2f
                tiles.forEachIndexed { i, bitmap ->
                    val x = (i % 2) * half
                    val y = (i / 2) * halfH
                    drawCropped(canvas, bitmap, RectF(x, y, x + half, y + halfH))
                }
            }
        }
        canvas.restore()
    }

    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        src.set(left, top, left + side, top + side)
        dst.set(target)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    companion object {
        private const val TILE_PX = 160

        /** A small copy for lists, so long playlists never hold dozens of full-size covers. */
        fun thumbnail(bitmap: Bitmap, px: Int): Bitmap =
            if (bitmap.width <= px && bitmap.height <= px) bitmap else Bitmap.createScaledBitmap(bitmap, px, px, true)

        /**
         * Cover art for one playlist song: the stored cover (saved when it played, or from
         * Spotify), the audio file's own art, or a generated cover until a real one is known.
         */
        suspend fun artFor(context: Context, entry: PlaylistEntry): Bitmap {
            ArtStore.load(context, entry.title, entry.artist)?.let { return it }
            val uri = entry.mediaUri
            if (entry.isLocal && uri != null) {
                val real = ArtLoader.loadReal(context, uri)
                if (real != null) {
                    ArtStore.save(context, entry.title, entry.artist, real)
                    return real
                }
            }
            return ArtLoader.load(context, null, "${entry.title}|${entry.artist}")
        }
    }
}
