package app.yourneeds.foliocue

private const val BRIEF_HIGHLIGHT_GAP_MS = 150

internal enum class CueAdvance { CONTINUE, NEXT_TRACK, SEEK, FINISHED }

internal class CueTimeline(private val cues: List<Cue>, restoredIndex: Int) {
    var index: Int = restoredIndex.coerceIn(cues.indices)
        private set
    val cue: Cue get() = cues[index]

    fun seek(index: Int) { this.index = index.coerceIn(cues.indices) }

    fun advance(positionMs: Int, trackEnded: Boolean): CueAdvance {
        while (trackEnded || positionMs >= visibleEnd(index)) {
            val previous = cue
            if (index == cues.lastIndex) return CueAdvance.FINISHED
            index++
            if (cue.audio != previous.audio) return CueAdvance.NEXT_TRACK
            if (cue.start < previous.start) return CueAdvance.SEEK
        }
        return CueAdvance.CONTINUE
    }

    fun fragmentAt(positionMs: Int, highlightDelayMs: Int = 0): String? {
        val target = positionMs.toLong() - highlightDelayMs
        var at = index
        while (target < cues[at].start && at > 0 && continuous(at - 1, at)) at--
        while (target >= visibleEnd(at) && at < cues.lastIndex && continuous(at, at + 1)) at++
        val clip = cues[at]
        if (target >= clip.start && target < visibleEnd(at)) return clip.fragment
        // Keep the previous mark through short gaps between words on the same track.
        if (at > 0 && target < clip.start && continuous(at - 1, at)) {
            val previousEnd = visibleEnd(at - 1)
            if (target >= previousEnd && clip.start - previousEnd <= BRIEF_HIGHLIGHT_GAP_MS)
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
