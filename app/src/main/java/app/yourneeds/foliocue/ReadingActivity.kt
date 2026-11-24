package app.yourneeds.foliocue

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Same five-minute inactivity limit as BookOrbit's webreader. Hidden, loading and audio time is excluded. */
internal class ReadingActivityMeter {
    private var previous: Long? = null
    private var lastInteraction: Long? = null
    fun activity(now: Long) { lastInteraction = now }
    fun sample(now: Long, visible: Boolean): Long {
        val before = previous
        previous = if (visible) now else null
        val active = lastInteraction ?: return 0
        if (!visible || before == null || now <= before || now - before > 2000) return 0
        return (minOf(now, active + 300_000) - before).coerceIn(0, now - before)
    }
}

@Composable
internal fun TrackReadingActivity(activity: MainActivity, book: OpenBook, enabled: Boolean,
                                  interaction: Long, ownerScope: CoroutineScope) {
    if (book.online == null) return
    val store = remember { ListeningStats(activity.applicationContext) }
    val meter = remember(book.file) { ReadingActivityMeter() }
    val currentEnabled by rememberUpdatedState(enabled)
    val pending = remember(book.file) { longArrayOf(0L) }
    fun collect(visible: Boolean) {
        pending[0] += meter.sample(SystemClock.elapsedRealtime(), visible)
    }
    fun flush() {
        if (pending[0] > 0) { store.record(book, pending[0], "read"); pending[0] = 0 }
    }
    fun upload() {
        flush()
        ownerScope.launch {
            store.synchronize(book.file.parentFile?.name ?: "local", book.online.catalog, discover = false)
        }
    }
    LaunchedEffect(interaction) { meter.activity(interaction) }
    LaunchedEffect(enabled) { if (!enabled) upload() }
    LaunchedEffect(book.file) {
        while (true) {
            collect(currentEnabled && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus())
            if (pending[0] >= 5000) flush()
            delay(1000)
        }
    }
    DisposableEffect(book.file) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { collect(currentEnabled); upload(); meter.sample(SystemClock.elapsedRealtime(), false) }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            collect(currentEnabled && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            upload()
            activity.lifecycle.removeObserver(observer)
        }
    }
}
