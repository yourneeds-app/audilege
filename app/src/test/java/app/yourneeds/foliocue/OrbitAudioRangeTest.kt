package app.yourneeds.foliocue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitAudioRangeTest {
    @Test fun acceptsTheRequestedOffsetWithKnownOrUnknownResourceLength() {
        assertTrue(validAudioRange("bytes 512-2047/2048", 512))
        assertTrue(validAudioRange("bytes 512-2047/*", 512))
    }

    @Test fun rejectsWrongOffsetsAndImpossibleOrMissingRangeHeaders() {
        assertFalse(validAudioRange("bytes 0-1023/2048", 512))
        assertFalse(validAudioRange("bytes 512-511/2048", 512))
        assertFalse(validAudioRange("bytes 512-2048/2048", 512))
        assertFalse(validAudioRange("bytes */2048", 512))
        assertFalse(validAudioRange(null, 512))
    }
}
