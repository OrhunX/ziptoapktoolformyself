package com.sen0413.ultimatewidget.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.LocalTrack
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.hub.SourceKind
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.card
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.formatTime
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.pressable
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.views.ArtworkView
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Everything our own player can play: bundled demo tracks and the phone's audio files. */
class LibraryScreen(host: Host) : Screen(host) {
    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Song(val track: LocalTrack, val index: Int) : Row()
    }

    private var tracks: List<LocalTrack> = emptyList()
    private var rows: List<Row> = emptyList()
    private var playingId: String? = null

    private val subtitle = context.label("", TextRole.BODY, Ink.MIST)
    private val permissionCard = context.card {
        add(context.label(context.getString(R.string.library_permission_title), TextRole.HEADING))
        add(context.label(context.getString(R.string.library_permission_body), TextRole.CAPTION, Ink.MIST), top = dp(6))
        add(
            context.pill(context.getString(R.string.library_allow)) { host.requestMediaAccess() },
            ViewGroup.LayoutParams.WRAP_CONTENT, top = dp(12),
        )
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Song
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Header -> ((convertView as? TextView) ?: headerView()).apply { text = row.title }
                is Row.Song -> ((convertView as? SongRow) ?: SongRow()).apply { bind(row) }
            }
    }

    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, 0, dp(16))
        adapter = this@LibraryScreen.adapter
    }

    override val view: View = context.column {
        setPadding(dp(24), dp(12), dp(24), 0)
        add(context.label(context.getString(R.string.library_title), TextRole.DISPLAY))
        add(subtitle, top = dp(4))
        add(permissionCard, top = dp(16))
        add(list, height = 0, weight = 1f, top = dp(8))
    }

    override fun onLibrary(tracks: List<LocalTrack>) {
        this.tracks = tracks
        val demo = tracks.withIndex().filter { it.value.coverRes != 0 }
        val device = tracks.withIndex().filter { it.value.coverRes == 0 }
        rows = buildList {
            if (demo.isNotEmpty()) {
                add(Row.Header(context.getString(R.string.library_section_demo)))
                demo.forEach { add(Row.Song(it.value, it.index)) }
            }
            if (device.isNotEmpty()) {
                add(Row.Header(context.getString(R.string.library_section_device)))
                device.forEach { add(Row.Song(it.value, it.index)) }
            }
        }
        subtitle.text = context.getString(R.string.library_subtitle, tracks.size)
        adapter.notifyDataSetChanged()
        onPermissionsChanged()
    }

    override fun onPermissionsChanged() {
        permissionCard.visibility = if (HubController.library.hasPermission()) View.GONE else View.VISIBLE
    }

    override fun render(state: HubState) {
        val id = if (state.active == SourceKind.LOCAL && state.local.isPlaying) state.local.track?.mediaId else null
        if (id != playingId) {
            playingId = id
            adapter.notifyDataSetChanged()
        }
    }

    private fun headerView(): TextView = context.label("", TextRole.LABEL, Ink.LAVENDER).apply {
        letterSpacing = 0.08f
        isAllCaps = true
        setPadding(0, dp(18), 0, dp(8))
    }

    private inner class SongRow : LinearLayout(context) {
        private val cover = ArtworkView(context).apply { cornerRadius = dp(12f) }
        private val note = ImageView(context).apply {
            setImageResource(R.drawable.ic_note)
            imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
        }
        private val title = context.label("", TextRole.HEADING, maxLines = 1)
        private val meta = context.label("", TextRole.CAPTION, Ink.MIST, maxLines = 1)
        private var row: Row.Song? = null
        private val addButton = context.iconButton(
            R.drawable.ic_add, context.getString(R.string.cd_add_to_playlist), Ink.MIST,
        ) {
            row?.let { host.pickPlaylistFor(HubController.draftOf(it.track)) }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            background = pressable(Color.TRANSPARENT, dp(16f))
            setPadding(dp(4), dp(8), 0, dp(8))
            val thumb = FrameLayout(context).apply {
                addView(cover, FrameLayout.LayoutParams(dp(52), dp(52)))
                addView(note, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            add(thumb, dp(52), dp(52))
            add(context.column {
                add(title)
                add(meta, top = dp(3))
            }, 0, weight = 1f, start = dp(14))
            add(addButton, dp(48), dp(48))
            // The list item itself is not clickable inside a ListView with focusable children.
            addButton.isFocusable = false
            setOnClickListener {
                row?.let { song -> HubController.scope.launch { HubController.playLibrary(tracks, song.index) } }
            }
        }

        fun bind(row: Row.Song) {
            this.row = row
            val track = row.track
            title.text = track.title
            title.setTextColor(if (track.id == playingId) Ink.LAVENDER else Ink.PAPER)
            meta.text = if (track.durationMs > 0) "${track.artist} · ${formatTime(track.durationMs)}" else track.artist
            if (track.coverRes != 0) {
                cover.setImageResource(track.coverRes)
                cover.background = null
                note.visibility = View.GONE
            } else {
                cover.setImageDrawable(null)
                cover.background = generativeTile(track.title + track.artist)
                note.visibility = View.VISIBLE
            }
        }
    }

    /** A two-color tile derived from the song's text, so each row has its own identity. */
    private fun generativeTile(seed: String): GradientDrawable {
        val hash = seed.hashCode()
        val hueA = abs(hash % 360).toFloat()
        val hueB = (hueA + 50f) % 360f
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hueA, 0.55f, 0.85f)),
                Color.HSVToColor(floatArrayOf(hueB, 0.70f, 0.40f)),
            ),
        )
    }
}
