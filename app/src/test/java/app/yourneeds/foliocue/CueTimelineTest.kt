package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueTimelineTest {
    @Test fun sameTrackGapDoesNotSeekOrShowNextWordEarly() {
        val timeline = CueTimeline(listOf(
            Cue("one", "part.mp3", 10, 190),
            Cue("two", "part.mp3", 610, 640),
            Cue("three", "part.mp3", 650, 900),
        ), 0)

        assertEquals(CueAdvance.CONTINUE, timeline.advance(200, false))
        assertNull(timeline.fragmentAt(200))
        assertEquals(CueAdvance.CONTINUE, timeline.advance(609, false))
        assertNull(timeline.fragmentAt(609))
        assertEquals(CueAdvance.CONTINUE, timeline.advance(610, false))
        assertEquals("two", timeline.fragmentAt(610))
        assertEquals(CueAdvance.CONTINUE, timeline.advance(650, false))
        assertEquals("three", timeline.fragmentAt(650))
    }

    @Test fun seeksOnBackwardsClipsAndSwitchesAtTheEndOfAnAudioPart() {
        val timeline = CueTimeline(listOf(
            Cue("one", "part.mp3", 600, 800),
            Cue("two", "part.mp3", 20, 210),
            Cue("three", "next.mp3", 0, 300),
        ), 0)

        assertEquals(CueAdvance.SEEK, timeline.advance(800, false))
        assertEquals("two", timeline.fragmentAt(20))
        assertEquals(CueAdvance.NEXT_TRACK, timeline.advance(210, false))
        assertEquals("next.mp3", timeline.cue.audio)
        assertEquals(CueAdvance.FINISHED, timeline.advance(300, false))
    }
}
