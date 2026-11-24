package app.yourneeds.foliocue

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterClockTest {
    @Test fun commonAudioFileUsesChapterDurationAndPreservesSilence() {
        val clock = ChapterClock(listOf(Cue("a","one", 8000,8100), Cue("b","one", 8400,8600)))
        assertEquals(600, clock.duration)
        assertEquals(250,clock.elapsed(0,8250))
        assertEquals(ChapterPoint(0,8250),clock.point(250))
        assertEquals(ChapterPoint(1,8450),clock.point(450))
    }

    @Test fun splitFilesAndReusedFileWithBackwardsClockCanBeScrubbed() {
        val clock = ChapterClock(listOf(Cue("a","one", 1000,2000), Cue("b","two", 100,1100),
            Cue("c","two", 0,500)))
        assertEquals(2500,clock.duration)
        assertEquals(ChapterPoint(1,100),clock.point(1000))
        assertEquals(ChapterPoint(2,150),clock.point(2150))
        assertEquals(2150,clock.elapsed(2,150))
        assertEquals(ChapterPoint(0,1000),clock.point(-20))
        assertEquals(ChapterPoint(2,499),clock.point(9000))
    }
}
