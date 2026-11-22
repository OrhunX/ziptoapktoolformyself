package com.sen0413.ultimatewidget.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.ui.kit.Ink
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
        SetupRow(R.string.setup_media, R.string.setup_media_body, R.string.grant,
            { HubController.library.hasPermission() }) { host.requestMediaAccess() },
        SetupRow(R.string.setup_notifications, R.string.setup_notifications_body, R.string.grant,
            { notificationsAllowed(context) }) { host.requestNotifications() },
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

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
