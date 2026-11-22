package com.sen0413.ultimatewidget.hub

import android.content.Context
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.graphics.scale
import com.sen0413.ultimatewidget.R
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.MediaController as PlatformController

/** Converts another app's MediaSession state into a [SourceSnapshot]. */
object ExternalSnapshots {
    private const val MAX_ART_PX = 400
    private const val MAX_QUEUE = 25

    private var lastOriginalArt: Bitmap? = null
    private var lastScaledArt: Bitmap? = null

    fun of(context: Context, controller: PlatformController): SourceSnapshot {
        val metadata = controller.metadata
        val label = appLabel(context, controller.packageName)
        val description = metadata?.description
        val title = metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE)
            ?: description?.title?.toString()
            ?: context.getString(R.string.unknown_title)
        val artist = metadata?.getString(PlatformMetadata.METADATA_KEY_ARTIST)
            ?: description?.subtitle?.toString()
            ?: ""
        val art = metadata?.let {
            it.getBitmap(PlatformMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(PlatformMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(PlatformMetadata.METADATA_KEY_DISPLAY_ICON)
        }?.let(::scaledArt)

        val playbackState = controller.playbackState
        val queue = controller.queue.orEmpty().take(MAX_QUEUE).mapIndexed { index, item ->
            QueueEntry(
                title = item.description.title?.toString().orEmpty(),
                subtitle = item.description.subtitle?.toString().orEmpty(),
                sourceLabel = label,
                index = index,
                queueId = item.queueId,
                sourcePackage = controller.packageName,
            )
        }
        val activeId = playbackState?.activeQueueItemId
        val currentIndex = if (activeId == null) -1 else queue.indexOfFirst { it.queueId == activeId }

        return SourceSnapshot(
            kind = SourceKind.EXTERNAL,
            appLabel = label,
            packageName = controller.packageName,
            track = TrackInfo(
                title = title,
                artist = artist,
                mediaId = metadata?.getString(PlatformMetadata.METADATA_KEY_MEDIA_ID),
                art = art,
                durationMs = metadata?.getLong(PlatformMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            ),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING,
            positionMs = playbackState?.position ?: 0L,
            positionAtElapsedMs = playbackState?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            queue = queue,
            currentQueueIndex = currentIndex,
        )
    }

    fun appLabel(context: Context, packageName: String): String = when (packageName) {
        KnownApps.SPOTIFY -> "Spotify"
        KnownApps.YOUTUBE_MUSIC -> "YouTube Music"
        KnownApps.YOUTUBE -> "YouTube"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    /** Widgets have a bitmap memory budget, so album art is scaled down once per bitmap. */
    @Synchronized
    private fun scaledArt(original: Bitmap): Bitmap {
        if (original === lastOriginalArt) lastScaledArt?.let { return it }
        val largest = maxOf(original.width, original.height)
        val scaled = if (largest <= MAX_ART_PX) {
            original
        } else {
            val factor = MAX_ART_PX.toFloat() / largest
            original.scale((original.width * factor).toInt(), (original.height * factor).toInt())
        }
        lastOriginalArt = original
        lastScaledArt = scaled
        return scaled
    }
}
