package com.sen0413.ultimatewidget.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.PlaylistEntry
import com.sen0413.ultimatewidget.data.PlaylistSort
import com.sen0413.ultimatewidget.data.RepeatMode
import com.sen0413.ultimatewidget.data.TrackMatcher
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.hub.KnownApps
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.card
import com.sen0413.ultimatewidget.ui.kit.chip
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.pressable
import com.sen0413.ultimatewidget.ui.kit.rounded
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.kit.sourceDot
import com.sen0413.ultimatewidget.ui.views.PlaylistCover
import com.sen0413.ultimatewidget.ui.views.ReorderList
import com.sen0413.ultimatewidget.ui.views.VisualizerView
import kotlinx.coroutines.launch

/**
 * Hub playlists: one list can hold songs from Spotify, YouTube Music and files on the phone.
 *
 * Overview: rich cards (cover collage, colors from the covers, length, source apps), sorting,
 * drag to reorder, long-press to select several and shuffle-play them together, and "enter
 * code" to import a shared playlist. Detail: a header with the cover, description, Play /
 * Shuffle / Repeat, songs you can drag into a new order or swipe away (with undo), search
 * buttons for songs that are not on this phone, and a menu to edit, share, merge or copy.
 */
class PlaylistsScreen(host: Host) : Screen(host) {
    private val dialogs = PlaylistDialogs(host)
    private val prefs = context.getSharedPreferences("playlists_ui", Context.MODE_PRIVATE)
    private var sort = runCatching { PlaylistSort.valueOf(prefs.getString("sort", null) ?: "MANUAL") }
        .getOrDefault(PlaylistSort.MANUAL)

    private var playlists: List<Playlist> = emptyList()
    private var openId: Long? = null
    private var state: HubState? = null
    private val selected = LinkedHashSet<Long>()
    private var lastBars = FloatArray(0)

    private val openPlaylist: Playlist? get() = playlists.firstOrNull { it.id == openId }

    // ---------------------------------------------------------------- overview views

    private val sortChip: TextView = context.chip(context.getString(R.string.sort_manual)) {}.apply {
        setOnClickListener { showSortMenu(it) }
    }
    private val overviewList = ReorderList(context)
    private val emptyCard = context.card {
        add(context.label(context.getString(R.string.playlist_empty_title), TextRole.HEADING))
        add(context.label(context.getString(R.string.playlist_empty_body), TextRole.CAPTION, Ink.MIST), top = dp(6))
    }

    private val overview: ScrollView = scrollColumn {
        add(context.label(context.getString(R.string.playlists_title), TextRole.DISPLAY))
        add(context.label(context.getString(R.string.playlists_subtitle), TextRole.BODY, Ink.MIST), top = dp(4))
        add(horizontalScroll(context.row {
            add(context.pill(context.getString(R.string.playlist_new)) {
                host.promptNewPlaylist { id -> open(id) }
            }, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(context.pill(context.getString(R.string.enter_code), filled = false) {
                dialogs.import { id -> open(id) }
            }, ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(10))
            add(sortChip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), start = dp(10))
        }), top = dp(16))
        add(emptyCard, top = dp(16))
        add(overviewList, top = dp(14))
    }

    private val selectionText = context.label("", TextRole.LABEL, Ink.PAPER, maxLines = 2)
    private val selectionBar: LinearLayout = context.column {
        visibility = View.GONE
        background = rounded(0xF01E1A2B.toInt(), dp(24f), Ink.STROKE, dp(1))
        elevation = dp(16f)
        setPadding(dp(16), dp(12), dp(12), dp(12))
        add(context.row {
            add(selectionText, 0, weight = 1f)
            add(context.iconButton(R.drawable.ic_close, context.getString(R.string.cd_close_selection)) {
                selected.clear()
                refresh()
            }, dp(44), dp(44))
        })
        add(context.row {
            add(context.pill(context.getString(R.string.select_mix)) { playSelected() }, 0, weight = 1f)
            add(context.pill(context.getString(R.string.select_save), filled = false) { saveSelected() },
                ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(10))
        }, top = dp(8))
    }

    // ---------------------------------------------------------------- detail views

    private val detailCover = PlaylistCover(context).apply {
        cornerRadius = dp(22f)
        elevation = dp(10f)
        contentDescription = context.getString(R.string.cd_cover)
    }
    private val detailName = context.label("", TextRole.TITLE, maxLines = 2)
    private val detailDescription = context.label("", TextRole.BODY, Ink.MIST, maxLines = 3)
    private val detailMeta = context.label("", TextRole.CAPTION, Ink.FAINT)
    private val detailSources = context.row { }
    private val repeatButton = context.iconButton(R.drawable.ic_repeat, context.getString(R.string.cd_repeat_all)) {
        HubController.scope.launch { HubController.cycleRepeat() }
    }
    private val missingText = context.label("", TextRole.CAPTION, Ink.PAPER)
    private val missingButtons = context.row { }
    private val missingBanner: LinearLayout = context.column {
        visibility = View.GONE
        background = rounded(0x26FF9F6E, dp(18f), 0x55FF9F6E, dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        add(missingText)
        add(missingButtons, top = dp(8))
    }
    private val detailEmpty = context.label(context.getString(R.string.playlist_detail_empty), TextRole.BODY, Ink.MIST)
    private val entryList = ReorderList(context)
    private val header: LinearLayout = context.column {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        add(context.row {
            gravity = Gravity.TOP
            add(detailCover, dp(128), dp(128))
            add(context.column {
                add(detailName)
                add(detailDescription, top = dp(4))
                add(detailMeta, top = dp(6))
                add(detailSources, top = dp(6))
            }, 0, weight = 1f, start = dp(16))
        })
        add(context.row {
            add(context.pill(context.getString(R.string.play)) { play(-1, shuffled = false) }, 0, weight = 1f)
            add(context.pill(context.getString(R.string.shuffle), filled = false) { play(-1, shuffled = true) },
                ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(10))
            add(repeatButton, dp(48), dp(48), start = dp(6))
        }, top = dp(14))
    }

    private val detail: ScrollView = scrollColumn {
        add(context.row {
            add(context.iconButton(R.drawable.ic_back, context.getString(R.string.cd_back)) { open(null) }, dp(48), dp(48))
            add(View(context), 0, dp(1), weight = 1f)
            add(context.iconButton(R.drawable.ic_more, context.getString(R.string.cd_more)) {}.apply {
                setOnClickListener { anchor -> openPlaylist?.let { showMenu(it, anchor) } }
            }, dp(48), dp(48))
        })
        add(header, top = dp(4))
        add(context.pill(context.getString(R.string.playlist_add_current), filled = false) { addCurrent() },
            ViewGroup.LayoutParams.WRAP_CONTENT, top = dp(12))
        add(missingBanner, top = dp(12))
        add(detailEmpty, top = dp(20))
        add(entryList, top = dp(10))
    }.apply { visibility = View.GONE }

    private val undoText = context.label("", TextRole.LABEL, Ink.PAPER, maxLines = 1)
    private var undoAction: (() -> Unit)? = null
    private val undoBar: LinearLayout = context.row {
        visibility = View.GONE
        background = rounded(0xF02E2450.toInt(), dp(22f))
        elevation = dp(16f)
        setPadding(dp(18), dp(4), dp(6), dp(4))
        add(undoText, 0, weight = 1f)
        add(context.pill(context.getString(R.string.undo), filled = false) {
            undoAction?.invoke()
            hideUndo()
        }, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private val hideUndoRunnable = Runnable { hideUndo() }

    override val view: View = FrameLayout(context).apply {
        addView(overview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(detail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
        addView(undoBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
    }

    init {
        overviewList.onMove = { from, to -> movePlaylist(from, to) }
        entryList.onMove = { from, to ->
            openPlaylist?.let { p -> HubController.scope.launch { HubController.playlists.moveEntry(p.id, from, to) } }
        }
        entryList.onSwipe = { index -> openPlaylist?.entries?.getOrNull(index)?.let { removeWithUndo(it) } }
    }

    private fun scrollColumn(block: LinearLayout.() -> Unit): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(context.column {
            setPadding(dp(24), dp(12), dp(24), dp(140))
            block()
        })
    }

    private fun horizontalScroll(content: View) = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(content)
    }

    // ---------------------------------------------------------------- data in

    /** New covers were saved: redraw covers in place (no list rebuild, scroll stays put). */
    override fun onArtChanged() {
        overviewCovers.forEach { (cover, p) -> cover.bind(p) }
        openPlaylist?.let { detailCover.bind(it) }
        rowsById.values.forEach { it.loadArt() }
    }

    private val overviewCovers = ArrayList<Pair<PlaylistCover, Playlist>>()

    override fun onPlaylists(playlists: List<Playlist>) {
        this.playlists = playlists
        if (openId != null && openPlaylist == null) openId = null
        selected.retainAll(playlists.map { it.id }.toSet())
        refresh()
    }

    override fun render(state: HubState) {
        val before = this.state
        this.state = state
        if (before == null || before.session != state.session || before.current.track?.title != state.current.track?.title) highlightCurrent()
        if (before?.repeat != state.repeat) renderRepeat()
    }

    override fun onBars(bars: FloatArray) {
        lastBars = bars
        nowPlayingViz?.setBars(bars)
    }

    override fun onBack(): Boolean {
        if (selected.isNotEmpty()) {
            selected.clear()
            refresh()
            return true
        }
        if (openId == null) return false
        open(null)
        return true
    }

    fun showImport(code: String) {
        open(null)
        dialogs.import(code) { id -> open(id) }
    }

    private fun open(id: Long?) {
        openId = id
        selected.clear()
        refresh()
        (if (id == null) overview else detail).scrollTo(0, 0)
    }

    private fun refresh() {
        val playlist = openPlaylist
        overview.visibility = if (playlist == null) View.VISIBLE else View.GONE
        detail.visibility = if (playlist == null) View.GONE else View.VISIBLE
        if (playlist == null) renderOverview() else renderDetail(playlist)
        renderSelection()
    }

    // ---------------------------------------------------------------- overview

    private fun sorted(): List<Playlist> {
        val favorites = playlists.filter { it.isFavorites }
        val rest = playlists.filter { !it.isFavorites }
        val ordered = when (sort) {
            PlaylistSort.MANUAL -> rest.sortedWith(compareBy({ it.position }, { it.createdAt }))
            PlaylistSort.NAME -> rest.sortedBy { it.name.lowercase() }
            PlaylistSort.RECENT -> rest.sortedByDescending { it.createdAt }
            PlaylistSort.MOST_PLAYED -> rest.sortedWith(compareByDescending<Playlist> { it.playCount }.thenByDescending { it.lastPlayedAt })
        }
        return favorites + ordered.filter { it.pinned } + ordered.filter { !it.pinned }
    }

    private var shownOrder: List<Playlist> = emptyList()

    private fun renderOverview() {
        sortChip.text = context.getString(sortLabel(sort))
        emptyCard.visibility = if (playlists.all { it.isFavorites && it.entries.isEmpty() }) View.VISIBLE else View.GONE
        shownOrder = sorted()
        overviewCovers.clear()
        val rows = shownOrder.map { playlistCard(it) }
        overviewList.setRows(rows.map { it.first })
        rows.forEachIndexed { i, (row, handle) ->
            val p = shownOrder[i]
            if (handle != null && canDrag(p)) overviewList.attachHandle(row, handle)
        }
    }

    private fun canDrag(p: Playlist) = sort == PlaylistSort.MANUAL && !p.isFavorites && selected.isEmpty()

    /** Reorders within the playlist's group (pinned or not); Favorites always stay first. */
    private fun movePlaylist(from: Int, to: Int) {
        val moved = shownOrder.getOrNull(from) ?: return
        val group = shownOrder.filter { !it.isFavorites && it.pinned == moved.pinned }.toMutableList()
        val groupStart = shownOrder.indexOfFirst { !it.isFavorites && it.pinned == moved.pinned }
        group.remove(moved)
        group.add((to - groupStart).coerceIn(0, group.size), moved)
        val pinned = if (moved.pinned) group else shownOrder.filter { !it.isFavorites && it.pinned }
        val unpinned = if (moved.pinned) shownOrder.filter { !it.isFavorites && !it.pinned } else group
        HubController.scope.launch { HubController.playlists.setPlaylistOrder((pinned + unpinned).map { it.id }) }
    }

    private fun playlistCard(p: Playlist): Pair<View, View?> {
        val isSelected = p.id in selected
        val cover = PlaylistCover(context).apply {
            cornerRadius = dp(16f)
            contentDescription = context.getString(R.string.cd_cover)
            bind(p)
        }
        overviewCovers += cover to p
        val check = ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(Ink.NIGHT)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ink.LAVENDER) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = if (isSelected) View.VISIBLE else View.GONE
        }
        val coverBox = FrameLayout(context).apply {
            addView(cover, FrameLayout.LayoutParams(dp(76), dp(76)))
            addView(check, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
        }
        val name = context.label(p.name, TextRole.HEADING, maxLines = 1)
        val title = context.row {
            if (p.pinned) add(ImageView(context).apply {
                setImageResource(R.drawable.ic_pin)
                imageTintList = ColorStateList.valueOf(Ink.LAVENDER)
                contentDescription = context.getString(R.string.cd_pinned)
            }, dp(16), dp(16))
            add(name, 0, weight = 1f, start = if (p.pinned) dp(6) else 0)
        }
        val description = context.label(p.description, TextRole.CAPTION, Ink.MIST, maxLines = 1).apply {
            visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
        }
        val meta = context.label(metaText(p), TextRole.CAPTION, Ink.FAINT, maxLines = 1)
        val dots = context.row {
            p.sources.take(4).forEachIndexed { i, pkg -> add(context.sourceDot(pkg, 9), dp(9), dp(9), start = if (i > 0) dp(5) else 0) }
        }
        val handle = if (canDrag(p)) context.iconButton(R.drawable.ic_drag, context.getString(R.string.cd_reorder), Ink.FAINT) {} else null
        val more = context.iconButton(R.drawable.ic_more, context.getString(R.string.cd_more), Ink.MIST) {}.apply {
            setOnClickListener { anchor -> showMenu(p, anchor) }
        }

        val card = context.row {
            setPadding(dp(12), dp(12), dp(4), dp(12))
            add(coverBox, dp(76), dp(76))
            add(context.column {
                add(title)
                add(description, top = dp(2))
                add(meta, top = dp(4))
                add(dots, top = dp(6))
            }, 0, weight = 1f, start = dp(14))
            handle?.let { add(it, dp(44), dp(48)) }
            add(more, dp(44), dp(48))
            isClickable = true
            isLongClickable = true
            setOnClickListener { if (selected.isNotEmpty()) toggleSelect(p) else open(p.id) }
            setOnLongClickListener { toggleSelect(p); true }
        }
        fun paint(color: Int) {
            val tint = ColorUtils.setAlphaComponent(color, 0x40)
            card.background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(tint, Ink.SURFACE)).apply {
                    cornerRadius = context.dp(24f)
                    setStroke(context.dp(if (isSelected) 2 else 1), if (isSelected) Ink.LAVENDER else Ink.STROKE)
                },
                rounded(0xFFFFFFFF.toInt(), context.dp(24f)),
            )
        }
        paint(if (p.color != 0) p.color else Ink.VIOLET)
        cover.onColor = { paint(it) }
        val wrapper = FrameLayout(context).apply {
            setPadding(0, 0, 0, dp(10))
            addView(card)
        }
        return wrapper to handle
    }

    private fun metaText(p: Playlist): String {
        val count = context.resources.getQuantityString(R.plurals.song_count, p.entries.size, p.entries.size)
        val total = p.totalDurationMs
        return if (total > 0) context.getString(R.string.playlist_meta, count, duration(total)) else count
    }

    private fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        return if (minutes >= 60) context.getString(R.string.duration_hm, minutes / 60, minutes % 60)
        else context.getString(R.string.duration_m, minutes.coerceAtLeast(1))
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            PlaylistSort.values().forEachIndexed { i, s -> menu.add(0, i, i, context.getString(sortLabel(s))) }
            setOnMenuItemClickListener { item ->
                sort = PlaylistSort.values()[item.itemId]
                prefs.edit().putString("sort", sort.name).apply()
                refresh()
                true
            }
        }.show()
    }

    private fun sortLabel(s: PlaylistSort) = when (s) {
        PlaylistSort.MANUAL -> R.string.sort_manual
        PlaylistSort.NAME -> R.string.sort_name
        PlaylistSort.RECENT -> R.string.sort_recent
        PlaylistSort.MOST_PLAYED -> R.string.sort_most_played
    }

    // ---------------------------------------------------------------- selection: mix several playlists

    private fun toggleSelect(p: Playlist) {
        if (!selected.remove(p.id)) selected.add(p.id)
        refresh()
    }

    private fun selectedPlaylists() = playlists.filter { it.id in selected }

    private fun renderSelection() {
        val show = selected.isNotEmpty() && openId == null
        selectionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val chosen = selectedPlaylists()
        val songs = chosen.flatMap { it.entries }.distinctBy { TrackMatcher.key(it.title, it.artist) }.size
        selectionText.text = context.getString(R.string.select_bar, chosen.size, songs)
    }

    private fun playSelected() {
        val chosen = selectedPlaylists()
        if (chosen.sumOf { it.entries.size } == 0) return
        selected.clear()
        refresh()
        HubController.scope.launch { HubController.playMix(chosen) }
        host.open(Tab.PLAYER)
    }

    private fun saveSelected() {
        val chosen = selectedPlaylists()
        val suggestion = chosen.joinToString(" + ") { it.name }.take(40)
        dialogs.promptName(R.string.select_save, suggestion) { name ->
            HubController.scope.launch {
                val id = HubController.playlists.createMixed(name, chosen.map { it.id })
                selected.clear()
                open(id)
            }
        }
    }

    // ---------------------------------------------------------------- detail

    private var shownEntries: List<PlaylistEntry> = emptyList()
    private val rowsById = HashMap<Long, EntryRow>()
    private var nowPlayingViz: VisualizerView? = null

    private fun renderDetail(p: Playlist) {
        detailCover.bind(p)
        detailCover.onColor = { color ->
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ColorUtils.setAlphaComponent(color, 0x55), ColorUtils.setAlphaComponent(color, 0x10)),
            ).apply {
                cornerRadius = context.dp(28f)
                setStroke(context.dp(1), Ink.STROKE)
            }
        }
        detailName.text = p.name
        detailDescription.text = p.description
        detailDescription.visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
        detailMeta.text = metaText(p)
        detailSources.removeAllViews()
        p.sources.forEachIndexed { i, pkg ->
            detailSources.add(sourceTag(pkg), ViewGroup.LayoutParams.WRAP_CONTENT, start = if (i > 0) context.dp(6) else 0)
        }
        renderRepeat()
        renderMissing(p)
        detailEmpty.visibility = if (p.entries.isEmpty()) View.VISIBLE else View.GONE

        if (p.entries != shownEntries) {
            shownEntries = p.entries
            rowsById.clear()
            val rows = p.entries.mapIndexed { i, e -> EntryRow(e, i).also { rowsById[e.id] = it } }
            entryList.setRows(rows)
            rows.forEach { entryList.attachHandle(it, it.handle) }
        }
        highlightCurrent()
    }

    private fun sourceTag(pkg: String?): TextView {
        val text = when (pkg) {
            null -> context.getString(R.string.source_local)
            KnownApps.SPOTIFY -> "Spotify"
            KnownApps.YOUTUBE_MUSIC -> "YouTube Music"
            KnownApps.YOUTUBE -> "YouTube"
            else -> runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        return context.label(text, TextRole.CAPTION, Ink.NIGHT, maxLines = 1).apply {
            background = rounded(Ink.source(pkg), dp(10f))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
    }

    private fun renderRepeat() {
        val mode = state?.repeat ?: RepeatMode.ALL
        repeatButton.setImageResource(if (mode == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.imageTintList = ColorStateList.valueOf(if (mode == RepeatMode.OFF) Ink.FAINT else Ink.LAVENDER)
        repeatButton.contentDescription = context.getString(when (mode) {
            RepeatMode.ALL -> R.string.cd_repeat_all
            RepeatMode.ONE -> R.string.cd_repeat_one
            RepeatMode.OFF -> R.string.cd_repeat_off
        })
    }

    private fun installed(pkg: String): Boolean =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** Songs that cannot play here: files from someone else's phone, or apps that are not installed. */
    private fun unplayable(p: Playlist): List<PlaylistEntry> =
        p.entries.filter { it.isMissing || (it.sourcePackage != null && !installed(it.sourcePackage)) }

    private fun renderMissing(p: Playlist) {
        val missing = unplayable(p)
        missingBanner.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        if (missing.isEmpty()) return
        missingText.text = context.resources.getQuantityString(R.plurals.missing_banner, missing.size, missing.size)
        missingButtons.removeAllViews()
        searchApps().forEachIndexed { i, (pkg, label) ->
            missingButtons.add(context.pill(context.getString(R.string.search_all_in, label), filled = false) {
                assign(missing, pkg, label)
            }, ViewGroup.LayoutParams.WRAP_CONTENT, start = if (i > 0) context.dp(8) else 0)
        }
    }

    private fun searchApps(): List<Pair<String, String>> = listOf(
        KnownApps.SPOTIFY to "Spotify",
        KnownApps.YOUTUBE_MUSIC to "YouTube Music",
    ).filter { installed(it.first) }

    private fun assign(entries: List<PlaylistEntry>, pkg: String, label: String) {
        HubController.scope.launch {
            HubController.playlists.assignSource(entries.map { it.id }, pkg, label)
            host.toast(context.getString(R.string.toast_assigned, entries.size, label))
        }
    }

    private fun highlightCurrent() {
        val p = openPlaylist ?: return
        val session = state?.session
        val currentId = if (session != null && session.playlistId == p.id) session.queue.getOrNull(session.index)?.queueId else null
        nowPlayingViz = null
        rowsById.values.forEach { row ->
            val isCurrent = row.entry.id == currentId
            row.setCurrent(isCurrent)
            if (isCurrent) nowPlayingViz = row.viz
        }
        nowPlayingViz?.setBars(lastBars)
    }

    private fun play(index: Int, shuffled: Boolean) {
        val p = openPlaylist ?: return
        if (p.entries.isEmpty()) return
        HubController.scope.launch { HubController.playPlaylist(p, index, forceShuffle = shuffled) }
    }

    private fun addCurrent() {
        val p = openPlaylist ?: return
        val draft = HubController.currentDraft()
        if (draft == null) {
            host.toast(context.getString(R.string.toast_nothing_playing))
            return
        }
        HubController.scope.launch {
            val added = HubController.playlists.addEntry(p.id, draft)
            host.toast(context.getString(if (added) R.string.toast_added else R.string.toast_already, p.name))
        }
    }

    private fun removeWithUndo(entry: PlaylistEntry) {
        val p = openPlaylist ?: return
        HubController.scope.launch {
            val removed = HubController.playlists.removeEntry(entry.id) ?: return@launch
            showUndo(context.getString(R.string.removed_undo, entry.title)) {
                HubController.scope.launch { HubController.playlists.restoreEntry(p.id, removed.first, removed.second) }
            }
        }
    }

    private fun showUndo(text: String, action: () -> Unit) {
        undoText.text = text
        undoAction = action
        undoBar.visibility = View.VISIBLE
        undoBar.alpha = 0f
        undoBar.animate().alpha(1f).setDuration(150).start()
        view.removeCallbacks(hideUndoRunnable)
        view.postDelayed(hideUndoRunnable, 4_500)
    }

    private fun hideUndo() {
        undoAction = null
        undoBar.visibility = View.GONE
    }

    // ---------------------------------------------------------------- the ⋮ menu

    private fun showMenu(p: Playlist, anchor: View) {
        val items = buildList {
            add(R.string.menu_edit)
            add(R.string.menu_share)
            add(R.string.menu_add_to)
            add(R.string.menu_mix_with)
            add(R.string.menu_copy)
            add(R.string.menu_rename)
            if (!p.isFavorites) add(R.string.menu_delete)
        }
        PopupMenu(context, anchor).apply {
            items.forEachIndexed { i, res -> menu.add(0, res, i, context.getString(res)) }
            setOnMenuItemClickListener { item -> onMenu(p, item.itemId); true }
        }.show()
    }

    private fun onMenu(p: Playlist, action: Int) {
        val store = HubController.playlists
        val others = playlists.filter { it.id != p.id }
        when (action) {
            R.string.menu_edit -> dialogs.edit(p)
            R.string.menu_share -> if (p.entries.isEmpty()) host.toast(context.getString(R.string.share_empty)) else dialogs.share(p)
            R.string.menu_add_to -> dialogs.pickPlaylist(R.string.menu_add_to, others) { target ->
                HubController.scope.launch {
                    val added = store.appendInto(p.id, target.id)
                    host.toast(context.getString(R.string.toast_appended, added, target.name))
                }
            }
            R.string.menu_mix_with -> dialogs.pickPlaylist(R.string.menu_mix_with, others) { other ->
                dialogs.promptName(R.string.menu_mix_with, "${p.name} + ${other.name}".take(40)) { name ->
                    HubController.scope.launch { open(store.createMixed(name, listOf(p.id, other.id))) }
                }
            }
            R.string.menu_copy -> dialogs.promptName(R.string.menu_copy, context.getString(R.string.copy_name, p.name)) { name ->
                HubController.scope.launch {
                    val id = store.copy(p.id, name)
                    host.toast(context.getString(R.string.toast_copied, name))
                    open(id)
                }
            }
            R.string.menu_rename -> dialogs.promptName(R.string.menu_rename, p.name) { name ->
                HubController.scope.launch { store.updateMeta(p.id, p.meta.copy(name = name)) }
            }
            R.string.menu_delete -> AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(context.getString(R.string.playlist_delete_title, p.name))
                .setPositiveButton(R.string.playlist_delete_confirm) { _, _ ->
                    if (openId == p.id) open(null)
                    HubController.scope.launch { store.delete(p.id) }
                }
                .setNegativeButton(R.string.playlist_cancel, null)
                .show()
        }
    }

    // ---------------------------------------------------------------- song rows

    private inner class EntryRow(val entry: PlaylistEntry, private val index: Int) : LinearLayout(context) {
        val handle: ImageView = context.iconButton(R.drawable.ic_drag, context.getString(R.string.cd_reorder), Ink.FAINT) {}
        private val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = rounded(Ink.SURFACE_STRONG, dp(10f))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = context.label(entry.title, TextRole.HEADING, maxLines = 1)
        private val meta = context.label(
            listOf(entry.artist, entry.sourceLabel).filter { it.isNotBlank() }.joinToString(" · "),
            TextRole.CAPTION, Ink.MIST, maxLines = 1,
        )
        val viz = VisualizerView(context).apply {
            style = VisualizerView.Style.BARS
            visibility = View.GONE
            setColors(Ink.EMBER, Ink.LAVENDER)
        }
        private val unplayable = entry.isMissing || (entry.sourcePackage != null && !installed(entry.sourcePackage))

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = pressable(0x00000000, dp(16f))
            setPadding(0, dp(4), dp(4), dp(4))
            add(handle, dp(40), dp(48))
            add(art, dp(46), dp(46))
            val texts = context.column {
                add(title)
                add(meta, top = dp(3))
            }
            add(texts, 0, weight = 1f, start = dp(12))
            add(viz, dp(26), dp(20), start = dp(8))
            if (unplayable) {
                alpha = 0.92f
                title.alpha = 0.55f
                meta.alpha = 0.55f
                art.alpha = 0.5f
                // Search buttons right on the row: play it from Spotify or YouTube Music instead.
                searchApps().filter { it.first != entry.sourcePackage }.forEach { (pkg, label) ->
                    add(TextView(context).apply {
                        text = if (pkg == KnownApps.SPOTIFY) "Spotify" else "YT Music"
                        textSize = 11.5f
                        setTextColor(Ink.NIGHT)
                        background = pressable(Ink.source(pkg), dp(12f))
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        contentDescription = context.getString(R.string.search_in, entry.title, label)
                        setOnClickListener { assign(listOf(entry), pkg, label) }
                    }, ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(6))
                }
            }
            setOnClickListener { play(index, shuffled = false) }
            loadArt()
        }

        fun loadArt() {
            HubController.scope.launch {
                art.setImageBitmap(PlaylistCover.thumbnail(PlaylistCover.artFor(context, entry), context.dp(46)))
            }
        }

        fun setCurrent(current: Boolean) {
            title.setTextColor(if (current) Ink.LAVENDER else Ink.PAPER)
            viz.visibility = if (current) View.VISIBLE else View.GONE
            background = if (current) pressable(Ink.SURFACE, dp(16f), Ink.STROKE, dp(1)) else pressable(0x00000000, dp(16f))
        }
    }
}
