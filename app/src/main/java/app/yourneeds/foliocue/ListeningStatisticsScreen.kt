package app.yourneeds.foliocue

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ListeningStatisticsScreen(context: Context, profile: Profile?, close: () -> Unit) {
    val store = remember { ListeningStats(context) }
    var summary by remember(profile) { mutableStateOf(store.summary(profile?.id ?: "local")) }
    LaunchedEffect(profile) {
        while (true) { delay(5000); summary = store.summary(profile?.id ?: "local") }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            FolioBar(stringResource(R.string.listening_statistics), back = close)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(profile?.label ?: stringResource(R.string.local), style = MaterialTheme.typography.titleMedium)
                    profile?.let { Text(it.user, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.statistics_local_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
