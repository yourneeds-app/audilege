package app.yourneeds.foliocue

import android.net.Network
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private const val PAGE_SIZE = 60
private val excludedContinueStatuses = setOf("unread", "read", "skimmed", "abandoned")
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
) : LibraryCatalog, ListeningStatisticsEndpoint {
    private val api = profile.api
    private val sessionLock = synchronized(sessionLocks) {
        sessionLocks.getOrPut("$api:${profile.id}:${profile.user}") { Any() }
    }
    @Volatile var supportsSharedQueue = false
        private set
    @Volatile var supportsSharedBookmarks = false
        private set
    @Volatile private var groupedProgress: Boolean? = null
    override val statisticsIdentity: String = keyOf("${profile.api}\n${profile.user}")

    override fun statisticsTimezone(): String = withSession { token ->
        val account = json(URI("$api/auth/me"), null, token)
        statisticsZone(account.optJSONObject("settings")?.optString("timezone")).id
    }

    override fun statisticsDaily(): JSONObject = withSession { token ->
        val rows = request(URI("$api/user-statistics/daily-reading?days=3650"), null, token) {
            org.json.JSONArray(it.bufferedReader(Charsets.UTF_8).readText())
        }
        JSONObject().apply {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val day = java.time.LocalDate.parse(row.getString("day")).toString()
                val seconds = row.getLong("readingSeconds")
                require(seconds >= 0 && !has(day))
                put(day, Math.multiplyExact(seconds, 1000))
            }
        }
    }

    override fun statisticsOverview(): JSONObject = withSession { token ->
        json(URI("$api/user-statistics/summary"), null, token).also { data ->
            for (field in listOf("trackedBooks", "startedBooks", "inProgressBooks", "completedBooks")) require(data.getInt(field) >= 0)
        }
    }

    override fun statisticsBooks(): List<StatisticsBook> = withSession { token ->
        val books = linkedMapOf<Long, StatisticsBook>()
        var page = 0
        var total: Int
        var expectedTotal: Int? = null
        do {
            val body = JSONObject().put("pagination", JSONObject().put("page", page).put("size", PAGE_SIZE))
                .put("collapseSeries", false).put("collapseLinked", false)
                .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
            val response = json(profile.catalog, body, token)
            val items = response.getJSONArray("items")
            total = response.getInt("total")
            require(expectedTotal == null || expectedTotal == total)
            expectedTotal = total
            require(total >= 0 && (items.length() > 0 || page * PAGE_SIZE >= total))
            items.objects().forEach { book ->
                val id = book.getLong("id")
                require(id > 0)
                require(!books.containsKey(id))
                books[id] = StatisticsBook(id, book.optString("title"), author(book))
            }
            page++
        } while (page.toLong() * PAGE_SIZE < total)
        require(books.size == total)
        books.values.toList()
    }

    override fun statisticsSessions(bookId: Long, page: Int): ListeningSessionPage = withSession { token ->
        require(bookId > 0 && page > 0)
        parseStatisticsSessions(json(URI("$api/books/$bookId/sessions?page=$page&pageSize=100&sortBy=startedAt&sortDir=asc"), null, token))
    }

    override fun saveStatisticsSession(event: ListeningEvent) = withSession { token ->
        request(URI("$api/books/files/${event.fileId}/sessions"), event.payload(), token) { Unit }
    }

    override fun page(url: URI): CatalogPage = withSession { token ->
        val index = url.query?.removePrefix("page=")?.toIntOrNull() ?: 0
        require(index >= 0)
        val body = JSONObject().put("pagination", JSONObject().put("page", index).put("size", PAGE_SIZE))
            .put("collapseSeries", false).put("collapseLinked", false)
            .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
        val response = json(profile.catalog, body, token)
        supportsSharedQueue = response.optBoolean("sharedQueue")
        supportsSharedBookmarks = response.optBoolean("sharedBookmarks")
        val items = response.getJSONArray("items")
        val entries = items.objects().mapNotNull { book ->
            val files = book.optJSONArray("files")
            val edition = files?.let(::preferredEpubFile)
            if (files != null && edition == null) return@mapNotNull null
            val primary = files?.objects()?.firstOrNull { it.optString("role") == "primary" }?.optLong("id")?.takeIf { it > 0 }
            catalogEntry(book, edition, primary, book.optDouble("readingProgress", Double.NaN).takeIf { it in 0.0..100.0 })
        }
        val hasMore = items.length() > 0 && (index + 1L) * PAGE_SIZE < response.optLong("total", 0L)
        CatalogPage(entries, if (hasMore) URI("${profile.catalog}?page=${index + 1}") else null)
    }

    fun continueReading(): List<CatalogEntry> {
        val response = withSession { token -> json(URI("$api/dashboard/scrollers/continue-reading?limit=50"), null, token) }
        val entries = response.getJSONArray("books").objects().mapNotNull(::continueEntry).distinctBy { it.url }
        if (response.optInt("total") <= 50) return entries
        val extra = try {
            withSession(::continueReadingOverflow)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The authenticated dashboard already succeeded. Discard partial overflow pages on failure.
            return entries
        }
        return (entries + extra).distinctBy { it.url }
    }

    private fun continueReadingOverflow(token: String): List<CatalogEntry> {
        // Dashboard scrollers have no second page. The general catalogue also includes libraries
        // which the user has chosen to hide from the dashboard or its shelves.
        val settings = json(URI("$api/auth/me"), null, token).getJSONObject("settings")
        val selected = libraryIds(settings.optJSONObject("dashboardConfig")?.optJSONArray("libraryIds"))
        val hidden = libraryIds(settings.optJSONObject("libraryVisibility")?.optJSONObject("hidden")?.optJSONArray("shelves"))
        val filter = JSONObject().put("type", "group").put("join", "AND").put("rules", JSONArray()
            .put(JSONObject().put("type", "rule").put("field", "readProgress").put("operator", "isInProgress")))
        val body = JSONObject().put("filter", filter).put("collapseSeries", false).put("collapseLinked", false)
            .put("sort", JSONArray().put(JSONObject().put("field", "lastReadAt").put("dir", "desc")))
        val result = mutableListOf<CatalogEntry>()
        val candidateIds = mutableSetOf<Long>()
        var index = 0
        var expectedTotal: Long? = null
        var total: Long
        do {
            body.put("pagination", JSONObject().put("page", index).put("size", PAGE_SIZE))
            val page = json(profile.catalog, body, token)
            val cards = page.getJSONArray("items")
            total = page.getLong("total")
            require(total >= 0 && (expectedTotal == null || total == expectedTotal))
            expectedTotal = total
            val remaining = (total - index.toLong() * PAGE_SIZE).coerceAtLeast(0L)
            require(cards.length().toLong() == minOf(PAGE_SIZE.toLong(), remaining))
            for (book in cards.objects()) {
                require(candidateIds.add(book.getLong("id")))
                val libraryId = book.optLong("libraryId")
                if (book.optString("status") != "present" || libraryId <= 0 || libraryId in hidden) continue
                if (selected.isNotEmpty() && libraryId !in selected) continue
                continueEntry(book)?.let(result::add)
            }
            index++
        } while (index.toLong() * PAGE_SIZE < total)
        require(candidateIds.size.toLong() == total)
        return result
    }

    private fun libraryIds(values: JSONArray?): Set<Long> = if (values == null) emptySet() else
        (0 until values.length()).mapNotNull { index -> (values.opt(index) as? Number)?.toLong()?.takeIf { it > 0 } }.toSet()

    private fun continueEntry(book: JSONObject): CatalogEntry? {
        if (book.optString("readStatus") in excludedContinueStatuses) return null
        val percent = book.optDouble("readingProgress", Double.NaN)
        if (!percent.isFinite() || percent <= 0.0 || percent >= 100.0) return null
        // Reading progress belongs to the primary file. Another EPUB can have a different spine.
        val primary = book.optJSONArray("files")?.objects()?.firstOrNull { it.optString("role") == "primary" } ?: return null
        if (!primary.optString("format").equals("epub", true)) return null
        val edition = primary.optLong("id").takeIf { it > 0 } ?: return null
        return catalogEntry(book, edition, edition, percent)
    }

    private fun catalogEntry(book: JSONObject, edition: Long?, primary: Long?, percent: Double?): CatalogEntry? {
        val id = book.optLong("id")
        if (id <= 0) return null
        val title = book.optString("title").ifBlank { book.optJSONObject("metadata")?.optString("title").orEmpty() }
        return CatalogEntry(title.ifBlank { "#$id" }, URI("$api/books/$id"), true, author(book),
            editionFileId = edition, primaryFileId = primary, readingPercent = percent, category = book.optString("category"),
            narrator = book.optJSONObject("metadata")?.optString("narrator").orEmpty(),
            played = book.optBoolean("played"),
            series = book.optJSONObject("metadata")?.optString("series").orEmpty(),
            volume = book.optJSONObject("metadata")?.optString("volume").orEmpty(),
            companionId = book.optLong("companionId").takeIf { it > 0 },
            resumeMode = when (book.optString("resumeMode")) {
                "listen" -> Mode.Listen
                "along" -> Mode.Follow
                "read" -> Mode.Read
                else -> null
            },
            resumeChapter = book.optInt("resumeChapter").takeIf { it > 0 },
            updated = runCatching { java.time.Instant.parse(book.getString("updatedAt")).toEpochMilli() }.getOrNull(),
            showKey = book.optJSONObject("podcast")?.optString("key").orEmpty(),
            showTitle = book.optJSONObject("podcast")?.optString("show").orEmpty(),
            added = book.optJSONObject("podcast")?.optLong("published", 0)?.takeIf { it > 0 }?.times(1000)
                ?: listOf("addedAt","addedOn","createdAt").firstNotNullOfOrNull { key ->
                    runCatching { java.time.Instant.parse(book.getString(key)).toEpochMilli() }.getOrNull()
                })
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

    fun newestPodcasts(network: Network): List<CatalogEntry> = withSession { token ->
        request(URI("$api/podcasts/autodownload"), null, token, network = network) { stream ->
            JSONObject(stream.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("items").objects().mapNotNull { book ->
                val edition = preferredEpubFile(book.getJSONArray("files")) ?: return@mapNotNull null
                catalogEntry(book, edition, edition, null)
            }.toList()
        }
    }

    fun downloadPodcast(fileId: Long, destination: File, network: Network, checkTransfer: () -> Unit): File =
        withSession { token ->
            request(URI("$api/books/files/$fileId/download"), null, token, network = network) {
                savePublication(it, destination, replaceExisting = false, checkTransfer = checkTransfer)
            }
        }

    fun syncBookmarks(detail: OrbitBook, book: Publication, pending: List<ReaderTarget>, removed: Set<String>): List<ReaderTarget> = withSession { token ->
        val url = URI("$api/books/${detail.id}/bookmarks?fileId=${detail.fileId}")
        val additions = JSONArray()
        pending.forEach { mark ->
            val cfi = mark.cfi?.let { "epubcfi(/${book.packageSpineStep}/${(mark.chapter + 1) * 2}!$it)" }
            additions.put(JSONObject().put("key",mark.syncKey).put("chapter",mark.chapter)
                .put("offset",0).put("cfi",cfi).put("anchor",mark.anchor)
                .put("audio_ms",mark.audioMs).put("label",mark.label))
        }
        val changes = if (pending.isEmpty() && removed.isEmpty()) null else
            JSONObject().put("add",additions).put("remove",JSONArray(removed))
        json(url,changes,token).getJSONArray("marks").objects().mapNotNull { mark ->
            val chapter = mark.optInt("chapter",-1)
            if (chapter !in book.spine.indices) return@mapNotNull null
            ReaderTarget(chapter,
                cfi = mark.optString("cfi").takeIf { it.startsWith("epubcfi(") && '!' in it }?.substringAfter('!')?.removeSuffix(")"),
                anchor = mark.optString("anchor").takeIf { !mark.isNull("anchor") && it.isNotBlank() },
                label = mark.optString("label"),
                audioMs = mark.optInt("audio_ms",-1).takeIf { it >= 0 },
                syncKey = mark.getString("key"), synced = true)
        }
    }

    fun queue(action: String? = null, bookId: Long? = null, remove: Boolean = false): List<QueueTitle> = withSession { token ->
        val body = action?.let { JSONObject().put("kind", it).put("book", requireNotNull(bookId)).put("remove", remove) }
        val url = URI(api.toString().removeSuffix("/api/v1") + "/web/player")
        json(url, body, token).getJSONArray("items").objects()
            .filter { it.optString("kind") == "queue" }
            .map { QueueTitle(it.getLong("book"), it.optString("title")) }
            .toList()
    }

    fun companionEntry(id: Long): CatalogEntry? = withSession { token ->
        val book = json(URI("$api/books/$id"), null, token)
        val file = preferredEpubFile(book.getJSONArray("files"))
        catalogEntry(book, file, file, null)
    }

    fun details(url: URI, resumeEpubFileId: Long? = null): OrbitBook = withSession { token ->
        val id = url.path.substringAfterLast('/').toLong()
        val book = json(URI("$api/books/$id"), null, token)
        val files = book.getJSONArray("files")
        val fileId = if (resumeEpubFileId == null) preferredEpubFile(files) else resumeEpubFileId.takeIf { expected ->
            expected > 0 && files.objects().any { it.optLong("id") == expected && it.optString("format").equals("epub", true) }
        }
        if (fileId == null) throw CatalogAccessException(R.string.no_epub)
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
            network: Network? = null, read: (InputStream) -> T,
        ): T {
            val target = url.toURL()
            val connection = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
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
