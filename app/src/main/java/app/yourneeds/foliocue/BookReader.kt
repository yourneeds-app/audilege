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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext

private enum class Mode(val label: Int) {
    Follow(R.string.follow), Listen(R.string.listen), Read(R.string.read),
}
private enum class Paper(val label: Int, val bg: Long, val ink: Long) {
    Day(R.string.day, 0xFFFAFBFD, 0xFF172C3D),
    Cream(R.string.cream, 0xFFF5ECD8, 0xFF302719),
    Night(R.string.night, 0xFF172330, 0xFFE8EEF5),
}
private enum class Typeface(val label: Int, val css: String) {
    Original(R.string.original, ""), Serif(R.string.serif, "serif"),
    Sans(R.string.sans, "sans-serif"), Mono(R.string.mono, "monospace"),
}

private data class Location(val index: Int, val anchor: String? = null, val revision: Int = 0)

@Composable
internal fun BookReader(activity: MainActivity, file: File, book: Publication, close: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    val bookKey = keyOf(file.absolutePath)
    var location by remember { mutableStateOf(Location(prefs.getInt("$bookKey.chapter", 0).coerceIn(book.spine.indices))) }
    val chapter = book.spine[location.index]
    var mode by remember {
        mutableStateOf(if (book.spine.any { it.overlay.isNotBlank() }) {
            Mode.entries.getOrElse(prefs.getInt("$bookKey.mode", 0)) { Mode.Follow }
        } else Mode.Read)
    }
    var paper by remember { mutableStateOf(Paper.entries.getOrElse(prefs.getInt("paper", 0)) { Paper.Day }) }
    var font by remember { mutableStateOf(Typeface.entries.getOrElse(prefs.getInt("font", 0)) { Typeface.Original }) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 100).coerceIn(75, 200)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f).coerceIn(0.75f, 2f)) }
    var controls by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf(false) }
    var wantsPlay by remember { mutableStateOf(false) }
    var scroll by remember(location) { mutableIntStateOf(prefs.getInt("$bookKey.scroll.${location.index}", 0)) }
    var view by remember { mutableStateOf<ReaderPage?>(null) }
    var fragment by remember(location) { mutableStateOf<String?>(null) }
    var audioError by remember(location) { mutableStateOf(false) }
    BackHandler(enabled = !controls && mode != Mode.Listen) { controls = true }

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

    DisposableEffect(activity) {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                playing = false
                wantsPlay = false
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
            Narration(marks, prefs.getInt("$bookKey.cue.${location.index}", 0),
                { fragment = it }, { audioError = true; playing = false })
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
                narration.attach(extracted, speed, true)
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
    LaunchedEffect(view, paper, font, size) { view?.appearance(paper.bg, paper.ink, font.css, size) }

    val colors = if (paper == Paper.Night) darkColorScheme(
        primary = Color(0xFFAAD9F3), onPrimary = Color(0xFF123B56),
        surface = Color(0xFF172330), onSurface = Color(0xFFE8EEF5),
    ) else MaterialTheme.colorScheme
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
        MenuItem(R.string.shelf, action = close),
    )) { menu ->
    Surface(color = Color(paper.bg), contentColor = Color(paper.ink), modifier = Modifier.fillMaxSize()) {
        Column {
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
                            ReaderPage(ctx, book, chapter, location.anchor, scroll, chapterLink = { path, anchor ->
                                val target = book.spine.indexOfFirst { it.path == path }
                                if (target >= 0) go(target, anchor)
                            }, position = { y ->
                                scroll = y
                                prefs.edit().putInt("$bookKey.scroll.${location.index}", y).apply()
                            })
                                .also { page ->
                                    view = page
                                    page.onPageTap = { controls = !controls }
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
                            else playing = !playing
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
private fun Choice(label: String, options: List<Int>, selected: Int, select: (Int) -> Unit) {
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
