package com.sen0413.ultimatewidget.ui

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.PlaylistEntry
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.card
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.pressable
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.kit.sourceDot
import kotlinx.coroutines.launch

/**
 * Hub playlists: one list can hold songs from Spotify, YouTube Music and local files.
 * The screen has two modes, the overview and one playlist's detail.
 */
class PlaylistsScreen(host: Host) : Screen(host) {
    private var playlists: List<Playlist> = emptyList()
    private var openId: Long? = null
    private var state: HubState? = null

    private val openPlaylist: Playlist? get() = playlists.firstOrNull { it.id == openId }

    // ---------------------------------------------------------------- overview

    private val overviewAdapter = object : BaseAdapter() {
        override fun getCount() = playlists.size
        override fun getItem(position: Int) = playlists[position]
        override fun getItemId(position: Int) = playlists[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? PlaylistRow) ?: PlaylistRow()).apply { bind(playlists[position]) }
    }

    private val emptyCard = context.card {
        add(context.label(context.getString(R.string.playlist_empty_title), TextRole.HEADING))
        add(context.label(context.getString(R.string.playlist_empty_body), TextRole.CAPTION, Ink.MIST), top = dp(6))
    }

    private val overview: LinearLayout = context.column {
        add(context.label(context.getString(R.string.playlists_title), TextRole.DISPLAY))
        add(context.label(context.getString(R.string.playlists_subtitle), TextRole.BODY, Ink.MIST), top = dp(4))
        add(
            context.pill(context.getString(R.string.playlist_new)) { host.promptNewPlaylist { id -> open(id) } },
            ViewGroup.LayoutParams.WRAP_CONTENT, top = dp(16),
        )
        add(emptyCard, top = dp(16))
        add(plainList(overviewAdapter, gapDp = 10), height = 0, weight = 1f, top = dp(12))
    }

    // ---------------------------------------------------------------- detail

    private val detailAdapter = object : BaseAdapter() {
        private val entries get() = openPlaylist?.entries.orEmpty()
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? EntryRow) ?: EntryRow()).apply { bind(entries[position], position) }
    }

    private val detailTitle = context.label("", TextRole.TITLE, maxLines = 1)
    private val detailCount = context.label("", TextRole.CAPTION, Ink.MIST)
    private val detailEmpty = context.label(context.getString(R.string.playlist_detail_empty), TextRole.BODY, Ink.MIST)

    private val detail: LinearLayout = context.column {
        visibility = View.GONE
        add(context.row {
            add(context.iconButton(R.drawable.ic_back, context.getString(R.string.cd_back)) { open(null) }, dp(48), dp(48))
            add(context.column {
                add(detailTitle)
                add(detailCount, top = dp(2))
            }, 0, weight = 1f, start = dp(4))
            add(context.iconButton(R.drawable.ic_delete, context.getString(R.string.cd_delete), Ink.MIST) {
                confirmDelete()
            }, dp(48), dp(48))
        })
        add(context.row {
            add(context.pill(context.getString(R.string.playlist_play_all)) { play(0) }, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(
                context.pill(context.getString(R.string.playlist_add_current), filled = false) { addCurrent() },
                ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(10),
            )
        }, top = dp(14))
        add(detailEmpty, top = dp(20))
        add(plainList(detailAdapter), height = 0, weight = 1f, top = dp(10))
    }

    override val view: View = FrameLayout(context).apply {
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(overview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(detail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun plainList(listAdapter: BaseAdapter, gapDp: Int = 0) = ListView(context).apply {
        divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(gapDp)
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, 0, dp(16))
        adapter = listAdapter
    }

    // ---------------------------------------------------------------- behavior

    override fun onPlaylists(playlists: List<Playlist>) {
        this.playlists = playlists
        if (openId != null && openPlaylist == null) openId = null
        refresh()
    }

    override fun render(state: HubState) {
        val before = this.state?.session
        this.state = state
        if (before != state.session) detailAdapter.notifyDataSetChanged()
    }

    override fun onBack(): Boolean {
        if (openId == null) return false
        open(null)
        return true
    }

    private fun open(id: Long?) {
        openId = id
        refresh()
    }

    private fun refresh() {
        val playlist = openPlaylist
        overview.visibility = if (playlist == null) View.VISIBLE else View.GONE
        detail.visibility = if (playlist == null) View.GONE else View.VISIBLE
        emptyCard.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        overviewAdapter.notifyDataSetChanged()
        if (playlist != null) {
            detailTitle.text = playlist.name
            detailCount.text = context.getString(R.string.playlist_count, playlist.entries.size)
            detailEmpty.visibility = if (playlist.entries.isEmpty()) View.VISIBLE else View.GONE
        }
        detailAdapter.notifyDataSetChanged()
    }

    private fun play(index: Int) {
        val playlist = openPlaylist ?: return
        if (playlist.entries.isEmpty()) return
        HubController.scope.launch { HubController.playPlaylist(playlist, index) }
    }

    private fun addCurrent() {
        val playlist = openPlaylist ?: return
        val draft = HubController.currentDraft()
        if (draft == null) {
            host.toast(context.getString(R.string.toast_nothing_playing))
            return
        }
        HubController.scope.launch {
            val added = HubController.playlists.addEntry(playlist.id, draft)
            host.toast(context.getString(if (added) R.string.toast_added else R.string.toast_already, playlist.name))
        }
    }

    private fun confirmDelete() {
        val playlist = openPlaylist ?: return
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(context.getString(R.string.playlist_delete_title, playlist.name))
            .setPositiveButton(R.string.playlist_delete_confirm) { _, _ ->
                HubController.scope.launch { HubController.playlists.delete(playlist.id) }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    private fun confirmRemove(entry: PlaylistEntry) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(context.getString(R.string.playlist_remove_title, entry.title))
            .setPositiveButton(R.string.playlist_remove_confirm) { _, _ ->
                HubController.scope.launch { HubController.playlists.removeEntry(entry.id) }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- rows

    private inner class PlaylistRow : LinearLayout(context) {
        private val name = context.label("", TextRole.HEADING, maxLines = 1)
        private val meta = context.label("", TextRole.CAPTION, Ink.MIST, maxLines = 1)
        private val dots = context.row { }
        private var playlist: Playlist? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            background = pressable(Ink.SURFACE, dp(20f), Ink.STROKE, dp(1))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            add(context.column {
                add(name)
                add(meta, top = dp(4))
            }, 0, weight = 1f)
            add(dots, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { playlist?.let { open(it.id) } }
        }

        fun bind(playlist: Playlist) {
            this.playlist = playlist
            name.text = playlist.name
            meta.text = context.getString(R.string.playlist_count, playlist.entries.size)
            // One dot per platform in the playlist: the cross-platform mix at a glance.
            dots.removeAllViews()
            playlist.entries.map { it.sourcePackage }.distinct().take(4).forEach { pkg ->
                dots.add(context.sourceDot(pkg, 10), dp(10), dp(10), start = dp(6))
            }
        }
    }

    private inner class EntryRow : LinearLayout(context) {
        private val dotHolder = FrameLayout(context)
        private val title = context.label("", TextRole.HEADING, maxLines = 1)
        private val meta = context.label("", TextRole.CAPTION, Ink.MIST, maxLines = 1)
        private var entry: PlaylistEntry? = null
        private var position = 0
        private val remove = context.iconButton(R.drawable.ic_delete, context.getString(R.string.cd_delete), Ink.FAINT) {
            entry?.let { confirmRemove(it) }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = pressable(Color.TRANSPARENT, dp(16f))
            setPadding(dp(4), dp(6), 0, dp(6))
            add(dotHolder, dp(24), dp(24))
            add(context.column {
                add(title)
                add(meta, top = dp(3))
            }, 0, weight = 1f, start = dp(10))
            add(remove, dp(48), dp(48))
            remove.isFocusable = false
            setOnClickListener { play(position) }
        }

        fun bind(entry: PlaylistEntry, position: Int) {
            this.entry = entry
            this.position = position
            dotHolder.removeAllViews()
            dotHolder.addView(context.sourceDot(entry.sourcePackage, 10), FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
            val session = state?.session
            val isCurrent = session != null && session.playlistId == openId && session.index == position
            title.text = entry.title
            title.setTextColor(if (isCurrent) Ink.LAVENDER else Ink.PAPER)
            meta.text = listOf(entry.artist, entry.sourceLabel).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}
