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

    fun fragmentAt(positionMs: Int): String? =
        cue.fragment.takeIf { positionMs >= cue.start && positionMs < cue.end }
}
