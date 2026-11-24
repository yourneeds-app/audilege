package app.yourneeds.foliocue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
    val cover: () -> InputStream?, val open: () -> Unit, val caption: String = "")

internal data class RecentBook(val item: ShelfItem, val modeLabel: Int, val chapter: String, val fraction: Float?)

@Composable
internal fun ShelfTabs(tab: ShelfTab, enabled: Boolean, onSelect: (ShelfTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        ShelfTab.entries.forEach { target ->
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
        ShelfTab.entries.forEach { target ->
            NavigationRailItem(tab == target, { onSelect(target) }, enabled = enabled,
                icon = { Icon(painterResource(target.icon), null) },
                label = { Text(stringResource(target.label), style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable
private fun ResumeCard(hero: RecentBook, covers: CoverStore, enabled: Boolean) {
    val item = hero.item
    Surface(onClick = item.open, enabled = enabled, shape = AudilegeLayout.cardShape,
        color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCover(item.title, covers, item.key, item.cover, Modifier.width(62.dp).aspectRatio(.71f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(hero.modeLabel), style = MaterialTheme.typography.labelMedium)
                Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = listOf(item.author, hero.chapter).filter(String::isNotBlank).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                hero.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                if (hero.modeLabel == R.string.shelf_continue_read) {
                    Icon(painterResource(R.drawable.shelf_book), null, Modifier.padding(8.dp).size(22.dp))
                } else {
                    Icon(Icons.Default.PlayArrow, null, Modifier.padding(8.dp).size(22.dp))
                }
            }
        }
    }
}

@Composable
internal fun ShelfContent(tab: ShelfTab, items: List<ShelfItem>, recent: RecentBook?, covers: CoverStore, busy: Boolean,
    list: Boolean, setList: (Boolean) -> Unit, openMenu: () -> Unit, more: (() -> Unit)?, seeAll: () -> Unit,
    header: @Composable () -> Unit, empty: @Composable () -> Unit, query: String, setQuery: (String) -> Unit) {
    var search by rememberSaveable(tab) { mutableStateOf(false) }
    var byAuthor by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(tab, query, byAuthor) { grid.scrollToItem(0) }
    val visible = remember(items, query, byAuthor, tab) {
        val filtered = items.filter { it.title.contains(query, true) || it.author.contains(query, true) }
        if (tab != ShelfTab.Discover) filtered else if (byAuthor) filtered.sortedWith(
            compareBy<ShelfItem> { it.author.isBlank() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.author }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        else filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
        FolioBar(stringResource(tab.label), openMenu) {
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
                if (tab == ShelfTab.Library && recent != null && query.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    ResumeCard(recent, covers, !busy)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (tab == ShelfTab.Downloads) R.string.local else R.string.shelf_mine),
                            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (tab == ShelfTab.Library && items.isNotEmpty()) TextButton(onClick = seeAll) { Text(stringResource(R.string.shelf_see_all)) }
                        IconButton(onClick = { setList(!list) }) { Icon(painterResource(if (list) R.drawable.shelf_grid else R.drawable.shelf_list),
                            stringResource(if (list) R.string.shelf_grid else R.string.shelf_list)) }
                    }
                }
                if (tab == ShelfTab.Discover) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!byAuthor, { byAuthor = false }, { Text(stringResource(R.string.shelf_by_title)) })
                            FilterChip(byAuthor, { byAuthor = true }, { Text(stringResource(R.string.shelf_by_author)) })
                        }
                    }
                }
                items(visible, key = { it.key }) { book -> LibraryTile(book, covers, list, !busy, book.open) }
                if (visible.isEmpty() && !busy) item(span = { GridItemSpan(maxLineSpan) }) {
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
