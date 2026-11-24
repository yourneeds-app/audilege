package app.yourneeds.foliocue

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Three complementary bands. The filters run at the decoded sample rate, before the sender's resampler. */
internal class AirPlayToneFilter(sampleRate: Int) {
    private class LowPass(cutoff: Double, sampleRate: Int) {
        private val k = tan(PI * cutoff.coerceAtMost(sampleRate * 0.45) / sampleRate)
        private val feed = k / (1.0 + k)
        private val feedback = (1.0 - k) / (1.0 + k)
        private val memory = DoubleArray(2)

        fun sample(input: Double, channel: Int): Double {
            val output = feed * input + memory[channel]
            memory[channel] = feed * input + feedback * output
            return output
        }

        fun clear() = memory.fill(0.0)
    }

    private val bassFilter = LowPass(600.0, sampleRate)
    private val trebleCut = LowPass(4_300.0, sampleRate)
    private val gain = DoubleArray(3) { 1.0 }
    private val target = DoubleArray(3) { 1.0 }
    private val step = DoubleArray(3)
    private val rampFrames = (sampleRate / 80).coerceAtLeast(1) // 12.5 ms
    private var remaining = 0
    private var tone = AudiobookTone()

    fun apply(samples: ShortArray, requested: AudiobookTone) {
        if (requested != tone) {
            if (tone.flat && remaining == 0) {
                bassFilter.clear()
                trebleCut.clear()
            }
            tone = requested
            target[0] = linear(tone.bass)
            target[1] = linear(tone.presence)
            target[2] = linear(tone.treble)
            for (band in gain.indices) step[band] = (target[band] - gain[band]) / rampFrames
            remaining = rampFrames
        }
        for (left in samples.indices step 2) {
            if (remaining == 0 && tone.flat) return
            if (remaining > 0) {
                for (band in gain.indices) gain[band] += step[band]
                if (--remaining == 0) {
                    target.copyInto(gain)
                    if (tone.flat) return
                }
            }
            val l = shape(samples[left].toDouble(), 0)
            val r = shape(samples[left + 1].toDouble(), 1)
            // A soft knee above -1 dB catches boosted peaks. One gain for both channels keeps stereo centered.
            val peak = maxOf(abs(l), abs(r))
            val knee = 29_200.0
            val scale = if (peak <= knee) 1.0 else {
                val headroom = Short.MAX_VALUE - knee
                val over = peak - knee
                (knee + headroom * over / (headroom + over)) / peak
            }
            samples[left] = (l * scale).roundToInt().toShort()
            samples[left + 1] = (r * scale).roundToInt().toShort()
        }
    }

    private fun shape(input: Double, channel: Int): Double {
        val low = bassFilter.sample(input, channel)
        val lowMid = trebleCut.sample(input, channel)
        return low * gain[0] + (lowMid - low) * gain[1] + (input - lowMid) * gain[2]
    }

    private fun linear(db: Int) = 10.0.pow(db.coerceIn(-6, 6) / 20.0)
}
