package app.yourneeds.foliocue

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContinueReadingApiTest {
    private val route = "/api/v1/dashboard/scrollers/continue-reading?limit=50"
    private val files = JSONArray("""[{"id":7,"role":"primary","format":"epub"},
        {"id":8,"role":"alternative","format":"epub","mediaOverlay":{"available":true}}]""")

    private fun card(id: Int, percent: Double, status: String = "reading") = JSONObject()
        .put("id", id).put("title", "Fixture $id").put("files", files).put("status", "present").put("libraryId", 5)
        .put("readingProgress", percent).put("readStatus", status)

    @Test fun returnsStartedEpubsInServerOrderAndResumesTheirExactEdition() {
        val cards = JSONArray().put(card(2, 0.1)).put(card(1, 93.5, "rereading")).put(card(2, 0.1))
            .put(card(3, 100.0)).put(card(4, 0.0)).put(card(5, 70.0, "read"))
            .put(card(6, 12.0, "abandoned")).put(card(7, 10.0, "unread")).put(card(8, 20.0, "skimmed"))
            .put(card(9, 50.0).put("files", JSONArray("""[{"id":5,"format":"pdf","role":"primary"},
                {"id":6,"format":"epub","role":"alternative"}]""")))
        withBookApi(respond = { path, _ ->
            when (path) {
                route -> JSONObject().put("books", cards).put("total", cards.length())
                "/api/v1/books/2" -> JSONObject().put("files", files)
                else -> error(path)
            }
        }) { catalog, requests ->
            val started = catalog.continueReading()
            assertEquals(listOf("Fixture 2", "Fixture 1"), started.map { it.title })
            assertEquals(listOf(0.1, 93.5), started.map { it.readingPercent })
            assertEquals(listOf(7L, 7L), started.map { it.editionFileId })
            val first = started.first()
            assertEquals(7L, catalog.details(first.url, first.editionFileId).fileId)
            assertEquals(8L, catalog.details(first.url).fileId)
            assertThrows(CatalogAccessException::class.java) { catalog.details(first.url, 999L) }
            assertEquals(listOf(route, "/api/v1/books/2", "/api/v1/books/2", "/api/v1/books/2"), requests.toList())
        }
    }

    @Test fun readsOverflowPagesWithoutRestoringHiddenLibrariesOrOtherEditions() {
        val dashboard = JSONArray().apply { for (id in 50 downTo 1) put(card(id, 5.0)) }
        val settings = JSONObject("""{"dashboardConfig":{"libraryIds":[5,9]},"libraryVisibility":{"hidden":{"shelves":[9]}}}""")
        val catalogPage = JSONArray().apply {
            for (id in 1..58) put(card(id, 5.0))
            put(card(59, 56.0).put("libraryId", 9))
            put(card(60, 45.0).put("libraryId", 15))
        }
        val lastPage = JSONArray().put(card(61, 55.0)).put(card(62, 0.0)).put(card(63, 100.0))
        withBookApi(respond = { path, body ->
            when (path) {
                route -> JSONObject().put("books", dashboard).put("total", 59)
                "/api/v1/auth/me" -> JSONObject().put("settings", settings)
                "/api/v1/books/query" -> {
                    val query = requireNotNull(body)
                    assertEquals("isInProgress", query.getJSONObject("filter").getJSONArray("rules").getJSONObject(0).getString("operator"))
                    assertEquals("lastReadAt", query.getJSONArray("sort").getJSONObject(0).getString("field"))
                    val index = query.getJSONObject("pagination").getInt("page")
                    JSONObject().put("total", 63).put("items", if (index == 0) catalogPage else lastPage)
                }
                else -> error(path)
            }
        }) { catalog, requests ->
            val expected = (50 downTo 1).toList() + (51..58).toList() + 61
            assertEquals(expected.map { "Fixture $it" }, catalog.continueReading().map { it.title })
            assertEquals(listOf(route, "/api/v1/auth/me", "/api/v1/books/query", "/api/v1/books/query"), requests.toList())
        }
    }

    @Test fun keepsOnlyTheConfirmedDashboardWhenAnOverflowPageIsIncomplete() {
        val dashboard = JSONArray().apply { for (id in 1..50) put(card(id, 20.0)) }
        val extra = JSONArray().apply { for (id in 1..60) put(card(id, 20.0)) }
        withBookApi(respond = { path, body ->
            when (path) {
                route -> JSONObject().put("books", dashboard).put("total", 61)
                "/api/v1/auth/me" -> JSONObject().put("settings", JSONObject())
                "/api/v1/books/query" -> {
                    val index = requireNotNull(body).getJSONObject("pagination").getInt("page")
                    JSONObject().put("total", 61).put("items", if (index == 0) extra else JSONArray())
                }
                else -> error(path)
            }
        }) { catalog, requests ->
            assertEquals((1..50).map { "Fixture $it" }, catalog.continueReading().map { it.title })
            assertEquals(listOf(route, "/api/v1/auth/me", "/api/v1/books/query", "/api/v1/books/query"), requests.toList())
        }
    }

    private fun withBookApi(respond: (String, JSONObject?) -> JSONObject, exercise: (OrbitCatalog, List<String>) -> Unit) {
        val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val requests = CopyOnWriteArrayList<String>()
        val worker = thread(isDaemon = true) {
            try {
                while (!listener.isClosed) listener.accept().use { connection ->
                    connection.soTimeout = 5000
                    val input = connection.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    requests += path
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val requestBody = buildString { repeat(headers["content-length"]?.toInt() ?: 0) { append(input.read().toChar()) } }
                    val body = respond(path, requestBody.takeIf(String::isNotEmpty)?.let(::JSONObject)).toString().toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                    }
                }
            } catch (_: java.net.SocketException) { /* server closed by the test */ }
        }
        try {
            val profile = Profile(label = "Fixture", server = "http://127.0.0.1:${listener.localPort}",
                user = "fixture", localHttp = true, accountLogin = true)
            exercise(OrbitCatalog(profile, """{"accessToken":"fixture"}"""), requests)
        } finally {
            listener.close()
            worker.join(1000)
        }
    }
}
