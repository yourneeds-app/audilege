package app.yourneeds.foliocue

import android.content.SharedPreferences
import kotlin.math.ln

/** Gains are in dB. Hardware equalizers expose different band centres on different devices. */
internal data class AudiobookTone(val bass: Int = 0, val presence: Int = 0, val treble: Int = 0) {
    init {
        require(bass in -6..6 && presence in -6..6 && treble in -6..6)
    }

    val flat get() = bass == 0 && presence == 0 && treble == 0

    fun gainAt(frequencyHz: Double): Double {
        if (!frequencyHz.isFinite() || frequencyHz <= 0.0) return 0.0
        return when {
            frequencyHz <= 180.0 -> bass.toDouble()
            frequencyHz < 2200.0 -> bass + (presence - bass) * ln(frequencyHz / 180.0) / ln(2200.0 / 180.0)
            frequencyHz < 6500.0 -> presence + (treble - presence) * ln(frequencyHz / 2200.0) / ln(6500.0 / 2200.0)
            else -> treble.toDouble()
        }
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(KEY, "$bass,$presence,$treble").apply()
    }

    companion object {
        const val KEY = "audiobookEqualizer"
        val Off = AudiobookTone()
        val Clear = AudiobookTone(-3, 3, 1)
        val Warm = AudiobookTone(3, -1, -2)
        val Gentle = AudiobookTone(0, 0, -4)

        fun read(prefs: SharedPreferences): AudiobookTone = decode(prefs.getString(KEY, null))

        internal fun decode(stored: String?): AudiobookTone {
            val bands = stored?.split(',') ?: return Off
            if (bands.size != 3) return Off
            val gains = bands.map { it.toIntOrNull()?.takeIf { gain -> gain in -6..6 } ?: return Off }
            return AudiobookTone(gains[0], gains[1], gains[2])
        }
    }
}
