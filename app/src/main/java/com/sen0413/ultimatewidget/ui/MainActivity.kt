package com.sen0413.ultimatewidget.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.SpotifyLink
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.ui.kit.Appearance
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.views.AmbientBackgroundView
import com.sen0413.ultimatewidget.ui.views.MiniPlayer
import com.sen0413.ultimatewidget.ui.views.NavBar
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import com.sen0413.ultimatewidget.widget.HubWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The single activity. It owns the window chrome (backdrop, navigation, mini player),
 * permission requests and dialogs; the four screens only render [HubState].
 */
class MainActivity : Activity(), Host {
    override val context: Context get() = this
    override lateinit var ambient: AmbientBackgroundView
        private set

    private lateinit var content: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var nav: NavBar
    private lateinit var mini: MiniPlayer

    private val screens = LinkedHashMap<Tab, Screen>()
    private var tab: Tab? = null
    private var latest: HubState? = null
    private var uiScope: CoroutineScope? = null
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.load(this)
        drawEdgeToEdge()

        ambient = AmbientBackgroundView(this)
        content = FrameLayout(this)
        nav = NavBar(this) { open(it) }
        mini = MiniPlayer(
            this,
            onOpen = { open(Tab.PLAYER) },
            onPlayPause = { HubController.scope.launch { HubController.playPause() } },
        )
        chrome = column {
            add(content, height = 0, weight = 1f)
            val miniHolder = FrameLayout(this@MainActivity).apply {
                setPadding(dp(16), 0, dp(16), dp(6))
                addView(mini)
            }
            add(miniHolder)
            add(nav)
        }
        val root = FrameLayout(this).apply {
            addView(ambient, matchParent())
            addView(chrome, matchParent())
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBarInsets(insets)
            chrome.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)

        registerBackHandling()
        open(savedInstanceState?.getString(STATE_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.PLAYER)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // A playlist share link (from a QR code or a message): umw://import?c=UMW1-...
        val data = intent?.data
        if (data != null && data.scheme == "umw") {
            intent.data = null
            val code = data.getQueryParameter("c").orEmpty()
            open(Tab.PLAYLISTS)
            (screen(Tab.PLAYLISTS) as? PlaylistsScreen)?.showImport(code)
        }
        intent?.getStringExtra(EXTRA_TAB)?.let { name ->
            intent.removeExtra(EXTRA_TAB)
            runCatching { Tab.valueOf(name) }.getOrNull()?.let { open(it) }
        }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_MIC)
            if (!VisualizerEngine.hasPermission(this)) requestMicrophone()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tab?.let { outState.putString(STATE_TAB, it.name) }
    }

    override fun onStart() {
        super.onStart()
        HubController.onScreenVisible(true)
        val scope = MainScope()
        uiScope = scope
        scope.launch { HubController.state.collect { render(it) } }
        scope.launch { VisualizerEngine.bars.collect { onBars(it) } }
        scope.launch { HubController.library.tracks.collect { tracks -> screens.values.forEach { it.onLibrary(tracks) } } }
        scope.launch { HubController.playlists.playlists.collect { lists -> screens.values.forEach { it.onPlaylists(lists) } } }
        scope.launch { HubController.messages.collect { toast(it) } }
        scope.launch { maybeLinkSpotify() }
    }

    /**
     * Spotify's SDK needs the user's approval once. Asked automatically the first time
     * Spotify songs are in a playlist; later, a quick silent refresh (at most twice a day)
     * gives the token used to look up exact ids for songs that still lack one.
     */
    private suspend fun maybeLinkSpotify() {
        when {
            SpotifyLink.validToken(this) != null -> HubController.resolveSpotifyIds()
            !HubController.hasSpotifySongs() -> Unit
            SpotifyLink.shouldAskFirstTime(this) -> SpotifyLink.authorize(this)
            SpotifyLink.isAuthorized(this) && HubController.spotifySongsWithoutId().isNotEmpty() &&
                SpotifyLink.shouldRefreshToken(this) -> SpotifyLink.authorize(this)
        }
    }

    override fun connectSpotify() {
        if (!SpotifyLink.isInstalled(this)) {
            toast(getString(R.string.spotify_not_installed))
            return
        }
        spotifyAuthByUser = true
        SpotifyLink.authorize(this)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a permission dialog or system settings: re-check everything.
        // While the app is visible the visualizer may (re)attach under Android's
        // while-in-use rules for RECORD_AUDIO.
        HubController.refresh()
        screens.values.forEach { it.onPermissionsChanged() }
        tab?.let { screens[it]?.onShown() }
    }

    override fun onPause() {
        tab?.let { screens[it]?.onHidden() }
        super.onPause()
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        HubController.onScreenVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- rendering

    private fun render(state: HubState) {
        latest = state
        ambient.playing = state.current.isPlaying
        mini.render(state)
        screens.values.forEach { it.render(state) }
    }

    private fun onBars(bars: FloatArray) {
        val current = tab ?: return
        screens[current]?.onBars(bars)
        if (current != Tab.PLAYER && bars.isNotEmpty()) {
            ambient.targetEnergy = (bars[0] + bars[minOf(1, bars.lastIndex)] + bars[minOf(2, bars.lastIndex)]) / 3f
        }
    }

    private fun screen(tab: Tab): Screen = screens.getOrPut(tab) {
        val created = when (tab) {
            Tab.PLAYER -> PlayerScreen(this)
            Tab.LIBRARY -> LibraryScreen(this)
            Tab.PLAYLISTS -> PlaylistsScreen(this)
            Tab.SETTINGS -> SettingsScreen(this)
        }
        created.view.visibility = View.GONE
        content.addView(created.view, matchParent())
        // A screen created later still needs everything that was published before it existed.
        created.onLibrary(HubController.library.tracks.value)
        created.onPlaylists(HubController.playlists.playlists.value)
        created.render(latest ?: HubController.state.value)
        created
    }

    override fun open(tab: Tab) {
        val previous = this.tab
        if (previous == tab) return
        previous?.let {
            screens[it]?.onHidden()
            screens[it]?.view?.visibility = View.GONE
        }
        this.tab = tab
        val next = screen(tab)
        next.view.visibility = View.VISIBLE
        if (previous != null && ValueAnimator.areAnimatorsEnabled()) {
            next.view.alpha = 0f
            next.view.translationY = dp(12f)
            next.view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        next.onShown()
        nav.select(tab)
        mini.visibility = if (tab == Tab.PLAYER) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- back

    private fun registerBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @Deprecated("Used on Android 12 and older; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        val current = tab
        when {
            current != null && screens[current]?.onBack() == true -> Unit
            current != Tab.PLAYER -> open(Tab.PLAYER)
            else -> finish()
        }
    }

    // ---------------------------------------------------------------- Host: permissions

    override fun requestMicrophone() = request(Manifest.permission.RECORD_AUDIO)

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun requestMediaAccess() = request(HubController.library.permission)

    private fun request(permission: String) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(permission), REQUEST_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permission = permissions.firstOrNull()
        val denied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        // Denied for good: Android will not show the dialog again, so open the app's settings.
        if (permission != null && denied && !shouldShowRequestPermissionRationale(permission)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        HubController.refresh()
        screens.values.forEach { it.onPermissionsChanged() }
    }

    override fun openNotificationAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    override fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, HubWidgetProvider::class.java), null, null)
        } else {
            toast(getString(R.string.pin_not_supported))
        }
    }

    // ---------------------------------------------------------------- Host: dialogs

    override fun pickPlaylistFor(draft: EntryDraft) {
        val lists = HubController.playlists.playlists.value
        val names = lists.map { it.name } + getString(R.string.playlist_new)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.playlist_pick_title)
            .setItems(names.toTypedArray()) { _, which ->
                if (which < lists.size) addTo(lists[which].id, lists[which].name, draft)
                else promptNewPlaylist { id -> addTo(id, null, draft) }
            }
            .show()
    }

    private fun addTo(playlistId: Long, knownName: String?, draft: EntryDraft) {
        HubController.scope.launch {
            val added = HubController.playlists.addEntry(playlistId, draft)
            val name = knownName
                ?: HubController.playlists.playlists.value.firstOrNull { it.id == playlistId }?.name.orEmpty()
            toast(getString(if (added) R.string.toast_added else R.string.toast_already, name))
        }
    }

    override fun promptNewPlaylist(onCreated: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.playlist_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.playlist_new)
            .setView(holder)
            .setPositiveButton(R.string.playlist_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    HubController.scope.launch { onCreated(HubController.playlists.create(name)) }
                }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    private var imageCallback: ((Uri) -> Unit)? = null

    private var spotifyAuthByUser = false

    override fun pickImage(onPicked: (Uri) -> Unit) {
        imageCallback = onPicked
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQUEST_IMAGE) }.onFailure { imageCallback = null }
    }

    @Deprecated("Framework Activity results; this app has no AndroidX activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpotifyLink.REQUEST_AUTH) {
            val ok = SpotifyLink.onAuthResult(this, resultCode, data)
            if (spotifyAuthByUser) toast(getString(if (ok) R.string.spotify_connected else R.string.spotify_failed))
            spotifyAuthByUser = false
            screens.values.forEach { it.onPermissionsChanged() }
            return
        }
        if (requestCode == REQUEST_IMAGE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) imageCallback?.invoke(uri)
            imageCallback = null
        }
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- window

    @Suppress("DEPRECATION") // the pre-Android 11 way to draw behind the system bars
    private fun drawEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            bars.top to maxOf(bars.bottom, ime.bottom)
        } else {
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    companion object {
        const val EXTRA_REQUEST_MIC = "com.sen0413.ultimatewidget.extra.REQUEST_MIC"
        const val EXTRA_TAB = "com.sen0413.ultimatewidget.extra.TAB"
        private const val STATE_TAB = "tab"
        private const val REQUEST_PERMISSION = 41
        private const val REQUEST_IMAGE = 42
    }
}
