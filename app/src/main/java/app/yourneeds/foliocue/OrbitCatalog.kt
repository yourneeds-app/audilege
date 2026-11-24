package app.yourneeds.foliocue

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

private const val PAGE_SIZE = 60
internal class OrbitHttp(val status: Int) : Exception("BookOrbit HTTP $status")

internal data class OrbitBook(
    val id: Long, val fileId: Long, val author: String, val description: String,
    val series: String = "", val narrator: String = "", val pages: Int? = null,
    val hasAudiobookCover: Boolean? = null,
)
internal interface ProgressEndpoint {
    fun progress(): JSONObject
    fun updateProgress(body: JSONObject, previous: JSONObject): ProgressReceipt?
}

internal data class OnlineBook(val catalog: OrbitCatalog, val detail: OrbitBook) : ProgressEndpoint {
    override fun progress() = catalog.progress(detail.fileId)
    override fun updateProgress(body: JSONObject, previous: JSONObject) = catalog.updateProgress(detail.fileId, body, previous)
}

internal class OrbitCatalog(
    private val profile: Profile,
    @Volatile private var session: String,
    private val readSession: (() -> String)? = null,
    private val persistSession: (String) -> Unit = {},
) : LibraryCatalog {
    private val api = profile.api
    private val sessionLock = synchronized(sessionLocks) {
        sessionLocks.getOrPut("$api:${profile.id}:${profile.user}") { Any() }
    }
    @Volatile private var groupedProgress: Boolean? = null

    override fun page(url: URI): CatalogPage = withSession { token ->
        val index = url.query?.removePrefix("page=")?.toIntOrNull() ?: 0
        require(index >= 0)
        val body = JSONObject().put("pagination", JSONObject().put("page", index).put("size", PAGE_SIZE))
            .put("collapseSeries", false).put("collapseLinked", false)
            .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
        val response = json(profile.catalog, body, token)
        val items = response.getJSONArray("items")
        val entries = items.objects().mapNotNull { book ->
            val id = book.optLong("id", 0)
            if (id <= 0) return@mapNotNull null
            val files = book.optJSONArray("files")
            val edition = files?.let(::preferredEpubFile)
            if (files != null && edition == null) return@mapNotNull null
            val title = book.optString("title").ifBlank { book.optJSONObject("metadata")?.optString("title").orEmpty() }
            CatalogEntry(title.ifBlank { "#$id" }, URI("$api/books/$id"), true, author(book), editionFileId = edition)
        }
        val hasMore = items.length() > 0 && (index + 1L) * PAGE_SIZE < response.optLong("total", 0L)
        CatalogPage(entries, if (hasMore) URI("${profile.catalog}?page=${index + 1}") else null)
    }

    override fun download(url: URI, destination: File): File = withSession { token ->
        val id = url.path.substringAfterLast('/').toLong()
        val detail = json(URI("$api/books/$id"), null, token)
        val fileId = preferredEpubFile(detail.getJSONArray("files"))
            ?: throw CatalogAccessException(R.string.no_epub)
        request(URI("$api/books/files/$fileId/download"), null, token) { savePublication(it, destination) }
    }

    fun download(book: OrbitBook, destination: File): File = withSession { token ->
        request(URI("$api/books/files/${book.fileId}/download"), null, token) { savePublication(it, destination) }
    }

    fun details(url: URI): OrbitBook = withSession { token ->
        val id = url.path.substringAfterLast('/').toLong()
        val book = json(URI("$api/books/$id"), null, token)
        val fileId = preferredEpubFile(book.getJSONArray("files"))
            ?: throw CatalogAccessException(R.string.no_epub)
        val meta = book.optJSONObject("metadata") ?: book
        val audioMeta = book.optJSONObject("audioMetadata") ?: meta.optJSONObject("audioMetadata")
        OrbitBook(id, fileId, author(book), meta.text("description"), meta.text("seriesName"),
            people(meta, "narrators").ifBlank { audioMeta?.let { people(it, "narrators") }.orEmpty() }
                .ifBlank { meta.text("narrator") },
            meta.optInt("pageCount").takeIf { it > 0 },
            book.optJSONObject("covers")?.takeIf { it.has("audio") }?.let { !it.isNull("audio") })
    }

    fun publication(book: OrbitBook, title: String): Publication = Publication(
        title,
        { path -> withSession { token -> openStream(epubResource(book, path, false), token) } },
        { path ->
            withSession { token ->
                val uri = epubResource(book, path, true)
                // The range probe refreshes the session before Android opens the data source.
                openStream(uri, token, "bytes=0-0").close()
                AudioStream(uri, mapOf("Authorization" to "Bearer $token", "android-allow-cross-domain-redirect" to "0"))
            }
        },
    )

    fun cover(id: Long): InputStream = withSession { token ->
        openStream(URI("$api/books/$id/thumbnail"), token)
    }

    fun audiobookCover(id: Long): InputStream = withSession { token ->
        openStream(URI("$api/books/$id/cover?medium=audio&strict=true"), token)
    }

    fun progress(fileId: Long): JSONObject = withSession { token ->
        if (groupedProgress != false) {
            try {
                val row = json(URI("$api/reading-progress?fileId=$fileId"), null, token).groupedPosition(fileId)
                groupedProgress = true
                return@withSession row
            } catch (failure: OrbitHttp) {
                if (failure.status != 404 && failure.status != 405) throw failure
            }
        }
        legacyProgress(fileId, null, token).also { groupedProgress = false }
    }

    fun updateProgress(fileId: Long, body: JSONObject, previous: JSONObject): ProgressReceipt? = withSession { token ->
        if (groupedProgress == true) {
            val payload = groupedProgressWrite(fileId, body, previous)
            request(URI("$api/reading-progress"), payload, token, "PUT") {
                JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).groupedReceipt(fileId)
            }
        } else {
            legacyProgress(fileId, body, token)
            null // Legacy writes have no receipt. ReadingSync verifies with a fresh GET.
        }
    }

    private fun legacyProgress(fileId: Long, body: JSONObject?, token: String): JSONObject =
        request(URI("$api/books/files/$fileId/progress"), body, token) {
            val response = it.bufferedReader(Charsets.UTF_8).readText().trim()
            when {
                response == "null" || (response.isEmpty() && body != null) -> JSONObject()
                else -> JSONObject(response)
            }
        }

    private fun epubResource(book: OrbitBook, path: String, audio: Boolean): URI {
        val endpoint = if (audio) "media-overlay/file" else "file"
        val escapedPath = if (audio) path else path.replace("%", "%25")
        val encoded = URI(null, null, "/$escapedPath", null).rawPath.removePrefix("/")
        return URI("$api/epub/${book.id}/$endpoint/$encoded?fileId=${book.fileId}")
    }

    private fun <T> withSession(operation: (String) -> T): T {
        val snapshot = synchronized(sessionLock) { currentSession() }
        try {
            return try {
                operation(JSONObject(snapshot).getString("accessToken"))
            } catch (failure: OrbitHttp) {
                if (failure.status != 401) throw failure
                operation(refreshAfter(snapshot))
            }
        } catch (failure: OrbitHttp) {
            throw catalogError(failure.status, R.string.session_expired)
        }
    }

    private fun currentSession(): String {
        readSession?.invoke()?.let { session = it }
        return session
    }

    private fun refreshAfter(snapshot: String): String = synchronized(sessionLock) {
        currentSession()
        if (session == snapshot) {
            val payload = JSONObject().put("refreshToken", JSONObject(snapshot).getString("refreshToken"))
            val renewed = sessionCredential(json(URI("$api/auth/refresh"), payload))
            persistSession(renewed)
            session = renewed
        }
        JSONObject(session).getString("accessToken")
    }

    fun credential(): String = session

    companion object {
        private val sessionLocks = mutableMapOf<String, Any>()
        fun signIn(profile: Profile, password: String): OrbitCatalog {
            val body = JSONObject().put("username", profile.user).put("password", password)
                .put("clientKind", "native").put("deviceLabel", "Audilege")
            try {
                return OrbitCatalog(profile, sessionCredential(json(URI("${profile.api}/auth/login"), body)))
            } catch (failure: OrbitHttp) {
                throw catalogError(failure.status, R.string.account_auth_error)
            }
        }

        private fun sessionCredential(tokens: JSONObject): String = JSONObject()
            .put("accessToken", tokens.getString("accessToken").also { require(it.isNotBlank()) })
            .put("refreshToken", tokens.getString("refreshToken").also { require(it.isNotBlank()) })
            .toString()

        private fun catalogError(status: Int, authentication: Int): Exception = when (status) {
            401 -> CatalogAccessException(authentication)
            403 -> CatalogAccessException(if (authentication == R.string.account_auth_error) authentication else R.string.account_permission)
            429 -> CatalogAccessException(R.string.auth_rate_limit)
            else -> OrbitHttp(status)
        }

        private fun author(book: JSONObject): String {
            val meta = book.optJSONObject("metadata") ?: book
            return people(meta, "authors").ifBlank { meta.text("author") }
        }

        private fun JSONObject.text(field: String) = if (isNull(field)) "" else optString(field, "")

        private fun people(meta: JSONObject, field: String): String {
            val values = meta.optJSONArray(field) ?: return ""
            return (0 until values.length()).map { index ->
                when (val person = values.opt(index)) {
                    is JSONObject -> person.text("name")
                    is String -> person.trim()
                    else -> ""
                }
            }.filter(String::isNotBlank).joinToString(", ")
        }

        private fun openStream(url: URI, token: String, range: String? = null): InputStream {
            val connection = url.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (range != null) connection.setRequestProperty("Range", range)
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw OrbitHttp(status)
                return object : java.io.FilterInputStream(connection.inputStream) {
                    override fun close() {
                        try { super.close() } finally { connection.disconnect() }
                    }
                }
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
        }

        private fun json(url: URI, body: JSONObject?, token: String? = null): JSONObject =
            request(url, body, token) { JSONObject(it.bufferedReader(Charsets.UTF_8).readText()) }

        private fun <T> request(
            url: URI, body: JSONObject?, token: String?, method: String = if (body == null) "GET" else "POST",
            read: (InputStream) -> T,
        ): T {
            val connection = url.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.requestMethod = method
                    connection.doOutput = true
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw OrbitHttp(status)
                return connection.inputStream.use(read)
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal fun preferredEpubFile(files: JSONArray): Long? = files.objects()
    .filter { it.optString("format").equals("epub", true) && it.optLong("id", 0) > 0 }
    .maxByOrNull { file ->
        when {
            file.optJSONObject("mediaOverlay")?.optBoolean("available") == true -> 3
            file.optString("filename").endsWith(".readaloud.epub", ignoreCase = true) -> 2
            file.optString("role") == "primary" -> 1
            else -> 0
        }
    }?.optLong("id")

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
