package com.sen0413.ultimatewidget.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandMapperTest {
    private val sampleRateMilliHz = 44_100_000
    private val captureSize = 1024

    /** An FFT frame with a single sinusoid at [hz]. */
    private fun tone(hz: Float, magnitude: Int = 120): ByteArray {
        val fft = ByteArray(captureSize)
        val binHz = 44_100f / captureSize
        val bin = (hz / binHz).toInt()
        fft[2 * bin] = magnitude.toByte()
        return fft
    }

    @Test
    fun silenceProducesFlatBarsAndZeroEnergy() {
        val mapper = BandMapper(16)
        val bars = mapper.map(ByteArray(captureSize), sampleRateMilliHz)
        assertTrue(bars.all { it == 0f })
        assertEquals(0f, mapper.lastEnergy, 0f)
    }

    @Test
    fun bassToneLightsOnlyLowBands() {
        val mapper = BandMapper(16, attack = 1f, decay = 1f)
        val bars = mapper.map(tone(100f), sampleRateMilliHz)
        val peak = bars.indices.maxBy { bars[it] }
        assertTrue("peak band was $peak", peak < 4)
        assertEquals(0f, bars.last(), 0f)
    }

    @Test
    fun trebleToneLightsHighBands() {
        val mapper = BandMapper(16, attack = 1f, decay = 1f)
        val bars = mapper.map(tone(8_000f), sampleRateMilliHz)
        val peak = bars.indices.maxBy { bars[it] }
        assertTrue("peak band was $peak", peak > 10)
        assertEquals(0f, bars.first(), 0f)
    }

    @Test
    fun barsRiseFastAndFallSlowly() {
        val mapper = BandMapper(16)
        val loud = tone(1_000f)
        val first = mapper.map(loud, sampleRateMilliHz)
        repeat(10) { mapper.map(loud, sampleRateMilliHz) }
        val settled = mapper.map(loud, sampleRateMilliHz)
        val peakBand = settled.indices.maxBy { settled[it] }
        val afterSilence = mapper.map(ByteArray(captureSize), sampleRateMilliHz)

        assertTrue("attack too slow", first[peakBand] > 0.6f * settled[peakBand])
        assertTrue("decay too fast", afterSilence[peakBand] > 0.5f * settled[peakBand])
        assertTrue("bars must fall", afterSilence[peakBand] < settled[peakBand])
    }

    @Test
    fun aSuddenBassKickIsABeatButASteadyToneIsNot() {
        val mapper = BandMapper(16)
        repeat(6) { mapper.map(ByteArray(captureSize), sampleRateMilliHz) }
        mapper.map(tone(60f), sampleRateMilliHz)
        assertTrue("kick after silence", mapper.isBeat)
        repeat(12) { mapper.map(tone(60f), sampleRateMilliHz) }
        assertTrue("steady bass is not a beat", !mapper.isBeat)
    }

    @Test
    fun unknownSampleRateFallsBackTo44k() {
        val mapper = BandMapper(16, attack = 1f, decay = 1f)
        val withRate = mapper.map(tone(2_000f), sampleRateMilliHz)
        val fallbackMapper = BandMapper(16, attack = 1f, decay = 1f)
        val withoutRate = fallbackMapper.map(tone(2_000f), 0)
        assertTrue(withRate.contentEquals(withoutRate))
    }
}
