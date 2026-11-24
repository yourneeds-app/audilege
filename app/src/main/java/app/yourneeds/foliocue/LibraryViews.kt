package app.yourneeds.foliocue

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class CoverStore {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val locks = Array(32) { Mutex() }
    private val slots = Semaphore(4)

    suspend fun get(key: String, load: () -> InputStream?): Bitmap? = get(key, listOf(load))

    suspend fun get(key: String, sources: List<() -> InputStream?>): Bitmap? =
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cache.get(key) ?: slots.withPermit {
                withContext(Dispatchers.IO) {
                    sources.firstNotNullOfOrNull { source ->
                        runCatching { source()?.use(::decodeCover) }.getOrNull()
                    }?.also { cache.put(key, it) }
                }
            }
        }

    private fun decodeCover(stream: InputStream): Bitmap? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > 12 * 1024 * 1024) return null
            out.write(buffer, 0, count)
        }
        val bytes = out.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth < 1 || options.outHeight < 1) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1400) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

@Composable
internal fun BookCover(title: String, covers: CoverStore, key: String, load: () -> InputStream?, modifier: Modifier) =
    BookCover(title, covers, key, listOf(load), modifier)

@Composable
internal fun BookCover(title: String, covers: CoverStore, key: String, sources: List<() -> InputStream?>, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, key) {
        value = null
        value = covers.get(key, sources)
    }
    BoxWithConstraints(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            // Keep the shelf slot stable, but round the artwork's own edges rather than
            // a larger coloured box. Different cover ratios remain intact and uncropped.
            val ratio = image.width.toFloat() / image.height
            val width = minOf(maxWidth, maxHeight * ratio)
            Image(image.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(width, width / ratio).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Fit)
        } else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(4.dp)) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.shelf_book), null)
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun modeIcon(mode: Mode) = when (mode) {
    Mode.Read -> R.drawable.shelf_book
    Mode.Listen -> R.drawable.shelf_headphones
    Mode.Follow -> R.drawable.shelf_along
}

internal fun modeAction(mode: Mode, resume: Boolean) = when (mode) {
    Mode.Read -> if (resume) R.string.shelf_continue_read else R.string.shelf_start_read
    Mode.Listen -> if (resume) R.string.shelf_continue_listen else R.string.shelf_start_listen
    Mode.Follow -> if (resume) R.string.shelf_continue_follow else R.string.shelf_start_follow
}

internal fun savedMode(prefs: SharedPreferences, file: File, publication: Publication?): Mode {
    if (publication != null && publication.spine.none { it.overlay.isNotBlank() }) return Mode.Read
    return Mode.entries.getOrElse(prefs.getInt("${keyOf(file.absolutePath)}.mode", 0)) { Mode.Follow }
}

@Composable
internal fun LibraryTile(item: ShelfItem, covers: CoverStore, list: Boolean, enabled: Boolean, open: () -> Unit) {
    Surface(onClick = open, enabled = enabled, color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        val description: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = if (list) 4 else 2, overflow = TextOverflow.Ellipsis)
                if (item.author.isNotBlank()) Text(item.author, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (list) 2 else 1,
                    overflow = TextOverflow.Ellipsis)
                if (item.caption.isNotBlank()) Text(item.caption, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (list) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BookCover(item.title, covers, item.key, item.cover, Modifier.width(54.dp).aspectRatio(.70f))
            description()
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BookCover(item.title, covers, item.key, item.cover, Modifier.fillMaxWidth().aspectRatio(.72f))
            description()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(
    book: BookDetail, covers: CoverStore, prefs: SharedPreferences, busy: Boolean, problem: Int?, storageRevision: Int,
    close: () -> Unit, open: (Mode, Boolean) -> Unit, download: () -> Unit, remove: () -> Unit,
) {
    val context = LocalContext.current
    val online = book.onlineBook
    val publication = book.publication
    val title = publication?.title ?: book.entry.title
    val author = online?.detail?.author.orEmpty().ifBlank { publication?.author.orEmpty() }.ifBlank { book.entry.author }
    val rawDescription = online?.detail?.description.orEmpty().ifBlank { publication?.description.orEmpty() }
    val description = remember(rawDescription) { Html.fromHtml(rawDescription, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
    val offline = remember(book.localFile, storageRevision) { book.localFile.isFile }
    val key = keyOf(book.localFile.absolutePath)
    val started = prefs.contains("$key.mode") || prefs.contains("$key.chapter")
    val sectionIndex = prefs.getInt("$key.chapter",0)
    val hasAudio = publication?.spine?.any { it.overlay.isNotBlank() } == true
    var choice by remember(book, started) { mutableStateOf(savedMode(prefs, book.localFile, publication)) }
    var confirm by remember(book) { mutableStateOf(false) }
    val coverKey = book.coverKey
    val loadCover: () -> InputStream? = {
        book.coverLoader?.let { runCatching { it() }.getOrNull() }
            ?: publication?.cover?.let(publication::open)
    }
    CoverTheme(covers, coverKey, loadCover) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            FolioBar(stringResource(R.string.book_info), back = close, backEnabled = !busy)
            Column(Modifier.weight(1f).align(Alignment.CenterHorizontally)
                .widthIn(max = AudilegeLayout.contentWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(AudilegeLayout.pageInset),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                BoxWithConstraints {
                    val coverWidth = if (maxWidth >= 600.dp) 190.dp else (maxWidth * .42f).coerceAtMost(160.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BookCover(title, covers, coverKey, loadCover, Modifier.width(coverWidth).aspectRatio(.70f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.titleMedium)
                            online?.detail?.series?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.shelf_series, it)) }
                            online?.detail?.pages?.takeIf { it > 0 }?.let { Text(stringResource(R.string.shelf_pages, it), style = MaterialTheme.typography.bodySmall) }
                            online?.detail?.narrator?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.shelf_narrator, it), style = MaterialTheme.typography.bodySmall) }
                            if (publication != null) Text(stringResource(if (hasAudio) R.string.shelf_audio else R.string.shelf_plain), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                HorizontalDivider()
                if (publication != null) {
                    val chapter = sectionIndex.coerceIn(publication.spine.indices)
                    val label = publication.contents.firstOrNull { it.path == publication.spine[chapter].path }?.label
                    Text(label?.takeIf(String::isNotBlank) ?: stringResource(R.string.shelf_section, chapter + 1, publication.spine.size),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { open(choice, online == null || book.preferLocal) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = MaterialTheme.shapes.medium) {
                        Icon(painterResource(modeIcon(choice)), null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(modeAction(choice, started)))
                    }
                    if (hasAudio) Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Mode.Read to R.string.shelf_read, Mode.Listen to R.string.shelf_listen, Mode.Follow to R.string.shelf_follow).forEach { (item, text) ->
                            val picked = item == choice
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().selectable(selected = picked, enabled = !busy, role = Role.RadioButton) { choice = item }
                                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(painterResource(modeIcon(item)), null)
                                    Text(stringResource(text), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (offline) {
                    Text(stringResource(R.string.shelf_offline_size, Formatter.formatFileSize(context, book.localFile.length())), style = MaterialTheme.typography.bodySmall)
                    if (online != null && !book.preferLocal) OutlinedButton(onClick = { open(choice, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.offline_open))
                    }
                    TextButton(enabled = !busy, onClick = { confirm = true }) { Text(stringResource(R.string.offline_remove)) }
                } else if (book.entry.url.scheme != "file") OutlinedButton(enabled = !busy, onClick = download,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(painterResource(R.drawable.shelf_download), null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.offline_save))
                }
                if (description.isNotBlank()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.about_book), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { AppVolumeFeedback(); Text(stringResource(R.string.offline_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; remove() }) { Text(stringResource(R.string.offline_remove)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
