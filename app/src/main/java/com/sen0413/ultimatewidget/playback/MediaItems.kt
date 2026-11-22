package com.sen0413.ultimatewidget.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sen0413.ultimatewidget.data.LocalTrack

/** Maps our tracks to Media3 items. */
object MediaItems {
    /**
     * The playable uri travels in RequestMetadata: a MediaController does not send an item's
     * local configuration to the session, so [resolve] restores it on the service side.
     */
    fun from(track: LocalTrack): MediaItem {
        val uri = Uri.parse(track.uri)
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
        // Only image uris are valid artwork; device tracks get their art from MediaStore instead.
        if (track.coverRes != 0 && track.artUri != null) metadata.setArtworkUri(Uri.parse(track.artUri))
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(metadata.build())
            .build()
    }

    /** Session side: make sure the item is playable, or return null if it cannot be. */
    fun resolve(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        val uri = item.requestMetadata.mediaUri ?: return null
        return item.buildUpon().setUri(uri).build()
    }
}
