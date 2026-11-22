package com.sen0413.ultimatewidget.ui

import android.content.Context
import android.view.View
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.LocalTrack
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.ui.views.AmbientBackgroundView

enum class Tab { PLAYER, LIBRARY, PLAYLISTS, SETTINGS }

/** What a screen may ask of the activity that hosts it. */
interface Host {
    val context: Context
    val ambient: AmbientBackgroundView
    fun requestMicrophone()
    fun requestNotifications()
    fun requestMediaAccess()
    fun openNotificationAccess()
    fun pinWidget()
    fun pickPlaylistFor(draft: EntryDraft)
    fun promptNewPlaylist(onCreated: (Long) -> Unit = {})
    fun toast(text: CharSequence)
    /** Lets the user choose a photo (for playlist covers). */
    fun pickImage(onPicked: (android.net.Uri) -> Unit)
    fun open(tab: Tab)
}

/**
 * One tab of the app. A screen builds its view once and then only re-renders from the
 * state it is handed; it never keeps its own copy of playback state.
 */
abstract class Screen(protected val host: Host) {
    abstract val view: View
    protected val context: Context get() = host.context

    open fun render(state: HubState) = Unit
    open fun onBars(bars: FloatArray) = Unit
    open fun onLibrary(tracks: List<LocalTrack>) = Unit
    open fun onPlaylists(playlists: List<Playlist>) = Unit
    open fun onPermissionsChanged() = Unit
    open fun onShown() = Unit
    open fun onHidden() = Unit

    /** Returns true if the screen consumed the back gesture. */
    open fun onBack(): Boolean = false
}
