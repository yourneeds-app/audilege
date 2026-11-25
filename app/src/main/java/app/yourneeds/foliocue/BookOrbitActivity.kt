package app.yourneeds.foliocue

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import kotlin.math.floor

internal enum class ActivityKind(val wire: String?, val label: Int) {
    ALL(null, R.string.activity_all), READ("read", R.string.activity_read),
    LISTEN("listen", R.string.activity_listen), TTS("tts", R.string.activity_tts)
}

/** Match BookOrbit's account timezone validation and UTC fallback; never substitute the device zone. */
internal fun statisticsZone(name: String?): ZoneId {
    val normalized = name?.trim().orEmpty()
    return runCatching { ZoneId.of(normalized) }.getOrElse {
        ZoneId.getAvailableZoneIds().firstOrNull { it.equals(normalized, ignoreCase = true) }
            ?.let(ZoneId::of) ?: ZoneId.of("UTC")
    }
}

/** BookOrbit 3.9.0 splitReadingSessionByDay: whole seconds, largest remainder, earliest tie first. */
internal fun bookOrbitDayParts(start: Long, end: Long, millis: Long, zone: ZoneId): Map<LocalDate, Long> {
    if (millis <= 0) return emptyMap()
    if (end <= start) return mapOf(Instant.ofEpochMilli(start).atZone(zone).toLocalDate() to millis)
    data class Piece(val day: LocalDate, val raw: Double)
    val pieces = mutableListOf<Piece>()
    var cursor = start
    val unit = if (millis % 1000 == 0L) 1000L else 1L // keep subsecond local queue previews lossless
    val duration = millis / unit
    while (cursor < end) {
        val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = minOf(end, if (boundary > cursor) boundary else end)
        pieces += Piece(day, duration.toDouble() * (until - cursor) / (end - start))
        cursor = until
    }
    val counts = pieces.map { floor(it.raw).toLong() }.toMutableList()
    var remaining = duration - counts.sum()
    pieces.indices.sortedWith(compareByDescending<Int> { pieces[it].raw - floor(pieces[it].raw) }.thenBy { it })
        .forEach { if (remaining > 0) { counts[it]++; remaining-- } }
    return pieces.indices.filter { counts[it] > 0 }.associate { pieces[it].day to counts[it] * unit }
}

internal fun addActivityWindow(data: JSONObject, bookId: Long, title: String, author: String,
                              start: Long, end: Long, millis: Long, kind: String?, zone: ZoneId) {
    addStatisticsWindow(data, bookId, title, author, start, end, millis, zone)
    addStatisticsWindow(data.objectAt("activities").objectAt(kind ?: "unknown"), bookId, title, author,
        start, end, millis, zone)
}

/** Rebuild only from fetched server snapshots. Old local totals and pending uploads are never mixed in. */
internal fun serverActivityData(account: JSONObject, kind: String? = null): JSONObject {
    val data = JSONObject()
    // Audilege Server includes Audiobookshelf imports, even for titles outside the local catalogue.
    account.optJSONObject("overview")?.optJSONObject("audilegeActivities")?.let { activities ->
        if (kind != null) return activities.optJSONObject(kind) ?: data
        activities.keys().forEach { mode -> mergeStatistics(data, activities.getJSONObject(mode)) }
        return data
    }
    account.objectAt("serverBooks").let { books -> books.keys().forEach { key ->
        val snapshot = books.getJSONObject(key)
        val selected = if (kind == null) snapshot else snapshot.optJSONObject("activities")?.optJSONObject(kind)
        if (selected != null) mergeStatistics(data, selected)
    } }
    return data
}

internal fun accountActiveDays(account: JSONObject): Set<LocalDate> {
    val dates = mutableSetOf<LocalDate>()
    for (days in listOf(account.optJSONObject("serverDays"), serverActivityData(account).optJSONObject("days"))) {
        days?.keys()?.forEach { day -> if (days.optLong(day) > 0) runCatching { LocalDate.parse(day) }.getOrNull()?.let(dates::add) }
    }
    return dates
}
