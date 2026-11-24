package app.yourneeds.foliocue

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class AirPlayToneFilterTest {
    private fun response(rate: Int, hz: Double, tone: AudiobookTone): Double {
        val data = ShortArray(rate / 5 * 2) { index ->
            if (index % 2 == 0) (4_000 * sin(2 * PI * hz * (index / 2) / rate)).roundToInt().toShort() else 0
        }
        val input = data.copyOf()
        AirPlayToneFilter(rate).apply(data, tone)
        var dryPower = 0.0
        var wetPower = 0.0
        for (i in data.size / 2 until data.size step 2) {
            dryPower += input[i].toDouble() * input[i]
            wetPower += data[i].toDouble() * data[i]
            assertEquals("No right-channel leakage", 0, data[i + 1].toInt())
        }
        return sqrt(wetPower / dryPower)
    }

    @Test fun eachBandChangesTheRequestedFrequencyRange() {
        for (rate in listOf(44_100, 48_000)) {
            val bass = AudiobookTone(bass = -6)
            assertTrue(response(rate, 100.0, bass) < 0.55)
            assertTrue(response(rate, 5_000.0, bass) > 0.96)
            val speech = AudiobookTone(presence = 6)
            assertTrue(response(rate, 2_200.0, speech) > 1.65)
            assertTrue(response(rate, 100.0, speech) < 1.08)
            assertTrue(response(rate, 14_000.0, speech) < 1.18)
            val treble = AudiobookTone(treble = 6)
            assertTrue(response(rate, 10_000.0, treble) > 1.8)
            assertTrue(response(rate, 250.0, treble) < 1.02)
        }
    }

    @Test fun bufferBoundariesPreserveTheWaveformAndSwitchingOffRestoresOriginalSamples() {
        val dry = ShortArray(8_192) { index -> (13_000 * sin((index / 2) * 0.34)).toInt().toShort() }
        val expected = dry.copyOf()
        val tone = AudiobookTone(-3, 4, -2)
        AirPlayToneFilter(48_000).apply(expected, tone)
        val actual = dry.copyOf()
        val filter = AirPlayToneFilter(48_000)
        for (offset in actual.indices step 278) {
            val block = dry.copyOfRange(offset, minOf(offset + 278, dry.size))
            filter.apply(block, tone)
            block.copyInto(actual, offset)
        }
        assertArrayEquals(expected, actual)
        val transition = dry.copyOf()
        filter.apply(transition, AudiobookTone())
        assertArrayEquals(dry.copyOfRange(1_200, dry.size), transition.copyOfRange(1_200, dry.size))
        val fullScale = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        filter.apply(fullScale, AudiobookTone())
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), fullScale)
    }

    @Test fun boostedPeaksKeepTheirPolarityAndStereoBalance() {
        val data = ShortArray(4_096) { if (it % 2 == 0) 32_000 else -16_000 }
        AirPlayToneFilter(44_100).apply(data, AudiobookTone(6, 6, 6))
        for (i in data.size / 2 until data.size step 2) {
            assertTrue(data[i].toInt() in 32_000..32_767)
            assertTrue(abs(data[i] + 2 * data[i + 1]) <= 1)
        }
    }
}
