package app.yourneeds.foliocue

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlinx.coroutines.withContext

internal enum class Mode(val label: Int) {
    Follow(R.string.follow), Listen(R.string.listen), Read(R.string.read),
}
internal enum class Paper(val label: Int, val bg: Long, val ink: Long) {
    Day(R.string.day, 0xFFFAFBFD, 0xFF172C3D),
    Cream(R.string.cream, 0xFFF5ECD8, 0xFF302719),
    Night(R.string.night, 0xFF172330, 0xFFE8EEF5),
}
internal enum class Typeface(val label: Int, val css: String) {
    Original(R.string.original, ""), Serif(R.string.serif, "serif"),
    Sans(R.string.sans, "sans-serif"), Mono(R.string.mono, "monospace"),
}

private data class Location(val index: Int, val anchor: String? = null, val revision: Int = 0, val cfi: String? = null)

@Composable
internal fun BookReader(
    activity: MainActivity, file: File, book: Publication, initialMode: Mode?,
    accent: Accent, online: OnlineBook?, serverProgress: JSONObject?,
    ownerScope: kotlinx.coroutines.CoroutineScope, close: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    val bookKey = keyOf(file.absolutePath)
    val sync = remember { online?.let { ReadingSync(it, serverProgress, prefs, bookKey) } }
    val initialPlace = remember {
        val place = sync?.pending ?: serverProgress
        place?.place(book, initialMode)
    }
    var location by remember {
        mutableStateOf(Location(
            initialPlace?.chapter ?: prefs.getInt("$bookKey.chapter", 0).coerceIn(book.spine.indices),
            initialPlace?.anchor, cfi = initialPlace?.cfi,
        ))
    }
    val chapter = book.spine[location.index]
    var mode by remember {
        mutableStateOf(initialMode ?: if (book.spine.any { it.overlay.isNotBlank() }) {
            Mode.entries.getOrElse(prefs.getInt("$bookKey.mode", 0)) { Mode.Follow }
        } else Mode.Read)
    }
    var paper by remember { mutableStateOf(Paper.entries.getOrElse(prefs.getInt("paper", 0)) { Paper.Day }) }
    var font by remember { mutableStateOf(Typeface.entries.getOrElse(prefs.getInt("font", 0)) { Typeface.Original }) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 100).coerceIn(75, 200)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f).coerceIn(0.75f, 2f)) }
    var controls by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf(false) }
    var wantsPlay by remember { mutableStateOf(initialMode != null && initialMode != Mode.Read) }
    val scope = rememberCoroutineScope()
    var syncState by remember { mutableStateOf(
        if (sync == null) null else if (sync.pending != null || serverProgress == null) SyncState.Pending else SyncState.Synced,
    ) }
    var syncing by remember { mutableStateOf(false) }
    var movement by remember(location) { mutableIntStateOf(0) }
    var lastAnchor by remember(location) { mutableStateOf<String?>(location.anchor) }
    var chapterFailed by remember(location) { mutableStateOf(false) }
    var scroll by remember(location) { mutableIntStateOf(prefs.getInt("$bookKey.scroll.${location.index}", 0)) }
    var view by remember { mutableStateOf<ReaderPage?>(null) }
    var fragment by remember(location) { mutableStateOf<String?>(null) }
    var audioError by remember(location) { mutableStateOf(false) }


    suspend fun syncPending(force: Boolean = false) {
        val bridge = sync ?: return
        val candidate = bridge.pending ?: return
        if (syncing || (!force && syncState == SyncState.Conflict)) return
        syncing = true
        try {
            val result = withContext(Dispatchers.IO) { bridge.exchange(candidate, force) }
            bridge.accept(result, candidate)
            syncState = if (result.state == SyncState.Conflict) result.state
                else if (bridge.pending == null) SyncState.Synced else SyncState.Pending
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            syncState = SyncState.Pending
        } finally { syncing = false }
    }

    fun stage(candidate: JSONObject?) {
        if (candidate == null || sync == null || sync.pending?.toString() == candidate.toString()) return
        sync.remember(candidate)
        if (syncState != SyncState.Conflict) syncState = SyncState.Pending
    }

    fun go(index: Int, anchor: String? = null, continuePlaying: Boolean = false) {
        if (index !in book.spine.indices) return
        if (index == location.index && anchor != null && mode != Mode.Listen) {
            view?.jump(anchor)
            return
        }
        wantsPlay = continuePlaying
        playing = false
        location = Location(index, anchor, location.revision + 1)

        prefs.edit().putInt("$bookKey.chapter", index).apply()
    }

    BackHandler {
        if (!controls && mode != Mode.Listen) controls = true
        else {
            ownerScope.launch { syncPending() }
            close()
        }
    }

    LaunchedEffect(activity, controls, mode) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (controls || mode == Mode.Listen) bars.show(WindowInsetsCompat.Type.systemBars())
        else bars.hide(WindowInsetsCompat.Type.systemBars())
    }

    LaunchedEffect(sync) {
        if (sync == null) return@LaunchedEffect
        while (true) {
            delay(10_000)
            syncPending()
        }
    }

    DisposableEffect(activity) {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                playing = false
                wantsPlay = false
                scope.launch { syncPending() }
            }
        }
        activity.lifecycle.addObserver(lifecycle)
        onDispose {
            activity.lifecycle.removeObserver(lifecycle)
            bars.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val cues by produceState<List<Cue>?>(null, location) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { book.cues(chapter) }.getOrElse { emptyList() }
        }
    }
    val chapterCues = cues.orEmpty()
    val narration = remember(location, chapterCues) {
        chapterCues.takeIf { it.isNotEmpty() }?.let { marks ->
            val anchoredCue = location.anchor?.let { anchor -> marks.indexOfFirst { it.fragment == anchor } }
            val startCue = anchoredCue?.takeIf { it >= 0 }
                ?: prefs.getInt("$bookKey.cue.${location.index}", 0)
            Narration(marks, startCue, {
                fragment = it
                if (it != null) lastAnchor = it
            }, { audioError = true; playing = false })
        }
    }
    var engine by remember(narration) { mutableStateOf<Narration?>(null) }
    var loadingAudio by remember(narration) { mutableStateOf(false) }
    val audioFile = remember(file) { File(context.cacheDir, "narration-$bookKey.audio") }

    DisposableEffect(narration) { onDispose { narration?.close() } }
    LaunchedEffect(narration, wantsPlay) {
        if (!wantsPlay || narration == null) return@LaunchedEffect
        wantsPlay = false
        playing = true
    }
    LaunchedEffect(narration, playing, mode, speed) {
        if (narration == null) return@LaunchedEffect
        if (!playing || mode == Mode.Read) {
            narration.pause()
            return@LaunchedEffect
        }
        val progressKey = "$bookKey.cue.${location.index}"
        try {
            suspend fun attach() {
                loadingAudio = true
                val audio = narration.audio
                val stream = book.audioStream?.let { source -> withContext(Dispatchers.IO) { source(audio) } }
                if (stream != null) {
                    narration.attach(context, stream, speed)
                } else {
                    val extracted = withContext(Dispatchers.IO) {
                        val part = File.createTempFile("folio-audio-", ".part", context.cacheDir)
                        try {
                            val input = book.open(audio) ?: error("Audio")
                            input.use { stream -> part.outputStream().use { stream.copyTo(it) } }
                            java.nio.file.Files.move(part.toPath(), audioFile.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            audioFile
                        } finally { part.delete() }
                    }
                    narration.attach(extracted, speed)
                }
                engine = narration
                loadingAudio = false
            }
            audioError = false
            if (engine == narration) narration.play(speed) else attach()
            while (true) {
                delay(16)
                if (narration.tick(speed)) {
                    engine = null
                    attach()
                }
                if (narration.finished) {
                    if (location.index < book.spine.lastIndex) go(location.index + 1, continuePlaying = true)
                    else playing = false
                    break
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            audioError = true
            playing = false
            engine = null
        } finally {
            narration.pause()
            prefs.edit().putInt(progressKey, if (narration.finished) 0 else narration.index).apply()
            loadingAudio = false
        }
    }

    LaunchedEffect(view, fragment, mode) {
        view?.highlight(if (mode == Mode.Follow) fragment else null)
    }
    LaunchedEffect(view, movement, location, mode) {
        if (mode != Mode.Read || sync == null || view == null ||
            (movement == 0 && location.revision == 0)) return@LaunchedEffect
        val chapterIndex = location.index
        delay(650)
        view?.capturePosition(null) { cfi, fraction ->
            if (location.index == chapterIndex && mode == Mode.Read)
                stage(book.progressPayload(chapterIndex, cfi, null, fraction, false))
        }
    }

    LaunchedEffect(narration, playing, mode, sync) {
        if (sync == null || narration == null || mode == Mode.Read || !playing) return@LaunchedEffect
        while (true) {
            delay(3500)
            val anchor = lastAnchor ?: continue
            val index = narration.index
            val fraction = index.toDouble() / chapterCues.size
            val chapterIndex = location.index
            stage(book.progressPayload(chapterIndex, null, anchor, fraction, true))
            if (mode == Mode.Follow) view?.capturePosition(anchor) { cfi, _ ->
                if (location.index == chapterIndex && mode == Mode.Follow && cfi != null)
                    stage(book.progressPayload(chapterIndex, cfi, anchor, fraction, true))
            }
        }
    }

    LaunchedEffect(view, paper, font, size) { view?.appearance(paper.bg, paper.ink, font.css, size, accent.highlight) }

    val colors = appearanceColors(paper, accent)
    MaterialTheme(colorScheme = colors) {
    FolioDrawer(book.title, listOf(
        MenuItem(R.string.contents) { dialog = R.string.contents },
        MenuItem(R.string.settings) { dialog = R.string.settings },
        MenuItem(R.string.focus_reading) {
            if (mode == Mode.Listen) {
                mode = Mode.Read
                playing = false
                wantsPlay = false
                prefs.edit().putInt("$bookKey.mode", mode.ordinal).apply()
            }
            controls = false
        },
        MenuItem(R.string.shelf) {
            ownerScope.launch { syncPending() }
            close()
        },
    )) { menu ->
    Surface(color = colors.background, contentColor = colors.onBackground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.then(if (controls || mode == Mode.Listen) Modifier.safeDrawingPadding() else Modifier)) {
            if (controls || mode == Mode.Listen) {
                FolioBar(book.title, menu)
                if (book.spine.any { it.overlay.isNotBlank() }) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Mode.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = entry == mode,
                                onClick = {
                                    mode = entry
                                    prefs.edit().putInt("$bookKey.mode", entry.ordinal).apply()
                                    if (entry == Mode.Read) { wantsPlay = false; playing = false }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, Mode.entries.size),
                                icon = {},
                            ) { Text(stringResource(entry.label)) }
                        }
                    }
                }
            }
            if (mode == Mode.Listen) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(book.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.section, location.index + 1, book.spine.size))
                }
            } else {
                key(location) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            ReaderPage(ctx, book, chapter, location.anchor, scroll, location.cfi, chapterLink = { path, anchor ->
                                val target = book.spine.indexOfFirst { it.path == path }
                                if (target >= 0) go(target, anchor)
                            }, position = { y ->
                                scroll = y
                                if (mode == Mode.Read) movement++
                                prefs.edit().putInt("$bookKey.scroll.${location.index}", y).apply()
                            })
                                .also { page ->
                                    view = page
                                    page.onPageTap = { controls = !controls }
                                    page.onPageError = { chapterFailed = true; controls = true }
                                }
                        },
                        onRelease = { page ->
                            if (view == page) view = null
                            page.stopLoading()
                            page.destroy()
                        },
                        update = {},
                    )
                }
            }
            if (controls || mode == Mode.Listen) {
                syncState?.let { state ->
                    Text(stringResource(state.label), Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelMedium)
                    if (state == SyncState.Conflict) Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(enabled = !syncing, onClick = {
                            val place = sync?.remote?.place(book, mode)
                            sync?.discardLocal()
                            syncState = SyncState.Synced
                            playing = false
                            wantsPlay = false
                            location = Location(place?.chapter ?: 0, place?.anchor, location.revision + 1, place?.cfi)
                            prefs.edit().putInt("$bookKey.chapter", location.index).apply()
                        }) { Text(stringResource(R.string.sync_remote)) }
                        TextButton(enabled = !syncing, onClick = { scope.launch { syncPending(true) } }) {
                            Text(stringResource(R.string.sync_local))
                        }
                    }
                }
                if (chapterFailed) TextButton(onClick = {
                    go(location.index, continuePlaying = playing)
                }) { Text(stringResource(R.string.stream_retry)) }
                if (audioError) Text(stringResource(R.string.audio_error), Modifier.padding(horizontal = 16.dp))
                else if (mode != Mode.Read && cues?.isEmpty() == true) {
                    Text(stringResource(R.string.no_audio), Modifier.padding(horizontal = 16.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    val previous = stringResource(R.string.previous)
                    val next = stringResource(R.string.next)
                    TextButton(enabled = location.index > 0, onClick = { go(location.index - 1) },
                        modifier = Modifier.semantics { contentDescription = previous }) {
                        Text("‹", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.headlineMedium)
                    }
                    if (mode != Mode.Read) {
                        Button(enabled = narration != null && !loadingAudio, onClick = {
                            if (narration?.finished == true) go(location.index, continuePlaying = true)
                            else {
                                playing = !playing
                                if (playing && mode == Mode.Follow) controls = false
                            }
                        }) { Text(stringResource(if (loadingAudio) R.string.connecting else if (playing) R.string.pause else R.string.play)) }
                    } else {
                        Text(stringResource(R.string.section_short, location.index + 1, book.spine.size),
                            style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(enabled = location.index < book.spine.lastIndex, onClick = { go(location.index + 1) },
                        modifier = Modifier.semantics { contentDescription = next }) {
                        Text("›", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
    }

    dialog?.let { title ->
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(title)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
            text = {
                if (title == R.string.contents) {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(book.contents) { entry ->
                            val index = book.spine.indexOfFirst { it.path == entry.path }
                            TextButton(onClick = { go(index, entry.fragment); dialog = null }) {
                                Text(entry.label.ifBlank { stringResource(R.string.section, index + 1, book.spine.size) })
                            }
                        }
                    }
                } else {
                    Column {
                        Choice(stringResource(R.string.theme), Paper.entries.map { it.label }, paper.ordinal) {
                            paper = Paper.entries[it]
                            prefs.edit().putInt("paper", it).apply()
                        }
                        Choice(stringResource(R.string.font), Typeface.entries.map { it.label }, font.ordinal) {
                            font = Typeface.entries[it]
                            prefs.edit().putInt("font", it).apply()
                        }
                        Text("${stringResource(R.string.size)} · $size %", Modifier.padding(top = 8.dp))
                        Slider(size.toFloat(), {
                            size = it.toInt()
                            prefs.edit().putInt("size", size).apply()
                        }, valueRange = 75f..200f)
                        Text("${stringResource(R.string.speed)} · ${"%.2f".format(speed)}×")
                        Slider(speed, {
                            speed = it
                            prefs.edit().putFloat("speed", speed).apply()
                        }, valueRange = 0.75f..2f, steps = 4)
                    }
                }
            })
    }
    }
}

@Composable
internal fun Choice(label: String, options: List<Int>, selected: Int, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label · ${stringResource(options[selected])}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { select(index); expanded = false })
            }
        }
    }
}
