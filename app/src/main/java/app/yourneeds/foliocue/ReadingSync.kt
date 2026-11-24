package app.yourneeds.foliocue

import android.content.SharedPreferences
import java.lang.ref.WeakReference
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ReadingPlace(val chapter: Int, val cfi: String?, val anchor: String?)

private val locationPattern = Regex("""^epubcfi\(/(\d+)/(\d+)(?:\[(?:\^.|[^\]])*\])?!([^\r\n]+)\)$""")

private fun JSONObject.valueOf(field: String): String? =
    if (isNull(field)) null else optString(field).takeIf(String::isNotBlank)

private fun JSONObject.stamp(field: String): Long? =
    valueOf(field)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

internal fun JSONObject.place(book: Publication, mode: Mode?): ReadingPlace? {
    val match = valueOf("cfi")?.let(locationPattern::matchEntire)
    val step = match?.groupValues?.get(2)?.toIntOrNull()
    val text = if (match != null && step != null && step % 2 == 0 && step / 2 - 1 in book.spine.indices &&
        match.groupValues[1].toIntOrNull() == book.packageSpineStep) {
        ReadingPlace(step / 2 - 1, match.groupValues[3], null)
    } else null

    val marker = valueOf("mediaOverlayFragment")
    val audio = marker?.let {
        val section = optInt("mediaOverlaySectionIndex", -1)
        val path = it.substringBefore('#').removePrefix("/")
        val index = if (section in book.spine.indices && (path.isEmpty() || book.spine[section].path == path)) section
            else book.spine.indexOfFirst { item -> item.path == path }
        if (index >= 0) ReadingPlace(index, null, it.substringAfter('#', "").ifBlank { null }) else null
    }
    val textStamp = stamp("textUpdatedAt")
    val narrationStamp = stamp("narrationUpdatedAt")
    val textIsNewer = textStamp != null && (narrationStamp == null || textStamp > narrationStamp)
    return if (mode == Mode.Read || textIsNewer) text ?: audio else audio ?: text
}

private val textFields = listOf("cfi", "pageNumber", "percentage", "koboLocationSource", "koboLocationType",
    "koboLocationValue", "koboContentSourceProgressPercent", "koreaderProgress")
private val narrationFields = listOf("mediaOverlayFragment", "mediaOverlaySectionIndex", "positionSeconds")
private val locatorFields = textFields + narrationFields

private fun comparable(value: JSONObject?, key: String): Any? = when (val raw = value?.opt(key)) {
    null, JSONObject.NULL -> null
    is Number -> raw.toDouble().takeUnless { key == "percentage" && it == 0.0 }
    else -> raw
}

/** Ignore server bookkeeping and numeric formatting when comparing two device positions. */
internal fun samePosition(a: JSONObject?, b: JSONObject?): Boolean =
    locatorFields.all { comparable(a, it) == comparable(b, it) }

internal fun serverHasCandidate(server: JSONObject, candidate: JSONObject): Boolean {
    val marker = candidate.valueOf("mediaOverlayFragment")
    return if (candidate.optString("source") == "narration" && marker != null) {
        server.valueOf("mediaOverlayFragment") == marker &&
            comparable(server, "mediaOverlaySectionIndex") == comparable(candidate, "mediaOverlaySectionIndex")
    } else candidate.valueOf("cfi") != null && candidate.valueOf("cfi") == server.valueOf("cfi")
}

/** Text updates leave narration to the server. Resending an older marker would undo another device's listening. */
internal fun writePayload(candidate: JSONObject, latest: JSONObject): JSONObject {
    val payload = JSONObject().put("source", candidate.optString("source", "text"))
    val fields = if (payload.optString("source") == "narration") locatorFields else textFields
    fields.forEach { field ->
        if (candidate.has(field)) payload.put(field, candidate.get(field))
        else if (field in textFields && latest.has(field)) payload.put(field, latest.get(field))
    }
    // Before BookOrbit separated update clocks, omitting a narration field could clear it.
    if (!latest.has("textUpdatedAt") && !latest.has("narrationUpdatedAt")) {
        narrationFields.forEach { field ->
            if (!payload.has(field) && latest.has(field)) payload.put(field, latest.get(field))
        }
    }
    return payload
}

internal class ReadingSync private constructor(
    @Volatile private var online: ProgressEndpoint, initial: JSONObject?, private val preferences: SharedPreferences, key: String,
) {
    private val pendingKey = "$key.sync.pending"
    private val baseKey = "$key.sync.base"
    private val timeKey = "$key.sync.time"
    private val mutex = Mutex()
    private fun stored(key: String) = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    @Volatile var pending: JSONObject? = stored(pendingKey)
        private set
    @Volatile private var baseline: JSONObject? = stored(baseKey)
    @Volatile var remote: JSONObject? = initial ?: baseline
        private set
    @Volatile private var conflict = false
    val local get() = pending ?: baseline
    val resume get() = pending ?: remote
    val localChangedAt: Long get() = preferences.getLong(timeKey, 0L).takeIf { it > 0 }
        ?: changedAt(baseline) ?: 0L
    val remoteChangedAt: Long? get() = changedAt(remote)
    val state: SyncState get() = when {
        conflict -> SyncState.Conflict
        pending != null || remote == null -> SyncState.Pending
        else -> SyncState.Synced
    }

    init { receiveInitial(initial) }

    @Synchronized private fun receiveInitial(initial: JSONObject?) {
        if (initial == null) return
        remote = initial
        if (pending == null) {
            baseline = initial
            conflict = false
            preferences.edit().putString(baseKey, initial.toString()).apply()
        } else conflict = !samePosition(baseline, initial) && !serverHasCandidate(initial, pending!!)
    }

    @Synchronized fun remember(place: JSONObject) {
        if (pending?.toString() == place.toString()) return
        pending = place
        preferences.edit().putString(pendingKey, place.toString())
            .putLong(timeKey, System.currentTimeMillis()).apply()
    }

    @Synchronized fun discardLocal() {
        pending = null
        baseline = remote
        conflict = false
        preferences.edit().remove(pendingKey).remove(timeKey).putString(baseKey, remote?.toString()).apply()
    }

    @Synchronized fun keepLocal() {
        if (pending != null) return
        val saved = baseline ?: return
        if (saved.valueOf("cfi") == null && saved.valueOf("mediaOverlayFragment") == null) return
        val copy = JSONObject(saved.toString())
        val useNarration = saved.valueOf("mediaOverlayFragment") != null &&
            (saved.stamp("narrationUpdatedAt") ?: 0L) >= (saved.stamp("textUpdatedAt") ?: 0L)
        copy.put("source", if (useNarration) "narration" else "text")
        if (copy.isNull("percentage")) copy.put("percentage", 0)
        remember(copy)
    }

    suspend fun refresh(): SyncState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (pending != null) return@withLock exchange(false)
            val latest = online.progress()
            synchronized(this@ReadingSync) {
                remote = latest
                if (pending != null) return@synchronized state
                conflict = !samePosition(baseline, latest)
                if (!conflict) discardLocal()
                state
            }
        }
    }

    suspend fun flush(force: Boolean = false): SyncState = withContext(Dispatchers.IO) {
        mutex.withLock { exchange(force) }
    }

    private fun exchange(force: Boolean): SyncState {
        val candidate = pending ?: return state
        if (conflict && !force) return SyncState.Conflict
        val expected = if (force && conflict) remote else baseline
        val latest = online.progress()
        if (serverHasCandidate(latest, candidate)) return acknowledge(latest, candidate)
        if (!samePosition(expected, latest)) {
            synchronized(this) { remote = latest; conflict = true }
            return SyncState.Conflict
        }
        online.updateProgress(writePayload(candidate, latest))
        val saved = online.progress()
        if (!serverHasCandidate(saved, candidate)) {
            synchronized(this) { remote = saved; conflict = true }
            return SyncState.Conflict
        }
        return acknowledge(saved, candidate)
    }

    @Synchronized private fun acknowledge(latest: JSONObject, candidate: JSONObject): SyncState {
        remote = latest
        baseline = latest
        conflict = false
        if (pending === candidate) discardLocal()
        else preferences.edit().putString(baseKey, latest.toString()).apply()
        return state
    }

    companion object {
        private val sessions = mutableMapOf<String, WeakReference<ReadingSync>>()

        @Synchronized fun open(online: ProgressEndpoint, initial: JSONObject?, preferences: SharedPreferences, key: String): ReadingSync {
            sessions.entries.removeAll { it.value.get() == null }
            sessions[key]?.get()?.let { live ->
                live.online = online
                live.receiveInitial(initial)
                return live
            }
            return ReadingSync(online, initial, preferences, key).also { sessions[key] = WeakReference(it) }
        }

        private fun changedAt(data: JSONObject?) = data?.let {
            listOfNotNull(it.stamp("textUpdatedAt"), it.stamp("narrationUpdatedAt")).maxOrNull() ?: it.stamp("updatedAt")
        }
    }
}

internal enum class SyncState(val label: Int) {
    Synced(R.string.sync_status), Pending(R.string.sync_pending), Conflict(R.string.sync_conflict),
}

internal fun Publication.progressPayload(chapter: Int, cfi: String?, anchor: String?, fraction: Double, narration: Boolean): JSONObject? {
    if (chapter !in spine.indices || (cfi == null && (anchor == null || !narration)) || !fraction.isFinite()) return null
    val percentage = (100.0 * (chapter + fraction.coerceIn(0.0, 1.0)) / spine.size).coerceIn(0.0, 100.0)
    val payload = JSONObject().put("source", if (narration) "narration" else "text").put("percentage", percentage)
    if (cfi != null) payload.put("cfi", "epubcfi(/$packageSpineStep/${(chapter + 1) * 2}!$cfi)")
    if (narration && anchor != null) {
        payload.put("mediaOverlayFragment", "${spine[chapter].path}#$anchor")
        payload.put("mediaOverlaySectionIndex", chapter)
        payload.put("positionSeconds", narrationSeconds(chapter, anchor) ?: JSONObject.NULL)
    }
    return payload
}
