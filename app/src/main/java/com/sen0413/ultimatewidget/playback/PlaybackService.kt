package com.sen0413.ultimatewidget.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sen0413.ultimatewidget.hub.HubAudio
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.LockPlayerActivity

/**
 * Our own player: ExoPlayer behind a Media3 MediaSession. Media3 keeps the notification,
 * lock screen, headphone buttons and our widget in sync with the same session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // A known session id lets the visualizer attach before playback starts.
                audioSessionId = HubAudio.localSessionId(this@PlaybackService)
                // Starts empty: songs are loaded when the user picks something to play.
                repeatMode = Player.REPEAT_MODE_ALL
            }

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                HubController.onLocalPlayerEvent(player)
            }
        })

        // Tapping our card opens the full-screen player on the lock screen, the app otherwise.
        val openApp = PendingIntent.getActivity(
            this, 0, LockPlayerActivity.intent(this, fromCard = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        HubController.onLocalPlayerEvent(player)
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers send items without a playable uri; restore it from RequestMetadata. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull(MediaItems::resolve).toMutableList())

        /** Lets the system resume our playlist from the media controls after a reboot. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                HubController.library.tracks.value.take(RESUME_LIMIT).map(MediaItems::from), 0, 0L,
            ),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        HubController.onLocalPlayerReleased()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val RESUME_LIMIT = 200
    }
}
