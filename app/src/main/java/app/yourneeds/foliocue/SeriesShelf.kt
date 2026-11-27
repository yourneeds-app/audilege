package app.yourneeds.foliocue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ShelfSeries(val name: String, val books: List<ShelfItem>) {
    val next: ShelfItem? get() {
        val current = books.firstOrNull { !it.played && it.percent > 0 && it.percent < 100 }
        if (current != null) return current
        val completed = books.indexOfLast { it.played || it.percent >= 100 }
        return if (completed >= 0) books.getOrNull(completed + 1) else null
    }
}

internal fun shelfSeries(items: List<ShelfItem>): List<ShelfSeries> = items
    .filter { it.category != "podcast" && it.series.isNotBlank() }
    .groupBy { it.series.trim().lowercase() }.values.map { entries ->
        val books = entries.groupBy { it.volume.replace(',', '.').toDoubleOrNull()?.toString() ?: it.title.lowercase() }
            .values.map { editions -> editions.maxWith(compareBy<ShelfItem> { it.played }.thenBy { it.percent }.thenBy { it.category == "audilege" }) }
            .sortedWith(compareBy<ShelfItem> { it.volume.replace(',', '.').toDoubleOrNull() ?: Double.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        ShelfSeries(entries.first().series.trim(), books)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

@Composable
internal fun SeriesRow(series: List<ShelfSeries>, covers: CoverStore, enabled: Boolean, continuing: Boolean,
    open: (String) -> Unit, seeAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (continuing) R.string.home_continue_series else R.string.home_series),
                Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (continuing) TextButton(onClick = seeAll) { Text(stringResource(R.string.shelf_see_all)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(series, key = { it.name }) { row ->
                val next = row.next.takeIf { continuing }
                val coverBooks = (listOfNotNull(next) + row.books).distinctBy { it.key }.take(3)
                Surface(onClick = { if (next != null) (next.resume ?: next.open)() else open(row.name) }, enabled = enabled,
                    shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.width(142.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.width(105.dp).height(103.dp)) {
                            coverBooks.withIndex().reversed().forEach { (index, book) ->
                                BookCover("", covers, book.key, book.cover,
                                    Modifier.offset(x = (index * 12).dp, y = (index * 5).dp).size(70.dp, 92.dp))
                            }
                        }
                        Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (next != null) Text(next.title, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
