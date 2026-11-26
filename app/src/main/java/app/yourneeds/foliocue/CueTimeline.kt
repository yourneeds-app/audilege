package app.yourneeds.foliocue

private const val BRIEF_HIGHLIGHT_GAP_MS = 150
private const val MIN_TRACK_TAIL_MS = 250
private const val MAX_TRACK_TAIL_MS = 15_000

internal enum class CueAdvance { CONTINUE, NEXT_TRACK, SEEK, FINISHED }

internal class CueTimeline(private val cues: List<Cue>, restoredIndex: Int) {
    var index: Int = restoredIndex.coerceIn(cues.indices)
        private set
    val cue: Cue get() = cues[index]

    fun seek(index: Int) { this.index = index.coerceIn(cues.indices) }

    /** A short unmarked tail may still contain narration. Preserve explicit clip jumps. */
    fun unmarkedTrackTail(durationMs: Int): Int? {
        var last = index
        while (last < cues.lastIndex && continuous(last, last + 1)) last++
        if (cues.drop(last + 1).any { it.audio == cue.audio }) return null
        return last.takeIf { durationMs - cues[last].end in MIN_TRACK_TAIL_MS..MAX_TRACK_TAIL_MS }
    }

    fun advance(positionMs: Int, trackEnded: Boolean, waitForTrackEndAt: Int? = null): CueAdvance {
        while (trackEnded || positionMs >= visibleEnd(index)) {
            if (!trackEnded && index == waitForTrackEndAt) return CueAdvance.CONTINUE
            val previous = cue
            if (index == cues.lastIndex) return CueAdvance.FINISHED
            index++
            if (cue.audio != previous.audio) return CueAdvance.NEXT_TRACK
            if (cue.start < previous.start) return CueAdvance.SEEK
        }
        return CueAdvance.CONTINUE
    }

    fun fragmentAt(positionMs: Int, highlightDelayMs: Int = 0,
                   gapLimitMs: Int = BRIEF_HIGHLIGHT_GAP_MS): String? {
        val target = positionMs.toLong() - highlightDelayMs
        var at = index
        while (target < cues[at].start && at > 0 && continuous(at - 1, at)) at--
        while (target >= visibleEnd(at) && at < cues.lastIndex && continuous(at, at + 1)) at++
        val clip = cues[at]
        if (target >= clip.start && target < visibleEnd(at)) return clip.fragment
        // Keep the previous mark through short gaps between words on the same track.
        if (at > 0 && target < clip.start && continuous(at - 1, at)) {
            val previousEnd = visibleEnd(at - 1)
            if (target >= previousEnd && clip.start - previousEnd <= gapLimitMs)
                return cues[at - 1].fragment
        }
        return null
    }

    private fun visibleEnd(at: Int): Int {
        val clip = cues[at]
        val next = cues.getOrNull(at + 1)
        // Some EPUBs overlap adjacent words. Once the next word starts it owns the highlight.
        if (next != null && next.audio == clip.audio && next.start >= clip.start) {
            return minOf(clip.end, next.start)
        }
        return clip.end
    }

    private fun continuous(from: Int, to: Int): Boolean =
        cues[from].audio == cues[to].audio && cues[to].start >= cues[from].start
}
