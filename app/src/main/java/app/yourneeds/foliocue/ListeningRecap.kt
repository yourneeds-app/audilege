package app.yourneeds.foliocue

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

internal enum class ListeningPeriod { WEEK, MONTH, YEAR, ALL }
internal data class ListeningRange(val start: LocalDate, val end: LocalDate)
internal data class AuthorListening(val name: String, val millis: Long)
internal data class ListeningRecap(
    val range: ListeningRange, val total: Long, val activeDays: Int, val average: Long,
    val bestStreak: Int, val bars: List<ListeningBar>, val books: List<BookListening>,
    val authors: List<AuthorListening>, val peak: ListeningBar?, val unassignedBookTime: Long,
)

internal fun listeningRange(period: ListeningPeriod, anchor: LocalDate, first: LocalDate): ListeningRange = when (period) {
    ListeningPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let {
        ListeningRange(it, it.plusDays(6))
    }
    ListeningPeriod.MONTH -> ListeningRange(anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()))
    ListeningPeriod.YEAR -> ListeningRange(LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31))
    ListeningPeriod.ALL -> ListeningRange(minOf(first, anchor), anchor)
}

/** Calendar totals and dated book history are independent: never invent dates for legacy book totals. */
internal fun listeningRecap(summary: ListeningSummary, period: ListeningPeriod, anchor: LocalDate,
                            today: LocalDate = LocalDate.now()): ListeningRecap {
    val history = summary.history.filter { it.date <= today }
    val first = history.filter { it.millis > 0 }.minOfOrNull { it.date } ?: today
    val range = listeningRange(period, minOf(anchor, today), first)
    val days = history.filter { it.date >= range.start && it.date <= range.end }
    val total = days.sumOf { it.millis }
    val active = days.filter { it.millis >= summary.activityThreshold }.map { it.date }.toSortedSet()
    val streakDates = summary.streakDays?.filter { it >= range.start && it <= minOf(range.end, today) }?.sorted() ?: active.toList()
    var best = 0
    var streak = 0
    var previous: LocalDate? = null
    for (date in streakDates) {
        streak = if (previous?.plusDays(1) == date) streak + 1 else 1
        best = maxOf(best, streak)
        previous = date
    }
    val byDate = days.associate { it.date to it.millis }
    val yearlyBars = period == ListeningPeriod.ALL && range.end.year - range.start.year >= 2
    val bars = mutableListOf<ListeningBar>()
    var cursor = when {
        yearlyBars -> range.start.withDayOfYear(1)
        period == ListeningPeriod.YEAR || period == ListeningPeriod.ALL -> range.start.withDayOfMonth(1)
        else -> range.start
    }
    while (cursor <= range.end) {
        val next = when {
            yearlyBars -> cursor.plusYears(1)
            period == ListeningPeriod.YEAR || period == ListeningPeriod.ALL -> cursor.plusMonths(1)
            else -> cursor.plusDays(1)
        }
        val start = maxOf(cursor, range.start)
        val end = minOf(next.minusDays(1), range.end)
        bars += ListeningBar(start, end, byDate.filterKeys { it >= start && it <= end }.values.sum())
        cursor = next
    }
    val books = summary.books.mapNotNull { book ->
        val dated = book.days.filterKeys {
            it >= range.start && it <= minOf(range.end, today)
        }.values.sum()
        val millis = dated + if (period == ListeningPeriod.ALL)
            (book.millis - book.days.values.sum()).coerceAtLeast(0) else 0
        book.copy(millis = millis).takeIf { millis > 0 }
    }.sortedWith(compareByDescending<BookListening> { it.millis }.thenBy { it.title }.thenBy { it.key })
    // Preserve the server's author label; do not guess how multi-author names should be split.
    val authors = books.filter { it.author.isNotBlank() }.groupBy { it.author }.map { (name, titles) ->
        AuthorListening(name, titles.sumOf { it.millis })
    }.sortedWith(compareByDescending<AuthorListening> { it.millis }.thenBy { it.name })
    val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(range.start, minOf(range.end, today)) + 1
    return ListeningRecap(range, total, active.size, total / elapsedDays.coerceAtLeast(1), best, bars, books, authors,
        bars.filter { it.millis > 0 }.maxByOrNull { it.millis }, (total - books.sumOf { it.millis }).coerceAtLeast(0))
}
