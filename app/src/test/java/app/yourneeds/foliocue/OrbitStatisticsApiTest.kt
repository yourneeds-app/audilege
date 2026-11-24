package app.yourneeds.foliocue

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitStatisticsApiTest {
    @Test fun nativeAccountPostsTypedListeningSessionsAndReadsPagedBookHistory() {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val calls = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()
        val event = ListeningEvent("audilege-fixture-session", 41, 8, "Fictional title", "Fixture author", start, start + 15_000, 15_000)
        val worker = thread(isDaemon = true) {
            try {
                repeat(7) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val first = input.readLine()
                        calls += first
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        assertEquals("Bearer fixture-access", headers["authorization"])
                        val body = buildString { repeat(headers["content-length"]?.toInt() ?: 0) { append(input.read().toChar()) } }
                        val (status, response) = when {
                            first.startsWith("GET /api/v1/auth/me ") -> 200 to """{"settings":{"timezone":"Europe/Berlin"}}"""
                            first.startsWith("GET /api/v1/user-statistics/daily-reading?") -> 200 to """[{"day":"2026-11-24","readingSeconds":15}]"""
                            first.startsWith("GET /api/v1/user-statistics/summary ") -> 200 to """{"trackedBooks":1,"startedBooks":1,"inProgressBooks":1,"completedBooks":0,"meanProgressPercent":1.0}"""
                            first.startsWith("POST /api/v1/books/query ") -> {
                                assertEquals(0, JSONObject(body).getJSONObject("pagination").getInt("page"))
                                200 to """{"items":[{"id":41,"title":"Fictional title","metadata":{"authors":["Fixture author"]}}],"total":1}"""
                            }
                            first.startsWith("POST /api/v1/books/files/8/sessions ") -> {
                                val payload = JSONObject(body)
                                assertEquals(event.id, payload.getString("sessionId"))
                                assertEquals("listen", payload.getString("sessionType"))
                                assertEquals("android", payload.getString("source"))
                                assertEquals(15, payload.getInt("durationSeconds"))
                                assertEquals(event.start, Instant.parse(payload.getString("startedAt")).toEpochMilli())
                                204 to ""
                            }
                            first.startsWith("GET /api/v1/books/41/sessions?") -> 200 to """
                                {"page":1,"total":1,"items":[{"id":501,"bookFileId":8,
                                "startedAt":"2026-11-24T12:00:00Z","endedAt":"2026-11-24T12:00:15Z",
                                "durationSeconds":15,"sessionType":"listen"}]}
                            """.trimIndent()
                            else -> error("Unexpected API request")
                        }
                        val bytes = response.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status Status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (failure: Throwable) { failures += failure }
        }
        try {
            val profile = Profile(label = "Fixture", server = "http://127.0.0.1:${server.localPort}", user = "reader",
                localHttp = true, accountLogin = true)
            val catalog = OrbitCatalog(profile, """{"accessToken":"fixture-access","refreshToken":"fixture-refresh"}""")
            val result = synchronizeListening(catalog, listOf(event), discover = true)
            assertEquals(setOf(event.id), result.uploaded.keys)
            assertEquals(15_000L, result.books.getValue(41).getJSONObject("books").getJSONObject("orbit:41").getLong("millis"))
            assertEquals(7, calls.size)
            assertEquals("Europe/Berlin", result.timezone)
            assertEquals(15_000L, result.serverDays!!.getLong("2026-11-24"))
            assertTrue(failures.isEmpty())
            assertNotEquals(catalog.statisticsIdentity,
                OrbitCatalog(profile.copy(user = "another-reader"), "{}").statisticsIdentity)
        } finally { server.close(); worker.join(1000) }
    }
}
