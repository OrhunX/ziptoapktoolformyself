package com.sen0413.ultimatewidget.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.hub.KnownApps
import com.sen0413.ultimatewidget.hub.SpotifyLink
import com.sen0413.ultimatewidget.hub.YouTubeEmbed
import com.sen0413.ultimatewidget.hub.YouTubeAccount
import com.sen0413.ultimatewidget.ui.kit.Appearance
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.chip
import com.sen0413.ultimatewidget.ui.kit.setChipSelected
import com.sen0413.ultimatewidget.ui.views.ColorPicker
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.card
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine

/** Setup: every permission next to the feature it unlocks, plus the widget and credits. */
class SettingsScreen(host: Host) : Screen(host) {

    private inner class SetupRow(
        title: Int,
        body: Int,
        actionLabel: Int,
        private val isDone: () -> Boolean,
        onAction: () -> Unit,
    ) {
        private val status = context.label(context.getString(R.string.granted), TextRole.LABEL, Ink.LAVENDER).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
        }
        private val action = context.pill(context.getString(actionLabel), filled = false, onClick = onAction)

        val view: LinearLayout = context.row {
            setPadding(0, dp(10), 0, dp(10))
            add(context.column {
                add(context.label(context.getString(title), TextRole.HEADING))
                add(context.label(context.getString(body), TextRole.CAPTION, Ink.MIST), top = dp(4))
            }, 0, weight = 1f)
            add(status, ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(12))
            add(action, ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(12))
        }

        fun refresh() {
            val done = isDone()
            status.visibility = if (done) View.VISIBLE else View.GONE
            action.visibility = if (done) View.GONE else View.VISIBLE
        }
    }

    private var listenerEnabled = false

    private val rows = listOf(
        SetupRow(R.string.setup_mic, R.string.setup_mic_body, R.string.grant,
            { VisualizerEngine.hasPermission(context) }) { host.requestMicrophone() },
        SetupRow(R.string.setup_access, R.string.setup_access_body, R.string.open_settings,
            { listenerEnabled }) { host.openNotificationAccess() },
        SetupRow(R.string.setup_spotify, R.string.setup_spotify_body, R.string.connect,
            { SpotifyLink.isAuthorized(context) }) { host.connectSpotify() },
        SetupRow(R.string.setup_youtube, R.string.setup_youtube_body, R.string.sign_in,
            { YouTubeAccount.isSignedIn(context) }) { host.signInYouTube() },
        SetupRow(R.string.setup_media, R.string.setup_media_body, R.string.grant,
            { HubController.library.hasPermission() }) { host.requestMediaAccess() },
        SetupRow(R.string.setup_notifications, R.string.setup_notifications_body, R.string.grant,
            { notificationsAllowed(context) }) { host.requestNotifications() },
        SetupRow(R.string.setup_lock, R.string.setup_lock_body, R.string.grant,
            { Settings.canDrawOverlays(context) }) { openOverlaySettings() },
        SetupRow(R.string.setup_hide_cards, R.string.setup_hide_cards_body, R.string.open_settings,
            { false }) { pickAppToHide() },
        SetupRow(R.string.setup_widget, R.string.setup_widget_body, R.string.add,
            { false }) { host.pinWidget() },
    )

    override val view: View = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(context.column {
            setPadding(dp(24), dp(12), dp(24), dp(24))
            add(context.label(context.getString(R.string.setup_title), TextRole.DISPLAY))
            add(context.label(context.getString(R.string.setup_subtitle), TextRole.BODY, Ink.MIST), top = dp(4))
            add(context.card {
                rows.forEach { add(it.view) }
            }, top = dp(16))
            add(appearanceCard(), top = dp(16))
            add(context.card {
                add(context.label(context.getString(R.string.about_title), TextRole.HEADING))
                add(context.label(context.getString(R.string.about_body), TextRole.CAPTION, Ink.MIST), top = dp(8))
            }, top = dp(16))
        })
    }

    override fun render(state: HubState) {
        if (listenerEnabled != state.notificationAccess) {
            listenerEnabled = state.notificationAccess
            onPermissionsChanged()
        }
    }

    override fun onPermissionsChanged() {
        rows.forEach { it.refresh() }
    }

    override fun onShown() = onPermissionsChanged()

    // ---------------------------------------------------------------- look and feel

    private fun appearanceCard(): View = context.card {
        add(context.label(context.getString(R.string.appearance_title), TextRole.HEADING))
        add(context.label(context.getString(R.string.appearance_body), TextRole.CAPTION, Ink.MIST), top = dp(4))
        val night = context.chip(context.getString(R.string.appearance_night)) { setGlass(false) }
        val glass = context.chip(context.getString(R.string.appearance_glass)) { setGlass(true) }
        night.setChipSelected(!Appearance.glass)
        glass.setChipSelected(Appearance.glass)
        add(context.row {
            add(night, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            add(glass, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
        }, top = dp(12))

        add(context.label(context.getString(R.string.backdrop_title), TextRole.HEADING), top = dp(20))
        add(context.label(context.getString(R.string.backdrop_body), TextRole.CAPTION, Ink.MIST), top = dp(4))
        val fromCover = context.chip(context.getString(R.string.backdrop_cover)) {}
        val customColor = context.chip(context.getString(R.string.backdrop_custom)) {}
        fun syncBackdrop() {
            val color = Appearance.backdrop
            fromCover.setChipSelected(color == 0)
            customColor.setChipSelected(color != 0)
            customColor.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (color != 0) ColorPicker.swatch(context, color).apply { setSize(dp(16), dp(16)) } else null, null, null, null,
            )
            customColor.compoundDrawablePadding = dp(8)
            host.ambient.refreshColors()
        }
        fromCover.setOnClickListener { Appearance.setBackdrop(context, 0); syncBackdrop() }
        customColor.setOnClickListener {
            val start = if (Appearance.backdrop != 0) Appearance.backdrop else Ink.VIOLET
            ColorPicker.show(context, context.getString(R.string.backdrop_custom), start, false) {
                Appearance.setBackdrop(context, it or 0xFF000000.toInt())
                syncBackdrop()
            }
        }
        syncBackdrop()
        add(context.row {
            add(fromCover, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            add(customColor, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
        }, top = dp(12))

        add(context.label(context.getString(R.string.lock_player_title), TextRole.HEADING), top = dp(20))
        add(context.label(context.getString(R.string.lock_player_body), TextRole.CAPTION, Ink.MIST), top = dp(4))
        val on = context.chip(context.getString(R.string.on)) {}
        val off = context.chip(context.getString(R.string.off)) {}
        fun syncLock() {
            val enabled = LockPlayerActivity.isEnabled(context)
            on.setChipSelected(enabled)
            off.setChipSelected(!enabled)
        }
        on.setOnClickListener {
            LockPlayerActivity.setEnabled(context, true)
            syncLock()
            if (!Settings.canDrawOverlays(context)) openOverlaySettings()
        }
        off.setOnClickListener { LockPlayerActivity.setEnabled(context, false); syncLock() }
        syncLock()
        add(context.row {
            add(on, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            add(off, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
        }, top = dp(12))

        add(context.label(context.getString(R.string.yt_in_app_title), TextRole.HEADING), top = dp(20))
        add(context.label(context.getString(R.string.yt_in_app_body), TextRole.CAPTION, Ink.MIST), top = dp(4))
        val ytOn = context.chip(context.getString(R.string.on)) {}
        val ytOff = context.chip(context.getString(R.string.off)) {}
        fun syncYt() {
            val enabled = YouTubeEmbed.isEnabled(context)
            ytOn.setChipSelected(enabled)
            ytOff.setChipSelected(!enabled)
        }
        ytOn.setOnClickListener {
            YouTubeEmbed.setEnabled(context, true)
            syncYt()
            if (!Settings.canDrawOverlays(context)) openOverlaySettings()
        }
        ytOff.setOnClickListener { YouTubeEmbed.setEnabled(context, false); syncYt() }
        syncYt()
        add(context.row {
            add(ytOn, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            add(ytOff, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
        }, top = dp(12))

        add(context.label(context.getString(R.string.sensitivity_title), TextRole.HEADING), top = dp(20))
        add(context.label(context.getString(R.string.sensitivity_body), TextRole.CAPTION, Ink.MIST), top = dp(4))
        add(SeekBar(context).apply {
            max = 100
            // 0..100 maps to a gain of 0.5..2.5
            progress = (((VisualizerEngine.gain - 0.5f) / 2f) * 100f).toInt().coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(Ink.LAVENDER)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ink.PAPER)
            contentDescription = context.getString(R.string.sensitivity_title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) VisualizerEngine.gain = 0.5f + value / 100f * 2f
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    VisualizerEngine.saveGain(context, 0.5f + bar.progress / 100f * 2f)
                }
            })
        }, top = dp(8))
    }

    private fun setGlass(glass: Boolean) {
        if (glass == Appearance.glass) return
        Appearance.setGlass(context, glass)
        (context as? Activity)?.recreate()
    }

    private fun openOverlaySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    /** Opens the notification settings of Spotify / YouTube Music, where their cards can be turned off. */
    private fun pickAppToHide() {
        val pm = context.packageManager
        val apps = listOf(KnownApps.SPOTIFY, KnownApps.YOUTUBE_MUSIC, KnownApps.YOUTUBE).mapNotNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() to pkg }.getOrNull()
        }
        if (apps.isEmpty()) {
            host.toast(context.getString(R.string.hide_cards_none))
            return
        }
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.setup_hide_cards)
            .setItems(apps.map { it.first }.toTypedArray()) { _, which ->
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, apps[which].second),
                    )
                }
            }
            .show()
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
