package com.sen0413.ultimatewidget.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.ui.LockPlayerActivity
import com.sen0413.ultimatewidget.widget.HubWidgetProvider
import kotlinx.coroutines.launch

/**
 * Our card on the lock screen and in the shade while another app (Spotify, YouTube Music...)
 * plays. Our own player already has a Media3 card; for other apps the hub publishes a mirror
 * MediaSession with the same song, and its buttons drive whichever app is active, including
 * hub playlists that hop between apps. Tapping the card opens the full-screen lock player.
 */
object LockScreenCard {
    private const val CHANNEL_ID = "now_playing"
    private const val NOTIFICATION_ID = 4132
    private const val ACTION_FAVORITE = "favorite"

    private var session: MediaSession? = null
    private var shown = false
    private var lastKey: Any? = null

    fun update(context: Context, state: HubState) {
        val source = state.current
        val track = source.track
        val show = source.kind == SourceKind.EXTERNAL && track != null && state.external != null
        if (!show) {
            hide(context)
            return
        }
        if (!HubNotifications.canPost(context)) return
        val s = session ?: MediaSession(context, "UltimateHub").also {
            it.setCallback(callback)
            session = it
        }

        val art = track!!.art ?: fallbackArt(context)
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, source.appLabel)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build(),
        )
        val position = source.positionAt(SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val favoriteIcon = if (state.isFavorite) R.drawable.ic_heart else R.drawable.ic_heart_outline
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO,
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_FAVORITE, context.getString(R.string.cd_favorite_add), favoriteIcon,
                    ).build(),
                )
                .setState(
                    if (source.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position, if (source.isPlaying) 1f else 0f, SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        s.isActive = true

        // Re-post only when what the card shows changed.
        val key = listOf(track.title, track.artist, System.identityHashCode(art), source.isPlaying, state.isFavorite, source.appLabel)
        if (key == lastKey && shown) return
        lastKey = key
        post(context, s, track.title, track.artist, source.appLabel, art, source.isPlaying, state.isFavorite)
        shown = true
    }

    fun hide(context: Context) {
        if (!shown && session?.isActive != true) return
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        session?.isActive = false
        shown = false
        lastKey = null
    }

    private fun post(
        context: Context,
        s: MediaSession,
        title: String,
        artist: String,
        appLabel: String,
        art: Bitmap?,
        playing: Boolean,
        favorite: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.card_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 7_200, LockPlayerActivity.intent(context, fromCard = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(appLabel)
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(context, R.drawable.ic_prev, R.string.cd_previous, HubWidgetProvider.ACTION_PREVIOUS))
            .addAction(
                action(
                    context,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) R.string.cd_pause_short else R.string.cd_play_short,
                    HubWidgetProvider.ACTION_PLAY_PAUSE,
                ),
            )
            .addAction(action(context, R.drawable.ic_next, R.string.cd_next, HubWidgetProvider.ACTION_NEXT))
            .addAction(
                action(
                    context,
                    if (favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                    if (favorite) R.string.cd_favorite_remove else R.string.cd_favorite_add,
                    HubWidgetProvider.ACTION_FAVORITE,
                ),
            )
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun action(context: Context, icon: Int, label: Int, hubAction: String): Notification.Action {
        val intent = Intent(context, HubWidgetProvider::class.java).setAction(hubAction)
        val pending = PendingIntent.getBroadcast(
            context, 7_300 + hubAction.hashCode() % 1000, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, icon), context.getString(label), pending,
        ).build()
    }

    private var placeholder: Bitmap? = null

    private fun fallbackArt(context: Context): Bitmap? =
        placeholder ?: BitmapFactory.decodeResource(context.resources, R.drawable.cover_night_train)
            ?.also { placeholder = it }

    /** The lock-screen controls of Android 13+ talk to the session directly. */
    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = run { HubController.scope.launch { HubController.setPlaying(true) }; Unit }
        override fun onPause() = run { HubController.scope.launch { HubController.setPlaying(false) }; Unit }
        override fun onSkipToNext() = run { HubController.scope.launch { HubController.next() }; Unit }
        override fun onSkipToPrevious() = run { HubController.scope.launch { HubController.previous() }; Unit }
        override fun onSeekTo(pos: Long) = run { HubController.scope.launch { HubController.seekTo(pos) }; Unit }
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ACTION_FAVORITE) HubController.scope.launch { HubController.toggleFavorite() }
        }
    }
}
