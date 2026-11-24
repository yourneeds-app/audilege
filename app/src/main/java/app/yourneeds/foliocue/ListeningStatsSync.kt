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
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("bookId", bookId).put("fileId", fileId)
        .put("title", title).put("author", author).put("start", start).put("end", end).put("millis", millis)
    fun payload(): JSONObject {
        require(millis >= 10_000 && millis <= end - start && millis % 1000 == 0L)
        return JSONObject().put("sessionId", id).put("startedAt", Instant.ofEpochMilli(start).toString())
            .put("endedAt", Instant.ofEpochMilli(end).toString()).put("durationSeconds", millis / 1000)
            .put("sessionType", "listen").put("source", "android")
    }
    companion object {
        fun parse(row: JSONObject) = ListeningEvent(row.getString("id"), row.getLong("bookId"), row.getLong("fileId"),
            row.getString("title"), row.optString("author"), row.getLong("start"), row.getLong("end"), row.getLong("millis"))
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
        require(id > 0 && end >= start && seconds >= 0 && seconds <= (end - start) / 1000)
        RemoteListeningSession(id, if (row.isNull("bookFileId")) null else row.getLong("bookFileId"),
            start, end, seconds * 1000, row.optString("sessionType").takeIf { it in setOf("listen", "read", "tts") })
    })
}

/** Allocate actual active time across the observed window, retaining all milliseconds at midnight. */
internal fun addStatisticsWindow(data: JSONObject, bookId: Long, title: String, author: String,
                                 start: Long, end: Long, millis: Long, zone: ZoneId = ZoneId.systemDefault()) {
    if (millis <= 0 || end <= start || millis > end - start) return
    val days = data.optJSONObject("days") ?: JSONObject().also { data.put("days", it) }
    var cursor = start
    var counted = 0L
    while (cursor < end) {
        val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val until = minOf(end, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val cumulative = if (until == end) millis else (millis.toDouble() * (until - start) / (end - start)).toLong()
        val part = cumulative - counted
        days.put(day.toString(), days.optLong(day.toString()) + part)
        counted = cumulative
        cursor = until
    }
    val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
    val key = "orbit:$bookId"
    val book = books.optJSONObject(key) ?: JSONObject().also { books.put(key, it) }
    book.put("title", title).put("author", author).put("millis", book.optLong("millis") + millis)
}

internal fun mergeStatistics(target: JSONObject, source: JSONObject) {
    val days = target.optJSONObject("days") ?: JSONObject().also { target.put("days", it) }
    source.optJSONObject("days")?.let { from -> from.keys().forEach { day -> days.put(day, days.optLong(day) + from.getLong(day)) } }
    val books = target.optJSONObject("books") ?: JSONObject().also { target.put("books", it) }
    source.optJSONObject("books")?.let { from -> from.keys().forEach { key ->
        val item = from.getJSONObject(key)
        val existing = books.optJSONObject(key)
        books.put(key, JSONObject(item.toString()).put("millis", (existing?.optLong("millis") ?: 0) + item.getLong("millis")))
    } }
}

internal data class ListeningSyncResult(val uploaded: Map<String, ListeningEvent>, val books: Map<Long, JSONObject>, val replaceAll: Boolean)

/** Retrying the same immutable IDs is safe after an HTTP response is lost. */
internal fun synchronizeListening(endpoint: ListeningStatisticsEndpoint, queued: List<ListeningEvent>,
                                  discover: Boolean): ListeningSyncResult {
    val duplicates = queued.groupBy { it.id }.any { it.value.size != 1 }
    require(!duplicates)
    val catalog = if (discover) endpoint.statisticsBooks().associateBy { it.id }.toMutableMap() else mutableMapOf()
    queued.forEach { event ->
        catalog.putIfAbsent(event.bookId, StatisticsBook(event.bookId, event.title, event.author))
        endpoint.saveStatisticsSession(event)
    }
    val snapshots = linkedMapOf<Long, JSONObject>()
    for ((id, book) in catalog) {
        val aggregate = JSONObject()
        val seen = mutableSetOf<Long>()
        val listeningIds = mutableListOf<Long>()
        val receipts = mutableMapOf<List<Long>, Int>()
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
                // Missing or unknown types must never turn reading time into audiobook time.
                if (row.kind != "listen") continue
                listeningIds.add(row.id)
                addStatisticsWindow(aggregate, id, book.title, book.author, row.start, row.end, row.millis)
                val key = listOf(row.fileId ?: -1, row.start, row.end, row.millis)
                receipts[key] = (receipts[key] ?: 0) + 1
            }
            if (seen.size > response.total) throw IOException("Invalid statistics count")
            page++
        } while (seen.size < (total ?: 0))
        val expected = queued.filter { it.bookId == id }.groupingBy { listOf(it.fileId, it.start, it.end, it.millis) }.eachCount()
        if (expected.any { (key, count) -> (receipts[key] ?: 0) < count })
            throw IOException("The server has not confirmed the uploaded listening sessions")
        aggregate.put("_rowIds", JSONArray(listeningIds))
        snapshots[id] = aggregate
    }
    return ListeningSyncResult(queued.associateBy { it.id }, snapshots, discover)
}
