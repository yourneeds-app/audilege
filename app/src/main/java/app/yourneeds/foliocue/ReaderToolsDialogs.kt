package app.yourneeds.foliocue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun SyncPlaceLabel(book: Publication, mode: Mode, payload: JSONObject?, time: Long?, template: Int, fallbackIndex: Int) {
    val index = payload?.place(book, mode)?.chapter ?: fallbackIndex
    val clock = time?.takeIf { it > 0 }?.let {
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(it))
    } ?: stringResource(R.string.reader_time_unknown)
    Text(stringResource(template, chapterName(book, index), clock),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun chapterName(book: Publication, index: Int): String =
    book.contents.firstOrNull { it.path == book.spine.getOrNull(index)?.path }?.label.orEmpty().ifBlank {
        stringResource(R.string.section, index + 1, book.spine.size)
    }

@Composable
internal fun ReaderSearchDialog(book: Publication, open: (ReaderTarget) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { mutableStateListOf<ReaderTarget>() }
    var scanning by remember(query) { mutableStateOf(false) }
    var failed by remember(query) { mutableIntStateOf(0) }
    LaunchedEffect(query, book) {
        val term = query.trim()
        if (term.length < 2) return@LaunchedEffect
        scanning = true
        delay(350)
        for ((index, item) in book.spine.withIndex()) {
            ensureActive()
            val found = try {
                withContext(Dispatchers.IO) {
                    val input = book.open(item.path) ?: error("Missing chapter")
                    searchChapter(xml(input), index, term, 100 - matches.size)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed++; emptyList() }
            matches.addAll(found)
            if (matches.size >= 100) break
        }
        scanning = false
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.reader_search)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.reader_search_query)) })
                if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (failed > 0) Text(stringResource(R.string.reader_search_failed, failed))
                if (!scanning && matches.isEmpty() && query.trim().length >= 2) Text(stringResource(R.string.reader_no_hits))
                if (matches.size == 100) Text(stringResource(R.string.reader_search_limit))
                LazyColumn(Modifier.heightIn(max = 370.dp)) {
                    items(matches) { target ->
                        TextButton(onClick = { open(target) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(chapterName(book, target.chapter), style = MaterialTheme.typography.labelMedium)
                                Text(target.label, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        })
}

@Composable
internal fun ReaderBookmarksDialog(book: Publication, marks: List<ReaderTarget>, add: () -> Unit,
    open: (ReaderTarget) -> Unit, remove: (ReaderTarget) -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.reader_bookmarks)) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } },
        text = {
            Column {
                TextButton(onClick = add) { Text(stringResource(R.string.reader_add_mark)) }
                if (marks.isEmpty()) Text(stringResource(R.string.reader_no_marks))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(marks) { mark ->
                        Column(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { open(mark) }) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(chapterName(book, mark.chapter), style = MaterialTheme.typography.labelMedium)
                                    if (mark.label.isNotBlank()) Text(mark.label, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            TextButton(onClick = { remove(mark) }) { Text(stringResource(R.string.reader_remove_mark)) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        })
}
