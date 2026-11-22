package com.sen0413.ultimatewidget.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.kit.Appearance
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.Type
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.views.AmbientBackgroundView
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The full-screen player on the lock screen. It reuses the app's Player screen and adds a
 * clock, a minimize button (back to the small lock-screen card) and gestures: swipe left or
 * right on the artwork for the next or previous song, swipe up to unlock.
 *
 * Shown over the keyguard (showWhenLocked); anything that needs the full app first asks
 * Android to unlock, then opens it.
 */
class LockPlayerActivity : Activity(), Host {
    override val context: Context get() = this
    override lateinit var ambient: AmbientBackgroundView
        private set

    private lateinit var player: PlayerScreen
    private var uiScope: CoroutineScope? = null
    private var backCallback: OnBackInvokedCallback? = null
    private lateinit var gestures: GestureDetector

    private val unlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The user unlocked the phone some other way: get out of the way.
            if (intent.action == Intent.ACTION_USER_PRESENT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Appearance.load(this)
        showOverLockScreen()
        drawEdgeToEdge()

        ambient = AmbientBackgroundView(this)
        player = PlayerScreen(this)

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            typeface = Type.display(context)
            textSize = 56f
            setTextColor(Ink.PAPER)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            typeface = Type.text(context)
            textSize = 15f
            setTextColor(Ink.MIST)
            gravity = Gravity.CENTER
        }
        val minimize = iconButton(R.drawable.ic_minimize, getString(R.string.cd_minimize)) { finish() }
        val unlock = iconButton(R.drawable.ic_lock_open, getString(R.string.cd_unlock)) { unlockThen(null) }

        val content = column {
            add(row {
                setPadding(dp(8), dp(4), dp(8), 0)
                add(minimize, dp(48), dp(48))
                add(View(context), 0, dp(1), weight = 1f)
                add(unlock, dp(48), dp(48))
            })
            add(clock, top = dp(2))
            add(date, top = dp(4))
            add(player.view, height = 0, weight = 1f, top = dp(6))
            add(label(getString(R.string.lock_swipe_hint), TextRole.CAPTION, Ink.FAINT).apply {
                gravity = Gravity.CENTER
            }, top = dp(4))
        }
        val root = FrameLayout(this).apply {
            addView(ambient, matchParent())
            addView(content, matchParent())
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = systemBarInsets(insets)
            content.setPadding(0, top, 0, bottom + dp(8))
            insets
        }
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val height = root.height.coerceAtLeast(1)
                val threshold = dp(90)
                return when {
                    // Up from the lower part of the screen: unlock, like the lock screen itself.
                    dy < -threshold && abs(dy) > abs(dx) * 1.5f && start.y > height * 0.55f -> {
                        unlockThen(null)
                        true
                    }
                    // Sideways over the artwork: next / previous song.
                    abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f && start.y < height * 0.62f -> {
                        HubController.scope.launch { if (dx < 0) HubController.next() else HubController.previous() }
                        true
                    }
                    else -> false
                }
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { finish() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
        ContextCompat.registerReceiver(
            this, unlockedReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        isShowing = true
        HubController.onScreenVisible(true)
        val scope = MainScope()
        uiScope = scope
        scope.launch {
            HubController.state.collect {
                ambient.playing = it.current.isPlaying
                player.render(it)
            }
        }
        scope.launch { VisualizerEngine.bars.collect { player.onBars(it) } }
        scope.launch { HubController.messages.collect { toast(it) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val keyguard = getSystemService(KeyguardManager::class.java)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (interactive && !keyguard.isKeyguardLocked) {
            // Not on the lock screen: from the card, open the regular app; otherwise step aside.
            if (intent?.getBooleanExtra(EXTRA_FROM_CARD, false) == true) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
            return
        }
        HubController.refresh()
        player.onShown()
    }

    override fun onPause() {
        player.onHidden()
        super.onPause()
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        isShowing = false
        HubController.onScreenVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockedReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    @Deprecated("Used on Android 12 and older; newer versions use OnBackInvokedCallback.")
    override fun onBackPressed() {
        finish()
    }

    // ---------------------------------------------------------------- unlock, then the app

    /** Asks Android to unlock; on success opens the app (optionally on [tab]). */
    private fun unlockThen(tab: Tab?, requestMic: Boolean = false) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val openApp = {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_REQUEST_MIC, requestMic)
            if (tab != null) intent.putExtra(MainActivity.EXTRA_TAB, tab.name)
            startActivity(intent)
            finish()
        }
        if (!keyguard.isKeyguardLocked) {
            openApp()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openApp()
        })
    }

    // ---------------------------------------------------------------- Host

    override fun requestMicrophone() = unlockThen(Tab.PLAYER, requestMic = true)
    override fun requestNotifications() = unlockThen(Tab.SETTINGS)
    override fun requestMediaAccess() = unlockThen(Tab.SETTINGS)
    override fun openNotificationAccess() = unlockThen(Tab.SETTINGS)
    override fun pinWidget() = unlockThen(Tab.SETTINGS)
    override fun pickPlaylistFor(draft: EntryDraft) = unlockThen(Tab.PLAYLISTS)
    override fun promptNewPlaylist(onCreated: (Long) -> Unit) = unlockThen(Tab.PLAYLISTS)
    override fun open(tab: Tab) = unlockThen(tab)

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- window

    @Suppress("DEPRECATION") // flags are the Android 8.0 way; 8.1+ uses the activity methods
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    @Suppress("DEPRECATION")
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
            bars.top to bars.bottom
        } else {
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    companion object {
        private const val PREFS = "lock_player"
        private const val EXTRA_FROM_CARD = "com.sen0413.ultimatewidget.extra.FROM_CARD"
        private const val KEY_ENABLED = "enabled"

        /** True while the lock player is on screen (read by the hub on wake-up). */
        @Volatile
        var isShowing: Boolean = false
            private set

        /** [fromCard]: opened by tapping our media card (then, when unlocked, the app opens instead). */
        fun intent(context: Context, fromCard: Boolean = false): Intent = Intent(context, LockPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_FROM_CARD, fromCard)

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
