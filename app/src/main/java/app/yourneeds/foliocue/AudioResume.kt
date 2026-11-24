package app.yourneeds.foliocue

import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

private fun JSONObject.number(field: String): Double? = optDouble(field, Double.NaN).takeIf(Double::isFinite)
private fun JSONObject.audioPercent() = number("narrationPercentage") ?: number("percentage")

private fun JSONObject.matchesMarker(other: JSONObject?): Boolean =
    other != null && !isNull("mediaOverlayFragment") && optString("mediaOverlayFragment") == other.optString("mediaOverlayFragment") &&
        optInt("mediaOverlaySectionIndex", -1) == other.optInt("mediaOverlaySectionIndex", -1)

/** The server can advance within the same SMIL fragment, while this phone can also have listened offline. */
internal fun canRestoreLocalAudio(selected: JSONObject, local: JSONObject?, previousServer: JSONObject?): Boolean {
    if (local == null || local.optString("source") != "narration" || !selected.matchesMarker(local)) return false
    val seconds = selected.number("positionSeconds")
    val percent = selected.audioPercent()
    val previousMarker = selected.matchesMarker(previousServer)
    if (previousMarker && seconds == previousServer?.number("positionSeconds") && percent == previousServer?.audioPercent()) return true
    if (seconds != null && seconds != local.number("positionSeconds")) return false
    val localPercent = local.audioPercent()
    return !previousMarker || percent == null || localPercent == null || percent == previousServer?.audioPercent() ||
        abs(percent - localPercent) < 0.01
}

/** BookOrbit seconds count clip durations across the whole book. Only map seconds inside the named clip. */
internal fun elapsedWithinCue(seconds: Double?, cueSeconds: Double?, cueLengthMs: Int, chapterStartMs: Int): Int? {
    if (seconds == null || cueSeconds == null) return null
    val offset = (seconds - cueSeconds) * 1000.0
    if (offset !in 0.0..cueLengthMs.toDouble()) return null
    return (chapterStartMs.toLong() + offset.roundToInt()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** A text locator or a different narration cue can be a newer position from another device. */
internal fun matchingAudioElapsed(elapsedMs: Int, savedAnchor: String?, requestedAnchor: String?, cfi: String?): Int? =
    elapsedMs.takeIf { it >= 0 && cfi == null && (requestedAnchor == null || requestedAnchor == savedAnchor) }

/** Pausing again inside the replayed passage must not walk the listener backwards on every play. */
internal fun resumeAfterPauseMs(positionMs: Int, replayUntilMs: Int, rewindSeconds: Int): Int =
    (maxOf(positionMs, replayUntilMs) - rewindSeconds.coerceIn(0, 30) * 1000).coerceIn(0, positionMs)
