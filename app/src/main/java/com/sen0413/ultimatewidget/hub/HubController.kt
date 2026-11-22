package com.sen0413.ultimatewidget.hub

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.ArtLoader
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.LocalTrack
import com.sen0413.ultimatewidget.data.MusicLibrary
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.PlaylistEntry
import com.sen0413.ultimatewidget.data.PlaylistStore
import com.sen0413.ultimatewidget.playback.DemoLibrary
import com.sen0413.ultimatewidget.playback.MediaItems
import com.sen0413.ultimatewidget.ui.LockPlayerActivity
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import com.sen0413.ultimatewidget.visualizer.VisualizerService
import com.sen0413.ultimatewidget.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.media.MediaMetadata as PlatformMetadata
import android.media.session.MediaController as PlatformController

/**
 * The hub: the one place that knows every source, owns the single [HubState], executes
 * commands on whichever app is active, plays hub playlists across apps, and decides when the
 * visualizer runs. Every surface (widget, app screens, editor) only renders [state].
 *
 * All mutable state is confined to the main thread; disk and decoding work is delegated to
 * the data layer, which switches to Dispatchers.IO itself.
 */
@MainThread
object HubController {
    private const val TAG = "HubController"
    private const val QUEUE_WINDOW = 25
    private const val SERVICE_LINGER_MS = 120_000L
    private const val FIRST_CHECK_MS = 2_500L
    private const val RECHECK_MS = 2_500L
    private const val MAX_NUDGES = 3

    val scope: CoroutineScope = MainScope()

    lateinit var library: MusicLibrary
        private set
    lateinit var playlists: PlaylistStore
        private set

    private lateinit var app: Context
    private lateinit var localConnection: LocalPlayerConnection
    private lateinit var sessions: ExternalSessionWatcher
    private val handler = Handler(Looper.getMainLooper())

    private var local = SourceSnapshot(SourceKind.LOCAL, "Local files", null, null, isPlaying = false)
    private var external: SourceSnapshot? = null
    private var externalController: PlatformController? = null
    private var active = SourceKind.LOCAL
    private var visualizerStatus = VisualizerStatus.IDLE
    private var notificationAccess = false
    private var screenOn = true
    private var visibleScreens = 0
    private var sessionGeneration = 0

    /** A hub playlist being played across apps, or null. */
    private var session: ActiveSession? = null

    private var localQueueKey: Any? = null
    private var pendingArtKey: String? = null
    private var lastRenderKey: Any? = null
    private var lastQueueKey: Any? = null
    private var lastVisualizerKey: Any? = null

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    /** One-off messages for the UI (shown as toasts). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private class ActiveSession(val playlist: Playlist, var index: Int) {
        var watch: ExternalTrackWatch? = null
        var localStarted = false
        /** Bumped for every entry, so checks scheduled for an earlier entry do nothing. */
        var generation = 0
        var query = ""
        var attempts = 0
        var nudges = 0
        /** Android blocked opening the app; we posted "tap to continue" and wait for the tap. */
        var waitingForTap = false
        val entry: PlaylistEntry get() = playlist.entries[index]
        val queue: List<QueueEntry> = playlist.entries.mapIndexed { i, e ->
            QueueEntry(e.title, e.artist, e.sourceLabel, index = i, queueId = e.id, sourcePackage = e.sourcePackage)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        library = MusicLibrary(app)
        playlists = PlaylistStore(app)
        localConnection = LocalPlayerConnection(app)
        sessions = ExternalSessionWatcher(app, ::onExternalControllerChanged)
        local = initialLocalSnapshot()
        screenOn = app.getSystemService(PowerManager::class.java).isInteractive

        AudioSessionRegistry.register(app)
        registerSystemReceivers()

        VisualizerEngine.loadSettings(app)
        VisualizerEngine.onFrame = { bars -> WidgetRenderer.pushBars(app, bars) }
        VisualizerEngine.onStatus = { status ->
            visualizerStatus = status
            publish()
        }

        notificationAccess = isListenerEnabled()
        if (notificationAccess) sessions.start()

        scope.launch {
            playlists.load()
            library.refresh()
        }
        // Favorites changed -> the heart on every surface follows.
        scope.launch { playlists.playlists.collect { publish() } }
        publish()
    }

    // ---------------------------------------------------------------- transport commands

    suspend fun playPause() {
        val s = session
        if (s != null && !s.entry.isLocal && external?.packageName != s.entry.sourcePackage) {
            playEntry(s.index) // the app was not running yet: ask it again
            return
        }
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { c ->
                if (c.isPlaying) {
                    c.pause()
                } else {
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0L)
                    c.play()
                }
            }
            SourceKind.EXTERNAL -> externalController?.let { c ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    c.transportControls.pause()
                } else {
                    c.transportControls.play()
                }
            }
        }
    }

    /** Explicit play or pause (lock-screen controls send these instead of a toggle). */
    suspend fun setPlaying(play: Boolean) {
        if (state.value.current.isPlaying != play) playPause()
    }

    suspend fun next() {
        val s = session
        if (s != null) {
            advance()
            return
        }
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { it.seekToNextMediaItem() }
            SourceKind.EXTERNAL -> externalController?.transportControls?.skipToNext()
        }
    }

    suspend fun previous() {
        val s = session
        if (s != null) {
            playEntry(if (s.index > 0) s.index - 1 else 0)
            return
        }
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { it.seekToPrevious() }
            SourceKind.EXTERNAL -> externalController?.transportControls?.skipToPrevious()
        }
    }

    /** [index] is a [QueueEntry.index] from the queue currently shown. */
    suspend fun skipTo(index: Int) {
        if (index < 0) return
        if (session != null) {
            playEntry(index)
            return
        }
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { c ->
                if (index < c.mediaItemCount) {
                    c.seekTo(index, 0L)
                    c.play()
                }
            }
            SourceKind.EXTERNAL -> {
                val entry = external?.queue?.firstOrNull { it.index == index } ?: return
                externalController?.transportControls?.skipToQueueItem(entry.queueId)
            }
        }
    }

    suspend fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { it.seekTo(target) }
            SourceKind.EXTERNAL -> externalController?.transportControls?.seekTo(target)
        }
    }

    /** The source chip: switch between our player and the other app's session. */
    fun switchSource() {
        if (external == null) return
        endSession()
        active = if (active == SourceKind.LOCAL) SourceKind.EXTERNAL else SourceKind.LOCAL
        publish()
    }

    // ---------------------------------------------------------------- library and playlists

    /** Plays [tracks] in our own player, starting at [index]. */
    suspend fun playLibrary(tracks: List<LocalTrack>, index: Int) {
        if (tracks.isEmpty()) return
        endSession()
        pauseOthers(keepPackage = null, keepLocal = true)
        active = SourceKind.LOCAL
        publish()
        localConnection.withController { c ->
            c.setMediaItems(tracks.map(MediaItems::from), index.coerceIn(0, tracks.lastIndex), 0L)
            c.repeatMode = Player.REPEAT_MODE_ALL
            c.prepare()
            c.play()
        }
    }

    /** Plays a hub playlist: each entry is played by the app it belongs to. */
    suspend fun playPlaylist(playlist: Playlist, index: Int) {
        if (playlist.entries.isEmpty()) return
        endSession()
        session = ActiveSession(playlist, index.coerceIn(0, playlist.entries.lastIndex))
        // Apps that are closed can only be opened by us while we are visible, or with the
        // "display over other apps" permission; say so once instead of stalling later.
        val closed = playlist.entries.mapNotNull { it.sourcePackage }.distinct().filter { sessions.controllerFor(it) == null }
        if (closed.isNotEmpty() && !Settings.canDrawOverlays(app)) {
            _messages.tryEmit(app.getString(R.string.toast_allow_overlay_tip))
        }
        playEntry(session!!.index)
    }

    /** The song playing right now, described so it can be stored in a playlist. */
    fun currentDraft(): EntryDraft? {
        val source = _state.value.current
        val track = source.track ?: return null
        if (track.title.isBlank()) return null
        val isLocal = source.kind == SourceKind.LOCAL
        return EntryDraft(
            sourcePackage = source.packageName,
            sourceLabel = if (isLocal) app.getString(R.string.source_local_file) else source.appLabel,
            title = track.title,
            artist = track.artist,
            mediaUri = if (isLocal) track.mediaUri else null,
            durationMs = track.durationMs,
        )
    }

    fun draftOf(track: LocalTrack) = EntryDraft(
        sourcePackage = null,
        sourceLabel = app.getString(R.string.source_local_file),
        title = track.title,
        artist = track.artist,
        mediaUri = track.uri,
        durationMs = track.durationMs,
    )

    /** The heart: saves the current song (from any app) to Favorites, or removes it. */
    suspend fun toggleFavorite() {
        val draft = currentDraft()
        if (draft == null) {
            _messages.tryEmit(app.getString(R.string.toast_nothing_playing))
            return
        }
        playlists.toggleFavorite(draft)
    }

    // ---------------------------------------------------------------- hub playlist playback

    private suspend fun playEntry(index: Int) {
        val s = session ?: return
        if (index !in s.playlist.entries.indices) {
            endSession()
            publish()
            return
        }
        s.index = index
        s.generation = ++sessionGeneration
        s.localStarted = false
        s.watch = null
        s.attempts = 0
        s.nudges = 0
        s.waitingForTap = false
        HubNotifications.cancelContinue(app)
        val entry = s.entry

        if (entry.isLocal) {
            val uri = entry.mediaUri
            if (uri == null) {
                advance()
                return
            }
            sessions.preferredPackage = null
            pauseOthers(keepPackage = null, keepLocal = true)
            active = SourceKind.LOCAL
            publish()
            val track = library.tracks.value.firstOrNull { it.uri == uri }
                ?: LocalTrack("hub:${entry.id}", entry.title, entry.artist, entry.durationMs, uri)
            localConnection.withController { c ->
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.setMediaItems(listOf(MediaItems.from(track)), 0, 0L)
                c.prepare()
                c.play()
            }
        } else {
            val pkg = entry.sourcePackage ?: return advance()
            // Stop everything else first: two apps playing at once is what made the hub lose track.
            pauseOthers(keepPackage = pkg, keepLocal = false)
            active = SourceKind.EXTERNAL
            sessions.preferredPackage = pkg
            s.watch = ExternalTrackWatch(entry.title, SystemClock.elapsedRealtime())
            s.query = listOf(entry.title, entry.artist).filter { it.isNotBlank() }.joinToString(" ")
            requestExternal(s)
            scheduleCheck(s.generation, FIRST_CHECK_MS)
            publish()
        }
    }

    /**
     * Asks the entry's app to play it. First through its live session (no app switch),
     * then through the app's own "play from search" activity, which Spotify and YouTube Music
     * handle more reliably. If Android does not let us open the app right now, a notification
     * offers the user a one-tap continue.
     */
    private fun requestExternal(s: ActiveSession) {
        val entry = s.entry
        val pkg = entry.sourcePackage ?: return
        s.attempts++
        val controller = sessions.controllerFor(pkg)
        if (controller != null && s.attempts == 1) {
            controller.transportControls.playFromSearch(s.query, searchExtras(entry))
            return
        }
        if (canStartActivities() && launchPlayFromSearch(pkg, entry, s.query)) return
        if (controller != null) {
            controller.transportControls.playFromSearch(s.query, searchExtras(entry))
            return
        }
        if (HubNotifications.showContinue(app, entry.sourceLabel, entry.title, playFromSearchIntent(pkg, entry, s.query))) {
            s.waitingForTap = true
        } else {
            _messages.tryEmit(app.getString(R.string.toast_app_not_running, entry.sourceLabel))
        }
    }

    private fun scheduleCheck(generation: Int, delayMs: Long) {
        handler.postDelayed({ scope.launch { checkExternal(generation) } }, delayMs)
    }

    /** Makes sure the requested song really plays: nudges, retries, and skips on a miss. */
    private suspend fun checkExternal(generation: Int) {
        val s = session ?: return
        if (s.generation != generation || s.entry.isLocal) return
        val watch = s.watch ?: return
        val pkg = s.entry.sourcePackage ?: return
        val controller = sessions.controllerFor(pkg)
        val now = SystemClock.elapsedRealtime()

        if (controller != null && s.waitingForTap) {
            // The user tapped "continue": the app is up now, give it a fresh grace period.
            s.waitingForTap = false
            s.watch = ExternalTrackWatch(s.entry.title, now)
            HubNotifications.cancelContinue(app)
            scheduleCheck(generation, RECHECK_MS)
            return
        }
        if (s.waitingForTap) {
            scheduleCheck(generation, RECHECK_MS * 2)
            return
        }

        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = controller?.metadata?.getString(PlatformMetadata.METADATA_KEY_TITLE)
        if (watch.isArmed) {
            // Right song; some apps load it paused. Start it once, then leave it to the user.
            if (!playing && s.nudges == 0) {
                s.nudges++
                controller?.transportControls?.play()
            }
            return
        }
        if (controller != null && watch.matches(title)) {
            if (!playing && s.nudges < MAX_NUDGES) {
                s.nudges++
                controller.transportControls.play()
            }
            refreshExternal()
            if (s.watch?.isArmed != true) scheduleCheck(generation, RECHECK_MS)
            return
        }
        if (s.watch?.isOverdue(now) == true) {
            // Never play a different song in its place: say so and move on.
            _messages.tryEmit(app.getString(R.string.toast_song_not_found, s.entry.title, s.entry.sourceLabel))
            controller?.transportControls?.pause()
            advance()
            return
        }
        if (s.attempts < 2) {
            requestExternal(s)
        } else if (controller != null && !playing && s.nudges < MAX_NUDGES) {
            s.nudges++
            controller.transportControls.play()
        }
        scheduleCheck(generation, RECHECK_MS)
    }

    private suspend fun advance() {
        val s = session ?: return
        if (s.index + 1 < s.playlist.entries.size) {
            playEntry(s.index + 1)
        } else {
            endSession()
            publish()
        }
    }

    private fun endSession() {
        if (session == null) return
        session = null
        sessionGeneration++
        sessions.preferredPackage = null
        HubNotifications.cancelContinue(app)
    }

    private fun searchExtras(entry: PlaylistEntry) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, entry.title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, entry.artist)
    }

    private fun playFromSearchIntent(pkg: String, entry: PlaylistEntry, query: String): Intent =
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchManager.QUERY, query)
            .putExtras(searchExtras(entry))

    /** Opens the app on its own search-and-play screen; Spotify and YouTube Music start the top result. */
    private fun launchPlayFromSearch(pkg: String, entry: PlaylistEntry, query: String): Boolean = try {
        app.startActivity(playFromSearchIntent(pkg, entry, query))
        true
    } catch (t: Throwable) {
        Log.i(TAG, "Could not launch $pkg: ${t.javaClass.simpleName}")
        false
    }

    /** Android lets us open another app only while we are on screen, or with the overlay permission. */
    private fun canStartActivities(): Boolean = visibleScreens > 0 || Settings.canDrawOverlays(app)

    /** Called by our activities from onStart/onStop. */
    fun onScreenVisible(visible: Boolean) {
        visibleScreens = (visibleScreens + if (visible) 1 else -1).coerceAtLeast(0)
    }

    private suspend fun pauseLocal() {
        if (local.isPlaying) localConnection.withController { it.pause() }
    }

    /** Pauses every source except the one about to play, so only one app ever sounds. */
    private suspend fun pauseOthers(keepPackage: String?, keepLocal: Boolean) {
        if (!keepLocal) pauseLocal()
        if (!notificationAccess) return
        for (c in sessions.otherSessions()) {
            if (c.packageName == keepPackage) continue
            when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING ->
                    c.transportControls.pause()
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- inputs

    /** Called by PlaybackService whenever our player reports events. */
    fun onLocalPlayerEvent(player: Player) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val mediaId = item?.mediaId
        val title = metadata?.title?.toString() ?: local.track?.title.orEmpty()
        val artist = metadata?.artist?.toString().orEmpty()
        val uri = (item?.requestMetadata?.mediaUri ?: item?.localConfiguration?.uri)?.toString()
        val coverRes = DemoLibrary.coverFor(mediaId)
        val art = if (item != null && coverRes == 0) localArt(uri, "$title|$artist") else null

        val count = player.mediaItemCount
        val index = player.currentMediaItemIndex
        val queueKey = Triple(count, index, mediaId)
        var queue = local.queue
        if (queueKey != localQueueKey) {
            localQueueKey = queueKey
            val label = app.getString(R.string.source_local_file)
            queue = buildList {
                for (i in index until minOf(count, index + QUEUE_WINDOW)) {
                    val m = player.getMediaItemAt(i).mediaMetadata
                    add(QueueEntry(m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), label, index = i))
                }
            }
        }

        local = local.copy(
            track = TrackInfo(
                title = title,
                artist = artist,
                mediaId = mediaId,
                coverRes = coverRes,
                art = art,
                durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                mediaUri = uri,
            ),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            positionAtElapsedMs = SystemClock.elapsedRealtime(),
            queue = queue,
            currentQueueIndex = index,
        )

        val s = session
        if (s != null && s.entry.isLocal) {
            if (player.isPlaying) s.localStarted = true
            if (s.localStarted && player.playbackState == Player.STATE_ENDED) {
                s.localStarted = false
                scope.launch { advance() }
            }
        }

        autoSelectSource()
        publish()
    }

    fun onLocalPlayerReleased() {
        local = local.copy(isPlaying = false)
        autoSelectSource()
        publish()
    }

    fun onNotificationAccessChanged(granted: Boolean) {
        notificationAccess = granted
        if (granted) sessions.start() else sessions.stop()
        publish()
    }

    fun onAnnouncedSessionsChanged() {
        lastVisualizerKey = null
        publish()
    }

    /** The visualizer service became a foreground service: (re)attach under its protection. */
    fun onVisualizerServiceReady() {
        lastVisualizerKey = null
        publish()
    }

    /** A widget button was tapped: a user-visible moment in which the service may start. */
    fun onWidgetInteraction() {
        if (::app.isInitialized) VisualizerService.start(app)
    }

    /** Re-checks permissions and access, then re-evaluates the visualizer. */
    fun refresh() {
        notificationAccess = isListenerEnabled()
        if (notificationAccess) sessions.start()
        lastVisualizerKey = null
        publish()
        scope.launch { library.refresh() }
    }

    // ---------------------------------------------------------------- internals

    /** Art for a local track: from cache now, or loaded in the background and published later. */
    private fun localArt(uri: String?, seed: String): android.graphics.Bitmap? {
        val key = uri ?: "gen:$seed"
        ArtLoader.cached(key)?.let { return it }
        if (pendingArtKey != key) {
            pendingArtKey = key
            scope.launch {
                val bitmap = ArtLoader.load(app, uri, seed)
                if (pendingArtKey == key) pendingArtKey = null
                val track = local.track
                if (track != null && track.coverRes == 0 && (track.mediaUri ?: "gen:${track.title}|${track.artist}") == key) {
                    local = local.copy(track = track.copy(art = bitmap))
                    publish()
                }
            }
        }
        return null
    }

    private val externalCallback = object : PlatformController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshExternal()
        override fun onMetadataChanged(metadata: PlatformMetadata?) = refreshExternal()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = refreshExternal()
        override fun onSessionDestroyed() = onExternalControllerChanged(null)
    }

    private fun onExternalControllerChanged(controller: PlatformController?) {
        val current = externalController
        if (current != null && controller != null && current.sessionToken == controller.sessionToken) {
            refreshExternal()
            return
        }
        current?.unregisterCallback(externalCallback)
        externalController = controller
        controller?.registerCallback(externalCallback, handler)
        refreshExternal()
    }

    private fun refreshExternal() {
        val snapshot = externalController?.let { ExternalSnapshots.of(app, it) }
        external = snapshot

        // Hub playlist: has the external app finished the song we asked for?
        val s = session
        val watch = s?.watch
        if (s != null && watch != null && snapshot != null && snapshot.packageName == s.entry.sourcePackage) {
            val now = SystemClock.elapsedRealtime()
            val wasArmed = watch.isArmed
            val result = watch.onMetadata(
                snapshot.track?.title, snapshot.isPlaying, now,
                positionMs = snapshot.positionAt(now), durationMs = snapshot.track?.durationMs ?: 0L,
            )
            if (!wasArmed && watch.isArmed) HubNotifications.cancelContinue(app)
            if (result == ExternalTrackWatch.Result.MOVED_ON) {
                s.watch = null
                externalController?.transportControls?.pause()
                scope.launch { advance() }
            }
        }

        autoSelectSource()
        publish()
    }

    /** Follows whichever source is actually playing; a hub playlist decides for itself. */
    private fun autoSelectSource() {
        if (session != null) return
        active = SourceSelector.select(
            current = active,
            hasExternal = external != null,
            localPlaying = local.isPlaying,
            externalPlaying = external?.isPlaying == true,
        )
    }

    private fun currentKind(): SourceKind =
        if (active == SourceKind.EXTERNAL && external != null) SourceKind.EXTERNAL else SourceKind.LOCAL

    private fun buildState(): HubState {
        val s = session
        val base = HubState(
            local = local,
            external = external,
            active = active,
            visualizer = visualizerStatus,
            notificationAccess = notificationAccess,
            session = s?.let { PlaylistSession(it.playlist.id, it.playlist.name, it.index, it.queue) },
        )
        val track = base.current.track
        val favorite = track != null && ::playlists.isInitialized && playlists.isFavorite(track.title, track.artist)
        return if (favorite) base.copy(isFavorite = true) else base
    }

    private fun publish() {
        val s = buildState()
        _state.value = s
        if (!::app.isInitialized) return

        val cur = s.current
        val renderKey = listOf(
            s.active, cur.packageName, cur.track?.title, cur.track?.artist, cur.track?.mediaId,
            System.identityHashCode(cur.track?.art), cur.isPlaying,
            cur.positionAtElapsedMs - cur.positionMs, s.visualizer, s.external != null, s.isFavorite,
        )
        if (renderKey != lastRenderKey) {
            lastRenderKey = renderKey
            WidgetRenderer.renderAll(app, s)
            LockScreenCard.update(app, s)
        }

        val queueKey = Triple(s.session?.playlistId, s.queue.map { it.queueId to it.title }, s.queueIndex)
        if (queueKey != lastQueueKey) {
            lastQueueKey = queueKey
            WidgetRenderer.notifyQueueChanged(app)
        }

        val visualizerKey = listOf(
            cur.kind, cur.packageName, cur.isPlaying, screenOn, powerSave(), VisualizerEngine.hasPermission(app),
        )
        if (visualizerKey != lastVisualizerKey) {
            lastVisualizerKey = visualizerKey
            refreshVisualizer(cur)
        }
    }

    private val stopVisualizerService = Runnable { VisualizerService.stop(app) }

    /** The visualizer runs only while music plays and the screen is on. */
    private fun refreshVisualizer(cur: SourceSnapshot) {
        if (!cur.isPlaying) {
            VisualizerEngine.pause()
            handler.removeCallbacks(stopVisualizerService)
            handler.postDelayed(stopVisualizerService, SERVICE_LINGER_MS)
            return
        }
        handler.removeCallbacks(stopVisualizerService)
        if (!screenOn) {
            VisualizerEngine.pause()
            return
        }
        VisualizerService.start(app)
        val sessionId = when (cur.kind) {
            SourceKind.LOCAL -> HubAudio.localSessionId(app)
            // Prefer the session the app announced; fall back to the output mix (session 0).
            SourceKind.EXTERNAL -> AudioSessionRegistry.sessionFor(cur.packageName) ?: 0
        }
        VisualizerEngine.targetFps = if (powerSave()) 10 else 18
        VisualizerEngine.attach(app, sessionId)
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        maybeShowLockPlayer(screenTurnedOff = false)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        maybeShowLockPlayer(screenTurnedOff = true)
                    }
                }
                publish()
            }
        }
        // System broadcasts are still delivered to non-exported receivers.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * The full-screen lock player: started as the screen turns off (so it is already there
     * when the phone wakes up) or on wake-up if music started meanwhile.
     */
    private fun maybeShowLockPlayer(screenTurnedOff: Boolean) {
        if (!LockPlayerActivity.isEnabled(app) || !Settings.canDrawOverlays(app)) return
        if (!_state.value.current.isPlaying) return
        if (!screenTurnedOff) {
            val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (!locked || LockPlayerActivity.isShowing) return
        }
        runCatching { app.startActivity(LockPlayerActivity.intent(app)) }
            .onFailure { Log.i(TAG, "Lock player not started: ${it.javaClass.simpleName}") }
    }

    private fun powerSave(): Boolean = app.getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    private fun initialLocalSnapshot(): SourceSnapshot {
        val demo = DemoLibrary.localTracks(app)
        val first = demo.first()
        val label = app.getString(R.string.source_local_file)
        return SourceSnapshot(
            kind = SourceKind.LOCAL,
            appLabel = app.getString(R.string.source_local),
            packageName = null,
            track = TrackInfo(
                title = first.title, artist = first.artist, mediaId = first.id,
                coverRes = first.coverRes, durationMs = first.durationMs, mediaUri = first.uri,
            ),
            isPlaying = false,
            queue = demo.mapIndexed { index, t -> QueueEntry(t.title, t.artist, label, index) },
            currentQueueIndex = 0,
        )
    }
}
