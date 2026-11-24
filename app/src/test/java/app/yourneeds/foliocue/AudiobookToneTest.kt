package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AudiobookToneTest {
    @Test fun storedCustomValuesAndPresetsFallBackSafely() {
        assertEquals(AudiobookTone(-6, 2, 6), AudiobookTone.decode("-6,2,6"))
        assertEquals(AudiobookTone.Clear, AudiobookTone.decode("-3,3,1"))
        for (invalid in listOf(null, "", "0,3", "bass,0,1", "9,0,1", "1,2,3,4")) {
            assertTrue(AudiobookTone.decode(invalid).flat)
        }
    }

    @Test fun hardwareBandsInterpolateBetweenControlsInLogFrequency() {
        val tone = AudiobookTone(-4, 4, 0)
        assertEquals(-4.0, tone.gainAt(60.0), 0.0)
        assertEquals(0.0, tone.gainAt(sqrt(180.0 * 2200.0)), 1e-8)
        assertEquals(4.0, tone.gainAt(2200.0), 0.0)
        assertEquals(2.0, tone.gainAt(sqrt(2200.0 * 6500.0)), 1e-8)
        assertEquals(0.0, tone.gainAt(14000.0), 0.0)
        assertEquals(0.0, tone.gainAt(Double.NaN), 0.0)
        assertFalse(tone.flat)
    }
}
