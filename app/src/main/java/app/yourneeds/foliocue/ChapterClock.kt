package app.yourneeds.foliocue

internal data class ChapterPoint(val index: Int, val mediaMs: Int)
internal data class TrackProgress(val elapsedMs: Int, val durationMs: Int)

/** Maps chapter time across reused files, discontinuities and split audio tracks. */
internal class ChapterClock(private val cues: List<Cue>) {
    private data class Span(val first: Int, val last: Int, val origin: Int, val end: Int, val offset: Long)
    private val spans: List<Span>
    val duration: Int

    init {
        require(cues.isNotEmpty())
        val parts = mutableListOf<Span>()
        var first = 0
        var total = 0L
        for (i in 1..cues.size) {
            if (i < cues.size && cues[i].audio == cues[i - 1].audio && cues[i].start >= cues[i - 1].start) continue
            val start = cues[first].start
            val end = cues[i - 1].end.coerceAtLeast(start + 1)
            parts += Span(first, i - 1, start, end, total)
            total += end.toLong() - start
            first = i
        }
        spans = parts
        duration = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun elapsed(index: Int, mediaMs: Int): Int {
        val span = spans.lastMatching { it.first <= index }
        return (span.offset + mediaMs.coerceIn(span.origin, span.end) - span.origin).coerceAtMost(duration.toLong()).toInt()
    }

    fun mediaEnd(index: Int): Int = spans.lastMatching { it.first <= index }.end

    fun point(elapsedMs: Int): ChapterPoint {
        val elapsed = elapsedMs.coerceIn(0, duration - 1)
        val span = spans.lastMatching { it.offset <= elapsed }
        val position = (span.origin.toLong() + elapsed - span.offset).toInt()
        var low = span.first
        var high = span.last
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (cues[mid].start <= position) low = mid else high = mid - 1
        }
        return ChapterPoint(low, position)
    }

    private inline fun <T> List<T>.lastMatching(matches: (T) -> Boolean): T {
        var low = 0
        var high = lastIndex
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (matches(this[mid])) low = mid else high = mid - 1
        }
        return this[low]
    }
}
