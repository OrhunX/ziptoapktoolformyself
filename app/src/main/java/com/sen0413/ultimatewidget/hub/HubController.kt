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
import com.sen0413.ultimatewidget.data.ArtStore
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.LocalTrack
import com.sen0413.ultimatewidget.data.MusicLibrary
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.PlaylistEntry
import com.sen0413.ultimatewidget.data.PlaylistStore
import com.sen0413.ultimatewidget.data.RepeatMode
import com.sen0413.ultimatewidget.data.ResumeStore
import com.sen0413.ultimatewidget.data.TrackMatcher
import com.sen0413.ultimatewidget.playback.MediaItems
import com.sen0413.ultimatewidget.ui.LockPlayerActivity
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import com.sen0413.ultimatewidget.visualizer.VisualizerService
import com.sen0413.ultimatewidget.widget.RenderThread
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
    private const val TAKEOVER_GRACE_MS = 4_000L
    private const val RESUME_SLACK_MS = 3_000L
    private const val ART_STABLE_MS = 2_500L
    private const val YT_LOOKUP_MS = 5_000L
    private const val MAX_YT_QUEUE_LOOKUPS = 15
    private const val MAX_RESTARTS = 3
    private const val RESTART_VERIFY_MS = 1_500L
    private const val MAX_SPOTIFY_LOOKUPS = 150
    private const val EMBED_NUDGE_MS = 6_000L
    /** A hub playlist paused this long stops acting on its own until the user presses something. */
    private const val DORMANT_AFTER_MS = 10 * 60_000L
    private const val EMBED_GIVE_UP_MS = 15_000L

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
    /** A YouTube song playing inside our app (YouTubeEmbed); shown in place of [local]. */
    private var embedded: SourceSnapshot? = null
    private var embedArt: android.graphics.Bitmap? = null
    /** The last song from before the app was closed, shown until something plays (ResumeStore). */
    private var restored: SourceSnapshot? = null
    private var restoredLast: ResumeStore.Last? = null
    private var lastSavedKey: Any? = null
    /** Where the next local song of a playlist starts (resuming a song that was half played). */
    private var pendingLocalStartMs = 0L
    private var external: SourceSnapshot? = null
    private var externalController: PlatformController? = null
    private var active = SourceKind.LOCAL
    private var visualizerStatus = VisualizerStatus.IDLE
    private var notificationAccess = false
    private var screenOn = true
    private var visibleScreens = 0
    private var sessionGeneration = 0
    private var shuffle = false
    private var repeat = RepeatMode.ALL
    private lateinit var browserBridge: BrowserBridge

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

    /**
     * A hub playlist being played across apps: one playlist, or several mixed together.
     * [order] holds indices into [entries] in play order (shuffled or not); [pos] points at
     * the entry playing now. QueueEntry.index in the shown queue is a position in [order].
     */
    private class ActiveSession(
        val name: String,
        val playlistIds: List<Long>,
        var entries: List<PlaylistEntry>,
        var order: MutableList<Int>,
        var pos: Int,
    ) {
        var watch: ExternalTrackWatch? = null
        var localStarted = false
        /** Bumped for every entry, so checks scheduled for an earlier entry do nothing. */
        var generation = 0
        var startedAt = 0L
        var query = ""
        var attempts = 0
        var nudges = 0
        /** Songs in a row that could not be started; stops a playlist of only missing songs. */
        var misses = 0
        /** Android does not let us open the app now; we posted "tap to continue" and wait. */
        var waitingForTap = false
        /** We opened the other app on top of ours (only while the user was in our app); come back. */
        var returnToApp = false
        /** We opened YouTube Music over the home screen (widget, background): send it back there. */
        var returnHome = false
        /** Video ids loaded into YouTube Music's own queue for this playlist (switched in the background). */
        var ytQueue: Set<String> = emptySet()
        /** The app already moved on to our next song by itself: play that entry without asking again. */
        var adoptNext = false
        /** Played through Spotify's SDK (exact song) rather than the shared media session. */
        var viaSpotifySdk = false
        /** Played inside our app with YouTube's embedded player. */
        var embedded = false
        var embedStarted = false
        var embedVideoId: String? = null
        /** A single song resumed from another app: the session ends with it. */
        var oneShot = false
        /** Paused for a long time: nothing happens automatically until the user acts. */
        var dormant = false
        /** We already made sure the requested song started from the beginning. */
        var restartChecked = false
        /** Seeks to the start sent for this song, and when the last one was sent. */
        var restartAttempts = 0
        var lastRestartAt = 0L
        var queue: List<QueueEntry> = buildQueue()
            private set

        val entry: PlaylistEntry get() = entries[order[pos]]

        fun rebuildQueue() {
            queue = buildQueue()
        }

        private fun buildQueue() = order.mapIndexed { i, idx ->
            val e = entries[idx]
            QueueEntry(e.title, e.artist, e.sourceLabel, index = i, queueId = e.id, sourcePackage = e.sourcePackage)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        library = MusicLibrary(app)
        playlists = PlaylistStore(app)
        localConnection = LocalPlayerConnection(app)
        sessions = ExternalSessionWatcher(app, ::onExternalControllerChanged)
        browserBridge = BrowserBridge(app)
        loadModes()
        local = initialLocalSnapshot()
        restoreLast()
        screenOn = app.getSystemService(PowerManager::class.java).isInteractive

        AudioSessionRegistry.register(app)
        registerSystemReceivers()

        VisualizerEngine.loadSettings(app)
        VisualizerEngine.onFrame = { bars ->
            // Coalesced: if the render thread is busy, only the newest frame is drawn.
            if (pendingBars.getAndSet(bars) == null) {
                RenderThread.post { pendingBars.getAndSet(null)?.let { WidgetRenderer.pushBars(app, it) } }
            }
        }
        VisualizerEngine.onStatus = { status ->
            visualizerStatus = status
            publish()
        }

        SpotifyLink.onPlayerState = ::onSpotifyState
        YouTubeEmbed.onUpdate = ::onEmbedUpdate
        YouTubeEmbed.onError = ::onEmbedError
        SpotifyLink.onAuthorized = { scope.launch { resolveSpotifyIds() } }

        notificationAccess = isListenerEnabled()
        if (notificationAccess) sessions.start()

        scope.launch {
            playlists.load()
            library.refresh()
            forgetGuessedYouTubeIds()
        }
        // Favorites changed -> the heart on every surface follows.
        // Songs reordered or removed while the playlist plays: the session follows the new list.
        scope.launch {
            playlists.playlists.collect {
                syncSession(it)
                publish()
            }
        }
        publish()
    }

    // ---------------------------------------------------------------- transport commands

    suspend fun playPause() {
        if (resumeLast(0)) return
        val s = session
        if (s != null && s.dormant && wake(s)) return
        if (s != null && s.embedded) {
            if (embedded?.isPlaying == true) YouTubeEmbed.pause() else YouTubeEmbed.play()
            return
        }
        if (s != null && !s.entry.isLocal && external?.packageName != s.entry.sourcePackage) {
            playEntry(s.pos) // the app was not running yet: ask it again
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
        if (resumeLast(1)) return
        session?.dormant = false
        if (session != null) {
            advance(auto = false)
            return
        }
        when (currentKind()) {
            SourceKind.LOCAL -> localConnection.withController { it.seekToNextMediaItem() }
            SourceKind.EXTERNAL -> externalController?.transportControls?.skipToNext()
        }
    }

    suspend fun previous() {
        if (resumeLast(-1)) return
        session?.dormant = false
        val s = session
        if (s != null) {
            playEntry(if (s.pos > 0) s.pos - 1 else 0)
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

    // ---------------------------------------------------------------- shuffle and repeat

    /** Shuffle on or off. In a hub playlist the current song keeps playing; the rest is reordered. */
    suspend fun toggleShuffle() {
        shuffle = !shuffle
        saveModes()
        val s = session
        if (s != null) {
            val current = s.order[s.pos]
            s.order = if (shuffle) {
                (listOf(current) + s.entries.indices.filter { it != current }.shuffled()).toMutableList()
            } else {
                s.entries.indices.toMutableList()
            }
            s.pos = s.order.indexOf(current)
            s.rebuildQueue()
        } else if (currentKind() == SourceKind.LOCAL) {
            localConnection.withController { it.shuffleModeEnabled = shuffle }
        } else {
            _messages.tryEmit(app.getString(R.string.toast_modes_hub_only))
        }
        publish()
    }

    /** Repeat: all → one song → off → all. */
    suspend fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.ALL
        }
        saveModes()
        if (session == null) {
            if (currentKind() == SourceKind.LOCAL) {
                localConnection.withController { it.repeatMode = playerRepeatMode() }
            } else {
                _messages.tryEmit(app.getString(R.string.toast_modes_hub_only))
            }
        }
        publish()
    }

    private fun playerRepeatMode(): Int = when (repeat) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    private fun loadModes() {
        val p = app.getSharedPreferences("hub", Context.MODE_PRIVATE)
        shuffle = p.getBoolean("shuffle", false)
        repeat = runCatching { RepeatMode.valueOf(p.getString("repeat", null) ?: "ALL") }.getOrDefault(RepeatMode.ALL)
    }

    private fun saveModes() {
        app.getSharedPreferences("hub", Context.MODE_PRIVATE).edit()
            .putBoolean("shuffle", shuffle).putString("repeat", repeat.name).apply()
    }

    suspend fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        if (session?.embedded == true) {
            YouTubeEmbed.seekTo(target)
            return
        }
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
    suspend fun playLibrary(tracks: List<LocalTrack>, index: Int, startMs: Long = 0L) {
        if (tracks.isEmpty()) return
        restored = null
        endSession()
        pauseOthers(keepPackage = null, keepLocal = true)
        active = SourceKind.LOCAL
        publish()
        localConnection.withController { c ->
            c.setMediaItems(tracks.map(MediaItems::from), index.coerceIn(0, tracks.lastIndex), startMs)
            c.repeatMode = playerRepeatMode()
            c.shuffleModeEnabled = shuffle
            c.prepare()
            c.play()
        }
    }

    /**
     * Plays a hub playlist: each entry is played by the app it belongs to. [index] is the song
     * the user tapped (-1 = from the start); [forceShuffle] is the Shuffle button. The
     * playlist's own defaults decide shuffle and repeat otherwise.
     */
    suspend fun playPlaylist(playlist: Playlist, index: Int = -1, forceShuffle: Boolean = false) {
        if (playlist.entries.isEmpty()) return
        shuffle = forceShuffle || playlist.defaultShuffle
        repeat = playlist.defaultRepeat
        saveModes()
        startSession(playlist.name, listOf(playlist.id), playlist.entries, index)
    }

    /** Several playlists shuffled together without creating a new playlist. */
    suspend fun playMix(selected: List<Playlist>) {
        val entries = selected.flatMap { it.entries }.distinctBy { TrackMatcher.key(it.title, it.artist) }
        if (entries.isEmpty()) return
        shuffle = true
        saveModes()
        startSession(app.getString(R.string.mix_name, selected.size), selected.map { it.id }, entries, -1)
    }

    private suspend fun startSession(name: String, ids: List<Long>, entries: List<PlaylistEntry>, index: Int, oneShot: Boolean = false) {
        restored = null
        endSession()
        val start = if (index in entries.indices) index else if (shuffle) entries.indices.random() else 0
        val order = if (shuffle) {
            (listOf(start) + entries.indices.filter { it != start }.shuffled()).toMutableList()
        } else {
            entries.indices.toMutableList()
        }
        session = ActiveSession(name, ids, entries, order, order.indexOf(start)).also { it.oneShot = oneShot }
        scope.launch { playlists.recordPlay(ids) }
        prefetchYouTubeIds(entries)
        playEntry(session!!.pos)
    }

    /** The song playing right now, described so it can be stored in a playlist. */
    fun currentDraft(): EntryDraft? {
        val s = session
        if (s != null && s.embedded) {
            val e = s.entry
            return EntryDraft(
                sourcePackage = e.sourcePackage,
                sourceLabel = e.sourceLabel,
                title = e.title,
                artist = e.artist,
                mediaUri = null,
                durationMs = embedded?.track?.durationMs ?: e.durationMs,
                mediaId = s.embedVideoId ?: e.mediaId,
            )
        }
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
            // The app's own id ("spotify:track:..."), so the song can later be asked for exactly.
            mediaId = if (isLocal) null else track.mediaId,
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
        if (index !in s.order.indices) {
            endSession()
            publish()
            return
        }
        s.pos = index
        s.dormant = false
        s.generation = ++sessionGeneration
        s.startedAt = SystemClock.elapsedRealtime()
        s.localStarted = false
        s.watch = null
        s.attempts = 0
        s.nudges = 0
        s.waitingForTap = false
        s.returnToApp = false
        s.returnHome = false
        s.restartChecked = false
        s.restartAttempts = 0
        s.lastRestartAt = 0L
        s.viaSpotifySdk = false
        s.embedded = false
        s.embedStarted = false
        s.embedVideoId = null
        HubNotifications.cancelContinue(app)
        val entry = s.entry
        // The previous in-app YouTube song stops at once (it is reused for the next one).
        if (wantsEmbed(entry)) {
            YouTubeEmbed.pause()
            embedded = null
        } else {
            stopEmbed()
        }

        if (entry.isLocal) {
            val uri = entry.mediaUri
            if (uri == null) {
                // A song from someone else's code that is not on this phone: skip it.
                missed(s)
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
                c.shuffleModeEnabled = false
                c.setMediaItems(listOf(MediaItems.from(track)), 0, pendingLocalStartMs)
                pendingLocalStartMs = 0L
                c.prepare()
                c.play()
            }
            s.misses = 0
        } else {
            val pkg = entry.sourcePackage ?: return missed(s)
            if (s.adoptNext) {
                // e.g. YouTube Music went on to the next song of its queue, which is our next song.
                s.adoptNext = false
                active = SourceKind.EXTERNAL
                sessions.preferredPackage = pkg
                s.watch = ExternalTrackWatch(entry.title, SystemClock.elapsedRealtime())
                s.query = listOf(entry.title, entry.artist).filter { it.isNotBlank() }.joinToString(" ")
                s.restartChecked = true // it starts at 0:00 by itself
                s.misses = 0
                scheduleCheck(s.generation, FIRST_CHECK_MS)
                publish()
                return
            }
            if (wantsEmbed(entry)) {
                playEmbedded(s, entry)
                return
            }
            s.viaSpotifySdk = pkg == KnownApps.SPOTIFY && SpotifyLink.isPlayableId(entry.mediaId) &&
                SpotifyLink.isAuthorized(app) && SpotifyLink.isInstalled(app)
            // Stop everything first, including the same app: the old song must not keep playing
            // while the next one is requested (that is what made "next" look ignored).
            // Through Spotify's SDK the new song replaces the old one directly, so Spotify
            // itself is not paused (a pause racing the play command could stop the new song).
            pauseOthers(keepPackage = if (s.viaSpotifySdk) KnownApps.SPOTIFY else null, keepLocal = false)
            active = SourceKind.EXTERNAL
            sessions.preferredPackage = pkg
            s.watch = ExternalTrackWatch(entry.title, SystemClock.elapsedRealtime())
            s.query = listOf(entry.title, entry.artist).filter { it.isNotBlank() }.joinToString(" ")
            requestExternal(s)
            scheduleCheck(s.generation, FIRST_CHECK_MS)
            publish()
        }
    }

    // ---------------------------------------------------------------- YouTube inside the app

    /** YouTube Music / YouTube songs play inside our app unless turned off or known not to embed. */
    private fun wantsEmbed(entry: PlaylistEntry): Boolean {
        val pkg = entry.sourcePackage
        if (pkg != KnownApps.YOUTUBE_MUSIC && pkg != KnownApps.YOUTUBE) return false
        if (!YouTubeEmbed.isEnabled(app)) return false
        val id = entry.mediaId
        return !(YouTubeLink.isVideoId(id) && YouTubeEmbed.isBlocked(app, id!!))
    }

    /**
     * Plays a YouTube song with the embedded player: no other app, no screen change. Its
     * video id is found with the unofficial search when the playlist does not have it yet.
     * Whatever cannot be played this way goes to the YouTube Music app ([embedFallback]).
     */
    private suspend fun playEmbedded(s: ActiveSession, entry: PlaylistEntry) {
        val generation = s.generation
        pauseOthers(keepPackage = null, keepLocal = false)
        sessions.preferredPackage = null
        active = SourceKind.LOCAL
        s.embedded = true
        embedArt = null
        embedded = embedSnapshot(entry, null, playing = false, positionMs = 0L, durationMs = entry.durationMs)
        publish()

        var id = entry.mediaId?.takeIf(YouTubeLink::isVideoId)
        if (id == null) {
            id = kotlinx.coroutines.withTimeoutOrNull(YT_LOOKUP_MS) { YouTubeLink.findVideoId(entry.title, entry.artist) }
            if (id != null) rememberYouTubeId(entry, id)
        }
        if (session !== s || s.generation != generation) return
        if (id == null || YouTubeEmbed.isBlocked(app, id)) {
            embedFallback(s)
            return
        }
        s.embedVideoId = id
        loadEmbedArt(s, entry, id)
        YouTubeEmbedService.start(app, entry.title)
        YouTubeEmbed.load(app, id)
        // Autoplay can be held back (e.g. an ad, a slow start): nudge once, then give up on embedding.
        handler.postDelayed({
            if (session === s && s.generation == generation && s.embedded && !s.embedStarted) YouTubeEmbed.play()
        }, EMBED_NUDGE_MS)
        handler.postDelayed({
            if (session === s && s.generation == generation && s.embedded && !s.embedStarted) {
                Log.i(TAG, "Embedded player did not start; using the app")
                scope.launch { if (session === s && s.generation == generation) embedFallback(s) }
            }
        }, EMBED_GIVE_UP_MS)
    }

    /** The song cannot play inside our app: play it in the YouTube Music app (v1.8 method). */
    private suspend fun embedFallback(s: ActiveSession) {
        val entry = s.entry
        val pkg = entry.sourcePackage ?: return missed(s)
        s.embedded = false
        s.embedStarted = false
        stopEmbed()
        val now = SystemClock.elapsedRealtime()
        s.startedAt = now
        s.attempts = 0
        pauseOthers(keepPackage = null, keepLocal = false)
        active = SourceKind.EXTERNAL
        sessions.preferredPackage = pkg
        s.watch = ExternalTrackWatch(entry.title, now)
        s.query = listOf(entry.title, entry.artist).filter { it.isNotBlank() }.joinToString(" ")
        requestExternal(s)
        scheduleCheck(s.generation, FIRST_CHECK_MS)
        publish()
    }

    private fun stopEmbed() {
        embedded = null
        embedArt = null
        if (!YouTubeEmbed.isActive) return
        YouTubeEmbed.stop()
        YouTubeEmbedService.stop(app)
    }

    private fun onEmbedUpdate(state: Int, positionMs: Long, durationMs: Long) {
        val s = session ?: return
        if (!s.embedded) return
        val playing = state == YouTubeEmbed.STATE_PLAYING
        if (playing && !s.embedStarted) {
            s.embedStarted = true
            s.misses = 0
        }
        val duration = if (durationMs > 0L) durationMs else s.entry.durationMs
        embedded = embedSnapshot(s.entry, s.embedVideoId, playing, positionMs, duration)
        if (state == YouTubeEmbed.STATE_ENDED && s.embedStarted) {
            s.embedStarted = false
            embedded = embedSnapshot(s.entry, s.embedVideoId, false, duration, duration)
            scope.launch { advance(auto = true) }
        }
        publish()
    }

    private fun onEmbedError(code: Int) {
        val s = session ?: return
        if (!s.embedded) return
        val id = s.embedVideoId
        if (id != null && YouTubeEmbed.isPermanentError(code)) YouTubeEmbed.block(app, id)
        val generation = s.generation
        scope.launch { if (session === s && s.generation == generation && s.embedded) embedFallback(s) }
    }

    private fun embedSnapshot(entry: PlaylistEntry, videoId: String?, playing: Boolean, positionMs: Long, durationMs: Long) =
        SourceSnapshot(
            kind = SourceKind.LOCAL,
            appLabel = app.getString(R.string.source_in_app, entry.sourceLabel),
            packageName = entry.sourcePackage,
            track = TrackInfo(
                title = entry.title,
                artist = entry.artist,
                mediaId = videoId,
                art = embedArt,
                durationMs = durationMs,
            ),
            isPlaying = playing,
            positionMs = positionMs,
            positionAtElapsedMs = SystemClock.elapsedRealtime(),
        )

    /** The song's stored cover, else YouTube's thumbnail (cropped to the square cover), kept for later. */
    private fun loadEmbedArt(s: ActiveSession, entry: PlaylistEntry, id: String) {
        val generation = s.generation
        scope.launch {
            val art = ArtStore.load(app, entry.title, entry.artist)
                ?: SpotifyLink.downloadImage("https://i.ytimg.com/vi/$id/hqdefault.jpg")?.let(::squareThumbnail)
                    ?.also { ArtStore.save(app, entry.title, entry.artist, it) }
                ?: return@launch
            if (session !== s || s.generation != generation || !s.embedded) return@launch
            embedArt = art
            embedded = embedded?.let { it.copy(track = it.track?.copy(art = art)) }
            publish()
        }
    }

    /** hqdefault is 4:3 with the 16:9 video letterboxed; the square in its middle is the cover. */
    private fun squareThumbnail(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val contentHeight = minOf(src.height, src.width * 9 / 16)
        val side = minOf(contentHeight, src.width)
        return android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    /**
     * Asks the entry's app to play it, in the background:
     *  1. through its live session: by the app's own id when we have it, else by search;
     *  2. again by search;
     * and when the app has no session at all: through its media browser service (no screen),
     * or, only while the user is inside our app, by opening it briefly. Otherwise a
     * "tap to continue" notification; nothing else is ever brought to the screen.
     */
    private fun requestExternal(s: ActiveSession) {
        val entry = s.entry
        val pkg = entry.sourcePackage ?: return
        s.attempts++
        val spotifyId = entry.mediaId
        if (s.viaSpotifySdk && spotifyId != null) {
            // Spotify's SDK: the exact song, from the beginning, without opening Spotify.
            val generation = s.generation
            SpotifyLink.play(app, spotifyId) { ok ->
                val current = session
                if (ok || current == null || current.generation != generation) return@play
                // Not possible right now (not approved, Spotify unreachable, Free account):
                // fall back to the shared media session for this song.
                current.viaSpotifySdk = false
                scope.launch {
                    pauseOthers(keepPackage = null, keepLocal = false)
                    if (session === current && current.generation == generation) requestExternal(current)
                }
            }
            return
        }
        if (pkg == KnownApps.YOUTUBE_MUSIC || pkg == KnownApps.YOUTUBE) {
            requestYouTube(s, entry, pkg)
            return
        }
        val controller = sessions.controllerFor(pkg)
        if (controller != null) {
            ask(controller, entry, s, preferId = s.attempts == 1)
            return
        }
        val generation = s.generation
        browserBridge.connect(pkg) { browserController ->
            val current = session
            if (current == null || current.generation != generation) return@connect
            when {
                browserController != null -> ask(browserController, entry, current, preferId = true)
                visibleScreens > 0 && launchPlayFromSearch(pkg, entry, current.query) -> current.returnToApp = true
                HubNotifications.showContinue(app, entry.sourceLabel, entry.title, playFromSearchIntent(pkg, entry, current.query)) ->
                    current.waitingForTap = true
                else -> _messages.tryEmit(app.getString(R.string.toast_app_not_running, entry.sourceLabel))
            }
        }
    }

    /**
     * YouTube Music only follows "play this" from its media session when the request comes from
     * apps it trusts (Assistant, Android Auto); from us it kept playing whatever it had loaded.
     * It does follow basic commands from anyone (play, pause, skip to a queue item), and links.
     *
     * So the first YouTube Music song of a playlist opens YouTube Music once, with all of the
     * playlist's YouTube Music songs as one temporary playlist in its queue; every later one is
     * a background skip within that queue. Missing video ids are found with the unofficial
     * search (YouTubeLink). Whatever fails falls back to a watch link, then to YouTube Music's
     * search screen.
     */
    private fun requestYouTube(s: ActiveSession, entry: PlaylistEntry, pkg: String) {
        val generation = s.generation
        scope.launch {
            var id = entry.mediaId?.takeIf(YouTubeLink::isVideoId)
            if (id == null) {
                id = kotlinx.coroutines.withTimeoutOrNull(YT_LOOKUP_MS) { YouTubeLink.findVideoId(entry.title, entry.artist) }
                if (id != null) rememberYouTubeId(entry, id)
            }
            if (session !== s || s.generation != generation) return@launch
            // 1. Already in YouTube Music's queue: switch in the background, no screen change.
            if (s.attempts == 1 && id != null && id in s.ytQueue && skipInYouTubeQueue(pkg, entry, id)) return@launch
            // 2. Open YouTube Music once: this song first, the playlist's other YouTube songs queued.
            val intent = when {
                id != null && s.attempts <= 2 -> {
                    val list = if (s.attempts == 1) buildYouTubeQueue(s, pkg, id) else null
                    if (session !== s || s.generation != generation) return@launch
                    watchIntent(pkg, id, list)
                }
                else -> playFromSearchIntent(pkg, entry, s.query)
            }
            startYouTube(s, entry, intent)
        }
    }

    /** Skips to the song inside YouTube Music's own queue (a command it accepts from any app). */
    private fun skipInYouTubeQueue(pkg: String, entry: PlaylistEntry, id: String): Boolean {
        val controller = sessions.controllerFor(pkg) ?: return false
        val queue = controller.queue ?: return false
        val item = queue.firstOrNull { it.description.mediaId == id }
            ?: queue.firstOrNull { TrackMatcher.sameTitle(entry.title, it.description.title?.toString().orEmpty()) }
            ?: return false
        controller.transportControls.skipToQueueItem(item.queueId)
        controller.transportControls.play()
        return true
    }

    /**
     * Makes one temporary YouTube playlist of the session's YouTube Music songs, starting with
     * [firstId] and following the play order. Returns its list id, or null.
     */
    private suspend fun buildYouTubeQueue(s: ActiveSession, pkg: String, firstId: String): String? {
        val rotated = s.order.indices.map { s.order[(s.pos + it) % s.order.size] }
        val ids = ArrayList<String>()
        ids += firstId
        var lookups = 0
        for (idx in rotated) {
            if (ids.size >= YouTubeLink.MAX_QUEUE) break
            val e = s.entries.getOrNull(idx) ?: continue
            if (e.sourcePackage != pkg) continue
            var id = e.mediaId?.takeIf(YouTubeLink::isVideoId)
            if (id == null && lookups < MAX_YT_QUEUE_LOOKUPS) {
                lookups++
                id = kotlinx.coroutines.withTimeoutOrNull(YT_LOOKUP_MS) { YouTubeLink.findVideoId(e.title, e.artist) }
                if (id != null) rememberYouTubeId(e, id)
            }
            if (id != null && id !in ids) ids += id
        }
        if (ids.size < 2) return null
        val list = YouTubeLink.temporaryPlaylist(ids) ?: return null
        s.ytQueue = ids.toSet()
        return list
    }

    private fun watchIntent(pkg: String, id: String, list: String?): Intent {
        val host = if (pkg == KnownApps.YOUTUBE_MUSIC) "https://music.youtube.com" else "https://www.youtube.com"
        val url = "$host/watch?v=$id" + (list?.let { "&list=$it" } ?: "")
        return Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /**
     * Opens YouTube Music with [intent]. Needs our app on screen or the "display over other
     * apps" permission; without either, a "tap to continue" notification carries the intent.
     */
    private fun startYouTube(s: ActiveSession, entry: PlaylistEntry, intent: Intent) {
        val pkg = entry.sourcePackage ?: return
        val visible = visibleScreens > 0
        val canStart = visible || Settings.canDrawOverlays(app)
        val started = canStart && runCatching { app.startActivity(intent) }
            .onFailure { Log.i(TAG, "Could not start $pkg: ${it.javaClass.simpleName}") }
            .isSuccess
        when {
            started && visible -> s.returnToApp = true
            started -> {
                // Started from the widget or the lock screen: keep YouTube Music in the background.
                val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                s.returnHome = !locked
            }
            HubNotifications.showContinue(app, entry.sourceLabel, entry.title, intent) -> s.waitingForTap = true
            else -> _messages.tryEmit(app.getString(R.string.toast_app_not_running, entry.sourceLabel))
        }
    }

    /** Remembers a song's YouTube video id (every copy of the song), for exact playback later. */
    private fun rememberYouTubeId(entry: PlaylistEntry, id: String) {
        val key = TrackMatcher.key(entry.title, entry.artist)
        val ids = playlists.playlists.value.flatMap { it.entries }
            .filter { it.sourcePackage == entry.sourcePackage && !YouTubeLink.isVideoId(it.mediaId) && TrackMatcher.key(it.title, it.artist) == key }
            .map { it.id }
            .plus(entry.id)
            .distinct()
        scope.launch { playlists.setMediaIds(ids.associateWith { id }) }
    }

    /**
     * Before 2.1 the lookup matched titles only, so a cover (e.g. a Kasane Teto version) could
     * be saved with the original's video id. Once, every stored YouTube id is checked against
     * YouTube (title and channel); ids of another version are dropped and looked up again with
     * the artist taken into account. Exact ids (imported playlists) pass the check and stay.
     */
    private suspend fun forgetGuessedYouTubeIds() {
        val prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE)
        if (prefs.getInt("yt_ids_version", 0) >= 2) return
        val songs = playlists.playlists.value.flatMap { it.entries }.filter {
            (it.sourcePackage == KnownApps.YOUTUBE_MUSIC || it.sourcePackage == KnownApps.YOUTUBE) && YouTubeLink.isVideoId(it.mediaId)
        }
        val wrong = ArrayList<Long>()
        var unknown = 0
        for ((_, group) in songs.groupBy { it.mediaId + "|" + TrackMatcher.key(it.title, it.artist) }.entries.take(400)) {
            val e = group.first()
            when (YouTubeLink.videoMatches(e.mediaId!!, e.title, e.artist)) {
                false -> wrong += group.map { it.id }
                null -> unknown++
                true -> Unit
            }
        }
        playlists.clearMediaIds(wrong)
        // Offline: try again next time (but give up after a few launches).
        val tries = prefs.getInt("yt_ids_tries", 0) + 1
        if (unknown == 0 || tries >= 3) prefs.edit().putInt("yt_ids_version", 2).apply()
        else prefs.edit().putInt("yt_ids_tries", tries).apply()
    }

    /** Looks up missing YouTube video ids for a playlist in the background, so it starts faster. */
    private fun prefetchYouTubeIds(entries: List<PlaylistEntry>) {
        val missing = entries.filter {
            (it.sourcePackage == KnownApps.YOUTUBE_MUSIC || it.sourcePackage == KnownApps.YOUTUBE) &&
                !YouTubeLink.isVideoId(it.mediaId)
        }.distinctBy { TrackMatcher.key(it.title, it.artist) }.take(YouTubeLink.MAX_QUEUE)
        if (missing.isEmpty()) return
        scope.launch {
            for (e in missing) {
                val id = YouTubeLink.findVideoId(e.title, e.artist) ?: continue
                rememberYouTubeId(e, id)
            }
        }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(home) }
    }

    private fun ask(controller: PlatformController, entry: PlaylistEntry, s: ActiveSession, preferId: Boolean) {
        val mediaId = entry.mediaId
        if (preferId && mediaId != null) {
            controller.transportControls.playFromMediaId(mediaId, searchExtras(entry))
        } else {
            controller.transportControls.playFromSearch(s.query, searchExtras(entry))
        }
    }

    private fun scheduleCheck(generation: Int, delayMs: Long) {
        handler.postDelayed({ scope.launch { checkExternal(generation) } }, delayMs)
    }

    /** Makes sure the requested song really plays: nudges, retries, and skips on a miss. */
    private suspend fun checkExternal(generation: Int) {
        val s = session ?: return
        if (s.generation != generation || s.entry.isLocal || s.dormant) return
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
            if (controller != null) ensureFromStart(s, controller)
            return
        }
        if (controller != null && watch.matches(title)) {
            // The requested song is loaded: only now is "play" safe (never on another song).
            ensureFromStart(s, controller)
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
            missed(s)
            return
        }
        // A wrong song may be playing (the app ignored the request): keep it quiet and ask again.
        // (Not through Spotify's SDK: there the old title only lingers until the new song loads.)
        if (controller != null && playing && !s.viaSpotifySdk) controller.transportControls.pause()
        if (s.attempts < 3) requestExternal(s)
        scheduleCheck(generation, RECHECK_MS)
    }

    /**
     * A chosen song always starts from the beginning, even when the app resumes the song it had
     * paused (YouTube Music does this when it is woken up with a paused song). The seek is sent
     * only once the song really plays (apps drop seeks while loading), and checked again a
     * moment later: up to [MAX_RESTARTS] seeks. The position is compared with the time since we
     * asked, so a song that legitimately started a few seconds ago is left alone.
     */
    private fun ensureFromStart(s: ActiveSession, controller: PlatformController) {
        if (s.restartChecked) return
        val state = controller.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING) return // called again once it plays
        val now = SystemClock.elapsedRealtime()
        var position = state.position
        if (state.lastPositionUpdateTime > 0L) {
            position += ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val since = now - if (s.restartAttempts == 0) s.startedAt else s.lastRestartAt
        if (position <= since + RESUME_SLACK_MS) {
            s.restartChecked = true // playing from the start
            return
        }
        if (s.restartAttempts >= MAX_RESTARTS) {
            s.restartChecked = true // the app refuses; leave it
            return
        }
        s.restartAttempts++
        s.lastRestartAt = now
        if (s.viaSpotifySdk) SpotifyLink.seekToStart() else controller.transportControls.seekTo(0L)
        val generation = s.generation
        handler.postDelayed({
            val cur = session
            if (cur === s && cur.generation == generation) externalController?.let { ensureFromStart(cur, it) }
        }, RESTART_VERIFY_MS)
    }

    /**
     * Spotify (through its SDK) reports the song it plays with its exact id. When that is the
     * song the playlist asked for and we did not know its id yet, remember it: next time it
     * is played exactly.
     */
    private fun onSpotifyState(state: com.spotify.protocol.types.PlayerState) {
        val s = session ?: return
        val entry = s.entry
        if (entry.sourcePackage != KnownApps.SPOTIFY || SpotifyLink.isPlayableId(entry.mediaId)) return
        val track = state.track ?: return
        val uri = track.uri
        val name = track.name
        if (!SpotifyLink.isPlayableId(uri) || name.isNullOrBlank()) return
        if (!TrackMatcher.sameTitle(entry.title, name)) return
        scope.launch { playlists.setMediaIds(sameSongIds(entry).associateWith { uri }) }
    }

    /** The same song saved in several playlists (and Favorites) shares one Spotify id. */
    private fun sameSongIds(entry: PlaylistEntry): List<Long> {
        val key = TrackMatcher.key(entry.title, entry.artist)
        return playlists.playlists.value.flatMap { it.entries }
            .filter {
                it.sourcePackage == entry.sourcePackage && !SpotifyLink.isPlayableId(it.mediaId) &&
                    TrackMatcher.key(it.title, it.artist) == key
            }
            .map { it.id }
    }

    /**
     * With the short-lived token from approving Spotify: looks up the exact id of saved Spotify
     * songs that lack one, and fetches covers for Spotify songs whose cover is not stored yet.
     */
    private val spotifyTried = HashSet<String>()

    suspend fun resolveSpotifyIds() {
        if (SpotifyLink.validToken(app) == null) return
        val spotifySongs = playlists.playlists.value.flatMap { it.entries }.filter { it.sourcePackage == KnownApps.SPOTIFY }
        val found = HashMap<Long, String>()
        var lookups = 0
        for ((_, group) in spotifySongs.groupBy { TrackMatcher.key(it.title, it.artist) }) {
            if (lookups >= MAX_SPOTIFY_LOOKUPS) break
            val first = group.first()
            val knownId = group.firstNotNullOfOrNull { e -> e.mediaId?.takeIf { SpotifyLink.isPlayableId(it) } }
            val needsArt = !ArtStore.has(app, first.title, first.artist)
            if (knownId != null && !needsArt) continue
            if (!spotifyTried.add(TrackMatcher.key(first.title, first.artist))) continue
            lookups++
            var imageUrl: String? = null
            if (knownId == null) {
                val track = SpotifyLink.findTrack(app, first.title, first.artist) ?: continue
                group.forEach { found[it.id] = track.uri }
                imageUrl = track.imageUrl
            } else {
                imageUrl = SpotifyLink.coverUrl(app, knownId)
                    ?: SpotifyLink.findTrack(app, first.title, first.artist)?.imageUrl
            }
            if (needsArt && imageUrl != null) {
                SpotifyLink.downloadImage(imageUrl)?.let { ArtStore.save(app, first.title, first.artist, it) }
            }
        }
        playlists.setMediaIds(found)
    }

    /** Spotify songs still missing their exact id or their cover. */
    fun spotifySongsNeedingLookup(): List<PlaylistEntry> =
        playlists.playlists.value.flatMap { it.entries }
            .filter { it.sourcePackage == KnownApps.SPOTIFY && (!SpotifyLink.isPlayableId(it.mediaId) || !ArtStore.has(app, it.title, it.artist)) }

    fun spotifySongsWithoutId(): List<PlaylistEntry> =
        playlists.playlists.value.flatMap { it.entries }
            .filter { it.sourcePackage == KnownApps.SPOTIFY && !SpotifyLink.isPlayableId(it.mediaId) }

    fun hasSpotifySongs(): Boolean =
        playlists.playlists.value.any { p -> p.entries.any { it.sourcePackage == KnownApps.SPOTIFY } }

    /** Skips a song that could not be started; ends the playlist when nothing can be played. */
    private suspend fun missed(s: ActiveSession) {
        s.misses++
        if (s.misses >= s.order.size) {
            _messages.tryEmit(app.getString(R.string.toast_nothing_playable))
            endSession()
            publish()
            return
        }
        advance(auto = false)
    }

    /**
     * Moves on. [auto] is a song that ended by itself: with "repeat one" it plays again.
     * At the end, "repeat all" (the default) starts over, reshuffled when shuffle is on.
     */
    /** The entry advance(auto = true) would play next, or null. */
    private fun nextEntry(s: ActiveSession): PlaylistEntry? = when {
        s.pos + 1 < s.order.size -> s.entries.getOrNull(s.order[s.pos + 1])
        repeat == RepeatMode.ALL && !shuffle -> s.entries.getOrNull(s.order[0])
        else -> null
    }

    private suspend fun advance(auto: Boolean) {
        val s = session ?: return
        if (auto) s.misses = 0
        if (s.oneShot) {
            if (auto && repeat == RepeatMode.ONE) {
                playEntry(s.pos)
                return
            }
            // A resumed single song is over: the app's own queue goes on from here.
            endSession()
            if (!auto) externalController?.transportControls?.skipToNext()
            publish()
            return
        }
        when {
            auto && repeat == RepeatMode.ONE -> playEntry(s.pos)
            s.pos + 1 < s.order.size -> playEntry(s.pos + 1)
            repeat != RepeatMode.OFF -> {
                if (shuffle) {
                    s.order = s.entries.indices.shuffled().toMutableList()
                    s.rebuildQueue()
                }
                playEntry(0)
            }
            else -> {
                endSession()
                publish()
            }
        }
    }

    private fun syncSession(lists: List<Playlist>) {
        val s = session ?: return
        if (s.playlistIds.size != 1) return
        val p = lists.firstOrNull { it.id == s.playlistIds[0] } ?: return
        if (p.entries == s.entries) return
        val newEntries = p.entries
        if (newEntries.isEmpty()) {
            endSession()
            return
        }
        val currentId = s.entry.id
        val newOrder = if (shuffle) {
            val kept = s.order.map { s.entries[it].id }.mapNotNull { id -> newEntries.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            (kept + newEntries.indices.filter { it !in kept }).toMutableList()
        } else {
            newEntries.indices.toMutableList()
        }
        val newPos = newOrder.indexOfFirst { newEntries[it].id == currentId }
        s.entries = newEntries
        s.order = newOrder
        // The playing song was removed: "next" continues from where it was.
        s.pos = if (newPos >= 0) newPos else (s.pos - 1).coerceIn(0, newOrder.lastIndex)
        s.rebuildQueue()
    }

    private fun endSession() {
        if (session == null) return
        session = null
        sessionGeneration++
        stopEmbed()
        sessions.preferredPackage = null
        HubNotifications.cancelContinue(app)
        browserBridge.disconnectAll()
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

    private fun returnToApp() {
        val intent = Intent(app, com.sen0413.ultimatewidget.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { app.startActivity(intent) }
    }

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

    // ---------------------------------------------------------------- long pauses

    private var pausedSince = 0L
    private val goDormant = Runnable {
        val s = session ?: return@Runnable
        if (pausedSince > 0L && SystemClock.elapsedRealtime() - pausedSince >= DORMANT_AFTER_MS && !_state.value.current.isPlaying) {
            s.dormant = true
            s.watch = null
            Log.i(TAG, "Hub playlist paused for a long time: waiting for the user")
        }
    }

    /** Notes when a hub playlist was paused; after [DORMANT_AFTER_MS] it stops acting on its own. */
    private fun trackPause(state: HubState) {
        val s = session
        if (s == null || state.current.isPlaying) {
            if (pausedSince != 0L) handler.removeCallbacks(goDormant)
            pausedSince = 0L
            return
        }
        if (pausedSince == 0L) {
            pausedSince = SystemClock.elapsedRealtime()
            handler.removeCallbacks(goDormant)
            handler.postDelayed(goDormant, DORMANT_AFTER_MS)
        }
    }

    /**
     * Play after a long pause: continue the same song where it was when the app still has it
     * loaded; otherwise ask for the playlist's song again.
     */
    private suspend fun wake(s: ActiveSession): Boolean {
        s.dormant = false
        if (s.entry.isLocal || s.embedded) return false // our own players resume as usual
        val title = external?.track?.title
        if (external?.packageName == s.entry.sourcePackage && !title.isNullOrBlank() && TrackMatcher.sameTitle(s.entry.title, title)) {
            s.watch = ExternalTrackWatch(s.entry.title, SystemClock.elapsedRealtime())
            s.restartChecked = true // keep its position
            return false // the normal toggle below presses play in that app
        }
        playEntry(s.pos)
        return true
    }

    // ---------------------------------------------------------------- the last song, across restarts

    /** Shows the song from before the app was closed (until something else plays). */
    private fun restoreLast() {
        val last = ResumeStore.load(app) ?: return
        restoredLast = last
        val snapshot = SourceSnapshot(
            kind = if (last.packageName == null) SourceKind.LOCAL else SourceKind.EXTERNAL,
            appLabel = last.appLabel.ifBlank { app.getString(R.string.source_local) },
            packageName = last.packageName,
            track = TrackInfo(
                title = last.title,
                artist = last.artist,
                mediaId = last.mediaId,
                durationMs = last.durationMs,
                mediaUri = last.mediaUri,
            ),
            isPlaying = false,
            positionMs = last.positionMs,
        )
        restored = snapshot
        scope.launch {
            val art = if (last.packageName == null) {
                ArtLoader.load(app, last.mediaUri, "${last.title}|${last.artist}")
            } else {
                ArtStore.load(app, last.title, last.artist)
            } ?: return@launch
            val cur = restored ?: return@launch
            if (cur !== snapshot) return@launch
            restored = cur.copy(track = cur.track?.copy(art = art))
            publish()
        }
    }

    private fun showingRestored(): Boolean =
        restored != null && session == null && local.track == null && external?.track == null

    /** Keeps the song on screen (and where it came from) whenever it changes, pauses or seeks. */
    private fun saveLast(state: HubState) {
        val cur = state.current
        val t = cur.track ?: return
        if (t.title.isBlank() || cur === restored) return
        val s = session
        val key = listOf(t.title, t.artist, cur.packageName, cur.isPlaying, cur.positionAtElapsedMs - cur.positionMs, s?.entry?.id)
        if (key == lastSavedKey) return
        lastSavedKey = key
        val now = SystemClock.elapsedRealtime()
        ResumeStore.save(
            app,
            ResumeStore.Last(
                title = t.title,
                artist = t.artist,
                packageName = cur.packageName,
                appLabel = cur.appLabel,
                mediaId = t.mediaId,
                mediaUri = t.mediaUri,
                durationMs = t.durationMs,
                positionMs = cur.positionAt(now).coerceAtLeast(0L),
                playlistIds = if (s != null && !s.oneShot) s.playlistIds else emptyList(),
                sessionName = s?.name.orEmpty(),
                entryId = s?.entry?.id ?: -1L,
                libraryIndex = if (s == null && cur.kind == SourceKind.LOCAL) local.currentQueueIndex else -1,
            ),
        )
    }

    /**
     * Play / next / previous while the remembered song is shown: continue where it was.
     * A song from a hub playlist continues inside that playlist; a file on the phone continues
     * at the same second; another app's song is asked for again (from the beginning).
     * [offset] is 0 for play, +1 for next, -1 for previous. Returns false when nothing is remembered.
     */
    private suspend fun resumeLast(offset: Int): Boolean {
        if (!showingRestored()) return false
        val last = restoredLast ?: return false
        val startMs = if (offset != 0 || (last.durationMs > 0 && last.positionMs > last.durationMs - 5_000L)) 0L else last.positionMs

        if (last.playlistIds.isNotEmpty()) {
            if (playlists.playlists.value.isEmpty()) playlists.load()
            val lists = last.playlistIds.mapNotNull { playlists.byId(it) }.filter { it.entries.isNotEmpty() }
            if (lists.isNotEmpty()) {
                val entries = if (lists.size == 1) lists[0].entries
                else lists.flatMap { it.entries }.distinctBy { TrackMatcher.key(it.title, it.artist) }
                var idx = entries.indexOfFirst { it.id == last.entryId }
                if (idx < 0) idx = entries.indexOfFirst { TrackMatcher.sameTitle(it.title, last.title) }
                if (idx < 0) idx = 0
                val start = (idx + offset).mod(entries.size)
                if (entries[start].isLocal) pendingLocalStartMs = startMs
                startSession(if (lists.size == 1) lists[0].name else last.sessionName, lists.map { it.id }, entries, start)
                return true
            }
        }

        if (last.packageName == null) {
            if (library.tracks.value.isEmpty()) library.refresh()
            val tracks = library.tracks.value
            val i = tracks.indexOfFirst { it.uri == last.mediaUri }
            if (i >= 0) {
                playLibrary(tracks, (i + offset).mod(tracks.size), startMs)
                return true
            }
            val uri = last.mediaUri ?: return false
            playLibrary(listOf(LocalTrack("resume", last.title, last.artist, last.durationMs, uri)), 0, startMs)
            return true
        }

        val entry = PlaylistEntry(
            id = -1L,
            sourcePackage = last.packageName,
            sourceLabel = last.appLabel,
            title = last.title,
            artist = last.artist,
            mediaUri = null,
            durationMs = last.durationMs,
            mediaId = last.mediaId,
        )
        startSession(last.title, emptyList(), listOf(entry), 0, oneShot = true)
        return true
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
        val coverRes = 0
        val art = if (item != null) localArt(uri, "$title|$artist") else null

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

        if (item != null) restored = null
        val s = session
        if (s != null && s.entry.isLocal) {
            if (player.isPlaying) s.localStarted = true
            if (s.localStarted && player.playbackState == Player.STATE_ENDED) {
                s.localStarted = false
                scope.launch { advance(auto = true) }
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
        if (snapshot?.track != null) restored = null

        // Hub playlist: has the external app finished the song we asked for?
        val s = session
        val watch = s?.watch
        if (s != null && !s.dormant && watch != null && snapshot != null && snapshot.packageName == s.entry.sourcePackage) {
            val now = SystemClock.elapsedRealtime()
            val wasArmed = watch.isArmed
            val result = watch.onMetadata(
                snapshot.track?.title, snapshot.isPlaying, now,
                positionMs = snapshot.positionAt(now), durationMs = snapshot.track?.durationMs ?: 0L,
            )
            if (watch.isArmed) externalController?.let { ensureFromStart(s, it) }
            // Come back as soon as the requested song is loaded (it may still be buffering),
            // so the other app is on screen for as short a time as possible.
            if ((s.returnHome || s.returnToApp) && watch.matches(snapshot.track?.title)) {
                if (s.returnHome) {
                    s.returnHome = false
                    goHome()
                }
                if (s.returnToApp) {
                    s.returnToApp = false
                    returnToApp()
                }
            }
            if (!wasArmed && watch.isArmed) {
                HubNotifications.cancelContinue(app)
                // Learn the song's YouTube video id from YouTube Music itself.
                val playingId = snapshot.track?.mediaId
                if ((s.entry.sourcePackage == KnownApps.YOUTUBE_MUSIC || s.entry.sourcePackage == KnownApps.YOUTUBE) &&
                    !YouTubeLink.isVideoId(s.entry.mediaId) && YouTubeLink.isVideoId(playingId)
                ) {
                    rememberYouTubeId(s.entry, playingId!!)
                }
            }
            if (result == ExternalTrackWatch.Result.USER_CHANGED) {
                // The user picked another song in that app: the hub playlist is over, hands off.
                endSession()
                autoSelectSource()
                publish()
                return
            }
            if (result == ExternalTrackWatch.Result.MOVED_ON) {
                s.watch = null
                val next = nextEntry(s)
                val newTitle = snapshot.track?.title
                if (repeat == RepeatMode.ONE && snapshot.isPlaying && !newTitle.isNullOrBlank() &&
                    TrackMatcher.sameTitle(s.entry.title, newTitle)
                ) {
                    // Repeat one, and the app is already playing the same song again (its own
                    // repeat): keep it going instead of pausing and asking for it once more.
                    s.watch = ExternalTrackWatch(s.entry.title, SystemClock.elapsedRealtime())
                    s.restartChecked = true
                    s.misses = 0
                    autoSelectSource()
                    publish()
                    return
                }
                if (next != null && next.sourcePackage == s.entry.sourcePackage && repeat != RepeatMode.ONE &&
                    !newTitle.isNullOrBlank() && TrackMatcher.sameTitle(next.title, newTitle)
                ) {
                    // The app moved on to exactly our next song (YouTube Music's queue): keep it.
                    s.adoptNext = true
                } else {
                    externalController?.transportControls?.pause()
                }
                scope.launch { advance(auto = true) }
            }
        }
        if (s != null) endSessionIfUserTookOver(s)

        autoSelectSource()
        publish()
    }

    /**
     * The user started something else (another app, or another song in Spotify outside the
     * hub's request): the hub playlist is over, and "next" goes back to that app's own queue.
     */
    private fun endSessionIfUserTookOver(s: ActiveSession) {
        if (SystemClock.elapsedRealtime() - s.startedAt < TAKEOVER_GRACE_MS || !notificationAccess) return
        val entryPkg = s.entry.sourcePackage
        val other = sessions.otherSessions().any {
            it.packageName != entryPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (other) {
            endSession()
            autoSelectSource()
        }
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
        val shownLocal = embedded?.takeIf { s?.embedded == true } ?: restored?.takeIf { showingRestored() } ?: local
        val base = HubState(
            local = shownLocal,
            external = external,
            active = active,
            visualizer = visualizerStatus,
            notificationAccess = notificationAccess,
            session = s?.let { PlaylistSession(it.playlistIds.firstOrNull() ?: -1L, it.name, it.pos, it.queue) },
            shuffle = shuffle,
            repeat = repeat,
        )
        val track = base.current.track
        val favorite = track != null && ::playlists.isInitialized && playlists.isFavorite(track.title, track.artist)
        return if (favorite) base.copy(isFavorite = true) else base
    }

    private var artCandidateKey: String? = null

    /**
     * Keeps the cover of a song another app is playing, once it has been stable for a moment
     * (right after a song change some apps still show the previous cover).
     */
    private fun scheduleArtCapture(s: HubState) {
        val cur = s.current
        if (cur.kind != SourceKind.EXTERNAL) return // local files keep their art in the file
        val t = cur.track ?: return
        val art = t.art ?: return
        if (t.title.isBlank()) return
        val key = "${t.title}|${t.artist}|${System.identityHashCode(art)}"
        if (key == artCandidateKey) return
        artCandidateKey = key
        handler.removeCallbacks(captureArt)
        handler.postDelayed(captureArt, ART_STABLE_MS)
    }

    private val captureArt = Runnable {
        val cur = _state.value.current
        val t = cur.track ?: return@Runnable
        val art = t.art ?: return@Runnable
        if ("${t.title}|${t.artist}|${System.identityHashCode(art)}" != artCandidateKey) return@Runnable
        ArtStore.save(app, t.title, t.artist, art)
        // Also under the playlist's own spelling of the song, which can differ slightly.
        val e = session?.entry
        if (e != null && (e.title != t.title || e.artist != t.artist) && TrackMatcher.sameTitle(e.title, t.title)) {
            ArtStore.save(app, e.title, e.artist, art)
        }
    }

    private val pendingRender = java.util.concurrent.atomic.AtomicReference<HubState?>(null)
    private val pendingBars = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)

    private fun publish() {
        val s = buildState()
        _state.value = s
        if (!::app.isInitialized) return
        scheduleArtCapture(s)
        saveLast(s)
        trackPause(s)

        val cur = s.current
        val renderKey = listOf(
            s.active, cur.packageName, cur.track?.title, cur.track?.artist, cur.track?.mediaId,
            System.identityHashCode(cur.track?.art), cur.isPlaying,
            cur.positionAtElapsedMs - cur.positionMs, s.visualizer, s.external != null, s.isFavorite,
            s.shuffle, s.repeat,
        )
        if (renderKey != lastRenderKey) {
            lastRenderKey = renderKey
            // Drawn off the main thread (covers, colors, RemoteViews), newest state only, so a
            // tap on a song gets an instant response.
            if (pendingRender.getAndSet(s) == null) {
                RenderThread.post {
                    val latest = pendingRender.getAndSet(null) ?: return@post
                    WidgetRenderer.renderAll(app, latest)
                    LockScreenCard.update(app, latest)
                }
            }
        }

        val queueKey = Triple(s.session?.playlistId, s.queue.map { it.queueId to it.title }, s.queueIndex)
        if (queueKey != lastQueueKey) {
            lastQueueKey = queueKey
            RenderThread.post { WidgetRenderer.notifyQueueChanged(app) }
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
            // A YouTube song inside the app plays in a WebView: analyse the output mix.
            SourceKind.LOCAL -> if (session?.embedded == true) 0 else HubAudio.localSessionId(app)
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

    /** Nothing loaded yet: the screens show the welcome card until the user picks music. */
    private fun initialLocalSnapshot(): SourceSnapshot = SourceSnapshot(
        kind = SourceKind.LOCAL,
        appLabel = app.getString(R.string.source_local),
        packageName = null,
        track = null,
        isPlaying = false,
    )
}
