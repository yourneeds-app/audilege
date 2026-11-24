package app.yourneeds.foliocue

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.io.IOException
import org.json.JSONObject

internal fun JSONObject.objectAt(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }
internal fun statisticsAccount(root: JSONObject, identity: String): JSONObject = root.objectAt("accounts").objectAt(identity)

private fun newEvent(bookId: Long, fileId: Long, title: String, author: String, start: Long, end: Long, millis: Long) =
    ListeningEvent("audilege-${UUID.randomUUID()}", bookId, fileId, title, author, start, end, millis)

/** The server requires ten seconds. Keep shorter intervals durable until they can be combined. */
private fun sealDraft(account: JSONObject, key: String) {
    val drafts = account.objectAt("drafts")
    val row = drafts.optJSONObject(key) ?: return
    val event = ListeningEvent.parse(row)
    val seconds = event.millis / 1000
    if (seconds < 10) return
    val posted = event.copy(millis = seconds * 1000)
    account.objectAt("outbox").put(posted.id, posted.json())
    val remainder = event.millis - posted.millis
    if (remainder == 0L) drafts.remove(key)
    else drafts.put(key, newEvent(event.bookId, event.fileId, event.title, event.author,
        event.end - remainder, event.end, remainder).json())
}

internal fun queueListening(account: JSONObject, bookId: Long, fileId: Long, title: String, author: String,
                           end: Long, millis: Long, zone: ZoneId = ZoneId.systemDefault()) {
    if (bookId <= 0 || fileId <= 0 || millis <= 0 || millis > 60_000) return
    var from = end - millis
    while (from < end) {
        val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val until = minOf(end, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val key = "$bookId:$fileId:$day"
        val drafts = account.objectAt("drafts")
        val old = drafts.optJSONObject(key)?.let(ListeningEvent::parse)
        val duration = (old?.millis ?: 0) + until - from
        val event = old?.copy(title = title, author = author, end = maxOf(old.end, until), millis = duration,
            start = minOf(old.start, until - duration))
            ?: newEvent(bookId, fileId, title, author, from, until, until - from)
        drafts.put(key, event.json())
        if (event.millis >= 30_000) sealDraft(account, key)
        from = until
    }
}

internal fun sealListeningDrafts(account: JSONObject) {
    account.objectAt("drafts").keys().asSequence().toList().forEach { sealDraft(account, it) }
}

internal fun queuedListening(account: JSONObject): List<ListeningEvent> = account.objectAt("outbox").let { rows ->
    rows.keys().asSequence().map { ListeningEvent.parse(rows.getJSONObject(it)) }.toList()
}

internal fun cloudListeningData(account: JSONObject): JSONObject {
    val data = JSONObject()
    val server = account.objectAt("serverBooks")
    server.keys().forEach { mergeStatistics(data, server.getJSONObject(it)) }
    for (name in listOf("outbox", "drafts")) {
        val pending = account.objectAt(name)
        pending.keys().forEach { key ->
            val event = ListeningEvent.parse(pending.getJSONObject(key))
            addStatisticsWindow(data, event.bookId, event.title, event.author, event.start, event.end, event.millis)
        }
    }
    return data
}

/** Atomically replace only confirmed server snapshots; newly queued listening remains untouched. */
internal fun commitListeningSync(account: JSONObject, result: ListeningSyncResult, now: Long) {
    // BookOrbit can resolve an old merged book ID to the same history as the new ID.
    // Normalize those aliases before changing either the cache or the upload queue.
    val server = if (result.replaceAll) JSONObject() else JSONObject(account.objectAt("serverBooks").toString())
    fun rows(snapshot: JSONObject): Set<Long> = snapshot.optJSONArray("_rowIds")?.let { ids ->
        (0 until ids.length()).mapTo(mutableSetOf()) { ids.getLong(it) }
    }.orEmpty()
    result.books.forEach { (id, snapshot) ->
        val incoming = rows(snapshot)
        var covered = false
        server.keys().asSequence().toList().filter { it != id.toString() }.forEach { oldId ->
            val previous = rows(server.getJSONObject(oldId))
            if (incoming.any { it in previous }) when {
                incoming.containsAll(previous) -> server.remove(oldId)
                previous.containsAll(incoming) -> covered = true
                else -> throw IOException("Book history changed; a complete refresh is required")
            }
        }
        if (!covered) server.put(id.toString(), snapshot)
    }
    val outbox = account.objectAt("outbox")
    result.uploaded.forEach { (id, event) ->
        if (outbox.optJSONObject(id)?.let(ListeningEvent::parse) == event) outbox.remove(id)
    }
    account.put("serverBooks", server)
    account.put("lastSync", now).remove("syncError")
}
