package app.yourneeds.foliocue

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookOrbitActivityTest {
    private val date = LocalDate.of(2026, 11, 24)
    private val time = Instant.parse("2026-11-24T00:00:30Z").toEpochMilli()
    private class Server(var zone: String = "UTC") : ListeningStatisticsEndpoint {
        override val statisticsIdentity = "fixture"
        var catalogReads = 0
        val sessions = mutableListOf<RemoteListeningSession>()
        val saved = linkedMapOf<String, ListeningEvent>()
        var timezoneReads = 0
        var changeZone = false
        override fun statisticsTimezone(): String {
            timezoneReads++
            return if (changeZone && timezoneReads > 1) "Asia/Tokyo" else zone
        }
        override fun statisticsBooks(): List<StatisticsBook> { catalogReads++; return listOf(StatisticsBook(41, "Fictional book", "Fixture author")) }
        override fun statisticsSessions(bookId: Long, page: Int) = ListeningSessionPage(page, sessions.size, sessions.toList())
        override fun saveStatisticsSession(event: ListeningEvent) {
            if (saved.putIfAbsent(event.id, event) == null) sessions += RemoteListeningSession(sessions.size + 1L,
                event.fileId, event.start, event.end, event.millis, event.kind)
        }
    }
    private fun amount(account: JSONObject, kind: String? = null) =
        listeningSummary(serverActivityData(account, kind), date, 1).total

    @Test fun mergedServerHistoryReplacesCatalogOnlySnapshots() {
        val day = date.toString()
        val listen = JSONObject().put("days", JSONObject().put(day, 27000))
        val read = JSONObject().put("days", JSONObject().put(date.minusDays(1).toString(), 6000))
        val account = JSONObject().put("overview", JSONObject().put("audilegeActivities",
            JSONObject().put("listen", listen).put("read", read)))
        account.put("serverBooks", JSONObject().put("41", listen))
        assertEquals(33000L, amount(account))
        assertEquals(27000L, amount(account, "listen"))
        assertEquals(0L, amount(account, "tts"))
        assertEquals(setOf(date.minusDays(1), date), accountActiveDays(account))
    }

    @Test fun allServerTypesAndUnknownHistoryAreImportedButNotMisclassified() {
        val server = Server()
        listOf("read", "listen", "tts", null).forEachIndexed { index, kind ->
            server.sessions += RemoteListeningSession(index + 1L, 8, time - 60_000, time, (index + 1) * 10_000L, kind)
        }
        val account = JSONObject()
        commitListeningSync(account, synchronizeListening(server, emptyList(), true), time)
        assertEquals(100_000L, amount(account))
        assertEquals(10_000L, amount(account, "read"))
        assertEquals(20_000L, amount(account, "listen"))
        assertEquals(30_000L, amount(account, "tts"))
        assertEquals(40_000L, amount(account, "unknown"))
    }

    @Test fun serverTimezoneOverridesTheDeviceAndAChangeRebuildsEveryBook() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val server = Server("America/Los_Angeles")
            server.sessions += RemoteListeningSession(1, 8, time - 10_000, time, 10_000, "read")
            val account = JSONObject()
            commitListeningSync(account, synchronizeListening(server, emptyList(), true), time)
            assertEquals(10_000L, serverActivityData(account).getJSONObject("days").getLong("2026-11-23"))
            server.zone = "Asia/Tokyo"
            val changed = synchronizeListening(server, emptyList(), false, account.getString("timezone"))
            assertTrue(changed.replaceAll)
            commitListeningSync(account, changed, time)
            assertEquals(2, server.catalogReads)
            assertFalse(serverActivityData(account).getJSONObject("days").has("2026-11-23"))
            assertEquals(10_000L, serverActivityData(account).getJSONObject("days").getLong("2026-11-24"))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun midnightUsesWholeSecondsAndTheServerLargestRemainderTieRule() {
        val start = Instant.parse("2026-11-23T23:59:59.500Z").toEpochMilli()
        val result = bookOrbitDayParts(start, start + 1000, 1000, ZoneId.of("UTC"))
        assertEquals(mapOf(date.minusDays(1) to 1000L), result)
        val autumn = Instant.parse("2026-10-24T23:59:59Z").toEpochMilli()
        val autumnEnd = Instant.parse("2026-10-26T00:00:01Z").toEpochMilli()
        val parts = bookOrbitDayParts(autumn, autumnEnd, 60_001_000, ZoneId.of("Europe/Berlin"))
        assertEquals(60_001_000L, parts.values.sum())
        assertTrue(parts.values.all { it % 1000 == 0L })
        assertTrue(parts.keys.contains(LocalDate.of(2026, 10, 25)))
    }

    @Test fun positiveServerTimeQualifiesForStreaksAndGlobalStreakDoesNotChangeWithMediaFilter() {
        val account = JSONObject().put("serverDays", JSONObject().put("2026-11-22", 1000)
            .put("2026-11-23", 2000).put("2026-11-24", 0))
        val active = accountActiveDays(account)
        val days = JSONObject().apply { active.forEach { put(it.toString(), 1000) } }
        val summary = listeningSummary(JSONObject().put("days", days), date, 1)
        assertEquals(2, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals(0, listeningSummary(JSONObject().put("days", days), date.plusDays(1), 1).currentStreak)
    }

    @Test fun legacyLocalTotalsAndUnsentEventsDoNotEnterServerStatistics() {
        val account = JSONObject().put("days", JSONObject().put("2026-11-24", 999_000))
        queueListening(account, 41, 8, "Fictional book", "", time, 30_000, kind = "read")
        assertEquals(0L, amount(account))
        assertEquals(1, queuedListening(account).size)
        val server = Server()
        commitListeningSync(account, synchronizeListening(server, queuedListening(account), true), time)
        assertEquals(30_000L, amount(account, "read"))
        assertTrue(queuedListening(account).isEmpty())
    }

    @Test fun typedQueuesDoNotMergeReadingWithListeningAndRetriesReuseTheSameIds() {
        val account = JSONObject()
        queueListening(account, 41, 8, "Book", "", time, 30_000, kind = "read")
        queueListening(account, 41, 8, "Book", "", time, 30_000, kind = "listen")
        val queue = queuedListening(account)
        assertEquals(2, queue.size)
        assertEquals(setOf("read", "listen"), queue.map { it.payload().getString("sessionType") }.toSet())
        val server = Server()
        synchronizeListening(server, queue, true) // Simulate a lost success response before local commit.
        commitListeningSync(account, synchronizeListening(server, queue, true), time)
        assertEquals(2, server.sessions.size)
        assertEquals(60_000L, amount(account))
    }

    @Test fun aTimezoneChangeDuringRefreshDoesNotProduceAMixedSnapshot() {
        val server = Server().apply { changeZone = true }
        try { synchronizeListening(server, emptyList(), true); fail("Mixed timezone snapshot accepted") }
        catch (_: IOException) { }
    }

    @Test fun manualZeroWindowSessionsFollowTheServerAndUnknownTimezoneFallsBackToUtc() {
        val input = JSONObject().put("page", 1).put("total", 1).put("items", JSONArray().put(JSONObject()
            .put("id", 1).put("bookFileId", JSONObject.NULL).put("startedAt", Instant.ofEpochMilli(time).toString())
            .put("endedAt", Instant.ofEpochMilli(time).toString()).put("durationSeconds", 15).put("sessionType", "read")))
        val row = parseStatisticsSessions(input).items.single()
        assertEquals(15_000L, bookOrbitDayParts(row.start, row.end, row.millis, ZoneId.of("UTC")).values.sum())
        assertEquals("UTC", statisticsZone("not/a/timezone").id)
        assertEquals("Europe/Berlin", statisticsZone(" europe/berlin ").id)
    }

    @Test fun readingMeterCountsVisibleReadingAndStopsAfterFiveMinutesOrBackground() {
        val meter = ReadingActivityMeter()
        meter.activity(0)
        assertEquals(0L, meter.sample(0, true))
        assertEquals(1000L, meter.sample(1000, true))
        assertEquals(0L, meter.sample(2000, false))
        assertEquals(0L, meter.sample(100_000, true))
        assertEquals(1000L, meter.sample(101_000, true))
        assertEquals(0L, meter.sample(301_000, true))
        assertEquals(0L, meter.sample(302_000, true))
        meter.activity(302_000)
        assertEquals(1000L, meter.sample(303_000, true))
    }
}
