package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject

class AudioResumeTest {
    @Test fun localAudioWithinALongCueKeepsItsMilliseconds() {
        assertEquals(45_125, matchingAudioElapsed(45_125, "sentence", "sentence", null))
        assertEquals(45_125, matchingAudioElapsed(45_125, null, null, null))
        assertEquals(45_125, resumeAfterPauseMs(45_125, 0, 0))
    }

    @Test fun differentNarrationAndNewerTextKeepTheirOwnPosition() {
        assertNull(matchingAudioElapsed(45_125, "previous", "different", null))
        assertNull(matchingAudioElapsed(45_125, "previous", null, "/4/2/1:12"))
        assertNull(matchingAudioElapsed(-1, null, null, null))
    }

    private fun mark(anchor: String, seconds: Double, percent: Double) = JSONObject()
        .put("mediaOverlayFragment", "text.xhtml#$anchor").put("mediaOverlaySectionIndex", 0)
        .put("positionSeconds", seconds).put("percentage", percent)

    @Test fun sameCueMovedOnServerKeepsRemoteTimeButUnchangedServerAllowsOfflineAdvance() {
        val local = mark("one", 80.0, 45.0).put("source", "narration")
        val previous = mark("one", 80.0, 40.0)
        assertTrue(canRestoreLocalAudio(previous, local, previous))
        assertTrue(canRestoreLocalAudio(mark("one", 80.0, 45.0), local, previous))
        assertFalse(canRestoreLocalAudio(mark("one", 95.0, 45.0), local, previous))
        assertFalse(canRestoreLocalAudio(mark("one", 80.0, 40.0).put("narrationPercentage", 56.0), local, previous))
        assertFalse(canRestoreLocalAudio(mark("two", 80.0, 45.0), local, previous))
        assertEquals(65_250, elapsedWithinCue(95.25, 80.0, 20_000, 50_000))
        assertNull(elapsedWithinCue(120.0, 80.0, 20_000, 50_000))
        assertNull(elapsedWithinCue(95.25, null, 20_000, 50_000))
    }

    @Test fun oneRewindPerPassageWithNoJumpBeforeChapterStart() {
        assertEquals(40_125, resumeAfterPauseMs(45_125, 0, 5))
        assertEquals(40_125, resumeAfterPauseMs(40_125, 45_125, 5))
        assertEquals(40_125, resumeAfterPauseMs(41_000, 45_125, 5))
        assertEquals(41_000, resumeAfterPauseMs(41_000, 45_125, 0))
        assertEquals(50_000, resumeAfterPauseMs(55_000, 45_125, 5))
        assertEquals(0, resumeAfterPauseMs(750, 0, 30))
    }
}
