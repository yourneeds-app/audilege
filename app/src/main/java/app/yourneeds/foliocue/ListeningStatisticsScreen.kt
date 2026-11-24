package app.yourneeds.foliocue

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ListeningStatisticsScreen(context: Context, profile: Profile?, catalog: OrbitCatalog? = null, close: () -> Unit) {
    val store = remember { ListeningStats(context) }
    val covers = remember { CoverStore() }
    val profileId = profile?.id ?: "local"
    val identity = catalog?.statisticsIdentity
    var kindName by rememberSaveable(profileId) { mutableStateOf(ActivityKind.ALL.name) }
    val kind = ActivityKind.valueOf(kindName)
    var refresh by remember { mutableIntStateOf(0) }
    val summary = remember(profileId, identity, kind, refresh) { store.summary(profileId, identity, kind.wire) }
    var syncInfo by remember(profileId, identity) { mutableStateOf(identity?.let { store.syncInfo(profileId, it) }) }
    var syncing by remember { mutableStateOf(false) }
    var periodName by rememberSaveable { mutableStateOf(ListeningPeriod.YEAR.name) }
    var anchorText by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    val period = ListeningPeriod.valueOf(periodName)
    val today = LocalDate.now(statisticsZone(summary.timezone))
    val anchor = anchorText?.let(LocalDate::parse) ?: today
    val recap = remember(summary, period, anchor, today) { listeningRecap(summary, period,
        if (period == ListeningPeriod.ALL) today else anchor, today) }
    var allBooks by remember(period, anchor, identity, kind) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun synchronize() {
        if (catalog == null || syncing) return
        syncing = true
        try { store.synchronize(profileId, catalog, discover = true) }
        finally {
            syncInfo = store.syncInfo(profileId, catalog.statisticsIdentity)
            refresh++
            syncing = false
        }
    }
    LaunchedEffect(profileId, catalog) {
        if (catalog != null) while (true) {
            synchronize()
            delay(300_000)
        }
    }
    LaunchedEffect(profileId, identity) {
        while (true) {
            delay(5000)
            refresh++
            syncInfo = identity?.let { store.syncInfo(profileId, it) }
        }
    }
    if (identity == null) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                FolioBar(stringResource(R.string.listening_statistics), back = close)
                Text(stringResource(R.string.activity_account_required), Modifier.align(Alignment.CenterHorizontally)
                    .widthIn(max = AudilegeLayout.contentWidth).fillMaxWidth().padding(AudilegeLayout.pageInset))
            }
        }
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            FolioBar(stringResource(R.string.listening_statistics), back = close)
            LazyColumn(Modifier.align(Alignment.CenterHorizontally).widthIn(max = AudilegeLayout.contentWidth).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = AudilegeLayout.pageInset, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile?.label ?: stringResource(R.string.local), style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            profile?.takeIf { !it.label.trim().equals(it.user.trim(), ignoreCase = true) }?.let {
                                Text(it.user, style = MaterialTheme.typography.bodyMedium)
                            }
                            val info = syncInfo
                            Text(stringResource(when {
                                syncing -> R.string.statistics_syncing
                                info?.error == "sign_in" -> R.string.statistics_sync_sign_in
                                info?.error == "unsupported" -> R.string.statistics_sync_unsupported
                                info?.error != null -> R.string.statistics_sync_offline
                                (info?.lastSync ?: 0) > 0 -> R.string.statistics_sync_done
                                else -> R.string.statistics_sync_pending
                            }), style = MaterialTheme.typography.bodyMedium,
                                color = if (info?.error != null && !syncing) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if ((info?.pending ?: 0) > 0) Text(stringResource(R.string.statistics_sync_collected), style = MaterialTheme.typography.bodySmall)
                            info?.timezone?.let { Text(stringResource(R.string.activity_timezone, it), style = MaterialTheme.typography.bodySmall) }
                            info?.completedBooks?.let { Text(stringResource(R.string.activity_completed_total, it), style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { scope.launch { synchronize() } }, enabled = !syncing) {
                                Text(stringResource(R.string.statistics_sync_now))
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityKind.entries.forEach { option ->
                            FilterChip(selected = kind == option, onClick = { kindName = option.name },
                                label = { Text(stringResource(option.label)) })
                        }
                    }
                    if (summary.unknownMillis > 0) Text(stringResource(R.string.activity_unknown_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListeningPeriod.entries.forEach { option ->
                            FilterChip(selected = period == option, onClick = { periodName = option.name },
                                label = { Text(stringResource(when (option) {
                                    ListeningPeriod.WEEK -> R.string.statistics_period_week
                                    ListeningPeriod.MONTH -> R.string.statistics_period_month
                                    ListeningPeriod.YEAR -> R.string.statistics_period_year
                                    ListeningPeriod.ALL -> R.string.statistics_period_all
                                })) })
                        }
                    }
                    val locale = LocalConfiguration.current.locales[0]
                    val rangeLabel = when (period) {
                        ListeningPeriod.YEAR -> stringResource(R.string.statistics_year_recap, anchor.year)
                        ListeningPeriod.MONTH -> anchor.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                        else -> "${recap.range.start.format(DateTimeFormatter.ofPattern("d.M.yyyy", locale))} – ${recap.range.end.format(DateTimeFormatter.ofPattern("d.M.yyyy", locale))}"
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (period != ListeningPeriod.ALL) IconButton(onClick = {
                            anchorText = shiftPeriod(anchor, period, -1).toString()
                        }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.statistics_previous_period)) }
                        Text(rangeLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (period != ListeningPeriod.ALL) IconButton(onClick = {
                            anchorText = minOf(shiftPeriod(anchor, period, 1), today).toString()
                        }, enabled = shiftPeriod(recap.range.start, period, 1) <= today) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.statistics_next_period))
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(if (period == ListeningPeriod.ALL) R.string.statistics_total else R.string.statistics_period_time), style = MaterialTheme.typography.labelLarge)
                            Text(listeningTimeLabel(recap.total), style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                }
                item {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatisticTile(stringResource(R.string.statistics_active_days), recap.activeDays.toString(), Modifier.weight(1f).fillMaxHeight())
                        StatisticTile(stringResource(R.string.statistics_heard_books),
                            if (recap.unassignedBookTime > 0) {
                                if (recap.books.isEmpty()) "—" else stringResource(R.string.statistics_minimum_count, recap.books.size)
                            } else recap.books.size.toString(),
                            Modifier.weight(1f).fillMaxHeight())
                    }
                }
                item {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatisticTile(stringResource(R.string.statistics_daily_average), listeningTimeLabel(recap.average), Modifier.weight(1f).fillMaxHeight())
                        StatisticTile(stringResource(R.string.statistics_best_streak), summary.bestStreak.toString(), Modifier.weight(1f).fillMaxHeight())
                    }
                    Text(stringResource(R.string.statistics_streak_hint), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pluralStringResource(R.plurals.statistics_current_streak_days, summary.currentStreak, summary.currentStreak),
                        style = MaterialTheme.typography.bodySmall)
                }
                item { ListeningChart(recap.bars, period) }
                recap.peak?.let { peak -> item {
                    val locale = LocalConfiguration.current.locales[0]
                    val format = when (period) {
                        ListeningPeriod.YEAR -> "LLLL"
                        ListeningPeriod.ALL -> if (recap.range.end.year - recap.range.start.year >= 2) "yyyy" else "LLLL yyyy"
                        else -> "EEEE, d. MMMM"
                    }
                    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.statistics_peak), style = MaterialTheme.typography.labelLarge)
                            Text(peak.start.format(DateTimeFormatter.ofPattern(format, locale)), style = MaterialTheme.typography.titleMedium)
                            Text(listeningTimeLabel(peak.millis), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } }
                item {
                    Text(stringResource(R.string.statistics_top_books), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    if (recap.unassignedBookTime > 0) Text(stringResource(R.string.statistics_undated_books),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recap.books.isEmpty()) item { Text(stringResource(R.string.statistics_period_empty)) }
                items(if (allBooks) recap.books else recap.books.take(5), key = { it.key }) { book ->
                    val sources = remember(book.key, profile, catalog) {
                        statisticsCoverSources(context, profile?.id ?: "local", book.key, catalog)
                    }
                    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text((recap.books.indexOf(book) + 1).toString(), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (recap.books.size > 5) item {
                    TextButton(onClick = { allBooks = !allBooks }) {
                        Text(stringResource(if (allBooks) R.string.statistics_show_top_five else R.string.statistics_show_all_books))
                    }
                }
                if (recap.authors.isNotEmpty()) {
                    item { Text(stringResource(R.string.statistics_top_authors), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }) }
                    items(recap.authors.take(5), key = { "author:${it.name}" }) { author ->
                        OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text((recap.authors.indexOf(author) + 1).toString(), style = MaterialTheme.typography.labelLarge)
                                Text(author.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listeningTimeLabel(author.millis), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningChart(bars: List<ListeningBar>, period: ListeningPeriod) {
    var selected by remember(period, bars.map { it.start }) { mutableIntStateOf(-1) }
    val locale = LocalConfiguration.current.locales[0]
    val shortDate = remember(locale) { DateTimeFormatter.ofPattern("d.M.", locale) }
    val yearly = bars.any { it.end.year > it.start.year || it.end.month != it.start.month }
    val barDate = remember(locale, period, yearly) { DateTimeFormatter.ofPattern(when (period) {
        ListeningPeriod.WEEK -> "EE"
        ListeningPeriod.MONTH -> "d"
        ListeningPeriod.YEAR -> "MMM"
        ListeningPeriod.ALL -> if (yearly) "yyyy" else "MMM yy"
    }, locale) }
    val peak = bars.maxOfOrNull { it.millis } ?: 0
    OutlinedCard(Modifier.fillMaxWidth(), shape = AudilegeLayout.cardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.statistics_chart), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(listeningTimeLabel(peak), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barWidth = maxOf(48.dp, (maxWidth - 4.dp * (bars.size - 1).coerceAtLeast(0)) / bars.size.coerceAtLeast(1))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                bars.forEachIndexed { index, bar ->
                    val dateLabel = "${bar.start.format(shortDate)}–${bar.end.format(shortDate)}"
                    val duration = listeningTimeLabel(bar.millis)
                    Column(Modifier.width(barWidth)
                        .background(if (selected == index) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .selectable(selected = selected == index, onClick = { selected = index })
                        .semantics { contentDescription = "$dateLabel: $duration" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.BottomCenter) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (bar.millis > 0) Box(Modifier.fillMaxWidth(.55f)
                                .height((128f * bar.millis / peak.coerceAtLeast(1)).dp.coerceAtLeast(2.dp))
                                .background(if (selected == index) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text(bar.start.format(barDate),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            }
            if (peak == 0L) Text(stringResource(R.string.statistics_chart_empty), style = MaterialTheme.typography.bodySmall)
            else if (selected in bars.indices) {
                val bar = bars[selected]
                Text("${bar.start.format(shortDate)}${if (bar.start != bar.end) " – ${bar.end.format(shortDate)}" else ""}: ${listeningTimeLabel(bar.millis)}",
                    style = MaterialTheme.typography.bodySmall)
            } else Text(stringResource(R.string.statistics_chart_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatisticTile(label: String, value: String, modifier: Modifier) {
    OutlinedCard(modifier, shape = AudilegeLayout.cardShape) {
        Column(Modifier.fillMaxSize().heightIn(min = 104.dp).padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun shiftPeriod(date: LocalDate, period: ListeningPeriod, amount: Long) = when (period) {
    ListeningPeriod.WEEK -> date.plusWeeks(amount)
    ListeningPeriod.MONTH -> date.withDayOfMonth(1).plusMonths(amount)
    ListeningPeriod.YEAR -> date.withDayOfYear(1).plusYears(amount)
    ListeningPeriod.ALL -> date
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
