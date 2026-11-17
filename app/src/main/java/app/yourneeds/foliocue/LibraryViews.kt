package app.yourneeds.foliocue

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class CoverStore {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val slots = Semaphore(4)

    suspend fun get(key: String, load: () -> InputStream?): Bitmap? = slots.withPermit {
        cache.get(key) ?: withContext(Dispatchers.IO) {
            runCatching {
                load()?.use { stream -> BitmapFactory.decodeStream(stream) }?.also { cache.put(key, it) }
            }.getOrNull()
        }
    }
}

@Composable
private fun BookCover(
    title: String, covers: CoverStore, key: String, load: () -> InputStream?, modifier: Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, key) {
        value = null
        value = covers.get(key, load)
    }
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LibraryTile(
    title: String, author: String, enabled: Boolean, covers: CoverStore, key: String,
    load: () -> InputStream?, open: () -> Unit,
) {
    Card(onClick = open, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BookCover(title, covers, key, load, Modifier.fillMaxWidth().aspectRatio(0.72f))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DetailScreen(
    book: BookDetail, covers: CoverStore, busy: Boolean, problem: Int?, storageRevision: Int,
    close: () -> Unit, open: (Mode, Boolean) -> Unit, download: () -> Unit, remove: () -> Unit,
) {
    val online = book.onlineBook
    val publication = book.publication
    val title = publication?.title ?: book.entry.title
    val author = online?.detail?.author.orEmpty().ifBlank { publication?.author.orEmpty() }.ifBlank { book.entry.author }
    val rawDescription = online?.detail?.description.orEmpty().ifBlank { publication?.description.orEmpty() }
    val description = remember(rawDescription) { Html.fromHtml(rawDescription, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
    val offline = remember(book.localFile, storageRevision) { book.localFile.isFile }
    var confirm by remember { mutableStateOf(false) }
    val cover: @Composable (Modifier) -> Unit = { modifier ->
        BookCover(title, covers, "detail:${book.localFile.absolutePath}:$storageRevision", {
            runCatching { online?.catalog?.cover(online.detail.id) }.getOrNull()
                ?: publication?.cover?.let(publication::open)
        }, modifier)
    }
    val info: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.titleMedium)
            if (online != null) Text(stringResource(R.string.stream_source), style = MaterialTheme.typography.labelMedium)
            if (offline) Text(stringResource(R.string.offline_ready), style = MaterialTheme.typography.labelMedium)
            if (publication != null) {
                val modes = if (publication.spine.any { it.overlay.isNotBlank() }) Mode.entries else listOf(Mode.Read)
                modes.forEach { mode ->
                    if (mode == modes.first()) Button(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = { open(mode, false) }) { Text(stringResource(mode.label)) }
                    else OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = { open(mode, false) }) { Text(stringResource(mode.label)) }
                }
            }
            if (!offline && book.entry.url.scheme != "file") {
                TextButton(enabled = !busy, onClick = download) { Text(stringResource(R.string.offline_save)) }
            }
            if (offline) {
                if (online != null) {
                    TextButton(enabled = !busy, onClick = {
                        open(if (publication?.spine?.any { it.overlay.isNotBlank() } == true) Mode.Follow else Mode.Read, true)
                    }) { Text(stringResource(R.string.offline_open)) }
                }
                TextButton(enabled = !busy, onClick = { confirm = true }) { Text(stringResource(R.string.offline_remove)) }
            }
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.safeDrawingPadding().widthIn(max = 1000.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = close, enabled = !busy) { Text(stringResource(R.string.back)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        cover(Modifier.width(235.dp).aspectRatio(0.71f))
                        info(Modifier.weight(1f))
                    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        cover(Modifier.align(Alignment.CenterHorizontally).width(158.dp).aspectRatio(0.71f))
                        info(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text(stringResource(R.string.offline_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; remove() }) { Text(stringResource(R.string.offline_remove)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

