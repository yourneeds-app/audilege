package app.yourneeds.foliocue

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import org.json.JSONObject

/** Counts elapsed listening time, never seeks, buffering or a paused media clock. */
internal class ListeningMeter {
    private var previousTime: Long? = null
    private var previousPosition = 0
    fun reset() { previousTime = null }
    fun sample(now: Long, position: Int, active: Boolean, speed: Float): Long {
        if (!active) { reset(); return 0 }
        val previous = previousTime
        val moved = position.toLong() - previousPosition
        previousTime = now
        previousPosition = position
        if (previous == null || !speed.isFinite() || speed <= 0) return 0
        val elapsed = now - previous
        if (elapsed !in 1..2000 || moved <= 0 || moved > elapsed * speed + 1000) return 0
        return minOf(elapsed, (moved / speed).roundToLong()).coerceAtLeast(0)
    }
}

internal data class BookListening(val key: String, val title: String, val author: String, val millis: Long)
internal data class ListeningSummary(
    val total: Long, val today: Long, val week: Long, val currentStreak: Int, val bestStreak: Int,
    val books: List<BookListening>,
)

internal fun listeningSummary(data: JSONObject, today: LocalDate): ListeningSummary {
    val days = data.optJSONObject("days") ?: JSONObject()
    val totals = days.keys().asSequence().mapNotNull { key ->
        runCatching { LocalDate.parse(key) to days.optLong(key).coerceAtLeast(0) }.getOrNull()
    }.toMap()
    // One minute on a local calendar day qualifies as a listening day.
    val active = totals.filter { it.value >= 60_000 && it.key <= today }.keys.sorted()
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    active.forEach { day ->
        run = if (previous?.plusDays(1) == day) run + 1 else 1
        best = maxOf(best, run)
        previous = day
    }
    var current = 0
    var cursor = if (today in active) today else today.minusDays(1)
    val activeSet = active.toSet()
    while (cursor in activeSet) { current++; cursor = cursor.minusDays(1) }
    val records = data.optJSONObject("books") ?: JSONObject()
    val books = records.keys().asSequence().mapNotNull { key -> records.optJSONObject(key)?.let {
        BookListening(key, it.optString("title"), it.optString("author"), it.optLong("millis").coerceAtLeast(0))
    } }.filter { it.millis > 0 }.sortedByDescending { it.millis }.toList()
    return ListeningSummary(totals.values.sum(), totals[today] ?: 0,
        totals.filter { (day, _) -> day >= today.minusDays(6) && day <= today }.values.sum(), current, best, books)
}

/** Split at local midnight so a session spanning two days can contribute to both. */
internal fun addListening(data: JSONObject, key: String, title: String, author: String, endMs: Long,
                          durationMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
    if (durationMs <= 0 || durationMs > 60_000) return
    val days = data.optJSONObject("days") ?: JSONObject().also { data.put("days", it) }
    var from = Instant.ofEpochMilli(endMs - durationMs)
    val end = Instant.ofEpochMilli(endMs)
    while (from < end) {
        val day = from.atZone(zone).toLocalDate()
        val until = minOf(end, day.plusDays(1).atStartOfDay(zone).toInstant())
        val count = until.toEpochMilli() - from.toEpochMilli()
        days.put(day.toString(), days.optLong(day.toString()) + count)
        from = until
    }
    val books = data.optJSONObject("books") ?: JSONObject().also { data.put("books", it) }
    val book = books.optJSONObject(key) ?: JSONObject().also { books.put(key, it) }
    book.put("title", title).put("author", author).put("millis", book.optLong("millis") + durationMs)
}

internal class ListeningStats(context: Context) {
    private val prefs = context.getSharedPreferences("listening-statistics", Context.MODE_PRIVATE)
    private fun data(profile: String) = runCatching { JSONObject(prefs.getString(profile, "{}")!!) }.getOrElse { JSONObject() }
    fun record(book: OpenBook, durationMs: Long) {
        if (durationMs <= 0) return
        val profile = book.file.parentFile?.name ?: "local"
        val data = data(profile)
        addListening(data, keyOf(book.file.absolutePath), book.publication.title, book.publication.author,
            System.currentTimeMillis(), durationMs)
        prefs.edit().putString(profile, data.toString()).apply()
    }
    fun summary(profile: String): ListeningSummary = listeningSummary(data(profile), LocalDate.now())
}
