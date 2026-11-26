package app.yourneeds.foliocue

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class ShelfEdition(val file: File, val local: OpenBook?, val entry: CatalogEntry?,
                                val fromDashboard: Boolean, val sections: Int) {
    val key = keyOf(file.absolutePath)
    val secondary = entry?.primaryFileId != null && entry.editionFileId != entry.primaryFileId
}

/** Metadata for streamed editions that have no EPUB file on disk. Positions stay in the reader's store. */
internal fun rememberStream(store: SharedPreferences, file: File, book: OnlineBook,
                            title: String, author: String, sections: Int, primary: Long?) {
    val record = JSONObject().put("book", book.detail.id).put("edition", book.detail.fileId)
        .put("title", title).put("author", author).put("sections", sections)
    val existingPrimary = store.getString(file.name, null)?.let { runCatching { JSONObject(it).optLong("primary") }.getOrNull() }
    (primary ?: existingPrimary)?.takeIf { it > 0 }?.let { record.put("primary", it) }
    store.edit().putString(file.name, record.toString()).apply()
}

private fun collectEditions(
    folder: File, library: List<OpenBook>, pages: List<CatalogEntry>, dashboard: List<CatalogEntry>?,
    profile: Profile?, streams: SharedPreferences?, reader: SharedPreferences, display: SharedPreferences,
): List<ShelfEdition> {
    val editions = linkedMapOf<File, ShelfEdition>()
    val localFiles = library.associateBy { it.file }
    fun addRemote(entry: CatalogEntry, server: Boolean, sections: Int = 0) {
        val id = entry.editionFileId ?: return
        val file = File(folder, keyOf("${entry.url}#$id") + ".epub")
        val local = localFiles[file]
        editions.putIfAbsent(file, ShelfEdition(file, local, entry, server, local?.publication?.spine?.size ?: sections))
    }
    dashboard.orEmpty().forEach { addRemote(it, true) }
    if (profile?.accountLogin == true) {
        val api = profile.api
        val byUrl = pages.associateBy { it.url }
        streams?.all?.forEach { (_, raw) ->
            val record = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val book = record.optLong("book")
            val edition = record.optLong("edition")
            if (book <= 0 || edition <= 0) return@forEach
            val url = URI("$api/books/$book")
            val current = byUrl[url]
            val entry = current?.copy(editionFileId = edition) ?: CatalogEntry(record.optString("title"), url, true,
                record.optString("author"), editionFileId = edition,
                primaryFileId = record.optLong("primary").takeIf { it > 0 })
            addRemote(entry, false, record.optInt("sections"))
        }
        // Older installations stored only the last streamed title. Other known editions can be recovered from the catalog.
        val lastFile = display.getString("last.${folder.name}", null)
        val lastUrl = display.getString("last.online.${folder.name}", null)
        if (lastFile != null && lastUrl != null && lastFile == File(lastFile).name) {
            val stored = reader.getString("edition.${keyOf(File(folder, lastFile).absolutePath)}", null)?.split(':')
            val id = stored?.getOrNull(0)?.toLongOrNull()
            val fileId = stored?.getOrNull(1)?.toLongOrNull()
            if (id != null && fileId != null && lastUrl == "$api/books/$id") {
                val uri = URI(lastUrl)
                val entry = CatalogEntry(display.getString("last.title.${folder.name}", "").orEmpty(), uri, true,
                    display.getString("last.author.${folder.name}", "").orEmpty(), editionFileId = fileId,
                    primaryFileId = byUrl[uri]?.primaryFileId)
                addRemote(entry, false, display.getInt("last.sections.${folder.name}", 0))
            }
        }
        pages.filter { it.book }.forEach { addRemote(it, false) }
    }
    for (book in library) editions.putIfAbsent(book.file, ShelfEdition(book.file, book, null, false, book.publication.spine.size))
    return editions.values.toList()
}

@Composable
internal fun continueReadingBooks(
    reader: SharedPreferences, display: SharedPreferences, streams: SharedPreferences?, folder: File,
    library: List<OpenBook>, pages: List<CatalogEntry>, profile: Profile?, orbit: OrbitCatalog?, reload: Int,
    cover: (CatalogEntry) -> InputStream?, resume: (OpenBook?, CatalogEntry?, Mode) -> Unit,
): List<RecentBook> {
    var dashboard by remember(orbit) { mutableStateOf<List<CatalogEntry>?>(null) }
    var syncRevision by remember(orbit) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(orbit, reload, lifecycle, syncRevision) {
        if (orbit == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { dashboard = withContext(Dispatchers.IO) { orbit.continueReading() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { dashboard = null }
        }
    }
    val editions = remember(folder, library, pages, dashboard, orbit) {
        collectEditions(folder, library, pages, dashboard, profile?.takeIf { orbit != null }, streams, reader, display)
    }
    val last = display.getString("last.${folder.name}", null)
    val byKey = remember(editions) { editions.associateBy { it.key } }
    var revision by remember(byKey) { mutableIntStateOf(0) }
    DisposableEffect(reader, byKey, dashboard) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) {
                revision++
            } else {
                val edition = byKey[key.substringBefore('.')]
                val field = key.substringAfter('.')
                if (edition != null && field in shelfFields) {
                    revision++
                    if (field == "sync.pending" && !reader.contains(key) && dashboard != null) {
                        val percent = reader.getFloat("${edition.key}.shelfPercent", -1f)
                        if (!edition.secondary && percent >= 0f && (percent > 0f && percent < 100f) != edition.fromDashboard) syncRevision++
                    }
                }
            }
        }
        reader.registerOnSharedPreferenceChangeListener(listener)
        onDispose { reader.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val ordered = remember(editions, dashboard, revision, last) {
        // Local additions have device timestamps. The server supplies the order of its own editions.
        editions.sortedByDescending {
            val localTime = reader.getLong("${it.key}.shelfOpenedAt", if (it.file.name == last) 1L else 0L)
            maxOf(localTime, it.entry?.updated ?: 0L)
        }
    }
    val shown = HashSet<String>()
    return buildList {
        for (edition in ordered) {
            val id = edition.key
            val entry = edition.entry
            val pending = reader.contains("$id.sync.pending")
            val associated = orbit != null && reader.contains("edition.$id")
            if (dashboard != null && !edition.fromDashboard && associated && !pending && !edition.secondary) continue
            val serverPosition = edition.fromDashboard && !pending
            val percent = if (serverPosition) entry?.readingPercent?.toFloat() else
                reader.getFloat("$id.shelfPercent", -1f).takeIf { it in 0f..100f }
            val index = reader.getInt("$id.chapter", 0)
            val olderPosition = index > 0 || reader.getInt("$id.scroll.$index", 0).let { it > 0 || it < -1 } ||
                reader.getInt("$id.elapsed.$index", 0) > 0 || reader.getInt("$id.cue.$index", 0) > 0
            if (percent == 100f || ((percent ?: 0f) <= 0f && !olderPosition)) continue
            if (!shown.add(entry?.url?.toString() ?: edition.file.absolutePath)) continue
            val publication = edition.local?.publication
            val mode = entry?.resumeMode?.takeIf { serverPosition } ?: when {
                entry?.category in listOf("audio", "podcast") -> Mode.Listen
                reader.contains("$id.mode") || publication != null -> savedMode(reader, edition.file, publication)
                entry?.category == "audilege" -> Mode.Follow
                else -> Mode.Read
            }
            val title = entry?.title?.takeIf(String::isNotBlank) ?: publication?.title.orEmpty()
            val artworkKey = "${edition.file.absolutePath}#${mode.name}:${entry?.coverRevision.orEmpty()}"
            val item = ShelfItem(artworkKey, title,
                publication?.author ?: entry?.author.orEmpty(), {
                    val audio = if (mode == Mode.Listen) {
                        entry?.url?.path?.substringAfterLast('/')?.toLongOrNull()?.let { id ->
                            runCatching { orbit?.audiobookCover(id) }.getOrNull()
                        } ?: publication?.audiobookCover?.let(publication::open)
                    } else null
                    audio ?: publication?.cover?.let(publication::open) ?: entry?.let(cover)
                }, { resume(edition.local, entry, mode) }, category = if (OfflineBooks.isPodcast(reader, edition.file)) "podcast" else entry?.category.orEmpty())
            val showLocalChapter = !serverPosition || reader.getFloat("$id.shelfPercent", -1f) == percent
            val knownChapter = showLocalChapter && (reader.contains("$id.chapter") || reader.contains("$id.shelfChapterIndex"))
            val label = if (knownChapter && reader.getInt("$id.shelfChapterIndex", -1) == index)
                reader.getString("$id.shelfChapter", null) else null
            val chapter = if (serverPosition && entry?.resumeChapter != null) stringResource(R.string.shelf_chapter, entry.resumeChapter) else label ?: if (knownChapter && index in 0 until edition.sections)
                stringResource(R.string.shelf_section, index + 1, edition.sections) else ""
            add(RecentBook(item, mode, chapter, percent?.div(100f) ?:
                if (index > 0 && edition.sections > 0) index.toFloat() / edition.sections else null))
        }
    }
}

private val shelfFields = setOf("shelfPercent", "chapter", "shelfOpenedAt", "mode", "sync.pending", "shelfChapter")
