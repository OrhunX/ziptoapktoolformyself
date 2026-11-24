package com.sen0413.ultimatewidget.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.PlaylistImporter
import com.sen0413.ultimatewidget.hub.PlaylistImporter.Source
import com.sen0413.ultimatewidget.hub.SpotifyLink
import com.sen0413.ultimatewidget.hub.YouTubeAccount
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.row
import kotlinx.coroutines.launch

/**
 * "Import from Spotify / YouTube Music": pick from your own playlists (after approving
 * Spotify or signing in to YouTube Music), or paste any playlist link.
 */
class ImportDialogs(private val host: Host) {
    private val context get() = host.context

    private fun dialog() = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)

    fun show(onImported: (Long) -> Unit) {
        val choices = arrayOf(
            context.getString(R.string.import_from_spotify),
            context.getString(R.string.import_from_youtube),
            context.getString(R.string.import_from_link),
        )
        dialog().setTitle(R.string.import_apps_title)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> withSpotify { pickMine(Source.SPOTIFY, onImported) }
                    1 -> withYouTube { pickMine(Source.YOUTUBE_MUSIC, onImported) }
                    else -> pasteLink(onImported)
                }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    private fun withSpotify(action: () -> Unit) {
        if (SpotifyLink.playlistToken(context) != null) action() else host.connectSpotify(action)
    }

    private fun withYouTube(action: () -> Unit) {
        if (YouTubeAccount.isSignedIn(context)) action() else host.signInYouTube(action)
    }

    /** The user's own playlists: choose several, import them one after another. */
    private fun pickMine(source: Source, onImported: (Long) -> Unit) {
        val wait = progress(context.getString(R.string.import_loading_lists))
        HubController.scope.launch {
            val lists = runCatching {
                if (source == Source.SPOTIFY) PlaylistImporter.spotifyMine(context) else PlaylistImporter.youTubeMine(context)
            }
            wait.dismiss()
            val mine = lists.getOrNull()
            when {
                lists.exceptionOrNull() is PlaylistImporter.NeedsSpotifyApproval -> host.connectSpotify { pickMine(source, onImported) }
                lists.exceptionOrNull() is PlaylistImporter.NeedsYouTubeSignIn -> host.signInYouTube { pickMine(source, onImported) }
                mine.isNullOrEmpty() -> host.toast(context.getString(R.string.import_no_lists))
                else -> {
                    val checked = BooleanArray(mine.size)
                    val names = mine.map { r ->
                        if (r.count > 0) context.getString(R.string.import_list_item, r.name, r.count) else r.name
                    }.toTypedArray()
                    dialog().setTitle(if (source == Source.SPOTIFY) R.string.import_from_spotify else R.string.import_from_youtube)
                        .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
                        .setPositiveButton(R.string.import_button) { _, _ ->
                            val chosen = mine.filterIndexed { i, _ -> checked[i] }
                            if (chosen.isNotEmpty()) importAll(chosen.map { source to it.id }, onImported)
                        }
                        .setNegativeButton(R.string.playlist_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun pasteLink(onImported: (Long) -> Unit) {
        val clip = runCatching {
            context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        val input = EditText(context).apply {
            hint = context.getString(R.string.import_link_hint)
            setTextColor(Ink.PAPER)
            setHintTextColor(Ink.FAINT)
            maxLines = 3
            if (clip != null && PlaylistImporter.parseLink(clip) != null) setText(clip)
        }
        val body = context.column {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(context.label(context.getString(R.string.import_link_body), TextRole.CAPTION, Ink.MIST))
            add(input, top = dp(8))
        }
        val d = dialog().setTitle(R.string.import_from_link).setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(R.string.import_button, null)
            .setNegativeButton(R.string.playlist_cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = PlaylistImporter.parseLink(input.text.toString())
                if (parsed == null) {
                    host.toast(context.getString(R.string.import_link_invalid))
                } else {
                    d.dismiss()
                    importAll(listOf(parsed), onImported)
                }
            }
        }
        d.show()
    }

    /** Fetches and saves each playlist; asks for approval or sign-in when a playlist needs it. */
    private fun importAll(items: List<Pair<Source, String>>, onImported: (Long) -> Unit) {
        val wait = progress(context.getString(R.string.import_working))
        HubController.scope.launch {
            var lastId: Long? = null
            var done = 0
            for ((index, item) in items.withIndex()) {
                val (source, id) = item
                val fetched = runCatching {
                    if (source == Source.SPOTIFY) PlaylistImporter.spotifyPlaylist(context, id)
                    else PlaylistImporter.youTubePlaylist(context, id)
                }
                val error = fetched.exceptionOrNull()
                if (error is PlaylistImporter.NeedsSpotifyApproval && SpotifyLink.playlistToken(context) == null) {
                    wait.dismiss()
                    host.connectSpotify { importAll(items.drop(index), onImported) }
                    return@launch
                }
                if (error is PlaylistImporter.NeedsYouTubeSignIn) {
                    wait.dismiss()
                    host.toast(context.getString(R.string.import_private))
                    host.signInYouTube { importAll(items.drop(index), onImported) }
                    return@launch
                }
                val playlist = fetched.getOrNull()
                if (playlist == null) {
                    host.toast(context.getString(R.string.import_failed))
                    continue
                }
                val (newId, count) = PlaylistImporter.save(context, HubController.playlists, source, playlist)
                host.toast(context.getString(R.string.import_apps_done, playlist.name, count))
                lastId = newId
                done++
            }
            wait.dismiss()
            if (done > 0) lastId?.let(onImported)
        }
    }

    private fun progress(text: String): AlertDialog = dialog()
        .setView(context.row {
            setPadding(dp(24), dp(20), dp(24), dp(20))
            add(ProgressBar(context), dp(36), dp(36))
            add(context.label(text, TextRole.BODY, Ink.PAPER), ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(16))
        })
        .setCancelable(false)
        .show()
}
