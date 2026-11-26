package app.yourneeds.foliocue

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NarrationNavigationTest {
    private val requests = mutableListOf<String>()
    private var offline = false

    private fun publication(withDuration: Boolean = false): Publication {
        val overlays = listOf("", "empty", "spokenA", "", "spokenB", "")
        val items = overlays.mapIndexed { i, id ->
            """<item id="p$i" href="p$i.xhtml" media-type="application/xhtml+xml" media-overlay="$id"/>"""
        }.joinToString("")
        val smils = overlays.filter(String::isNotEmpty).joinToString("") {
            """<item id="$it" href="$it.smil" media-type="application/smil+xml"/>"""
        }
        val spine = overlays.indices.joinToString("") { """<itemref idref="p$it"/>""" }
        val duration = if (withDuration) """<metadata><meta property="media:duration" refines="#spokenB">2s</meta></metadata>""" else ""
        val opf = """<package>$duration<manifest>$items$smils<item id="audio" href="100% Narration.mp3" media-type="audio/mpeg"/></manifest><spine>$spine</spine></package>"""
        fun clips(page: Int) = """<smil><body><par><text src="p$page.xhtml#word"/><audio src="100%25%20Narration.mp3" clipBegin="0" clipEnd="2s"/></par></body></smil>"""
        return Publication("Fixture", { path ->
            requests += path
            when (path) {
                "META-INF/container.xml" -> """<container><rootfile full-path="book.opf"/></container>"""
                "book.opf" -> opf
                "empty.smil" -> "<smil><body/></smil>"
                "spokenA.smil" -> if (offline) throw IOException("Connection interrupted") else clips(2)
                "spokenB.smil" -> clips(4)
                else -> error("Unexpected resource $path")
            }.byteInputStream()
        })
    }

    @Test fun skipsUnvoicedFrontMatterAndInterludesInBothDirections() {
        val book = publication()
        assertFalse(book.mayHaveNarration(0))
        assertTrue(book.mayHaveNarration(1))
        assertEquals(2, book.findNarratedSection(0, 1))
        assertFalse(book.mayHaveNarration(1))
        assertFalse("First play must not preload the back of the book", "spokenB.smil" in requests)
        assertEquals(4, book.findNarratedSection(3, 1))
        assertEquals(2, book.findNarratedSection(3, -1))
        assertNull(book.findNarratedSection(1, -1))
        assertNull(book.findNarratedSection(5, 1))
        assertEquals(1, requests.count { it == "empty.smil" })
    }

    @Test fun transientFailureStopsTheSearchAndTheSameSectionCanBeRetried() {
        val book = publication()
        offline = true
        assertThrows(IOException::class.java) { book.findNarratedSection(0, 1) }
        assertTrue(book.mayHaveNarration(2))
        assertFalse("A network error must not skip a narrated chapter", "spokenB.smil" in requests)
        offline = false
        assertEquals(2, book.findNarratedSection(0, 1))
        assertEquals(2, requests.count { it == "spokenA.smil" })
    }

    @Test fun recognizesSharedAudioInTheNextNarratedChapter() {
        val book = publication()
        val audio = book.cues(book.spine[2]).single().audio
        assertTrue(book.nextSectionUsesAudio(2, audio))
        assertFalse(book.nextSectionUsesAudio(2, "another.mp3"))
        assertFalse(book.nextSectionUsesAudio(4, audio))
    }

    @Test fun remainingAudioUsesKnownDurationsWithoutFetchingUnplayedChapters() {
        assertNull(publication().narrationAfter(2))
        val book = publication(withDuration = true)
        assertEquals(2000L, book.narrationAfter(2))
        assertEquals(0L, book.narrationAfter(4))
        assertFalse(requests.any { it.endsWith(".smil") })
    }

    @Test fun resolvesLiteralAndEncodedFilenamesWithoutRelaxingResourceBoundaries() {
        val base = "OPS/Media Overlays/one.smil"
        val href = "../Audio/100% Track 1.mp3#phrase%201"
        val expected = resource(base, "../Audio/100%25%20Track%201.mp3#phrase%201")
        assertEquals("/OPS/Audio/100% Track 1.mp3", expected?.path)
        assertEquals("phrase 1", expected?.fragment)
        assertEquals(expected, resource(base, href))
        assertNull(resource(base, "../Audio/../%2e%2e/escaped file.mp3"))
        assertNull(resource(base, "//example.org/track one.mp3"))
        assertNull(resource(base, "../Audio/track one.mp3?download=yes"))
    }
}
