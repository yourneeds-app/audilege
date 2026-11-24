package app.yourneeds.foliocue

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class StatisticsBook(val id: Long, val title: String, val author: String)
internal data class ListeningEvent(
    val id: String, val bookId: Long, val fileId: Long, val title: String, val author: String,
    val start: Long, val end: Long, val millis: Long,
    val kind: String = "listen",
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("bookId", bookId).put("fileId", fileId)
        .put("title", title).put("author", author).put("start", start).put("end", end).put("millis", millis).put("kind", kind)
    fun payload(): JSONObject {
        require(millis >= 10_000 && millis <= end - start && millis % 1000 == 0L)
        require(kind in setOf("read", "listen", "tts"))
        return JSONObject().put("sessionId", id).put("startedAt", Instant.ofEpochMilli(start).toString())
            .put("endedAt", Instant.ofEpochMilli(end).toString()).put("durationSeconds", millis / 1000)
            .put("sessionType", kind).put("source", "android")
    }
    companion object {
        fun parse(row: JSONObject) = ListeningEvent(row.getString("id"), row.getLong("bookId"), row.getLong("fileId"),
            row.getString("title"), row.optString("author"), row.getLong("start"), row.getLong("end"), row.getLong("millis"), row.optString("kind", "listen"))
    }
}

internal data class RemoteListeningSession(val id: Long, val fileId: Long?, val start: Long, val end: Long,
                                          val millis: Long, val kind: String?)
internal data class ListeningSessionPage(val page: Int, val total: Int, val items: List<RemoteListeningSession>)
internal interface ListeningStatisticsEndpoint {
    val statisticsIdentity: String
    fun statisticsBooks(): List<StatisticsBook>
    fun statisticsSessions(bookId: Long, page: Int): ListeningSessionPage
    fun saveStatisticsSession(event: ListeningEvent)
    fun statisticsTimezone(): String = "UTC"
    fun statisticsDaily(): JSONObject? = null
    fun statisticsOverview(): JSONObject? = null
}

internal fun parseStatisticsSessions(body: JSONObject): ListeningSessionPage {
    val items = body.getJSONArray("items")
    val page = body.getInt("page")
    val total = body.getInt("total")
    require(page > 0 && total >= 0 && items.length() <= 100)
    return ListeningSessionPage(page, total, (0 until items.length()).map { index ->
        val row = items.getJSONObject(index)
        val id = row.getLong("id")
        val start = Instant.parse(row.getString("startedAt")).toEpochMilli()
        val end = Instant.parse(row.getString("endedAt")).toEpochMilli()
        val seconds = row.getLong("durationSeconds")
        require(id > 0 && seconds >= 0)
        RemoteListeningSession(id, if (row.isNull("bookFileId")) null else row.getLong("bookFileId"),
            start, end, Math.multiplyExact(seconds, 1000), row.optString("sessionType").takeIf { it in setOf("listen", "read", "tts") })
    })
}

/** Allocate actual active time across the observed window, retaining all milliseconds at midnight. */
internal fun addStatisticsWindow(data: JSONObject, bookId: Long, title: String, author: String,
                                 start: Long, end: Long, millis: Long, zone: ZoneId = ZoneId.systemDefault()) {
    if (millis <= 0) return
    val days = data.optJSONObject("days") ?: JSONObject().also { data.put("days", it) }
    val book = data.objectAt("books").objectAt("orbit:$bookId")
    val bookDays = book.objectAt("days")
    for ((day, part) in bookOrbitDayParts(start, end, millis, zone)) {
        days.put(day.toString(), days.optLong(day.toString()) + part)
        bookDays.put(day.toString(), bookDays.optLong(day.toString()) + part)
    }
    book.put("title", title).put("author", author).put("millis", book.optLong("millis") + millis)
}

internal fun mergeStatistics(target: JSONObject, source: JSONObject) {
    val days = target.optJSONObject("days") ?: JSONObject().also { target.put("days", it) }
    source.optJSONObject("days")?.let { from -> from.keys().forEach { day -> days.put(day, days.optLong(day) + from.getLong(day)) } }
    val books = target.optJSONObject("books") ?: JSONObject().also { target.put("books", it) }
    source.optJSONObject("books")?.let { from -> from.keys().forEach { key ->
        val item = from.getJSONObject(key)
        val existing = books.optJSONObject(key)
        val merged = JSONObject(item.toString()).put("millis", (existing?.optLong("millis") ?: 0) + item.getLong("millis"))
        val dates = JSONObject(existing?.optJSONObject("days")?.toString() ?: "{}")
        item.optJSONObject("days")?.let { sourceDays -> sourceDays.keys().forEach { day ->
            dates.put(day, dates.optLong(day) + sourceDays.getLong(day))
        } }
        merged.put("days", dates)
        books.put(key, merged)
    } }
}

internal data class ListeningSyncResult(val uploaded: Map<String, ListeningEvent>, val books: Map<Long, JSONObject>, val replaceAll: Boolean,
    val timezone: String = "UTC", val serverDays: JSONObject? = null, val overview: JSONObject? = null)

/** Retrying the same immutable IDs is safe after an HTTP response is lost. */
internal fun synchronizeListening(endpoint: ListeningStatisticsEndpoint, queued: List<ListeningEvent>,
                                  discover: Boolean, cachedTimezone: String? = null): ListeningSyncResult {
    val duplicates = queued.groupBy { it.id }.any { it.value.size != 1 }
    require(!duplicates)
    val zone = statisticsZone(endpoint.statisticsTimezone())
    val complete = discover || (cachedTimezone != null && cachedTimezone != zone.id)
    val catalog = if (complete) endpoint.statisticsBooks().associateBy { it.id }.toMutableMap() else mutableMapOf()
    queued.forEach { event ->
        catalog.putIfAbsent(event.bookId, StatisticsBook(event.bookId, event.title, event.author))
        endpoint.saveStatisticsSession(event)
    }
    val snapshots = linkedMapOf<Long, JSONObject>()
    for ((id, book) in catalog) {
        val aggregate = JSONObject()
        val seen = mutableSetOf<Long>()
        val sessionIds = mutableListOf<Long>()
        val receipts = mutableMapOf<Pair<String?, List<Long>>, Int>()
        var page = 1
        var total: Int? = null
        do {
            val response = endpoint.statisticsSessions(id, page)
            if (response.page != page || (total != null && total != response.total))
                throw IOException("Statistics changed during pagination")
            total = response.total
            if (response.items.isEmpty() && seen.size < response.total) throw IOException("Incomplete statistics page")
            for (row in response.items) {
                if (!seen.add(row.id)) throw IOException("Repeated statistics row")
                sessionIds.add(row.id)
                addActivityWindow(aggregate, id, book.title, book.author, row.start, row.end, row.millis, row.kind, zone)
                val key = row.kind to listOf(row.fileId ?: -1, row.start, row.end, row.millis)
                receipts[key] = (receipts[key] ?: 0) + 1
            }
            if (seen.size > response.total) throw IOException("Invalid statistics count")
            page++
        } while (seen.size < (total ?: 0))
        val expected = queued.filter { it.bookId == id }.groupingBy { it.kind to listOf(it.fileId, it.start, it.end, it.millis) }.eachCount()
        if (expected.any { (key, count) -> (receipts[key] ?: 0) < count })
            throw IOException("The server has not confirmed the uploaded listening sessions")
        aggregate.put("_rowIds", JSONArray(sessionIds))
        snapshots[id] = aggregate
    }
    val days = endpoint.statisticsDaily()
    val overview = endpoint.statisticsOverview()
    if (zone != statisticsZone(endpoint.statisticsTimezone())) throw IOException("Account timezone changed during refresh")
    return ListeningSyncResult(queued.associateBy { it.id }, snapshots, complete, zone.id, days, overview)
}
