package com.sen0413.ultimatewidget.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.data.EntryDraft
import com.sen0413.ultimatewidget.data.Playlist
import com.sen0413.ultimatewidget.data.RepeatMode
import com.sen0413.ultimatewidget.data.ShareCode
import com.sen0413.ultimatewidget.data.SharedEntry
import com.sen0413.ultimatewidget.data.SharedPlaylist
import com.sen0413.ultimatewidget.data.TrackMatcher
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.chip
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.kit.setChipSelected
import com.sen0413.ultimatewidget.ui.views.ColorPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The dialogs of the Playlists tab: edit, share code, import code and "pick a playlist". */
class PlaylistDialogs(private val host: Host) {
    private val context: Context get() = host.context
    private val store get() = HubController.playlists

    private fun dialog() = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)

    private fun field(hint: Int, text: String = "", multiLine: Boolean = false): EditText = EditText(context).apply {
        setHint(hint)
        setText(text)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (!multiLine) maxLines = 1 else maxLines = 4
        setTextColor(Ink.PAPER)
        setHintTextColor(Ink.FAINT)
    }

    private fun scroll(view: View) = ScrollView(context).apply { addView(view) }

    // ---------------------------------------------------------------- name prompts

    fun promptName(title: Int, initial: String, onName: (String) -> Unit) {
        val input = field(R.string.playlist_name_hint, initial)
        val holder = context.column {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(input)
        }
        dialog().setTitle(title).setView(holder)
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    /** Lets the user choose one of [candidates]. */
    fun pickPlaylist(title: Int, candidates: List<Playlist>, onPick: (Playlist) -> Unit) {
        if (candidates.isEmpty()) {
            host.toast(context.getString(R.string.no_other_playlists))
            return
        }
        dialog().setTitle(title)
            .setItems(candidates.map { context.getString(R.string.pick_row, it.name, it.entries.size) }.toTypedArray()) { _, which ->
                onPick(candidates[which])
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- edit

    fun edit(playlist: Playlist) {
        var meta = playlist.meta
        val name = field(R.string.edit_name, meta.name)
        val description = field(R.string.edit_description, meta.description, multiLine = true)
        val emoji = field(R.string.emoji_hint, meta.emoji).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            textSize = 24f
            gravity = Gravity.CENTER
        }

        val coverAuto = context.chip(context.getString(R.string.cover_auto)) {}
        val coverPhoto = context.chip(context.getString(R.string.cover_photo)) {}
        val coverEmoji = context.chip(context.getString(R.string.cover_emoji)) {}
        val colorAuto = context.chip(context.getString(R.string.color_auto)) {}
        val colorPick = context.chip(context.getString(R.string.color_pick)) {}
        val pin = context.chip(context.getString(R.string.edit_pin)) {}
        val shuffle = context.chip(context.getString(R.string.edit_default_shuffle)) {}
        val repeatChips = RepeatMode.values().associateWith { mode ->
            context.chip(context.getString(when (mode) {
                RepeatMode.ALL -> R.string.repeat_all
                RepeatMode.ONE -> R.string.repeat_one
                RepeatMode.OFF -> R.string.repeat_off
            })) {}
        }
        var pendingPhoto: Uri? = null
        var useEmoji = meta.emoji.isNotEmpty() && meta.coverPath == null

        fun sync() {
            val photo = meta.coverPath != null || pendingPhoto != null
            coverAuto.setChipSelected(!photo && !useEmoji)
            coverPhoto.setChipSelected(photo)
            coverEmoji.setChipSelected(!photo && useEmoji)
            emoji.visibility = if (useEmoji && !photo) View.VISIBLE else View.GONE
            colorAuto.setChipSelected(meta.color == 0)
            colorPick.setChipSelected(meta.color != 0)
            colorPick.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (meta.color != 0) ColorPicker.swatch(context, meta.color).apply { setSize(context.dp(16), context.dp(16)) } else null,
                null, null, null,
            )
            colorPick.compoundDrawablePadding = context.dp(8)
            pin.setChipSelected(meta.pinned)
            shuffle.setChipSelected(meta.defaultShuffle)
            repeatChips.forEach { (mode, chip) -> chip.setChipSelected(meta.defaultRepeat == mode) }
        }
        coverAuto.setOnClickListener { useEmoji = false; pendingPhoto = null; meta = meta.copy(coverPath = null); sync() }
        coverEmoji.setOnClickListener { useEmoji = true; pendingPhoto = null; meta = meta.copy(coverPath = null); sync() }
        coverPhoto.setOnClickListener {
            host.pickImage { uri ->
                pendingPhoto = uri
                useEmoji = false
                sync()
            }
        }
        colorAuto.setOnClickListener { meta = meta.copy(color = 0); sync() }
        colorPick.setOnClickListener {
            ColorPicker.show(context, context.getString(R.string.color_pick), if (meta.color != 0) meta.color else Ink.VIOLET, false) {
                meta = meta.copy(color = it)
                sync()
            }
        }
        pin.setOnClickListener { meta = meta.copy(pinned = !meta.pinned); sync() }
        shuffle.setOnClickListener { meta = meta.copy(defaultShuffle = !meta.defaultShuffle); sync() }
        repeatChips.forEach { (mode, chip) -> chip.setOnClickListener { meta = meta.copy(defaultRepeat = mode); sync() } }

        val body = context.column {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            add(context.label(context.getString(R.string.edit_name), TextRole.LABEL, Ink.MIST))
            add(name)
            add(context.label(context.getString(R.string.edit_description), TextRole.LABEL, Ink.MIST), top = dp(12))
            add(description)
            add(context.label(context.getString(R.string.edit_cover), TextRole.LABEL, Ink.MIST), top = dp(14))
            add(context.row {
                add(coverAuto, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                add(coverPhoto, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
                add(coverEmoji, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
            }, top = dp(6))
            add(emoji, dp(96), top = dp(6))
            add(context.label(context.getString(R.string.edit_color), TextRole.LABEL, Ink.MIST), top = dp(14))
            add(context.row {
                add(colorAuto, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                add(colorPick, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
            }, top = dp(6))
            add(context.label(context.getString(R.string.edit_playback), TextRole.LABEL, Ink.MIST), top = dp(14))
            add(context.row {
                add(pin, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
                add(shuffle, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
            }, top = dp(6))
            add(context.label(context.getString(R.string.edit_default_repeat), TextRole.CAPTION, Ink.MIST), top = dp(10))
            add(context.row {
                RepeatMode.values().forEachIndexed { i, mode ->
                    add(repeatChips.getValue(mode), ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = if (i > 0) dp(8) else 0)
                }
            }, top = dp(6))
        }
        sync()

        dialog().setTitle(R.string.edit_title).setView(scroll(body))
            .setPositiveButton(R.string.editor_save) { _, _ ->
                val finalMeta = meta.copy(
                    name = name.text.toString().trim().ifEmpty { playlist.name },
                    description = description.text.toString(),
                    emoji = if (useEmoji) emoji.text.toString().trim() else "",
                )
                HubController.scope.launch {
                    val photo = pendingPhoto
                    val cover = if (photo != null) saveCover(playlist.id, photo) else finalMeta.coverPath
                    store.updateMeta(playlist.id, finalMeta.copy(coverPath = cover))
                }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    /** Decodes, crops to a square, scales to 600 px and stores the chosen photo. */
    private suspend fun saveCover(playlistId: Long, uri: Uri): String? {
        val jpeg = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 600) sample *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching null
                val side = minOf(bitmap.width, bitmap.height)
                val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                val scaled = if (side > 600) Bitmap.createScaledBitmap(square, 600, 600, true) else square
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            }.getOrNull()
        } ?: run {
            host.toast(context.getString(R.string.cover_failed))
            return null
        }
        return store.saveCover(playlistId, jpeg)
    }

    // ---------------------------------------------------------------- share code

    fun share(playlist: Playlist) {
        val from = field(R.string.share_name_hint, context.getSharedPreferences("share", Context.MODE_PRIVATE).getString("from", "").orEmpty())
        val pin = field(R.string.share_pin_hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val body = context.column {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(context.label(context.getString(R.string.share_body), TextRole.CAPTION, Ink.MIST))
            add(from, top = dp(10))
            add(pin, top = dp(4))
            add(context.label(context.getString(R.string.share_pin_note), TextRole.CAPTION, Ink.FAINT), top = dp(4))
        }
        dialog().setTitle(context.getString(R.string.share_title, playlist.name)).setView(body)
            .setPositiveButton(R.string.share_create) { _, _ ->
                val sender = from.text.toString().trim()
                context.getSharedPreferences("share", Context.MODE_PRIVATE).edit().putString("from", sender).apply()
                val shared = SharedPlaylist(
                    name = playlist.name,
                    description = playlist.description,
                    color = playlist.color,
                    emoji = playlist.emoji,
                    from = sender,
                    entries = playlist.entries.map {
                        // Files on the phone travel as their name only.
                        SharedEntry(it.sourcePackage, it.sourceLabel, it.title, it.artist, it.durationMs, it.mediaId)
                    },
                )
                val code = ShareCode.encode(shared, pin.text.toString().takeIf { it.isNotBlank() })
                showCode(playlist.name, code, protected = pin.text.isNotBlank())
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    private fun showCode(name: String, code: String, protected: Boolean) {
        val qr = ImageView(context).apply {
            contentDescription = context.getString(R.string.cd_qr)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val link = ShareCode.DEEP_LINK + code
        val bitmap = if (link.length <= MAX_QR_CHARS) qrBitmap(link, context.dp(240)) else null
        val codeText = TextView(context).apply {
            text = code
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ink.MIST)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val body = context.column {
            setPadding(dp(24), dp(8), dp(24), 0)
            gravity = Gravity.CENTER_HORIZONTAL
            if (bitmap != null) {
                qr.setImageBitmap(bitmap)
                add(qr, dp(240), dp(240), gravity = Gravity.CENTER_HORIZONTAL)
                add(context.label(context.getString(R.string.share_qr_hint), TextRole.CAPTION, Ink.MIST).apply { gravity = Gravity.CENTER }, top = dp(8))
            } else {
                add(context.label(context.getString(R.string.share_qr_too_long), TextRole.CAPTION, Ink.MIST))
            }
            add(codeText, top = dp(12))
            if (protected) add(context.label(context.getString(R.string.share_pin_reminder), TextRole.CAPTION, Ink.LAVENDER), top = dp(8))
            add(context.row {
                gravity = Gravity.CENTER
                add(context.pill(context.getString(R.string.share_copy), filled = false) {
                    copy(code)
                }, ViewGroup.LayoutParams.WRAP_CONTENT)
                add(context.pill(context.getString(R.string.share_send)) {
                    val message = context.getString(R.string.share_message, name, code, link)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.share_send))) }
                }, ViewGroup.LayoutParams.WRAP_CONTENT, start = dp(10))
            }, top = dp(14))
        }
        dialog().setTitle(R.string.share_code_ready).setView(scroll(body)).setPositiveButton(R.string.done, null).show()
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Playlist code", text))
        host.toast(context.getString(R.string.share_copied))
    }

    private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    // ---------------------------------------------------------------- import

    /** Paste (or receive via link / QR) a code, preview it and import it. */
    fun import(prefilled: String? = null, onImported: (Long) -> Unit) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        val input = field(R.string.import_hint, prefilled ?: "").apply {
            maxLines = 4
            textSize = 13f
        }
        val pin = field(R.string.import_pin_hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            visibility = View.GONE
        }
        val status = context.label("", TextRole.CAPTION, Ink.MIST)
        val paste = context.pill(context.getString(R.string.import_paste), filled = false) {
            input.setText(clip.orEmpty())
        }
        paste.visibility = if (prefilled == null && ShareCode.looksLikeCode(clip)) View.VISIBLE else View.GONE
        val body = context.column {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(paste, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(input, top = dp(6))
            add(pin, top = dp(4))
            add(status, top = dp(8))
        }
        val dialog = dialog().setTitle(R.string.import_title).setView(scroll(body))
            .setPositiveButton(R.string.import_button, null)
            .setNegativeButton(R.string.playlist_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = ShareCode.decode(input.text.toString(), pin.text.toString().takeIf { it.isNotBlank() })) {
                    is ShareCode.Result.Ok -> {
                        dialog.dismiss()
                        importShared(result.playlist, onImported)
                    }
                    ShareCode.Result.NeedsPin -> {
                        pin.visibility = View.VISIBLE
                        pin.requestFocus()
                        status.text = context.getString(R.string.import_needs_pin)
                        status.setTextColor(Ink.LAVENDER)
                    }
                    ShareCode.Result.WrongPin -> {
                        status.text = context.getString(R.string.import_wrong_pin)
                        status.setTextColor(Ink.EMBER)
                    }
                    ShareCode.Result.Invalid -> {
                        status.text = context.getString(R.string.import_invalid)
                        status.setTextColor(Ink.EMBER)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun importShared(shared: SharedPlaylist, onImported: (Long) -> Unit) {
        // Songs that were files on the sender's phone: look for the same song here.
        val library = HubController.library.tracks.value.associateBy { TrackMatcher.key(it.title, it.artist) }
        val localLabel = context.getString(R.string.source_local_file)
        var localTotal = 0
        var localFound = 0
        val drafts = shared.entries.map { e ->
            if (e.sourcePackage == null) {
                localTotal++
                val match = library[TrackMatcher.key(e.title, e.artist)]
                if (match != null) localFound++
                EntryDraft(null, localLabel, e.title, e.artist, match?.uri, e.durationMs)
            } else {
                EntryDraft(e.sourcePackage, e.sourceLabel, e.title, e.artist, null, e.durationMs, e.mediaId)
            }
        }
        val headline = if (shared.from.isNotBlank()) {
            context.getString(R.string.import_preview_from, shared.name, shared.entries.size, shared.from)
        } else {
            context.getString(R.string.import_preview, shared.name, shared.entries.size)
        }
        val detail = buildString {
            if (shared.description.isNotBlank()) append(shared.description).append("\n\n")
            if (localTotal > 0) append(context.getString(R.string.import_local_found, localFound, localTotal))
        }
        dialog().setTitle(headline).setMessage(detail.ifBlank { null })
            .setPositiveButton(R.string.import_button) { _, _ ->
                HubController.scope.launch {
                    val id = store.importShared(shared, drafts)
                    host.toast(context.getString(R.string.import_done, shared.name))
                    onImported(id)
                }
            }
            .setNegativeButton(R.string.playlist_cancel, null)
            .show()
    }

    private companion object {
        /** About what a version 40 QR code holds at the lowest error correction. */
        const val MAX_QR_CHARS = 2_900
    }
}

