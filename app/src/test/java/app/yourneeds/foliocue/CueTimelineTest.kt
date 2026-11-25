package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CueTimelineTest {
    @Test fun sharedPhraseAndWordStartDoesNotMaskSubsequentWords() {
        val timeline = CueTimeline(listOf(Cue("phrase", "a", 50, 950), Cue("first", "a", 50, 220),
            Cue("second", "a", 220, 430), Cue("third", "a", 550, 900)), 0)
        assertEquals(CueAdvance.CONTINUE,timeline.advance(50,false))
        assertEquals("first",timeline.fragmentAt(50))
        assertEquals(CueAdvance.CONTINUE,timeline.advance(260,false))
        assertEquals("second",timeline.fragmentAt(260))
        assertEquals("second", timeline.fragmentAt(500))
    }
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

    @Test fun briefGapKeepsItsMarkUntilTheNextWordButNeverAcrossTracks() {
        val timeline = CueTimeline(listOf(
            Cue("first", "a", 0, 200),
            Cue("second", "a", 240, 360),
            Cue("third", "b", 380, 440),
        ), 0)
        assertEquals(CueAdvance.CONTINUE, timeline.advance(210, false))
        assertEquals("first", timeline.fragmentAt(210))
        assertEquals("first", timeline.fragmentAt(239))
        assertEquals("second", timeline.fragmentAt(240))
        assertNull(timeline.fragmentAt(365))
    }

    @Test fun adjustsTheHighlightWithoutChangingThePlayingCueOrCrossingTracks() {
        val timeline = CueTimeline(listOf(
            Cue("one", "part.mp3", 0, 200),
            Cue("two", "part.mp3", 200, 400),
            Cue("three", "next.mp3", 0, 200),
        ), 0)
        assertEquals("two", timeline.fragmentAt(180, -30))
        assertEquals(0, timeline.index)
        assertEquals(CueAdvance.CONTINUE, timeline.advance(210, false))
        assertEquals("one", timeline.fragmentAt(210, 30))
        assertNull(timeline.fragmentAt(395, -30))
        assertEquals(1, timeline.index)
    }

    @Test fun overlappingWordsSwitchAtTheNextOnsetWithoutSeeking() {
        val timeline = CueTimeline(listOf(
            Cue("first", "same.mp3", 100, 390),
            Cue("second", "same.mp3", 250, 300),
            Cue("third", "same.mp3", 360, 500),
        ), 0)
        assertEquals("first", timeline.fragmentAt(249))
        assertEquals(CueAdvance.CONTINUE, timeline.advance(250, false))
        assertEquals("second", timeline.fragmentAt(250))
        assertEquals("first", timeline.fragmentAt(250, 25))
        assertEquals(CueAdvance.CONTINUE, timeline.advance(330, false))
        assertEquals("second", timeline.fragmentAt(330))
        assertEquals("third", timeline.fragmentAt(360))
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
