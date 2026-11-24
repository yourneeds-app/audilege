package app.yourneeds.foliocue

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicationsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun parsesClockValues() {
        assertEquals(62456, clock("npt=01:02.456"))
        assertEquals(125, clock("125ms"))
        assertEquals(90000, clock("1.5min"))
        assertEquals(200, clock("0.2s"))
        assertNull(clock("NaN"))
        assertNull(clock("-4s"))
    }

    @Test fun resolvesEncodedFragmentsAndBlocksForeignResources() {
        assertEquals("short-word", resource("OPS/smil/one.smil", "../text/one.xhtml#short%2Dword")?.fragment)
        assertNull(resource("OPS/chapter.xhtml", "https://example.org/audio.mp3"))
        assertNull(resource("OPS/chapter.xhtml", "%2e%2e/audio.mp3"))
    }

    @Test fun rejectsXmlEntities() {
        assertThrows(IllegalArgumentException::class.java) {
            xml("""<!DOCTYPE x [<!ENTITY local SYSTEM "file:///secret">]><x>&local;</x>""".byteInputStream())
        }
    }

    @Test fun parsesSpineContentsAndVeryShortWords() {
        val file = temporary.newFile("fictional.epub")
        val entries = mapOf(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fictional test book</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="first" href="text/one.xhtml" media-type="application/xhtml+xml" media-overlay="sync"/>
                    <item id="sync" href="smil/one.smil" media-type="application/smil+xml"/>
                    <item id="audio" href="audio/one.mp3" media-type="audio/mpeg"/>
                  </manifest>
                  <spine><itemref idref="first"/></spine>
                </package>
            """.trimIndent(),
            "OPS/nav.xhtml" to """
                <!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                 <body><nav epub:type="toc"><a href="text/one.xhtml#begin">Chapter one</a></nav></body></html>
            """.trimIndent(),
            "OPS/smil/one.smil" to """
                <smil xmlns="http://www.w3.org/ns/SMIL"><body><seq>
                    <par><text src="../text/one.xhtml#the"/><audio src="../audio/one.mp3" clipBegin="0.050s" clipEnd="0.090s"/></par>
                    <par><text src="../text/one.xhtml#book"/><audio src="../audio/one.mp3" clipBegin="0.090s" clipEnd="0.800s"/></par>
                </seq></body></smil>
            """.trimIndent(),
            "OPS/text/one.xhtml" to """<html><body><p id="begin"><span id="the">The</span> <span id="book">book</span>.</p></body></html>""",
            "OPS/audio/one.mp3" to "Placeholder for a fictional parser test.",
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val book = Publication(file)
        assertEquals("Fictional test book", book.title)
        assertEquals(Section("OPS/text/one.xhtml", "Chapter one", "begin"), book.contents.single())
        val cues = book.cues(book.spine.single())
        assertEquals(listOf("the", "book"), cues.map { it.fragment })
        assertEquals(listOf(40, 710), cues.map { it.end - it.start })

        val requested = mutableListOf<String>()
        val online = Publication("Fallback", { path ->
            requested += path
            entries[path]?.byteInputStream()
        })
        assertEquals(listOf("META-INF/container.xml", "OPS/book.opf", "OPS/nav.xhtml"), requested)
        assertEquals(cues, online.cues(online.spine.single()))
        assertEquals("OPS/smil/one.smil", requested.last())
        assertEquals(null, requested.firstOrNull { it.endsWith(".mp3") || it.endsWith(".epub") })
        assertEquals(cues, online.cues(online.spine.single()))
        assertEquals(1, requested.count { it == "OPS/smil/one.smil" })

        val payload = online.progressPayload(0, "/4/2/2/1:2", "the", 0.25, true)!!
        assertEquals(0.0, payload.getDouble("positionSeconds"), 0.0001)
        assertEquals(0.04, online.narrationSeconds(0, "book")!!, 0.0001)
        assertEquals("epubcfi(/6/2!/4/2/2/1:2)", payload.getString("cfi"))
        assertEquals("OPS/text/one.xhtml#the", payload.getString("mediaOverlayFragment"))
        assertEquals(ReadingPlace(0, null, "the"), payload.place(online, Mode.Follow))
        assertEquals(ReadingPlace(0, "/4/2/2/1:2", null), payload.place(online, Mode.Read))
        payload.put("textUpdatedAt", "2026-11-24T09:05:00Z").put("narrationUpdatedAt", "2026-11-24T09:00:00Z")
        assertEquals(ReadingPlace(0, "/4/2/2/1:2", null), payload.place(online, Mode.Follow))
    }
}
