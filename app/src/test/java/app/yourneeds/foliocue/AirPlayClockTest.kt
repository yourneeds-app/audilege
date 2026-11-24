package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayClockTest {
    @Test fun keepsTheRestoredPositionUntilTheReceiverCanPlayAudio() {
        val clock = AirPlayClock(18_000, 1f, 200L)
        assertEquals(18_000, clock.position(0, 0))
        clock.submitted(4_800, 48_000)
        assertEquals(18_000, clock.position(100, 100))
        assertEquals(18_000, clock.position(150, 0))
        assertEquals(18_000, clock.position(349, 0))
        assertFalse(clock.drained(0))
        assertEquals(18_100, clock.position(350, 0))
        assertTrue(clock.drained(0))
    }

    @Test fun growingQueueCannotWindBackPositionAndUnderrunCannotAdvanceIt() {
        val clock = AirPlayClock(0, 1f, 0L)
        clock.submitted(8_820, 44_100)
        assertEquals(100, clock.position(100, 100))
        clock.submitted(4_410, 44_100)
        assertEquals(100, clock.position(110, 230))
        assertFalse(clock.drained(230))
        assertEquals(300, clock.position(310, 0))
        assertEquals(300, clock.position(10_000, 0))
    }

    @Test fun outputFramesAdvanceTheBookAtItsSelectedReadingSpeed() {
        val clock = AirPlayClock(200, 2f, 0)
        clock.submitted(4_410, 44_100)
        assertEquals(400, clock.position(100, 0))
        clock.submitted(12_000, 48_000)
        assertEquals(800, clock.position(300, 50))
        assertEquals(900, clock.position(350, 0))
    }

    @Test fun newlySubmittedFramesStillNeedToReachTheReceiverAfterDecoderEnd() {
        val clock = AirPlayClock(200, 1f, 220L)
        assertTrue(clock.drained(0))
        clock.submitted(4_800, 48_000)
        assertFalse(clock.drained(0))
        assertEquals(200, clock.position(100, 0))
        assertFalse(clock.drained(0))
        assertEquals(300, clock.position(320, 0))
        assertTrue(clock.drained(0))
    }
}
