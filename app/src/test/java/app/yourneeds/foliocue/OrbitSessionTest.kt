package app.yourneeds.foliocue

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitSessionTest {
    private fun tokens(access: String, refresh: String) =
        JSONObject().put("accessToken", access).put("refreshToken", refresh).toString()

    private class Fixture(val reply: (String, String, String) -> Pair<Int, String>) : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val profile = Profile(label = "Session fixture", server = "http://127.0.0.1:${socket.localPort}",
            user = "reader", localHttp = true, accountLogin = true)
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                thread(isDaemon = true) {
                    client.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1].substringBefore('?')
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                        val body = buildString { repeat(headers["content-length"]?.toInt() ?: 0) { append(input.read().toChar()) } }
                        val (code, json) = reply(path, headers["authorization"].orEmpty(), body)
                        val bytes = json.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code Status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }

    @Test fun concurrentCatalogsRotateTheRefreshTokenOnlyOnceAndReusePersistedSession() {
        val old = tokens("old-access", "old-refresh")
        val renewed = tokens("new-access", "new-refresh")
        val stored = AtomicReference(old)
        val refreshes = AtomicInteger()
        val waiting = CountDownLatch(2)
        Fixture { path, auth, body ->
            when (path) {
                "/api/v1/books/query" -> if (auth == "Bearer old-access") {
                    waiting.countDown()
                    waiting.await(5, TimeUnit.SECONDS)
                    401 to "{}"
                } else 200 to """{"items":[],"total":0,"page":0,"size":60}"""
                "/api/v1/auth/refresh" -> {
                    val valid = JSONObject(body).getString("refreshToken") == "old-refresh" && refreshes.incrementAndGet() == 1
                    if (valid) 200 to renewed else 401 to "{}"
                }
                else -> 404 to "{}"
            }
        }.use { server ->
            val first = OrbitCatalog(server.profile, old, readSession = { stored.get() }) { stored.set(it) }
            val second = OrbitCatalog(server.profile, old, readSession = { stored.get() }) { stored.set(it) }
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val requests = listOf(first, second).map { catalog -> thread {
                try { catalog.page(server.profile.catalog) } catch (failure: Throwable) { errors.add(failure) }
            } }
            requests.forEach { it.join(10_000); assertFalse("Request did not finish", it.isAlive) }
            assertTrue("Concurrent request failed: ${errors.map { it.javaClass.simpleName }}", errors.isEmpty())
            assertEquals(1, refreshes.get())
            assertEquals(renewed, stored.get())
            // A previously constructed client must also see a later sign-in on the same profile.
            stored.set(tokens("replacement-access", "replacement-refresh"))
            first.page(server.profile.catalog)
            assertEquals(1, refreshes.get())
        }
    }

    @Test fun deniedRefreshStillRequiresSignInWithoutAnEndlessRetry() {
        val refreshes = AtomicInteger()
        Fixture { path, _, _ ->
            if (path == "/api/v1/auth/refresh") refreshes.incrementAndGet()
            401 to "{}"
        }.use { server ->
            val catalog = OrbitCatalog(server.profile, tokens("expired", "revoked"))
            try {
                catalog.page(server.profile.catalog)
                fail("Revoked session was accepted")
            } catch (failure: CatalogAccessException) {
                assertEquals(R.string.session_expired, failure.messageId)
            }
            assertEquals(1, refreshes.get())
        }
    }
}
