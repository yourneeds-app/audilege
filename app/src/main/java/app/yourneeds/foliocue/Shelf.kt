package app.yourneeds.foliocue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.InputStream

internal enum class ShelfTab(val label: Int, val icon: Int) {
    Library(R.string.shelf_home, R.drawable.shelf_book),
    Discover(R.string.shelf_discover, R.drawable.shelf_discover),
    Downloads(R.string.downloads, R.drawable.shelf_download),
    Profile(R.string.shelf_profile, R.drawable.shelf_profile),
}

internal data class ShelfItem(val key: String, val title: String, val author: String,
    val cover: () -> InputStream?, val open: () -> Unit, val caption: String = "", val category: String = "", val added: Long? = null,
    val showKey: String = "", val showTitle: String = "")

internal data class RecentBook(val item: ShelfItem, val mode: Mode, val chapter: String, val fraction: Float?)

@Composable
internal fun ShelfTabs(tab: ShelfTab, enabled: Boolean, onSelect: (ShelfTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(ShelfTab.Library, ShelfTab.Downloads).forEach { target ->
            NavigationBarItem(tab == target, { onSelect(target) }, enabled = enabled,
                icon = { Icon(painterResource(target.icon), null) }, label = {
                    Text(stringResource(target.label), maxLines = 2, style = MaterialTheme.typography.labelSmall)
                })
        }
    }
}

@Composable
internal fun ShelfRail(tab: ShelfTab, enabled: Boolean, onSelect: (ShelfTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(ShelfTab.Library, ShelfTab.Downloads).forEach { target ->
            NavigationRailItem(tab == target, { onSelect(target) }, enabled = enabled,
                icon = { Icon(painterResource(target.icon), null) },
                label = { Text(stringResource(target.label), style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable
private fun ResumeCard(book: RecentBook, covers: CoverStore, enabled: Boolean, modifier: Modifier) {
    val item = book.item
    Surface(onClick = item.open, enabled = enabled, shape = AudilegeLayout.cardShape,
        color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCover(item.title, covers, item.key, item.cover, Modifier.width(62.dp).aspectRatio(.71f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = listOf(item.author, book.chapter).filter(String::isNotBlank).joinToString(" Â· ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(modeIcon(book.mode)), null, Modifier.size(16.dp))
                    Text(stringResource(book.mode.label), style = MaterialTheme.typography.labelMedium)
                }
                book.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) }
            }
        }
    }
}

@Composable
private fun ContinueReading(books: List<RecentBook>, covers: CoverStore, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shelf_continue_read), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = if (books.size == 1) maxWidth.coerceAtMost(400.dp) else minOf(maxWidth * .90f, 340.dp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(books, key = { it.item.key }) { book ->
                    ResumeCard(book, covers, enabled, Modifier.width(width))
                }
            }
        }
    }
}

@Composable
internal fun ShelfContent(tab: ShelfTab, items: List<ShelfItem>, recent: List<RecentBook>, covers: CoverStore, busy: Boolean,
    sort: Int, setSort: (Int) -> Unit, category: String, setCategory: (String) -> Unit,
    list: Boolean, setList: (Boolean) -> Unit, openMenu: () -> Unit, more: (() -> Unit)?, seeAll: () -> Unit,
    header: @Composable () -> Unit, empty: @Composable () -> Unit, query: String, setQuery: (String) -> Unit,
    showKey: String, openPodcasts: (String) -> Unit) {
    var search by rememberSaveable(tab) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val choices = listOf(R.string.sort_new, R.string.sort_old, R.string.sort_az, R.string.sort_za, R.string.shelf_by_author)
    val grid = rememberLazyGridState()
    val shows = remember(items) {
        items.filter { it.category == "podcast" }.sortedByDescending { it.added }
            .distinctBy { it.showKey }.sortedBy { it.showTitle.lowercase() }
    }
    val podcastView = tab == ShelfTab.Discover && category == "podcast"
    val show = shows.firstOrNull { showKey.isNotEmpty() && it.showKey == showKey }
    LaunchedEffect(tab, query, sort, category, showKey) { grid.scrollToItem(0) }
    val visible = remember(items, query, sort, category, tab, showKey) {
        val filtered = items.filter { (tab != ShelfTab.Discover || category.isEmpty() || it.category == category) &&
            (!podcastView || showKey.isEmpty() || it.showKey == showKey) &&
            (tab != ShelfTab.Library || query.isNotBlank() || it.category != "podcast") &&
            (it.title.contains(query, true) || it.author.contains(query, true) || it.showTitle.contains(query, true)) }
        val alphabet = compareBy<ShelfItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        filtered.sortedWith(when (sort) {
            0 -> compareByDescending<ShelfItem> { it.added }.then(alphabet)
            1 -> compareBy<ShelfItem> { it.added == null }.thenBy { it.added }.then(alphabet)
            3 -> alphabet.reversed()
            4 -> compareBy<ShelfItem,String>(String.CASE_INSENSITIVE_ORDER) { it.author }.then(alphabet)
            else -> alphabet
        })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
        FolioBar(stringResource(if (tab == ShelfTab.Discover) R.string.shelf_categories else tab.label), openMenu) {
            Box {
                TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.shelf_sort)) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    choices.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(stringResource(label)) },
                        leadingIcon = { if (index == sort) Text("✓") },
                        onClick = { setSort(index); sortMenu = false }) }
                }
            }
            IconButton(onClick = { search = !search; if (!search) setQuery("") }) {
                Icon(if (search) Icons.Default.Close else Icons.Default.Search, stringResource(if (search) R.string.close else R.string.shelf_search))
            }
        }
        if (search || query.isNotEmpty()) OutlinedTextField(query, setQuery, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AudilegeLayout.pageInset), label = { Text(stringResource(R.string.shelf_filter)) })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                list -> GridCells.Fixed(1)
                maxWidth < 360.dp -> GridCells.Fixed(2)
                maxWidth < 520.dp -> GridCells.Fixed(3)
                else -> GridCells.Adaptive(136.dp)
            }
            LazyVerticalGrid(columns, state = grid, contentPadding = PaddingValues(start = AudilegeLayout.pageInset, end = AudilegeLayout.pageInset, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                if (tab == ShelfTab.Library && recent.isNotEmpty() && !search && query.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReading(recent, covers, !busy)
                }
                if ((tab == ShelfTab.Library || podcastView) && query.isBlank() && shows.isNotEmpty() && show == null)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.shelf_podcasts), Modifier.weight(1f).semantics { heading() },
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (!podcastView) TextButton(onClick = { openPodcasts("") }) { Text(stringResource(R.string.shelf_see_all)) }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shows, key = { it.key }) { episode ->
                                    Box(Modifier.width(106.dp)) {
                                        LibraryTile(episode.copy(title = episode.showTitle.ifBlank { episode.author },
                                            author = "", caption = ""), covers, false, !busy) { openPodcasts(episode.showKey) }
                                    }
                                }
                            }
                        }
                    }
                if (tab != ShelfTab.Library || visible.isNotEmpty() || shows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (podcastView) show?.showTitle ?: stringResource(R.string.shelf_episodes)
                            else stringResource(if (tab == ShelfTab.Downloads) R.string.local else R.string.shelf_mine),
                            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (tab == ShelfTab.Library && items.isNotEmpty()) TextButton(onClick = seeAll) { Text(stringResource(R.string.shelf_see_all)) }
                        IconButton(onClick = { setList(!list) }) { Icon(painterResource(if (list) R.drawable.shelf_grid else R.drawable.shelf_list),
                            stringResource(if (list) R.string.shelf_grid else R.string.shelf_list)) }
                    }
                }
                if (tab == ShelfTab.Discover) item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf("" to R.string.shelf_discover, "ebook" to R.string.shelf_ebooks,
                            "audio" to R.string.shelf_audio_category, "audilege" to R.string.audilege_brand, "podcast" to R.string.shelf_podcasts)) {
                            if (value.isEmpty() || items.any { it.category == value }) {
                                FilterChip(category == value, { setCategory(value) }, { Text(stringResource(label)) })
                            }
                        }
                    }
                }
                items(visible, key = { it.key }) { book -> LibraryTile(book, covers, list, !busy, book.open) }
                if (visible.isEmpty() && !busy && (tab != ShelfTab.Library || shows.isEmpty() || query.isNotBlank())) item(span = { GridItemSpan(maxLineSpan) }) {
                    if (query.isNotBlank()) Text(stringResource(R.string.shelf_no_results)) else empty()
                }
                if (more != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = more, enabled = !busy) {
                        Text(stringResource(if (query.isBlank()) R.string.more else R.string.shelf_more_hits))
                    }
                }
            }
        }
    }
}
