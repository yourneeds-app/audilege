package app.yourneeds.foliocue

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningRecapTest {
    private val today = LocalDate.of(2026, 11, 24)
    private val zone = ZoneId.of("Europe/Berlin")
    private fun end(date: String) = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun add(data: JSONObject, book: Long, date: String, millis: Long) {
        val end = end(date)
        addStatisticsWindow(data, book, "Book $book", if (book <= 2) "Same author" else "Other author",
            end - millis, end, millis, zone)
    }

    @Test fun yearHasTwelveMonthsAndRankingsUseOnlySelectedYear() {
        val data = JSONObject()
        add(data, 1, "2025-12-31", 120_000)
        add(data, 1, "2026-01-01", 60_000)
        add(data, 2, "2026-11-23", 180_000)
        add(data, 3, "2026-11-24", 120_000)
        val recap = listeningRecap(listeningSummary(data, today), ListeningPeriod.YEAR, today, today)
        assertEquals(12, recap.bars.size)
        assertEquals(360_000L, recap.total)
        assertEquals(recap.total, recap.bars.sumOf { it.millis })
        assertEquals(3, recap.activeDays)
        assertEquals(2, recap.bestStreak)
        assertEquals(360_000L / 328, recap.average)
        assertEquals(listOf("Book 2", "Book 3", "Book 1"), recap.books.map { it.title })
        assertEquals(240_000L, recap.authors.first().millis)
        assertEquals(11, recap.peak!!.start.monthValue)
        assertEquals(0L, recap.bars.last().millis)
        val previous = listeningRecap(listeningSummary(data, today), ListeningPeriod.YEAR, today.minusYears(1), today)
        assertEquals(120_000L, previous.total)
        assertEquals(120_000L / 365, previous.average)
        assertEquals(1, previous.books.size)
    }

    @Test fun leapMonthAndIsoWeekIncludeMissingDatesAndCrossYearBoundaries() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val month = listeningRecap(listeningSummary(JSONObject(), today), ListeningPeriod.MONTH, leapDay, today)
        assertEquals(29, month.bars.size)
        assertEquals(29, month.range.end.dayOfMonth)
        assertEquals(0L, month.total)
        assertNull(month.peak)
        val week = listeningRange(ListeningPeriod.WEEK, LocalDate.of(2026, 1, 1), today)
        assertEquals(LocalDate.of(2025, 12, 29), week.start)
        assertEquals(LocalDate.of(2026, 1, 4), week.end)
    }

    @Test fun legacyTotalsArePreservedButNeverAssignedToYearOrAuthorRanking() {
        val data = JSONObject().put("days", JSONObject().put("2026-11-24", 600_000))
            .put("books", JSONObject().put("legacy", JSONObject().put("title", "Old").put("author", "Writer").put("millis", 600_000)))
        add(data, 2, "2026-11-24", 60_000)
        val summary = listeningSummary(data, today)
        val year = listeningRecap(summary, ListeningPeriod.YEAR, today, today)
        assertEquals(660_000L, year.total)
        assertEquals(600_000L, year.unassignedBookTime)
        assertEquals(listOf("Book 2"), year.books.map { it.title })
        val all = listeningRecap(summary, ListeningPeriod.ALL, today, today)
        assertEquals(2, all.books.size)
        assertEquals(0L, all.unassignedBookTime)
    }

    @Test fun localMidnightAndMergedCloudBooksKeepDatedRankings() {
        val end = LocalDate.of(2026, 1, 1).atStartOfDay(zone).plusSeconds(3).toInstant().toEpochMilli()
        val local = JSONObject()
        addListening(local, "local-book", "Local book", "Writer", end, 5000, zone)
        val previousYear = listeningRecap(listeningSummary(local, today), ListeningPeriod.YEAR, today.minusYears(1), today)
        assertEquals(2000L, previousYear.books.single().millis)
        val first = JSONObject()
        val second = JSONObject()
        add(first, 1, "2026-01-01", 10_000)
        add(second, 1, "2026-11-24", 20_000)
        mergeStatistics(first, second)
        val recap = listeningRecap(listeningSummary(first, today), ListeningPeriod.YEAR, today, today)
        assertEquals(30_000L, recap.books.single().millis)
        assertEquals(0L, recap.unassignedBookTime)
        assertEquals(0, recap.activeDays)
    }

    @Test fun allTimeUsesYearBarsForLongHistoryAndDoesNotIncludeFutureDays() {
        val data = JSONObject()
        add(data, 1, "2023-12-31", 60_000)
        add(data, 1, "2026-11-24", 60_000)
        add(data, 1, "2027-01-01", 60_000)
        val recap = listeningRecap(listeningSummary(data, today), ListeningPeriod.ALL, today, today)
        assertEquals(4, recap.bars.size)
        assertEquals(120_000L, recap.total)
        assertEquals(120_000L, recap.books.single().millis)
        assertEquals(2023, recap.bars.first().start.year)
        assertEquals(0L, recap.bars[1].millis)
    }
}
