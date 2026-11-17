package app.yourneeds.foliocue

internal enum class CueAdvance { CONTINUE, NEXT_TRACK, SEEK, FINISHED }

internal class CueTimeline(private val cues: List<Cue>, restoredIndex: Int) {
    var index: Int = restoredIndex.coerceIn(cues.indices)
        private set
    val cue: Cue get() = cues[index]

    fun advance(positionMs: Int, trackEnded: Boolean): CueAdvance {
        while (trackEnded || positionMs >= cue.end) {
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
        while (target >= cues[at].end && at < cues.lastIndex && continuous(at, at + 1)) at++
        val clip = cues[at]
        return clip.fragment.takeIf { target >= clip.start && target < clip.end }
    }

    private fun continuous(from: Int, to: Int): Boolean =
        cues[from].audio == cues[to].audio && cues[to].start >= cues[from].start
}
