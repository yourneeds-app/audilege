package app.yourneeds.foliocue

import org.junit.Assert.*
import org.junit.Test

class ReaderToolsTest {
    private fun page(body: String) = xml("""<html xmlns="http://www.w3.org/1999/xhtml" lang="en">
        <head><title>Not searchable</title></head><body>$body</body></html>""".byteInputStream())

    @Test fun findsTextAcrossWordSpansAndReturnsCharacterOffsetForEachOccurrence() {
        val root = page("<p><span id='w1'>First river</span> <i id='w2'>stone</i> and river stone.</p>")
        val hits = searchChapter(root, 4, "RIVER   stone")
        assertEquals(2, hits.size)
        assertEquals("/4/2/2/1:6", hits.first().cfi)
        assertEquals("/4/2/5:5", hits.last().cfi)
        assertEquals(4, hits.first().chapter)
        assertEquals("w1", narrationAnchor(root, hits.first().cfi!!, setOf("w1", "w2")))
    }

    @Test fun respectsParagraphBoundariesAndLimitAndSkipsBookTitle() {
        val root = page("<p>first river</p><p>stone river river</p>")
        assertTrue(searchChapter(root, 0, "river stone").isEmpty())
        assertTrue(searchChapter(root, 0, "searchable").isEmpty())
        assertEquals(2, searchChapter(root, 0, "river", limit = 2).size)
    }

    @Test fun sentenceReplayLocatesFirstTimedWordOfCurrentSentence() {
        val root = page("<p><span id='one'>Wind.</span> <span id='two'>A</span> <span id='three'>bell</span> rang.</p>")
        val ids = setOf("one", "two", "three")
        assertEquals("two", firstCueOfSentence(root, "three", ids))
        assertEquals("one", firstCueOfSentence(root, "one", ids))
        assertNull(firstCueOfSentence(root, "unknown", ids))
    }

    @Test fun resolvesTextOnlyCfiToNarrationInsideAnUntimedParent() {
        val root = page("<p><span>Bridge</span> <span id='s1'>water</span></p>")
        assertEquals("s1", narrationAnchor(root, "/4/2/2/1:2", setOf("s1")))
        assertNull(narrationAnchor(root, "/4/999/1:0", setOf("s1")))
    }
}
