package app.yourneeds.foliocue

import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Estimates receiver playout from submitted PCM, native queue depth and the sender's RTP latency. */
internal class AirPlayClock(private val startMs: Int, private val speed: Float, private val latencyMs: Long) {
    private data class Point(val wallMs: Long, val mediaMs: Int)
    private val inFlight = ArrayDeque<Point>()
    private var acceptedMs = 0.0
    private var sentMs = startMs
    private var heardMs = startMs

    @Synchronized fun submitted(frames: Int, rate: Int) {
        acceptedMs += frames * 1000.0 / rate
    }

    @Synchronized fun position(now: Long, backlogMs: Long): Int {
        val sent = startMs + ((acceptedMs - backlogMs).coerceAtLeast(0.0) * speed).roundToInt()
        if (sent > sentMs) {
            sentMs = sent
            inFlight.addLast(Point(now + latencyMs, sent))
        }
        while (inFlight.isNotEmpty() && inFlight.first.wallMs <= now) heardMs = inFlight.removeFirst().mediaMs
        return heardMs
    }

    @Synchronized fun drained(backlogMs: Long): Boolean =
        backlogMs == 0L && inFlight.isEmpty() && heardMs >= startMs + (acceptedMs * speed).roundToInt()
}
