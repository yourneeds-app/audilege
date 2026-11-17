package app.yourneeds.foliocue

import android.content.SharedPreferences
import org.json.JSONObject

internal data class ReadingPlace(
    val chapter: Int, val cfi: String?, val anchor: String?,
)

private val locationPattern = Regex("""^epubcfi\(/(\d+)/(\d+)(?:\[(?:\^.|[^\]])*\])?!([^\r\n]+)\)$""")

internal fun JSONObject.place(book: Publication, mode: Mode?): ReadingPlace? {
    val marker = optString("mediaOverlayFragment", "").takeUnless { it == "null" || it.isBlank() }
    if (mode != Mode.Read && marker != null) {
        val section = optInt("mediaOverlaySectionIndex", -1)
        val path = marker.substringBefore('#')
        val index = if (section in book.spine.indices && (path.isBlank() || book.spine[section].path == path)) section
            else book.spine.indexOfFirst { it.path == path }
        if (index in book.spine.indices) return ReadingPlace(index, null,
            marker.substringAfter('#', "").ifBlank { null })
    }
    val cfi = optString("cfi", "")
    val match = locationPattern.matchEntire(cfi) ?: return null
    val step = match.groupValues[2].toIntOrNull() ?: return null
    val index = step / 2 - 1
    if (step % 2 != 0 || index !in book.spine.indices || match.groupValues[1].toIntOrNull() != book.packageSpineStep) return null
    return ReadingPlace(index, match.groupValues[3], null)
}

private val progressFields = listOf(
    "cfi", "pageNumber", "percentage", "positionSeconds", "mediaOverlayFragment", "mediaOverlaySectionIndex",
    "koboLocationSource", "koboLocationType", "koboLocationValue", "koboContentSourceProgressPercent", "koreaderProgress",
)
private val clockFields = progressFields + listOf("updatedAt", "textUpdatedAt", "narrationUpdatedAt")

internal class ReadingSync(
    private val online: OnlineBook, initial: JSONObject?, private val preferences: SharedPreferences, key: String,
) {
    private val pendingKey = "$key.sync.pending"
    private val baseKey = "$key.sync.base"
    private fun stored(key: String) = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private var baseline = stored(baseKey) ?: initial
    var pending = stored(pendingKey)
        private set
    var remote = initial
        private set

    fun remember(place: JSONObject) {
        pending = place
        preferences.edit().putString(pendingKey, place.toString())
            .putString(baseKey, baseline?.toString()).apply()
    }

    fun discardLocal() {
        pending = null
        baseline = remote
        preferences.edit().remove(pendingKey).remove(baseKey).apply()
    }

    fun exchange(candidate: JSONObject, force: Boolean): SyncResult {
        val latest = online.progress()
        val base = baseline
        if (!force && (base == null || clockFields.any { base.opt(it)?.toString() != latest.opt(it)?.toString() })) {
            return SyncResult(SyncState.Conflict, latest)
        }
        val payload = JSONObject()
        progressFields.forEach { field -> if (latest.has(field)) payload.put(field, latest.get(field)) }
        val keys = candidate.keys()
        while (keys.hasNext()) {
            val field = keys.next()
            payload.put(field, candidate.get(field))
        }
        online.updateProgress(payload)
        return SyncResult(SyncState.Synced, online.progress())
    }

    fun accept(result: SyncResult, sent: JSONObject) {
        remote = result.remote
        if (result.state == SyncState.Synced) {
            baseline = result.remote
            if (pending === sent) discardLocal()
            else pending?.let(::remember)
        }
    }
}

internal enum class SyncState(val label: Int) {
    Synced(R.string.sync_status), Pending(R.string.sync_pending), Conflict(R.string.sync_conflict),
}
internal data class SyncResult(val state: SyncState, val remote: JSONObject)

internal fun Publication.progressPayload(chapter: Int, cfi: String?, anchor: String?, fraction: Double, narration: Boolean): JSONObject? {
    if (cfi == null && (anchor == null || !narration)) return null
    val percentage = (100.0 * (chapter + fraction.coerceIn(0.0, 1.0)) / spine.size).coerceIn(0.0, 100.0)
    val payload = JSONObject().put("source", if (narration) "narration" else "text").put("percentage", percentage)
    if (cfi != null) payload.put("cfi", "epubcfi(/$packageSpineStep/${(chapter + 1) * 2}!$cfi)")
    if (narration && anchor != null) {
        payload.put("mediaOverlayFragment", "${spine[chapter].path}#$anchor")
        payload.put("mediaOverlaySectionIndex", chapter)
        payload.put("positionSeconds", JSONObject.NULL)
    }
    return payload
}

