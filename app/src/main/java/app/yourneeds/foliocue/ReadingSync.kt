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
    val preferText = when {
        textStamp != narrationStamp -> (textStamp ?: Long.MIN_VALUE) > (narrationStamp ?: Long.MIN_VALUE)
        optString("source") == "narration" -> false
        optString("source") == "text" -> true
        else -> mode == Mode.Read
    }
    return if (preferText) text ?: audio else audio ?: text
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

private fun JSONObject.newestLocator(): Int? {
    if (valueOf("cfi") == null || valueOf("mediaOverlayFragment") == null) return null
    val text = stamp("textUpdatedAt")
    val narration = stamp("narrationUpdatedAt")
    if (text == null && narration == null) return null
    return (text ?: Long.MIN_VALUE).compareTo(narration ?: Long.MIN_VALUE)
}

/** Reaffirming the same locator is harmless, switching the newest locator can move the reader. */
internal fun samePosition(a: JSONObject?, b: JSONObject?): Boolean =
    locatorFields.all { comparable(a, it) == comparable(b, it) } && a?.newestLocator() == b?.newestLocator()

private fun sameNarration(server: JSONObject, candidate: JSONObject): Boolean =
    candidate.optString("source") == "narration" && candidate.valueOf("mediaOverlayFragment") != null &&
        server.valueOf("mediaOverlayFragment") == candidate.valueOf("mediaOverlayFragment") &&
        comparable(server, "mediaOverlaySectionIndex") == comparable(candidate, "mediaOverlaySectionIndex")

internal fun serverHasCandidate(server: JSONObject, candidate: JSONObject): Boolean {
    val cfi = candidate.valueOf("cfi")
    if (candidate.optString("source") != "narration") {
        if (cfi == null || cfi != server.valueOf("cfi")) return false
        val narrationTime = server.stamp("narrationUpdatedAt") ?: return true
        return server.valueOf("mediaOverlayFragment") == null ||
            (server.stamp("textUpdatedAt") ?: Long.MIN_VALUE) >= narrationTime
    }
    if (!sameNarration(server, candidate)) return false
    if (cfi == null || cfi == server.valueOf("cfi")) return true
    // BookOrbit can convert the same fragment to its own CFI with ID assertions.
    // It stamps both clocks together only when narration also moved the text.
    val narrationStamp = server.stamp("narrationUpdatedAt") ?: return false
    return server.valueOf("cfi") != null && narrationStamp == server.stamp("textUpdatedAt")
}

/** A narration POST can commit its marker while BookOrbit keeps a previous, later text position. */
private fun retainedText(server: JSONObject, previous: JSONObject?, candidate: JSONObject): Boolean =
    candidate.valueOf("cfi") != null && sameNarration(server, candidate) &&
        textFields.all { comparable(server, it) == comparable(previous, it) } &&
        server.valueOf("textUpdatedAt") == previous?.valueOf("textUpdatedAt")

/** Text updates leave narration to the server. Resending an older marker would undo another device's listening. */
internal fun writePayload(candidate: JSONObject, latest: JSONObject): JSONObject {
    val payload = JSONObject().put("source", candidate.optString("source", "text"))
    val narration = payload.optString("source") == "narration"
    val legacy = !latest.has("textUpdatedAt") && !latest.has("narrationUpdatedAt")
    val fields = if (narration) locatorFields else textFields
    fields.forEach { field ->
        if (candidate.has(field)) payload.put(field, candidate.get(field))
        else if (field == "cfi" && narration && !legacy) return@forEach
        else if (field in textFields && latest.has(field)) payload.put(field, latest.get(field))
    }
    // Before BookOrbit separated update clocks, omitting a narration field could clear it.
    if (legacy) {
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
    private val submittedKey = "$key.sync.submitted"
    private val baseKey = "$key.sync.base"
    private val timeKey = "$key.sync.time"
    private val mutex = Mutex()
    private fun stored(key: String) = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    @Volatile var pending: JSONObject? = stored(pendingKey)
        private set
    @Volatile private var submitted: JSONObject? = stored(submittedKey)
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
        } else conflict = !samePosition(baseline, initial) && !serverHasCandidate(initial, pending!!) &&
            !retainedText(initial, baseline, pending!!) && !priorWriteArrived(initial, pending!!)
    }

    @Synchronized fun remember(place: JSONObject) {
        if (pending?.toString() == place.toString()) return
        pending = place
        preferences.edit().putString(pendingKey, place.toString())
            .putLong(timeKey, System.currentTimeMillis()).apply()
    }

    @Synchronized fun discardLocal() {
        pending = null
        submitted = null
        baseline = remote
        conflict = false
        preferences.edit().remove(pendingKey).remove(submittedKey).remove(timeKey).putString(baseKey, remote?.toString()).apply()
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
        var expected = if (force && conflict) remote else baseline
        val latest = online.progress()
        if (serverHasCandidate(latest, candidate)) return acknowledge(latest, candidate)
        if (!samePosition(expected, latest) && priorWriteArrived(latest, candidate)) {
            // Playback may have queued a new word while the server's last reply was lost.
            expected = latest
            synchronized(this) {
                baseline = latest
                remote = latest
                preferences.edit().putString(baseKey, latest.toString()).apply()
            }
        }
        var saved = latest
        if (!retainedText(latest, expected, candidate)) {
            if (!samePosition(expected, latest)) return markConflict(latest)
            submit(candidate, writePayload(candidate, latest))
            saved = online.progress()
            if (serverHasCandidate(saved, candidate)) return acknowledge(saved, candidate)
            if (!retainedText(saved, latest, candidate)) return markConflict(saved)
        }
        // A deliberate rewind needs a text write on BookOrbit. Guard the second write and
        // verify both locators so a concurrently changing device is never silently acknowledged.
        val checked = online.progress()
        if (serverHasCandidate(checked, candidate)) return acknowledge(checked, candidate)
        if (!samePosition(saved, checked) || checked.valueOf("textUpdatedAt") != saved.valueOf("textUpdatedAt"))
            return markConflict(checked)
        val text = JSONObject(candidate.toString()).put("source", "text")
        submit(candidate, writePayload(text, checked))
        saved = online.progress()
        if (!serverHasCandidate(saved, candidate)) return markConflict(saved)
        return acknowledge(saved, candidate)
    }

    private fun priorWriteArrived(server: JSONObject, candidate: JSONObject): Boolean {
        val previous = submitted ?: return false
        return previous.toString() != candidate.toString() &&
            (serverHasCandidate(server, previous) || retainedText(server, baseline, previous))
    }

    private fun submit(candidate: JSONObject, body: JSONObject) {
        submitted = candidate
        preferences.edit().putString(submittedKey, candidate.toString()).apply()
        online.updateProgress(body)
    }

    @Synchronized private fun markConflict(latest: JSONObject): SyncState {
        remote = latest
        conflict = true
        return SyncState.Conflict
    }

    @Synchronized private fun acknowledge(latest: JSONObject, candidate: JSONObject): SyncState {
        remote = latest
        baseline = latest
        conflict = false
        submitted = null
        if (pending === candidate) discardLocal()
        else preferences.edit().remove(submittedKey).putString(baseKey, latest.toString()).apply()
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
    val currentCfi = if (narration) narratedCfi(chapter, anchor) ?: cfi else cfi
    if (currentCfi != null) payload.put("cfi", "epubcfi(/$packageSpineStep/${(chapter + 1) * 2}!$currentCfi)")
    if (narration && anchor != null) {
        payload.put("mediaOverlayFragment", "${spine[chapter].path}#$anchor")
        payload.put("mediaOverlaySectionIndex", chapter)
        payload.put("positionSeconds", narrationSeconds(chapter, anchor) ?: JSONObject.NULL)
    }
    return payload
}
