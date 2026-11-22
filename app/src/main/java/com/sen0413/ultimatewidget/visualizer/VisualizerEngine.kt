package com.sen0413.ultimatewidget.visualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sen0413.ultimatewidget.hub.VisualizerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads the real audio spectrum with android.media.audiofx.Visualizer and publishes bar
 * heights. Runs on its own thread: creating an android.media.audiofx.Visualizer is a slow
 * call into the audio system, and doing it on the main thread froze the app for a moment
 * whenever a song started. Capture callbacks arrive on that thread; status changes are
 * handed to the main thread.
 *
 * Sources, in order of preference (chosen by HubController):
 *  1. our own player's audio session (exact),
 *  2. a session another app announced for equalizers,
 *  3. the output mix, session 0 (device-dependent).
 * If none delivers data, the bars stay flat: we never fake movement.
 */
object VisualizerEngine {
    const val BAND_COUNT = 16

    private const val TAG = "VisualizerEngine"
    private const val SILENCE_ENERGY = 1.5f
    private const val SILENCE_TIMEOUT_MS = 4_000L
    private const val MAX_RATE_MILLIHZ = 20_000
    private const val PREFS = "visualizer"
    private const val KEY_GAIN = "gain"

    private val worker: Handler = Handler(HandlerThread("visualizer").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())

    private var visualizer: Visualizer? = null
    private var sessionId: Int? = null
    private val mapper = BandMapper(BAND_COUNT)

    private val _bars = MutableStateFlow(FloatArray(BAND_COUNT))
    val bars: StateFlow<FloatArray> = _bars.asStateFlow()

    /** Frames pushed to the widget per second; lowered to 10 in Battery Saver. */
    @Volatile
    var targetFps: Int = 18

    /** Sensitivity chosen in Setup (0.5..2.5); applied to the band mapper. */
    var gain: Float
        get() = mapper.gain
        set(value) {
            mapper.gain = value
        }

    /** SystemClock.uptimeMillis() of the last bass kick; views pulse with it. */
    @Volatile
    var lastBeatAt: Long = 0L
        private set

    fun loadSettings(context: Context) {
        gain = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_GAIN, 1.3f)
    }

    fun saveGain(context: Context, value: Float) {
        gain = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_GAIN, value).apply()
    }

    var onFrame: ((FloatArray) -> Unit)? = null
    var onStatus: ((VisualizerStatus) -> Unit)? = null

    private var status = VisualizerStatus.IDLE
    private var lastSoundAt = 0L
    private var lastPushAt = 0L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts (or keeps) capturing [audioSessionId]; returns at once, the work runs on the worker. */
    fun attach(context: Context, audioSessionId: Int) {
        val app = context.applicationContext
        worker.removeCallbacksAndMessages(null)
        worker.post { attachNow(app, audioSessionId) }
    }

    private fun attachNow(context: Context, audioSessionId: Int) {
        if (!hasPermission(context)) {
            releaseNow()
            clearBars()
            setStatus(VisualizerStatus.NEEDS_PERMISSION)
            return
        }

        val existing = visualizer
        if (existing != null && sessionId == audioSessionId) {
            if (!existing.getEnabled()) existing.setEnabled(true)
            lastSoundAt = SystemClock.elapsedRealtime()
            setStatus(VisualizerStatus.RUNNING)
            return
        }

        releaseNow()
        try {
            val v = Visualizer(audioSessionId)
            v.setEnabled(false) // capture size can only change while disabled
            val range = Visualizer.getCaptureSizeRange()
            v.setCaptureSize(1024.coerceIn(range[0], range[1]))
            val rate = minOf(Visualizer.getMaxCaptureRate(), MAX_RATE_MILLIHZ)
            val result = v.setDataCaptureListener(captureListener, rate, false, true)
            check(result == Visualizer.SUCCESS) { "setDataCaptureListener returned $result" }
            v.setEnabled(true)
            visualizer = v
            sessionId = audioSessionId
            mapper.reset()
            lastSoundAt = SystemClock.elapsedRealtime()
            setStatus(VisualizerStatus.RUNNING)
        } catch (t: Throwable) {
            // RuntimeException / UnsupportedOperationException when the device refuses the session.
            Log.w(TAG, "Visualizer unavailable for session $audioSessionId", t)
            releaseNow()
            clearBars()
            setStatus(VisualizerStatus.NO_AUDIO)
        }
    }

    /** Paused or screen off: stop capturing, flatten the bars, keep the session for later. */
    fun pause() {
        worker.removeCallbacksAndMessages(null)
        worker.post { pauseNow() }
    }

    private fun pauseNow() {
        runCatching { visualizer?.setEnabled(false) }
        clearBars()
        setStatus(VisualizerStatus.IDLE)
    }

    fun release() {
        worker.post { releaseNow() }
    }

    private fun releaseNow() {
        visualizer?.let { v ->
            runCatching { v.setEnabled(false) }
            runCatching { v.release() }
        }
        visualizer = null
        sessionId = null
    }

    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) = Unit

        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null) return
            val now = SystemClock.elapsedRealtime()
            val bands = mapper.map(fft, samplingRate)
            if (mapper.isBeat) lastBeatAt = SystemClock.uptimeMillis()

            if (mapper.lastEnergy > SILENCE_ENERGY) {
                lastSoundAt = now
                setStatus(VisualizerStatus.RUNNING)
            } else if (now - lastSoundAt > SILENCE_TIMEOUT_MS) {
                setStatus(VisualizerStatus.NO_AUDIO)
            }

            _bars.value = bands
            val frameInterval = 1000L / targetFps.coerceAtLeast(1)
            if (now - lastPushAt >= frameInterval) {
                lastPushAt = now
                onFrame?.invoke(bands)
            }
        }
    }

    private fun clearBars() {
        mapper.reset()
        val empty = FloatArray(BAND_COUNT)
        _bars.value = empty
        onFrame?.invoke(empty)
    }

    private fun setStatus(newStatus: VisualizerStatus) {
        if (newStatus == status) return
        status = newStatus
        main.post { onStatus?.invoke(newStatus) }
    }
}
