package app.yourneeds.foliocue

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ListeningStatisticsScreen(context: Context, profile: Profile?, catalog: OrbitCatalog? = null, close: () -> Unit) {
    val store = remember { ListeningStats(context) }
    val covers = remember { CoverStore() }
    var localOnly by rememberSaveable(profile?.id) { mutableStateOf(false) }
    val profileId = profile?.id ?: "local"
    val identity = catalog?.statisticsIdentity
    val selectedIdentity = identity.takeUnless { localOnly }
    var summary by remember(profileId, selectedIdentity) { mutableStateOf(store.summary(profileId, selectedIdentity)) }
    var syncInfo by remember(profileId, identity) { mutableStateOf(identity?.let { store.syncInfo(profileId, it) }) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun synchronize() {
        if (catalog == null || syncing) return
        syncing = true
        try { store.synchronize(profileId, catalog, discover = true) }
        finally {
            syncInfo = store.syncInfo(profileId, catalog.statisticsIdentity)
            summary = store.summary(profileId, selectedIdentity)
            syncing = false
        }
    }
    LaunchedEffect(profileId, catalog) {
        if (catalog != null) while (true) {
            synchronize()
            delay(300_000)
        }
    }
    LaunchedEffect(profileId, selectedIdentity) {
        while (true) {
            delay(5000)
            summary = store.summary(profileId, selectedIdentity)
            syncInfo = identity?.let { store.syncInfo(profileId, it) }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            FolioBar(stringResource(R.string.listening_statistics), back = close)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(profile?.label ?: stringResource(R.string.local), style = MaterialTheme.typography.titleMedium)
                    profile?.let { Text(it.user, style = MaterialTheme.typography.bodyMedium) }
                    if (identity != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !localOnly, onClick = { localOnly = false }, label = { Text("BookOrbit") })
                        FilterChip(selected = localOnly, onClick = { localOnly = true }, label = { Text(stringResource(R.string.statistics_device_only)) })
                    }
                    Text(stringResource(if (selectedIdentity != null) R.string.statistics_cloud_hint else R.string.statistics_local_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedIdentity != null) {
                        val info = syncInfo
                        Text(stringResource(when {
                            syncing -> R.string.statistics_syncing
                            info?.error == "sign_in" -> R.string.statistics_sync_sign_in
                            info?.error == "unsupported" -> R.string.statistics_sync_unsupported
                            info?.error != null -> R.string.statistics_sync_offline
                            (info?.lastSync ?: 0) > 0 -> R.string.statistics_sync_done
                            else -> R.string.statistics_sync_pending
                        }), style = MaterialTheme.typography.bodySmall)
                        if ((info?.pending ?: 0) > 0) Text(stringResource(R.string.statistics_sync_collected), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { synchronize() } }, enabled = !syncing) {
                            Text(stringResource(R.string.statistics_sync_now))
                        }
                    }
                }
                item { ListeningChart(summary.days) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.statistics_total), style = MaterialTheme.typography.labelLarge)
                            Text(listeningTimeLabel(summary.total), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatisticTile(stringResource(R.string.statistics_today), listeningTimeLabel(summary.today), Modifier.weight(1f))
                        StatisticTile(stringResource(R.string.statistics_week), listeningTimeLabel(summary.week), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatisticTile(stringResource(R.string.statistics_streak), summary.currentStreak.toString(), Modifier.weight(1f))
                        StatisticTile(stringResource(R.string.statistics_best_streak), summary.bestStreak.toString(), Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.statistics_streak_hint), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { Text(stringResource(R.string.statistics_books), style = MaterialTheme.typography.titleLarge) }
                if (summary.books.isEmpty()) item { Text(stringResource(R.string.statistics_empty)) }
                items(summary.books, key = { it.key }) { book ->
                    val sources = remember(book.key, profile, catalog) {
                        statisticsCoverSources(context, profile?.id ?: "local", book.key, catalog)
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BookCover(book.title, covers, "statistics:${profile?.id}:${book.key}", sources,
                            Modifier.width(48.dp).aspectRatio(.70f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.title, fontWeight = FontWeight.SemiBold)
                            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                            Text(listeningTimeLabel(book.millis), color = MaterialTheme.colorScheme.primary)
                        }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningChart(days: List<ListeningDay>) {
    var weekly by rememberSaveable { mutableStateOf(false) }
    var selected by remember(weekly) { mutableIntStateOf(-1) }
    val bars = remember(days, weekly) { listeningBars(days, weekly) }
    val locale = LocalConfiguration.current.locales[0]
    val shortDate = remember(locale) { DateTimeFormatter.ofPattern("d.M.", locale) }
    val weekday = remember(locale) { DateTimeFormatter.ofPattern("EE", locale) }
    val peak = bars.maxOfOrNull { it.millis } ?: 0
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.statistics_chart), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !weekly, onClick = { weekly = false }, label = { Text(stringResource(R.string.statistics_daily)) })
                FilterChip(selected = weekly, onClick = { weekly = true }, label = { Text(stringResource(R.string.statistics_weekly)) })
            }
            Text(listeningTimeLabel(peak), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                bars.forEachIndexed { index, bar ->
                    val dateLabel = if (weekly) "${bar.start.format(shortDate)}–${bar.end.format(shortDate)}"
                        else "${bar.start.format(weekday)} ${bar.start.format(shortDate)}"
                    val duration = listeningTimeLabel(bar.millis)
                    Column(Modifier.weight(1f).clickable { selected = index }
                        .semantics { contentDescription = "$dateLabel: $duration" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.BottomCenter) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (bar.millis > 0) Box(Modifier.fillMaxWidth(.55f)
                                .height((128f * bar.millis / peak.coerceAtLeast(1)).dp.coerceAtLeast(2.dp))
                                .background(if (selected == index) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text(if (weekly) "${bar.start.format(shortDate)}\n${bar.end.format(shortDate)}"
                            else "${bar.start.format(weekday)}\n${bar.start.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (peak == 0L) Text(stringResource(R.string.statistics_chart_empty), style = MaterialTheme.typography.bodySmall)
            else if (selected in bars.indices) {
                val bar = bars[selected]
                Text("${bar.start.format(shortDate)}${if (weekly) " – ${bar.end.format(shortDate)}" else ""}: ${listeningTimeLabel(bar.millis)}",
                    style = MaterialTheme.typography.bodySmall)
            } else Text(stringResource(R.string.statistics_chart_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatisticTile(label: String, value: String, modifier: Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun listeningTimeLabel(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> stringResource(R.string.statistics_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.statistics_minutes, seconds / 60)
        else -> stringResource(R.string.statistics_hours_minutes, seconds / 3600, seconds / 60 % 60)
    }
}
