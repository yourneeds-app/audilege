package app.yourneeds.foliocue

import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import kotlin.math.roundToInt

/** Applies the requested curve to the bands offered by this MediaPlayer session. */
internal class LocalPlaybackEqualizer(private val onUnavailable: (Boolean) -> Unit) {
    private var effect: AudioEffect? = null
    private var tone = AudiobookTone()

    fun apply(player: MediaPlayer, requested: AudiobookTone) {
        tone = requested
        if (tone.flat) {
            close()
            return
        }
        try {
            // Session zero is the device-wide output mix and must never be used here.
            val sessionId = player.audioSessionId
            require(sessionId > 0)
            val existing = effect
            if (existing is DynamicsProcessing) configure(existing, player)
            else {
                try {
                    configure(existing ?: Equalizer(0, sessionId), player)
                } catch (_: RuntimeException) {
                    releaseEffect()
                    // Several Android audio stacks provide DynamicsProcessing but no usable Equalizer.
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, BAND_CENTRES.size, false, 0, false, 0, false,
                    ).build()
                    configure(DynamicsProcessing(0, sessionId, config), player)
                }
            }
        } catch (_: RuntimeException) {
            // Some devices omit the effect or reject it on particular outputs.
            releaseEffect()
            onUnavailable(true)
        }
    }

    private fun configure(current: AudioEffect, player: MediaPlayer) {
        if (effect !== current) {
            effect = current
            current.setControlStatusListener { changed, hasControl ->
                if (effect === changed) {
                    if (hasControl) apply(player, tone) else onUnavailable(true)
                }
            }
        }
        if (!current.hasControl()) {
            onUnavailable(true)
            return
        }
        when (current) {
            is Equalizer -> {
                val range = current.bandLevelRange
                val bands = current.numberOfBands.toInt()
                require(bands > 0 && range.size >= 2 && range[0] < range[1])
                for (index in 0 until bands) {
                    val band = index.toShort()
                    val hz = current.getCenterFreq(band) / 1000.0
                    require(hz > 0)
                    val millibels = (tone.gainAt(hz) * 100).roundToInt()
                    current.setBandLevel(band, millibels.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
            }
            is DynamicsProcessing -> {
                val bands = DynamicsProcessing.Eq(true, true, BAND_CENTRES.size)
                for (index in BAND_CENTRES.indices) {
                    val gain = tone.gainAt(BAND_CENTRES[index]).toFloat()
                    bands.setBand(index, DynamicsProcessing.EqBand(true, BAND_CUTOFFS[index], gain))
                }
                current.setPreEqAllChannelsTo(bands)
            }
        }
        check(current.setEnabled(true) == AudioEffect.SUCCESS)
        onUnavailable(false)
    }

    private fun releaseEffect() {
        val old = effect ?: return
        effect = null
        try {
            old.setControlStatusListener(null)
            if (old.hasControl()) old.setEnabled(false)
        } catch (_: RuntimeException) {
            // The audio server may already have dropped the effect.
        } finally {
            try { old.release() } catch (_: RuntimeException) { }
        }
    }

    fun close() {
        releaseEffect()
        onUnavailable(false)
    }

    private companion object {
        val BAND_CENTRES = doubleArrayOf(180.0, 630.0, 2200.0, 3800.0, 6500.0)
        val BAND_CUTOFFS = floatArrayOf(340f, 1180f, 2890f, 5000f, 20000f)
    }
}
