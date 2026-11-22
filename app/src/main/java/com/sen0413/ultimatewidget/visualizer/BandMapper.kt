package com.sen0413.ultimatewidget.visualizer

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns one FFT frame from android.media.audiofx.Visualizer into [bandCount] bar heights
 * in 0..1, using logarithmically spaced bands (how we hear pitch) and attack/decay smoothing
 * so the bars rise fast and fall slowly at 15-20 fps. Pure Kotlin, unit-tested.
 *
 * FFT layout (Visualizer.getFft): bytes 0 and 1 are the real parts of DC and Nyquist, then
 * for k = 1 until n/2 the pair (real, imaginary) sits at 2k and 2k+1.
 */
class BandMapper(
    private val bandCount: Int,
    private val minHz: Float = 40f,
    private val maxHz: Float = 16_000f,
    private val attack: Float = 0.65f,
    private val decay: Float = 0.22f,
) {
    private val smoothed = FloatArray(bandCount)

    /** Mean magnitude of the last frame; near zero means silence or no audio access. */
    var lastEnergy: Float = 0f
        private set

    fun map(fft: ByteArray, samplingRateMilliHz: Int): FloatArray {
        val n = fft.size
        val bins = n / 2
        if (bins < 2) return smoothed.copyOf()

        val sampleRate = if (samplingRateMilliHz > 0) samplingRateMilliHz / 1000f else 44_100f
        val top = min(maxHz, sampleRate / 2f)
        val binHz = sampleRate / n
        val raw = FloatArray(bandCount)
        var energy = 0f

        for (band in 0 until bandCount) {
            val fLow = minHz * (top / minHz).pow(band.toFloat() / bandCount)
            val fHigh = minHz * (top / minHz).pow((band + 1).toFloat() / bandCount)
            val kLow = (fLow / binHz).toInt().coerceIn(1, bins - 1)
            val kHigh = (fHigh / binHz).toInt().coerceIn(kLow, bins - 1)
            var sum = 0f
            for (k in kLow..kHigh) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += sqrt(re * re + im * im)
            }
            val magnitude = sum / (kHigh - kLow + 1)
            energy += magnitude
            // Higher bands carry less energy in music; a gentle tilt keeps all bars alive.
            val tilt = 1f + 0.8f * band / (bandCount - 1).coerceAtLeast(1)
            raw[band] = (ln(1f + magnitude * tilt) / ln(1f + 128f)).coerceIn(0f, 1f)
        }
        lastEnergy = energy / bandCount

        for (band in 0 until bandCount) {
            val factor = if (raw[band] > smoothed[band]) attack else decay
            smoothed[band] += (raw[band] - smoothed[band]) * factor
        }
        return smoothed.copyOf()
    }

    fun reset() = smoothed.fill(0f)
}
