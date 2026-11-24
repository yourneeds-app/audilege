package app.yourneeds.foliocue

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningStatsTest {
    @Test fun meterIgnoresPauseBufferingAndSeeksAndCountsRealTimeAtDoubleSpeed() {
        val meter = ListeningMeter()
        assertEquals(0L, meter.sample(0, 0, true, 2f))
        assertEquals(250L, meter.sample(250, 500, true, 2f))
        assertEquals(0L, meter.sample(500, 500, true, 2f))
        assertEquals(0L, meter.sample(750, 30_000, true, 2f))
        assertEquals(0L, meter.sample(1000, 10_000, true, 2f))
        assertEquals(0L, meter.sample(1250, 10_500, false, 2f))
        assertEquals(0L, meter.sample(20_000, 10_500, true, 2f))
        assertEquals(250L, meter.sample(20_250, 11_000, true, 2f))
        assertEquals(0L, meter.sample(30_250, 12_000, true, 2f))
    }

    @Test fun midnightSplitsDaysAndBookTotalsStayEqualToDailyTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val end = LocalDate.of(2026, 11, 25).atStartOfDay(zone).plusSeconds(2).toInstant().toEpochMilli()
        val data = JSONObject()
        addListening(data, "one", "First book", "Writer", end, 5000, zone)
        addListening(data, "two", "Second book", "Another writer", end, 2000, zone)
        val summary = listeningSummary(data, LocalDate.of(2026, 11, 25))
        assertEquals(3000L, data.getJSONObject("days").getLong("2026-11-24"))
        assertEquals(4000L, summary.today)
        assertEquals(7000L, summary.total)
        assertEquals(summary.total, summary.books.sumOf { it.millis })
        assertEquals(listOf("First book", "Second book"), summary.books.map { it.title })
    }

    @Test fun streakAllowsTodayToContinueYesterdayButBreaksAfterAMissedDay() {
        val data = JSONObject().put("days", JSONObject()
            .put("2026-11-20", 60_000).put("2026-11-21", 61_000)
            .put("2026-11-22", 59_000).put("2026-11-23", 90_000))
        val today = LocalDate.of(2026, 11, 24)
        val summary = listeningSummary(data, today)
        assertEquals(1, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals(0, listeningSummary(data, today.plusDays(1)).currentStreak)
        data.getJSONObject("days").put(today.toString(), 60_000)
        assertEquals(2, listeningSummary(data, today).currentStreak)
    }

    @Test fun chartFillsMissingDaysAndAggregatesExactlyFourSevenDayPeriods() {
        val today = LocalDate.of(2026, 11, 24)
        val data = JSONObject().put("days", JSONObject()
            .put(today.toString(), 60_000).put(today.minusDays(6).toString(), 120_000)
            .put(today.minusDays(7).toString(), 30_000).put(today.minusDays(27).toString(), 90_000)
            .put(today.minusDays(28).toString(), 1000))
        val summary = listeningSummary(data, today)
        assertEquals(28, summary.days.size)
        assertEquals(today.minusDays(27), summary.days.first().date)
        assertEquals(today, summary.days.last().date)
        val daily = listeningBars(summary.days, false)
        assertEquals(7, daily.size)
        assertEquals(summary.week, daily.sumOf { it.millis })
        assertEquals(5, daily.count { it.millis == 0L })
        val weekly = listeningBars(summary.days, true)
        assertEquals(4, weekly.size)
        assertEquals(300_000L, weekly.sumOf { it.millis })
        assertEquals(today, weekly.last().end)
        weekly.forEach { assertEquals(6L, java.time.temporal.ChronoUnit.DAYS.between(it.start, it.end)) }
    }
}
