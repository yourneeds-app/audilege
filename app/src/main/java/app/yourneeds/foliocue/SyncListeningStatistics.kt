package app.yourneeds.foliocue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Composable
internal fun SyncListeningStatistics(profile: Profile?, catalog: OrbitCatalog?) {
    val context = LocalContext.current.applicationContext
    val store = remember { ListeningStats(context) }
    LaunchedEffect(profile, catalog) {
        if (profile == null || catalog == null) return@LaunchedEffect
        while (true) {
            store.synchronize(profile.id, catalog, discover = store.syncInfo(profile.id, catalog.statisticsIdentity).lastSync == 0L)
            delay(60_000)
        }
    }
}
