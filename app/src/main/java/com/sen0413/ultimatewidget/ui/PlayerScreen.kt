package com.sen0413.ultimatewidget.ui

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.sen0413.ultimatewidget.R
import com.sen0413.ultimatewidget.hub.HubController
import com.sen0413.ultimatewidget.hub.HubState
import com.sen0413.ultimatewidget.hub.SourceSnapshot
import com.sen0413.ultimatewidget.hub.VisualizerStatus
import com.sen0413.ultimatewidget.ui.kit.Ink
import com.sen0413.ultimatewidget.ui.kit.TextRole
import com.sen0413.ultimatewidget.ui.kit.add
import com.sen0413.ultimatewidget.ui.kit.chip
import com.sen0413.ultimatewidget.ui.kit.column
import com.sen0413.ultimatewidget.ui.kit.dp
import com.sen0413.ultimatewidget.ui.kit.formatTime
import com.sen0413.ultimatewidget.ui.kit.iconButton
import com.sen0413.ultimatewidget.ui.kit.label
import com.sen0413.ultimatewidget.ui.kit.pill
import com.sen0413.ultimatewidget.ui.kit.pressable
import com.sen0413.ultimatewidget.ui.kit.row
import com.sen0413.ultimatewidget.ui.kit.setChipSelected
import com.sen0413.ultimatewidget.ui.views.ArtworkView
import com.sen0413.ultimatewidget.ui.views.PlayPauseView
import com.sen0413.ultimatewidget.ui.views.ScrubberView
import com.sen0413.ultimatewidget.ui.views.VisualizerView
import com.sen0413.ultimatewidget.visualizer.VisualizerEngine
import com.sen0413.ultimatewidget.widget.ThemeMode
import com.sen0413.ultimatewidget.widget.VideoLinks
import com.sen0413.ultimatewidget.widget.WidgetTheme
import kotlinx.coroutines.launch
import kotlin.math.min

/** Now Playing: artwork, a real-audio visualizer, transport and the hub actions. */
class PlayerScreen(host: Host) : Screen(host) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    private var style = runCatching { VisualizerView.Style.valueOf(prefs.getString("style", null) ?: "RING") }
        .getOrDefault(VisualizerView.Style.RING)

    private val sourceChip = context.label("", TextRole.LABEL, Ink.NIGHT).apply {
        gravity = Gravity.CENTER
        minHeight = dp(32)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        setOnClickListener { HubController.switchSource() }
    }
    private val sessionLabel = context.label("", TextRole.CAPTION, Ink.MIST, maxLines = 1).apply {
        gravity = Gravity.END
    }

    private val ring = VisualizerView(context).apply { style = VisualizerView.Style.RING }
    private val art = ArtworkView(context).apply {
        elevation = dp(18f)
        contentDescription = context.getString(R.string.cd_cover)
    }
    private val stage = ArtStage(context, art, ring)

    private val title = context.label("", TextRole.DISPLAY, maxLines = 1)
    private val artist = context.label("", TextRole.BODY, Ink.MIST, maxLines = 1)
    private val heart = context.iconButton(R.drawable.ic_heart_outline, context.getString(R.string.cd_favorite_add)) {
        HubController.scope.launch { HubController.toggleFavorite() }
    }
    private val addButton = context.iconButton(R.drawable.ic_add, context.getString(R.string.cd_add_to_playlist)) {
        val draft = HubController.currentDraft()
        if (draft == null) host.toast(context.getString(R.string.toast_nothing_playing)) else host.pickPlaylistFor(draft)
    }

    private val strip = VisualizerView(context).apply {
        contentDescription = context.getString(R.string.cd_visualizer)
    }
    private val hint = context.label("", TextRole.CAPTION, Ink.MIST).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val scrubber = ScrubberView(context).apply {
        contentDescription = context.getString(R.string.cd_seek)
    }
    private val elapsed = context.label("0:00", TextRole.CAPTION, Ink.MIST)
    private val duration = context.label("0:00", TextRole.CAPTION, Ink.MIST)

    private val playPause = PlayPauseView(context)
    private val styleChips = LinkedHashMap<VisualizerView.Style, TextView>()
    private val videoButton = context.pill(context.getString(R.string.watch_video), filled = false) { openVideo() }

    private var state: HubState? = null
    private var shown = false
    private var artKey: Any? = null
    private var lastSecond = -1L
    private var previewFraction: Float? = null

    override val view: View = context.column {
        setPadding(dp(24), dp(12), dp(24), dp(8))

        add(context.row {
            add(sourceChip, ViewGroup.LayoutParams.WRAP_CONTENT)
            add(sessionLabel, 0, weight = 1f, start = dp(12))
        })

        add(stage, height = 0, weight = 1f, top = dp(8))

        add(context.row {
            add(context.column {
                add(title)
                add(artist, top = dp(4))
            }, 0, weight = 1f)
            add(heart, dp(48), dp(48))
            add(addButton, dp(48), dp(48))
        }, top = dp(8))

        add(strip, height = dp(56), top = dp(10))
        add(hint, top = dp(6))

        add(scrubber, top = dp(6))
        add(context.row {
            add(elapsed, 0, weight = 1f)
            add(duration, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        add(context.row {
            gravity = Gravity.CENTER
            add(context.iconButton(R.drawable.ic_prev, context.getString(R.string.cd_previous), sizeDp = 56) {
                HubController.scope.launch { HubController.previous() }
            }, dp(56), dp(56))
            add(playPause, dp(76), dp(76), start = dp(28))
            add(context.iconButton(R.drawable.ic_next, context.getString(R.string.cd_next), sizeDp = 56) {
                HubController.scope.launch { HubController.next() }
            }, dp(56), dp(56), start = dp(28))
        }, top = dp(2))

        val options = context.row {
            for (s in VisualizerView.Style.values()) {
                val chip = context.chip(context.getString(styleLabel(s))) { selectStyle(s) }
                styleChips[s] = chip
                add(chip, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = if (styleChips.size > 1) dp(8) else 0)
            }
            add(videoButton, ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), start = dp(8))
        }
        videoButton.minHeight = dp(40)
        add(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(options)
        }, top = dp(10))
    }

    init {
        playPause.setOnClickListener { HubController.scope.launch { HubController.playPause() } }
        hint.setOnClickListener { host.requestMicrophone() }
        scrubber.onPreview = { previewFraction = it; updateClock(force = true) }
        scrubber.onSeek = { fraction ->
            val total = state?.current?.track?.durationMs ?: 0L
            if (total > 0) HubController.scope.launch { HubController.seekTo((fraction * total).toLong()) }
        }
        applyStyle()
    }

    private fun styleLabel(s: VisualizerView.Style): Int = when (s) {
        VisualizerView.Style.RING -> R.string.style_ring
        VisualizerView.Style.BURST -> R.string.style_burst
        VisualizerView.Style.BLOB -> R.string.style_blob
        VisualizerView.Style.BARS -> R.string.style_bars
        VisualizerView.Style.MIRROR -> R.string.style_mirror
        VisualizerView.Style.WAVE -> R.string.style_wave
        VisualizerView.Style.PEAKS -> R.string.style_peaks
        VisualizerView.Style.LED -> R.string.style_led
        VisualizerView.Style.SPECTRUM -> R.string.style_spectrum
        VisualizerView.Style.PARTICLES -> R.string.style_particles
    }

    private fun selectStyle(s: VisualizerView.Style) {
        style = s
        prefs.edit().putString("style", s.name).apply()
        applyStyle()
    }

    private fun applyStyle() {
        val ringMode = style.aroundArtwork
        stage.ringMode = ringMode
        ring.visibility = if (ringMode) View.VISIBLE else View.INVISIBLE
        strip.visibility = if (ringMode) View.GONE else View.VISIBLE
        if (ringMode) ring.style = style else strip.style = style
        styleChips.forEach { (s, chip) -> chip.setChipSelected(s == style) }
    }

    override fun render(state: HubState) {
        this.state = state
        val source = state.current
        val track = source.track

        sourceChip.text = source.appLabel
        sourceChip.background = pressable(Ink.source(source.packageName), context.dp(16f))
        sourceChip.contentDescription = context.getString(R.string.cd_source, source.appLabel)
        val session = state.session
        sessionLabel.text = if (session != null) {
            context.getString(R.string.playlist_position, session.name, session.index + 1, session.queue.size)
        } else {
            ""
        }

        val titleText = track?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.nothing_playing)
        title.text = titleText
        artist.text = track?.artist.orEmpty()
        playPause.playing = source.isPlaying
        playPause.contentDescription =
            context.getString(if (source.isPlaying) R.string.cd_pause else R.string.cd_play, titleText)

        heart.setImageResource(if (state.isFavorite) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        heart.imageTintList = android.content.res.ColorStateList.valueOf(if (state.isFavorite) Ink.EMBER else Ink.PAPER)
        heart.contentDescription =
            context.getString(if (state.isFavorite) R.string.cd_favorite_remove else R.string.cd_favorite_add)

        videoButton.visibility = if (VideoLinks.intentFor(context, source) != null) View.VISIBLE else View.GONE
        scrubber.isEnabled = (track?.durationMs ?: 0L) > 0L
        duration.text = formatTime(track?.durationMs ?: 0L)

        renderArtwork(source)
        renderHint(state.visualizer)
        updateClock(force = true)
        if (shown) scheduleFrame()
    }

    private fun renderArtwork(source: SourceSnapshot) {
        val track = source.track
        val key: Any? = track?.art ?: track?.coverRes?.takeIf { it != 0 }
        if (key == artKey && artKey != null) return
        artKey = key
        when {
            track?.art != null -> art.setImageBitmap(track.art)
            track != null && track.coverRes != 0 -> art.setImageResource(track.coverRes)
            else -> {
                art.setImageResource(R.drawable.cover_placeholder)
                artKey = null
            }
        }
        // One palette drives the whole screen: backdrop, visualizer and the play button.
        val colors = WidgetTheme.colors(context, ThemeMode.COVER, source)
        val glow = ColorUtils.blendARGB(colors.background, colors.accent, 0.35f)
        host.ambient.setColors(glow, colors.accent, colors.background)
        val (top, bottom) = VisualizerView.accentPair(colors.accent)
        strip.setColors(top, bottom)
        ring.setColors(top, bottom)
        scrubber.setColor(top)
    }

    private fun renderHint(status: VisualizerStatus) {
        val text = when (status) {
            VisualizerStatus.NEEDS_PERMISSION -> R.string.vis_status_permission
            VisualizerStatus.NO_AUDIO -> R.string.vis_status_no_audio
            else -> 0
        }
        if (text == 0) {
            hint.visibility = View.GONE
        } else {
            hint.visibility = View.VISIBLE
            hint.text = context.getString(text)
            hint.isClickable = status == VisualizerStatus.NEEDS_PERMISSION
            hint.setTextColor(if (status == VisualizerStatus.NEEDS_PERMISSION) Ink.LAVENDER else Ink.MIST)
        }
    }

    override fun onBars(bars: FloatArray) {
        if (!shown) return
        if (style.aroundArtwork) ring.setBars(bars) else strip.setBars(bars)
        scheduleFrame()
    }

    override fun onShown() {
        shown = true
        scheduleFrame()
    }

    override fun onHidden() {
        shown = false
        view.removeCallbacks(frame)
        art.scaleX = 1f
        art.scaleY = 1f
    }

    // ---------------------------------------------------------------- per-frame work

    private var frameScheduled = false
    private val frame = Runnable {
        frameScheduled = false
        if (!shown) return@Runnable
        updateClock(force = false)
        val bass = if (style.aroundArtwork) ring.bassEnergy() else strip.bassEnergy()
        // The cover breathes with the bass and gives a short kick on every beat.
        val since = SystemClock.uptimeMillis() - VisualizerEngine.lastBeatAt
        val kick = if (since in 0..400) kotlin.math.exp(-since / 140f) else 0f
        val scale = 1f + 0.035f * bass + 0.05f * kick
        art.scaleX = scale
        art.scaleY = scale
        host.ambient.targetEnergy = bass
        if (state?.current?.isPlaying == true) scheduleFrame()
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        view.postOnAnimation(frame)
    }

    private fun updateClock(force: Boolean) {
        val source = state?.current ?: return
        val total = source.track?.durationMs ?: 0L
        val preview = previewFraction
        val position = if (preview != null && total > 0) {
            (preview * total).toLong()
        } else {
            source.positionAt(SystemClock.elapsedRealtime()).coerceAtLeast(0L).let { if (total > 0) min(it, total) else it }
        }
        if (total > 0 && preview == null) scrubber.fraction = position.toFloat() / total
        val second = position / 1000L
        if (force || second != lastSecond) {
            lastSecond = second
            elapsed.text = formatTime(position)
        }
    }

    private fun openVideo() {
        val source = state?.current ?: return
        val intent = VideoLinks.intentFor(context, source) ?: return
        runCatching { context.startActivity(intent) }
    }

    /** Lays the artwork out as a centered square and keeps the ring wrapped around it. */
    private class ArtStage(
        context: Context,
        private val art: ArtworkView,
        private val ring: VisualizerView,
    ) : FrameLayout(context) {
        var ringMode: Boolean = true
            set(value) {
                field = value
                requestLayout()
            }

        init {
            clipChildren = false
            clipToPadding = false
            addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val w = right - left
            val h = bottom - top
            val side = (min(w, h) * (if (ringMode) 0.70f else 0.92f)).toInt().coerceAtLeast(0)
            val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            art.measure(exact, exact)
            val l = (w - side) / 2
            val t = (h - side) / 2
            art.layout(l, t, l + side, t + side)
            art.cornerRadius = if (ringMode) side / 2f else dp(28f)
            ring.ringInnerRadius = side / 2f
        }
    }
}
