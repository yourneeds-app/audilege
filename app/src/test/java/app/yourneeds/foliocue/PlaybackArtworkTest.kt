package app.yourneeds.foliocue

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackArtworkTest {
    @Test
    fun keepsEmbeddedAudiobookAndBookCoversSeparate() {
        val book = publication("storyteller:audio-cover-image other-property")
        assertEquals("OPS/portrait.jpg", book.cover)
        assertEquals("OPS/art/audio cover.jpg", book.audiobookCover)
        assertEquals(listOf("embedded audio cover", "book cover"),
            listeningCoverSources(book, null).map { it()!!.use { stream -> stream.reader().readText() } })
    }

    @Test
    fun neverGuessesAudiobookCoverFromFilenameOrUsesTheBookCoverForBothSlots() {
        assertNull(publication("").audiobookCover)
        assertNull(publication("cover-image storyteller:audio-cover-image").audiobookCover)
        assertNull(publication("storyteller:audio-cover-image", "text/html").audiobookCover)
    }

    @Test
    fun requestsOnlyTheOfficialAudioSlotAndKeepsTheStandardCoverAsFallback() {
        val calls = CopyOnWriteArrayList<String>()
        ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }.use { server ->
            val worker = thread(isDaemon = true) {
                repeat(3) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val request = input.readLine()
                        calls += request
                        while (!input.readLine().isNullOrEmpty()) Unit
                        val reply = when (request.substringAfter(' ').substringBefore(' ')) {
                            "/api/v1/books/41" -> """{"id":41,"files":[{"id":8,"format":"epub"}],"covers":{"audio":{"source":"custom"}}}"""
                            "/api/v1/books/41/cover?medium=audio&strict=true" -> "server audio cover"
                            "/api/v1/books/41/thumbnail" -> "library thumbnail"
                            else -> error("Unexpected test URL")
                        }.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(reply)
                            flush()
                        }
                    }
                }
            }
            val profile = Profile(label = "Fixture", server = "http://127.0.0.1:${server.localPort}",
                user = "reader", localHttp = true, accountLogin = true)
            val catalog = OrbitCatalog(profile, """{"accessToken":"fixture","refreshToken":"fixture"}""")
            val detail = catalog.details(URI("${profile.api}/books/41"))
            assertEquals(true, detail.hasAudiobookCover)
            val sources = listeningCoverSources(publication("storyteller:audio-cover-image", streaming = true),
                OnlineBook(catalog, detail))
            assertEquals(listOf("server audio cover", "embedded audio cover", "library thumbnail", "book cover"),
                sources.map { it()!!.use { stream -> stream.reader().readText() } })
            worker.join(1000)
            assertEquals(listOf("GET /api/v1/books/41 HTTP/1.1",
                "GET /api/v1/books/41/cover?medium=audio&strict=true HTTP/1.1",
                "GET /api/v1/books/41/thumbnail HTTP/1.1"), calls.toList())
        }
    }

    private fun publication(audioProperties: String, audioType: String = "image/jpeg", streaming: Boolean = false): Publication {
        val entries = mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='OPS/package.opf'/></container>",
            "OPS/package.opf" to """
                <package xmlns:dc="http://purl.org/dc/elements/1.1/"
                    prefix="storyteller: https://storyteller-platform.gitlab.io/storyteller/docs/vocabulary">
                  <metadata><dc:title>Test edition</dc:title></metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="portrait.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="audio" href="art/audio%20cover.jpg" media-type="$audioType" properties="$audioProperties"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent(),
            "OPS/portrait.jpg" to "book cover",
            "OPS/art/audio cover.jpg" to "embedded audio cover",
        )
        return Publication("Fallback", { entries[it]?.byteInputStream() },
            if (streaming) { _ -> error("Cover loading must not open audio") } else null)
    }
}
