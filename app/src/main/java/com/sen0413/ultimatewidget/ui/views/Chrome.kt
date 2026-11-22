package com.sen0413.ultimatewidget.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.ui.Tab
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pressable
import com.sen0413.ultimatewidget.ui.kit.rounded

/** Bottom navigation: four destinations, the selected one gets a pill behind its icon. */
class NavBar(context: Context, private val onSelect: (Tab) -> Unit) : LinearLayout(context) {
    private class Item(val tab: Tab, val icon: ImageView, val text: TextView)

    private val items = ArrayList<Item>()

    init {
        orientation = HORIZONTAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addItem(Tab.PLAYER, R.drawable.ic_equalizer, R.string.tab_player)
        addItem(Tab.LIBRARY, R.drawable.ic_note, R.string.tab_library)
        addItem(Tab.PLAYLISTS, R.drawable.ic_playlist, R.string.tab_playlists)
        addItem(Tab.SETTINGS, R.drawable.ic_tune, R.string.tab_settings)
    }

    private fun addItem(tab: Tab, iconRes: Int, labelRes: Int) {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val text = context.label(context.getString(labelRes), TextRole.CAPTION)
        val cell = context.column {
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(56)
            background = pressable(Color.TRANSPARENT, dp(16f))
            contentDescription = context.getString(labelRes)
            isClickable = true
            isFocusable = true
            add(icon, dp(64), dp(32), gravity = Gravity.CENTER_HORIZONTAL)
            add(text, ViewGroup.LayoutParams.WRAP_CONTENT, top = dp(4), gravity = Gravity.CENTER_HORIZONTAL)
            setOnClickListener { onSelect(tab) }
        }
        items += Item(tab, icon, text)
        addView(cell, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun select(tab: Tab) {
        for (item in items) {
            val selected = item.tab == tab
            item.icon.imageTintList = ColorStateList.valueOf(if (selected) Ink.NIGHT else Ink.MIST)
            item.icon.background = if (selected) rounded(Ink.LAVENDER, dp(16f)) else null
            item.text.setTextColor(if (selected) Ink.PAPER else Ink.FAINT)
        }
    }
}

/** A compact now-playing bar shown above the navigation on every tab except the player. */
class MiniPlayer(
    context: Context,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
) : LinearLayout(context) {
    private val art = ArtworkView(context).apply { cornerRadius = dp(10f) }
    private val title = context.label("", TextRole.LABEL, maxLines = 1)
    private val subtitle = context.label("", TextRole.CAPTION, Ink.MIST, maxLines = 1)
    private val toggle = context.iconButton(R.drawable.ic_play, "", onClick = onPlayPause)
    private var artKey: Any? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(Ink.SURFACE_STRONG, dp(20f), Ink.STROKE, dp(1))
        setPadding(dp(10), dp(8), dp(6), dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onOpen() }
        add(art, dp(44), dp(44))
        add(context.column {
            add(title)
            add(subtitle, top = dp(2))
        }, 0, weight = 1f, start = dp(12))
        add(toggle, dp(48), dp(48))
    }

    fun render(state: HubState) {
        val source = state.current
        val track = source.track
        val name = track?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.nothing_playing)
        title.text = name
        subtitle.text = listOf(track?.artist.orEmpty(), source.appLabel).filter { it.isNotBlank() }.joinToString(" · ")
        toggle.setImageResource(if (source.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription =
            context.getString(if (source.isPlaying) R.string.cd_pause else R.string.cd_play, name)

        val key: Any? = track?.art ?: track?.coverRes?.takeIf { it != 0 }
        if (key != artKey || key == null) {
            artKey = key
            when {
                track?.art != null -> art.setImageBitmap(track.art)
                track != null && track.coverRes != 0 -> art.setImageResource(track.coverRes)
                else -> art.setImageResource(R.drawable.cover_placeholder)
            }
        }
    }
}
