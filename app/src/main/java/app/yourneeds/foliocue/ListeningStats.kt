package app.yourneeds.foliocue

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

internal data class BookListening(val key: String, val title: String, val author: String, val millis: Long,
                                 val days: Map<LocalDate, Long> = emptyMap())
internal data class ListeningDay(val date: LocalDate, val millis: Long)
internal data class ListeningBar(val start: LocalDate, val end: LocalDate, val millis: Long)
internal data class ListeningSummary(
    val total: Long, val today: Long, val week: Long, val currentStreak: Int, val bestStreak: Int,
    val books: List<BookListening>, val days: List<ListeningDay> = emptyList(),
    val history: List<ListeningDay> = days,
)

internal fun listeningBars(days: List<ListeningDay>, weekly: Boolean): List<ListeningBar> =
    if (weekly) days.takeLast(28).chunked(7).map { week ->
        ListeningBar(week.first().date, week.last().date, week.sumOf { it.millis })
    } else days.takeLast(7).map { ListeningBar(it.date, it.date, it.millis) }

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
        val dates = it.optJSONObject("days") ?: JSONObject()
        BookListening(key, it.optString("title"), it.optString("author"), it.optLong("millis").coerceAtLeast(0),
            dates.keys().asSequence().mapNotNull { date -> runCatching {
                LocalDate.parse(date) to dates.optLong(date).coerceAtLeast(0)
            }.getOrNull() }.toMap())
    } }.filter { it.millis > 0 }.sortedByDescending { it.millis }.toList()
    val recentDays = (27 downTo 0).map { ago -> today.minusDays(ago.toLong()).let { ListeningDay(it, totals[it] ?: 0) } }
    return ListeningSummary(totals.values.sum(), totals[today] ?: 0,
        totals.filter { (day, _) -> day >= today.minusDays(6) && day <= today }.values.sum(), current, best, books, recentDays,
        totals.entries.sortedBy { it.key }.map { ListeningDay(it.key, it.value) })
}

/** Split at local midnight so a session spanning two days can contribute to both. */
internal fun addListening(data: JSONObject, key: String, title: String, author: String, endMs: Long,
                          durationMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
    if (durationMs <= 0 || durationMs > 60_000) return
    val days = data.optJSONObject("days") ?: JSONObject().also { data.put("days", it) }
    val books = data.objectAt("books")
    val book = books.objectAt(key)
    val bookDays = book.objectAt("days")
    var from = Instant.ofEpochMilli(endMs - durationMs)
    val end = Instant.ofEpochMilli(endMs)
    while (from < end) {
        val day = from.atZone(zone).toLocalDate()
        val until = minOf(end, day.plusDays(1).atStartOfDay(zone).toInstant())
        val count = until.toEpochMilli() - from.toEpochMilli()
        days.put(day.toString(), days.optLong(day.toString()) + count)
        bookDays.put(day.toString(), bookDays.optLong(day.toString()) + count)
        from = until
    }
    book.put("title", title).put("author", author).put("millis", book.optLong("millis") + durationMs)
}

internal class ListeningStats(context: Context) {
    private val prefs = context.getSharedPreferences("listening-statistics", Context.MODE_PRIVATE)
    private fun data(profile: String) = runCatching { JSONObject(prefs.getString(profile, "{}")!!) }.getOrElse { JSONObject() }
    private fun <T> edit(profile: String, durable: Boolean = false, operation: (JSONObject) -> T): T = synchronized(storageLock) {
        val root = data(profile)
        val result = operation(root)
        val editor = prefs.edit().putString(profile, root.toString())
        if (durable) check(editor.commit()) else editor.apply()
        result
    }
    fun record(book: OpenBook, durationMs: Long) {
        if (durationMs <= 0) return
        val profile = book.file.parentFile?.name ?: "local"
        edit(profile) { root ->
            val online = book.online
            if (online == null) addListening(root, keyOf(book.file.absolutePath), book.publication.title,
                book.publication.author, System.currentTimeMillis(), durationMs)
            else queueListening(statisticsAccount(root, online.catalog.statisticsIdentity), online.detail.id,
                online.detail.fileId, book.publication.title, book.publication.author, System.currentTimeMillis(), durationMs)
        }
    }

    fun summary(profile: String, identity: String? = null): ListeningSummary = synchronized(storageLock) {
        val root = data(profile)
        val selected = if (identity == null) root else cloudListeningData(statisticsAccount(root, identity))
        listeningSummary(selected, LocalDate.now())
    }

    fun syncInfo(profile: String, identity: String): StatisticsSyncInfo = synchronized(storageLock) {
        val account = statisticsAccount(data(profile), identity)
        val pending = account.objectAt("outbox").length() + account.objectAt("drafts").length()
        StatisticsSyncInfo(account.optLong("lastSync"), pending,
            account.optString("syncError").takeIf(String::isNotBlank))
    }

    suspend fun synchronize(profile: String, endpoint: ListeningStatisticsEndpoint, discover: Boolean) {
        val mutex = synchronized(syncLocks) { syncLocks.getOrPut("$profile:${endpoint.statisticsIdentity}") { Mutex() } }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val queued = edit(profile, durable = true) { root ->
                        val account = statisticsAccount(root, endpoint.statisticsIdentity)
                        sealListeningDrafts(account)
                        queuedListening(account)
                    }
                    if (queued.isEmpty() && !discover) return@withLock
                    val result = synchronizeListening(endpoint, queued, discover)
                    edit(profile, durable = true) { root ->
                        commitListeningSync(statisticsAccount(root, endpoint.statisticsIdentity), result, System.currentTimeMillis())
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    val reason = when {
                        failure is OrbitHttp && failure.status in setOf(400, 403, 404, 405, 422) -> "unsupported"
                        failure is CatalogAccessException && failure.messageId == R.string.session_expired -> "sign_in"
                        failure is CatalogAccessException && failure.messageId == R.string.account_permission -> "unsupported"
                        else -> "offline"
                    }
                    edit(profile) { statisticsAccount(it, endpoint.statisticsIdentity).put("syncError", reason) }
                }
            }
        }
    }

    companion object {
        private val storageLock = Any()
        private val syncLocks = mutableMapOf<String, Mutex>()
    }
}

internal data class StatisticsSyncInfo(val lastSync: Long, val pending: Int, val error: String?)
