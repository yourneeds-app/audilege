package app.yourneeds.foliocue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.roundToInt
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
    Home(R.string.shelf_start, R.drawable.shelf_start),
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
        listOf(ShelfTab.Home, ShelfTab.Library, ShelfTab.Downloads).forEach { target ->
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
        listOf(ShelfTab.Home, ShelfTab.Library, ShelfTab.Downloads).forEach { target ->
            NavigationRailItem(tab == target, { onSelect(target) }, enabled = enabled,
                icon = { Icon(painterResource(target.icon), null) },
                label = { Text(stringResource(target.label), style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable
private fun ContinueReading(books: List<RecentBook>, covers: CoverStore, enabled: Boolean) {
    val lead = books.first()
    val item = lead.item
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CoverTheme(covers, item.key, item.cover) {
            val accent = MaterialTheme.colorScheme.primary
            val base = lerp(accent, Color(0xff101d21), .63f)
            Surface(onClick = item.open, enabled = enabled, shape = AudilegeLayout.cardShape,
                color = base, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.background(Brush.horizontalGradient(listOf(base, lerp(accent,Color(0xff243430),.56f)))).padding(18.dp)) {
                    val artWidth = (maxWidth * .26f).coerceAtMost(120.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val label = when (lead.mode) {
                                Mode.Read -> R.string.home_last_read
                                Mode.Listen -> R.string.home_last_listen
                                Mode.Follow -> R.string.home_last_follow
                            }
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                            if (item.author.isNotBlank()) Text(item.author, style = MaterialTheme.typography.bodyMedium)
                            lead.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                color = Color.White, trackColor = Color.White.copy(alpha = .28f)) }
                            val location = listOfNotNull(lead.chapter.takeIf(String::isNotBlank), lead.percentLabel()).joinToString(" · ")
                            if (location.isNotEmpty()) Text(location, style = MaterialTheme.typography.labelMedium)
                            Surface(modifier = Modifier.padding(top = 6.dp), shape = CircleShape, color = Color(0xffe6efe1), contentColor = Color(0xff1d332d)) {
                                Text(stringResource(modeAction(lead.mode,true)), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        BookCover(item.title, covers, item.key, item.cover, Modifier.width(artWidth).aspectRatio(if (lead.mode == Mode.Listen || item.category == "podcast") 1f else .67f), crop = item.category == "podcast")
                    }
                }
            }
        }
        if (books.size > 1) {
            Text(stringResource(R.string.home_started), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (book in books.drop(1).take(2)) {
                    Surface(onClick = book.item.open, enabled = enabled, modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            BookCover(book.item.title, covers, book.item.key, book.item.cover, Modifier.width(32.dp).aspectRatio(if (book.mode == Mode.Listen || book.item.category == "podcast") 1f else .67f), crop = book.item.category == "podcast")
                            Column {
                                Text(book.item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(stringResource(book.mode.label), book.percentLabel()).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun RecentBook.percentLabel() = fraction?.let { "${(it * 100).roundToInt()} %" }

@Composable
internal fun ShelfContent(tab: ShelfTab, items: List<ShelfItem>, recent: List<RecentBook>, covers: CoverStore, busy: Boolean,
    sort: Int, setSort: (Int) -> Unit, category: String, setCategory: (String) -> Unit,
    list: Boolean, setList: (Boolean) -> Unit, openMenu: () -> Unit, more: (() -> Unit)?, seeAll: () -> Unit,
    header: @Composable () -> Unit, empty: @Composable () -> Unit, query: String, setQuery: (String) -> Unit,
    showKey: String, openPodcasts: (String) -> Unit, importEpub: () -> Unit) {
    val canSearch = tab == ShelfTab.Home || tab == ShelfTab.Library
    var search by rememberSaveable(tab) { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val choices = listOf(R.string.sort_new, R.string.sort_old, R.string.sort_az, R.string.sort_za, R.string.shelf_by_author)
    val grid = rememberLazyGridState()
    val shows = remember(items) {
        items.filter { it.category == "podcast" }.sortedByDescending { it.added }
            .distinctBy { it.showKey }.sortedBy { it.showTitle.lowercase() }
    }
    val home = tab == ShelfTab.Home && query.isBlank() && !search
    val canFilter = tab == ShelfTab.Library || tab == ShelfTab.Discover
    val podcastView = canFilter && category == "podcast"
    val show = shows.firstOrNull { showKey.isNotEmpty() && it.showKey == showKey }
    LaunchedEffect(tab, query, sort, category, showKey) { grid.scrollToItem(0) }
    val visible = remember(items, query, sort, category, tab, showKey, home) {
        val filtered = items.filter { (!canFilter || category.isEmpty() || it.category == category) &&
            (!podcastView || showKey.isEmpty() || it.showKey == showKey) &&
            (!home || it.category != "podcast") &&
            (it.title.contains(query, true) || it.author.contains(query, true) || it.showTitle.contains(query, true)) }
        val alphabet = compareBy<ShelfItem, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        filtered.sortedWith(when (if (home) 0 else sort) {
            0 -> compareByDescending<ShelfItem> { it.added }.then(alphabet)
            1 -> compareBy<ShelfItem> { it.added == null }.thenBy { it.added }.then(alphabet)
            3 -> alphabet.reversed()
            4 -> compareBy<ShelfItem,String>(String.CASE_INSENSITIVE_ORDER) { it.author }.then(alphabet)
            else -> alphabet
        })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().displayCutoutPadding()) {
        FolioBar(stringResource(if (tab == ShelfTab.Discover) R.string.shelf_categories else tab.label), openMenu, brand = tab == ShelfTab.Home) {
            if (tab == ShelfTab.Library) IconButton(onClick = importEpub, enabled = !busy) {
                Icon(painterResource(R.drawable.menu_upload), stringResource(R.string.import_epub))
            }
            if (!home) Box {
                TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.shelf_sort)) }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    choices.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(stringResource(label)) },
                        leadingIcon = { if (index == sort) Text("✓") },
                        onClick = { setSort(index); sortMenu = false }) }
                }
            }
            if (canSearch) IconButton(onClick = { search = !search; if (!search) setQuery("") }) {
                Icon(if (search) Icons.Default.Close else Icons.Default.Search, stringResource(if (search) R.string.close else R.string.shelf_search))
            }
        }
        if (canSearch && (search || query.isNotEmpty())) OutlinedTextField(query, setQuery, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = AudilegeLayout.pageInset), label = { Text(stringResource(R.string.shelf_filter)) })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                home -> GridCells.Fixed(3)
                list -> GridCells.Fixed(1)
                maxWidth < 360.dp -> GridCells.Fixed(2)
                maxWidth < 520.dp -> GridCells.Fixed(3)
                else -> GridCells.Adaptive(136.dp)
            }
            LazyVerticalGrid(columns, state = grid, contentPadding = PaddingValues(start = AudilegeLayout.pageInset, end = AudilegeLayout.pageInset, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                if (canFilter && (category.isNotEmpty() || items.any { it.category.isNotEmpty() })) item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf("" to R.string.shelf_all_media, "ebook" to R.string.shelf_ebooks,
                            "audio" to R.string.shelf_audio_category, "audilege" to R.string.audilege_brand, "podcast" to R.string.shelf_podcasts)) {
                            if (value.isEmpty() || value == category || items.any { it.category == value }) {
                                FilterChip(category == value, { setCategory(value) }, { Text(stringResource(label)) })
                            }
                        }
                    }
                }
                if (home && recent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReading(recent, covers, !busy)
                }
                if ((home || podcastView) && query.isBlank() && shows.isNotEmpty() && show == null)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (home) R.string.home_podcasts else R.string.shelf_podcasts), Modifier.weight(1f).semantics { heading() },
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (!podcastView) TextButton(onClick = { openPodcasts("") }) { Text(stringResource(R.string.shelf_see_all)) }
                            }
                            if (home) shows.take(3).forEach { episode ->
                                Surface(onClick = { openPodcasts(episode.showKey) }, enabled = !busy,
                                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        BookCover(episode.showTitle, covers, episode.key, episode.cover, Modifier.size(52.dp), crop = true)
                                        Column(Modifier.weight(1f)) {
                                            Text(episode.showTitle.ifBlank { episode.author }, style = MaterialTheme.typography.titleSmall)
                                            if (episode.author.isNotBlank() && episode.author != episode.showTitle)
                                                Text(episode.author, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(shows, key = { it.key }) { episode ->
                                    Box(Modifier.width(106.dp)) {
                                        LibraryTile(episode.copy(title = episode.showTitle.ifBlank { episode.author },
                                            author = "", caption = ""), covers, false, !busy) { openPodcasts(episode.showKey) }
                                    }
                                }
                            }
                        }
                    }
                if (!home || visible.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (podcastView) show?.showTitle ?: stringResource(R.string.shelf_episodes)
                            else stringResource(if (home) R.string.home_new else if (tab == ShelfTab.Downloads) R.string.local else R.string.shelf_mine),
                            Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (home && items.isNotEmpty()) TextButton(onClick = seeAll) { Text(stringResource(R.string.shelf_see_all)) }
                        if (!home) IconButton(onClick = { setList(!list) }) { Icon(painterResource(if (list) R.drawable.shelf_grid else R.drawable.shelf_list),
                            stringResource(if (list) R.string.shelf_grid else R.string.shelf_list)) }
                    }
                }
                items(if (home) visible.take(3) else visible, key = { it.key }) { book -> LibraryTile(book, covers, list && !home, !busy, book.open) }
                if (visible.isEmpty() && !busy && (!home || shows.isEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
                    if (query.isNotBlank() || (canFilter && category.isNotEmpty())) Text(stringResource(R.string.shelf_no_results)) else empty()
                }
                if (more != null && !home) item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = more, enabled = !busy) {
                        Text(stringResource(if (query.isBlank()) R.string.more else R.string.shelf_more_hits))
                    }
                }
            }
        }
    }
}
