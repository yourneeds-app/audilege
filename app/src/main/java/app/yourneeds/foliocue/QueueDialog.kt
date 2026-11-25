package app.yourneeds.foliocue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun QueueDialog(
    titles: List<QueueTitle>, busy: Boolean, failed: Boolean,
    close: () -> Unit, retry: () -> Unit, open: (Long) -> Unit, promote: (Long) -> Unit, remove: (Long) -> Unit,
) {
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.queue_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) LinearProgressIndicator()
                if (failed) TextButton(onClick = retry, enabled = !busy) { Text(stringResource(R.string.retry)) }
                if (titles.isEmpty() && !busy && !failed) Text(stringResource(R.string.queue_empty))
                LazyColumn {
                    items(titles, key = { it.id }) { title ->
                        Column {
                            TextButton(onClick = { open(title.id) }, enabled = !busy) {
                                Text(title.title, style = MaterialTheme.typography.titleSmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { promote(title.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.queue_first))
                                }
                                TextButton(onClick = { remove(title.id) }, enabled = !busy) {
                                    Text(stringResource(R.string.queue_remove))
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}
