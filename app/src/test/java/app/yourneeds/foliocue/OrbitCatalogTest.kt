package app.yourneeds.foliocue

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbitCatalogTest {
    @Test
    fun nativeLoginRenewsSessionAndReadsTheUsersCatalog() {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val calls = CopyOnWriteArrayList<String>()
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            repeat(4) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val start = input.readLine().split(' ')
                    val route = start[1].substringAfter("/api/v1/")
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    val auth = headers["authorization"]
                    val inputBody = buildString {
                        repeat(headers.getValue("content-length").toInt()) { append(input.read().toChar()) }
                    }
                    val body = JSONObject(inputBody)
            val (status, response) = when (route) {
                "auth/login" -> {
                    calls += "${start[0]} $route ${body.getString("clientKind")} ${body.getString("username")}"
                    200 to """{"accessToken":"initial","refreshToken":"refresh-one","user":{"id":5}}"""
                }
                "auth/refresh" -> {
                    calls += "$route ${body.getString("refreshToken")}"
                    200 to """{"accessToken":"renewed","refreshToken":"refresh-two"}"""
                }
                "books/query" -> {
                    calls += "$route $auth ${body.getJSONObject("pagination").getInt("page")}"
                    if (auth == "Bearer initial") 401 to "{}" else 200 to """
                        {"items":[
                          {"id":41,"title":"Example EPUB","files":[{"id":8,"format":"epub"}]},
                          {"id":42,"title":"Audio only","files":[{"id":9,"format":"mp3"}]}
                        ],"total":61,"page":0,"size":60}
                    """.trimIndent()
                }
                else -> 404 to "{}"
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
                    val message = if (status == 200) "OK" else "Error"
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 $status $message\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        try {
            val profile = Profile(label = "Test", server = "http://127.0.0.1:${server.localPort}",
                user = "reader", localHttp = true, accountLogin = true)
            val login = OrbitCatalog.signIn(profile, "fixture-password")
            var stored = ""
            val resumed = OrbitCatalog(profile, login.credential()) { stored = it }
            val page = resumed.page(profile.catalog)
            assertEquals(listOf("Example EPUB"), page.entries.map { it.title })
            assertEquals(URI("${profile.api}/books/41"), page.entries.single().url)
            assertEquals("page=1", page.next?.query)
            assertEquals("refresh-two", JSONObject(stored).getString("refreshToken"))
            assertEquals(listOf("POST auth/login native reader", "books/query Bearer initial 0",
                "auth/refresh refresh-one", "books/query Bearer renewed 0"), calls.toList())
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun choosesTheEpubWithEmbeddedNarrationBeforeThePrimaryTextOnlyFile() {
        val files = JSONArray("""
            [{"id":1,"format":"epub","role":"primary"},
            {"id":2,"format":"epub","mediaOverlay":{"available":true}},
            {"id":3,"format":"m4b"}]
        """.trimIndent())
        assertEquals(2L, preferredEpubFile(files))
        assertNull(preferredEpubFile(JSONArray("""[{"id":3,"format":"m4b"}]""")))
    }
}
