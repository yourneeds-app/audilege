package app.yourneeds.foliocue

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt
import androidx.compose.runtime.*
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

private data class Location(val index: Int, val anchor: String? = null, val revision: Int = 0,
    val cfi: String? = null, val scroll: Int? = null)

@Composable
internal fun BookReader(
    activity: MainActivity, file: File, book: Publication, initialMode: Mode?,
    accent: Accent, online: OnlineBook?, serverProgress: JSONObject?,
    covers: CoverStore, ownerScope: kotlinx.coroutines.CoroutineScope, resumeSession: Boolean = false, close: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    val bookKey = keyOf(file.absolutePath)
    var mode by remember {
        mutableStateOf(initialMode ?: if (book.spine.any { it.overlay.isNotBlank() }) {
            Mode.entries.getOrElse(prefs.getInt("$bookKey.mode", 0)) { Mode.Follow }
        } else Mode.Read)
    }
    val sync = remember { online?.let { ReadingSync.open(it, serverProgress, prefs, bookKey) } }
    val initialPlace = remember {
        if (resumeSession && AudilegePlayback.activeBook?.file == file) {
            return@remember ReadingPlace(AudilegePlayback.chapter, null, AudilegePlayback.anchor)
        }
        val place = sync?.resume ?: serverProgress
        place?.place(book, mode) ?: if (place != null) ReadingPlace(0, null, null) else null
    }
    var location by remember {
        val index = initialPlace?.chapter ?: prefs.getInt("$bookKey.chapter", 0).coerceIn(book.spine.indices)
        val savedCfi = if (initialPlace == null && mode == Mode.Read) prefs.getString("$bookKey.cfi.$index", null) else null
        mutableStateOf(Location(index, initialPlace?.anchor, cfi = initialPlace?.cfi ?: savedCfi,
            scroll = if (initialPlace != null) 0 else null))
    }
    val chapter = book.spine[location.index]
    val defaultPaper = if (mode == Mode.Follow) Paper.Cream else Paper.Day
    val style = rememberReaderStyle(prefs, defaultPaper)
    val paper = style.paper
    var inspection by remember { mutableStateOf<Location?>(null) }
    val displayed = inspection ?: location
    val history = remember { mutableStateListOf<ReaderTarget>() }
    val bookmarks = remember(bookKey) { ReaderBookmarks(prefs, bookKey) }
    var marks by remember(bookKey) { mutableStateOf(bookmarks.load().filter { it.chapter in book.spine.indices }) }
    var selectedPassage by remember { mutableStateOf<Pair<Int, PagePassage>?>(null) }
    var highlightDelay by remember { mutableIntStateOf(prefs.getInt("$bookKey.highlightDelay", 0).coerceIn(-1500, 1500)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f).coerceIn(0.75f, 2f)) }
    var controls by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Int?>(null) }
    val playback = AudilegePlayback
    val openedAudio = remember(file, online) { OpenBook(file, book, Mode.Listen, online, serverProgress) }
    val attached = playback.activeBook?.file == file
    val playing = attached && playback.playing
    val scope = rememberCoroutineScope()
    var syncState by remember { mutableStateOf(sync?.state) }
    var syncing by remember { mutableStateOf(false) }
    var movement by remember(location) { mutableIntStateOf(0) }
    var lastAnchor by remember(location) { mutableStateOf<String?>(location.anchor) }
    var chapterFailed by remember(displayed) { mutableStateOf(false) }
    var view by remember { mutableStateOf<ReaderPage?>(null) }
    val audioError = attached && playback.failed


    suspend fun syncPending(force: Boolean = false) {
        val bridge = sync ?: return
        if (!force && syncState == SyncState.Conflict) return
        syncing = true
        try {
            if (force) bridge.keepLocal()
            syncState = if (bridge.pending != null) bridge.flush(force) else bridge.refresh()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            syncState = SyncState.Pending
        } finally { syncing = false }
    }

    fun stage(candidate: JSONObject?) {
        if (candidate == null) return
        val percent = candidate.optDouble("percentage", Double.NaN)
        if (percent.isFinite()) prefs.edit().putFloat("$bookKey.shelfPercent", percent.toFloat()).apply()
        val place = candidate.place(book, Mode.Read)
        if (place?.cfi != null && candidate.optString("source") == "text") {
            prefs.edit().putString("$bookKey.cfi.${place.chapter}", place.cfi).apply()
        }
        if (sync == null || sync.pending?.toString() == candidate.toString()) return
        sync.remember(candidate)
        if (syncState != SyncState.Conflict) syncState = SyncState.Pending
    }

    fun go(index: Int, anchor: String? = null, continuePlaying: Boolean = false) {
        if (index !in book.spine.indices) return
        if (!continuePlaying && index == location.index && anchor != null && mode != Mode.Listen) {
            view?.jump(anchor)
            return
        }
        if (continuePlaying) {
            val existing = playback.activeBook?.file == file && playback.chapter == index && anchor != null && playback.seekAnchor(anchor)
            if (existing) {
                if (mode != Mode.Listen && view?.chapterPath == book.spine[index].path) {
                    view?.setFollowing(true)
                    view?.jump(anchor)
                }
                return
            }
            playback.start(context, openedAudio, index, anchor, false, sync)
        } else if (playback.activeBook?.file == file) playback.stop()
        location = Location(index, anchor, location.revision + 1)
        prefs.edit().putInt("$bookKey.chapter", index).apply()
    }

    fun captureVisible(done: (ReaderTarget) -> Unit) {
        val current = displayed
        val page = view
        val fallback = ReaderTarget(current.index, current.cfi, current.anchor, offset = page?.scrollY ?: 0)
        if (mode == Mode.Listen || page == null) {
            val label = playback.progress?.elapsedMs?.let { ms ->
                val s = ms / 1000
                "%d:%02d".format(s / 60, s % 60)
            }.orEmpty()
            done(fallback.copy(anchor = playback.anchor.takeIf { attached } ?: lastAnchor ?: current.anchor, label = label))
        } else {
            var text = ""
            page.capturePosition(null, excerpt = { text = it }) { cfi, _ ->
                done(fallback.copy(cfi = cfi ?: current.cfi, anchor = null, label = text))
            }
        }
    }
    fun navigate(target: ReaderTarget) {
        if (target.chapter !in book.spine.indices) return
        val next = Location(target.chapter, target.anchor, displayed.revision + 1, target.cfi, target.offset)
        if (mode == Mode.Read) {
            location = next
            inspection = null
            prefs.edit().putInt("$bookKey.chapter", target.chapter).apply()
        } else {
            inspection = next
            if (mode == Mode.Listen) {
                mode = Mode.Follow
                prefs.edit().putInt("$bookKey.mode", mode.ordinal).apply()
            }
        }
    }
    fun visit(target: ReaderTarget) {
        captureVisible { old ->
            if (old != history.lastOrNull()) history.add(old)
            if (history.size > 50) history.removeAt(0)
            navigate(target)
        }
    }
    fun addBookmark(target: ReaderTarget) {
        marks = listOf(target) + marks.filterNot { old ->
            old.chapter == target.chapter && when {
                target.cfi != null -> target.cfi == old.cfi
                target.anchor != null -> target.anchor == old.anchor
                else -> old.cfi == null && old.anchor == null && old.offset == target.offset
            }
        }
        bookmarks.save(marks)
    }
    fun saveNow(done: () -> Unit = {}) {
        if (syncState == SyncState.Conflict) { done(); return }
        val activeChapter = location.index
        val page = view
        if (mode == Mode.Read && (movement > 0 || location.revision > 0) && page != null) {
            page.capturePosition(null) { cfi, fraction ->
                stage(book.progressPayload(activeChapter, cfi ?: location.cfi, null, fraction, false))
                ownerScope.launch { syncPending() }
                done()
            }
        } else if (mode == Mode.Follow && attached && playback.anchor != null && page?.chapterPath == book.spine[activeChapter].path) {
            val anchor = playback.anchor
            val fraction = playback.fraction
            stage(book.progressPayload(activeChapter, null, anchor, fraction, true))
            page.capturePosition(anchor) { cfi, _ ->
                if (cfi != null) stage(book.progressPayload(activeChapter, cfi, anchor, fraction, true))
                ownerScope.launch { syncPending() }
                done()
            }
        } else {
            if (mode != Mode.Read && attached && playback.anchor != null) {
                stage(book.progressPayload(playback.chapter, null, playback.anchor, playback.fraction, true))
            }
            ownerScope.launch { syncPending() }
            done()
        }
    }
    fun leaveReader() {
        saveNow(close)
    }
    val saveOnStop by rememberUpdatedState({ saveNow() })

    fun changeMode(entry: Mode) {
        if (mode == entry) return
        val previous = mode
        fun store() {
            mode = entry
            prefs.edit().putInt("$bookKey.mode", entry.ordinal).apply()
            if (entry != Mode.Listen) controls = false
        }
        if (entry == Mode.Read) {
            val next = inspection ?: location.copy(anchor = playback.anchor.takeIf { attached } ?: location.anchor,
                revision = location.revision + 1)
            if (attached) playback.pause()
            inspection = null
            location = next
            store()
        } else if (previous == Mode.Read || inspection != null) {
            captureVisible { target ->
                inspection = null
                store()
                location = Location(target.chapter, target.anchor, location.revision + 1, target.cfi, target.offset)
                playback.start(context, openedAudio, target.chapter, target.anchor, false, sync, target.cfi)
            }
        } else store()
    }

    BackHandler {
        if (!controls && mode != Mode.Listen) controls = true
        else leaveReader()
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

    DisposableEffect(activity, mode) {
        val window = activity.window
        if (mode != Mode.Listen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity) {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val lifecycle = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) playback.readerVisible(true)
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                playback.readerVisible(false)
                saveOnStop()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                scope.launch { syncPending() }
            }
        }
        activity.lifecycle.addObserver(lifecycle)
        onDispose {
            activity.lifecycle.removeObserver(lifecycle)
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val cues by produceState<List<Cue>?>(null, location) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { book.cues(chapter) }.getOrElse { emptyList() }
        }
    }
    val chapterCues = cues.orEmpty()
    fun followAgain() {
        val anchor = if (attached) playback.anchor else lastAnchor
        val audible = (if (attached) playback.chapter else location.index).coerceIn(book.spine.indices)
        if (inspection != null && inspection != location && anchor != null) {
            location = Location(audible, anchor, displayed.revision + 1)
        }
        inspection = null
        view?.setFollowing(true)
        if (view?.chapterPath == book.spine[audible].path && anchor != null) view?.jump(anchor)
    }
    fun repeatSentence() {
        val active = playback.anchor ?: lastAnchor ?: chapterCues.firstOrNull()?.fragment ?: return
        val index = location.index
        val spineItem = book.spine[index]
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { book.open(spineItem.path)?.let(::xml)?.let { root ->
                    firstCueOfSentence(root, active, chapterCues.mapNotNullTo(mutableSetOf()) { it.fragment })
                } }.getOrNull()
            } ?: active
            inspection = null
            go(index, id, continuePlaying = true)
        }
    }
    DisposableEffect(file) {
        playback.connect(openedAudio, context, sync)
        onDispose { playback.readerVisible(false) }
    }
    LaunchedEffect(file) {
        if (initialMode != null && initialMode != Mode.Read && (!resumeSession || playback.activeBook?.file != file))
            playback.start(context, openedAudio, location.index, location.anchor, initialPlace == null, sync, location.cfi)
        if (initialMode == Mode.Read && playback.activeBook?.file == file) playback.pause()
    }
    LaunchedEffect(attached, playback.chapter) {
        if (attached && mode != Mode.Read && playback.chapter != location.index) {
            location = Location(playback.chapter, playback.anchor, location.revision + 1)
        }
    }
    LaunchedEffect(view, mode, attached, displayed.index, location.index) {
        snapshotFlow { playback.marker }.collect { fragment ->
            if (attached && mode == Mode.Follow && displayed.index == location.index) view?.highlight(fragment)
            else view?.highlight(null)
            if (attached) playback.anchor?.let { lastAnchor = it }
        }
    }
    LaunchedEffect(highlightDelay) { playback.highlightDelay = highlightDelay }
    LaunchedEffect(speed) { if (playback.activeBook?.file == file) playback.changeSpeed(speed) }
    LaunchedEffect(playback.speed, attached) { if (attached && speed != playback.speed) speed = playback.speed }
    LaunchedEffect(chapterFailed, audioError, mode, cues) {
        if (chapterFailed || audioError || (mode == Mode.Follow && cues?.isEmpty() == true)) controls = true
    }
    LaunchedEffect(view, movement, location, mode) {
        if (mode != Mode.Read || view == null ||
            (movement == 0 && location.revision == 0)) return@LaunchedEffect
        val chapterIndex = location.index
        delay(650)
        val current = view ?: return@LaunchedEffect
        current.whenReady {
            current.capturePosition(null) { cfi, fraction ->
                if (view === current && location.index == chapterIndex && mode == Mode.Read)
                    stage(book.progressPayload(chapterIndex, cfi, null, fraction, false))
            }
        }
    }

    LaunchedEffect(attached, playing, mode, sync) {
        if (!attached || mode == Mode.Read || !playing) return@LaunchedEffect
        while (true) {
            delay(3500)
            val anchor = playback.anchor ?: continue
            val fraction = playback.fraction
            val chapterIndex = location.index
            stage(book.progressPayload(chapterIndex, null, anchor, fraction, true))
            if (mode == Mode.Follow && view?.chapterPath == book.spine[chapterIndex].path) view?.capturePosition(anchor) { cfi, _ ->
                if (location.index == chapterIndex && mode == Mode.Follow && cfi != null && view?.chapterPath == book.spine[chapterIndex].path)
                    stage(book.progressPayload(chapterIndex, cfi, anchor, fraction, true))
            }
        }
    }

    LaunchedEffect(view, style) { view?.appearance(style) }
    LaunchedEffect(view, mode, inspection) { view?.setFollowing(mode == Mode.Follow && inspection == null) }

    val colors = if (mode == Mode.Listen) MaterialTheme.colorScheme else appearanceColors(paper, accent)
    val readerMenu = (if (book.spine.any { it.overlay.isNotBlank() })
        listOf(MenuItem(R.string.player_modes) { dialog = R.string.player_modes }) else emptyList()) + listOf(
        MenuItem(R.string.reader_search) { dialog = R.string.reader_search },
        MenuItem(R.string.reader_bookmarks) { dialog = R.string.reader_bookmarks },
    ) + (if (history.isNotEmpty()) listOf(MenuItem(R.string.reader_last_place) {
        navigate(history.removeAt(history.lastIndex))
    }) else emptyList()) + listOf(
        MenuItem(R.string.focus_reading) {
            if (mode == Mode.Listen) changeMode(Mode.Read)
            controls = false
        },
    ) + if (mode == Mode.Follow) listOf(MenuItem(R.string.sync_timing) { dialog = R.string.sync_timing }) else emptyList()
    PlayerTheme(mode, colors, book, online, file, covers) {
    SystemBarAppearance(activity)
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.then(if (controls || mode == Mode.Listen) Modifier.safeDrawingPadding() else Modifier)) {
            if (controls || mode == Mode.Listen) {
                FolioBar(stringResource(if (mode == Mode.Listen) R.string.player_title else mode.label), back = ::leaveReader, overflow = readerMenu) {
                    if (mode == Mode.Listen) IconButton(onClick = { dialog = R.string.contents }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.List, stringResource(R.string.contents))
                    }
                    if (mode != Mode.Listen) TextButton(onClick = { dialog = R.string.settings }) {
                        Text("Aa", Modifier.semantics { contentDescription = context.getString(R.string.settings) })
                    }
                }
            }
            if (mode == Mode.Listen) {
                ListeningArtwork(book, online, file, covers, location.index, Modifier.weight(1f))
            } else {
                key(displayed) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            val targetPage = displayed
                            val startScroll = targetPage.scroll ?: prefs.getInt("$bookKey.scroll.${targetPage.index}", 0)
                            ReaderPage(ctx, book, book.spine[targetPage.index], targetPage.anchor, startScroll, targetPage.cfi, chapterLink = { path, anchor ->
                                val target = book.spine.indexOfFirst { it.path == path }
                                if (target >= 0) visit(ReaderTarget(target, anchor = anchor))
                            }, position = { y ->
                                if (mode == Mode.Read) movement++
                                if (mode == Mode.Read) prefs.edit().putInt("$bookKey.scroll.${targetPage.index}", y).apply()
                            })
                                .also { page ->
                                    view = page
                                    page.onPageTap = { controls = !controls }
                                    page.onBrowse = {
                                        if (mode == Mode.Follow && inspection == null) {
                                            page.setFollowing(false)
                                            inspection = targetPage
                                        }
                                    }
                                    page.onTurnAtEdge = { direction ->
                                        val next = targetPage.index + direction
                                        if (next in book.spine.indices) {
                                            navigate(ReaderTarget(next, offset = if (direction < 0) Int.MAX_VALUE else 0))
                                        }
                                    }
                                    page.onPassage = { passage -> selectedPassage = targetPage.index to passage }
                                    ViewCompat.addAccessibilityAction(page, ctx.getString(R.string.reader_controls)) { _, _ ->
                                        controls = true
                                        true
                                    }
                                    ViewCompat.addAccessibilityAction(page, ctx.getString(R.string.reader_passage)) { _, _ ->
                                        page.passageAt(36f, 36f, null) { selectedPassage = targetPage.index to it }
                                        true
                                    }
                                    page.onPageError = { chapterFailed = true; controls = true }
                                }
                        },
                        onRelease = { page ->
                            if (view == page) view = null
                            page.dispose()
                        },
                        update = {},
                    )
                }
            }
            if (mode == Mode.Follow && inspection != null) {
                TextButton(onClick = ::followAgain, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.reader_follow_again))
                }
            }
            if (controls || mode == Mode.Listen) {
                syncState?.takeIf { it != SyncState.Synced }?.let { state ->
                    Text(stringResource(state.label), Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelMedium)
                    if (state == SyncState.Conflict && sync != null) {
                        SyncPlaceLabel(book, mode, sync.local, sync.localChangedAt, R.string.reader_local_place, displayed.index)
                        SyncPlaceLabel(book, mode, sync.remote, sync.remoteChangedAt, R.string.reader_remote_place, 0)
                        Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(enabled = !syncing, onClick = {
                            val place = sync?.remote?.place(book, mode)
                            sync?.discardLocal()
                            syncState = SyncState.Synced
                            if (attached) playback.stop()
                            inspection = null
                            location = Location(place?.chapter ?: 0, place?.anchor, location.revision + 1, place?.cfi, 0)
                            val edit = prefs.edit().putInt("$bookKey.chapter", location.index)
                            if (place == null) edit.putInt("$bookKey.scroll.0", 0).remove("$bookKey.cfi.0")
                                .putInt("$bookKey.cue.0", 0).remove("$bookKey.elapsed.0").putFloat("$bookKey.shelfPercent", 0f)
                            edit.apply()
                        }) { Text(stringResource(R.string.sync_remote)) }
                        TextButton(enabled = !syncing, onClick = {
                            if (sync.local?.place(book,mode) != null) scope.launch { syncPending(true) }
                            else if (mode != Mode.Read && attached && playback.anchor != null) {
                                stage(book.progressPayload(playback.chapter,null,playback.anchor,playback.fraction,true))
                                scope.launch { syncPending(true) }
                            } else view?.capturePosition(null) { cfi, part ->
                                stage(book.progressPayload(displayed.index,cfi,null,part,false))
                                scope.launch { syncPending(true) }
                            }
                        }) {
                            Text(stringResource(R.string.sync_local))
                        }
                        }
                    }
                }
                if (chapterFailed) TextButton(onClick = {
                    val retry = displayed.copy(revision = displayed.revision + 1)
                    if (inspection != null) inspection = retry else location = retry
                }) { Text(stringResource(R.string.stream_retry)) }
                if (audioError) Text(stringResource(R.string.audio_error), Modifier.padding(horizontal = 16.dp))
                else if (mode != Mode.Read && cues?.isEmpty() == true) {
                    Text(stringResource(R.string.no_audio), Modifier.padding(horizontal = 16.dp))
                }
                ReaderDock(
                    mode, displayed.index, book.spine.size, playback.progress.takeIf { attached }, speed, playing, attached && playback.loading, chapterCues.isNotEmpty(),
                    onPrevious = {
                        if (mode == Mode.Listen) go(location.index - 1, continuePlaying = playing)
                        else visit(ReaderTarget(displayed.index - 1))
                    },
                    onNext = {
                        if (mode == Mode.Listen) go(location.index + 1, continuePlaying = playing)
                        else visit(ReaderTarget(displayed.index + 1))
                    },
                    onContents = { dialog = R.string.contents },
                    onSpeed = { dialog = R.string.speed },
                    onRepeat = if (mode == Mode.Follow && chapterCues.isNotEmpty()) ::repeatSentence else null,
                    onPlay = {
                        if (attached) {
                            if (playing) saveNow()
                            playback.toggle()
                        } else playback.start(context, openedAudio, location.index, location.anchor, true, sync, location.cfi)
                        if (playback.playing && mode == Mode.Follow) controls = false
                    },
                )
            }
        }
    }

    dialog?.let { title ->
        val choose: (ReaderTarget) -> Unit = { target -> dialog = null; visit(target) }
        if (title == R.string.reader_search) ReaderSearchDialog(book, choose) { dialog = null }
        else if (title == R.string.reader_bookmarks) ReaderBookmarksDialog(book, marks,
            add = { captureVisible(::addBookmark) }, open = choose, remove = { mark ->
                marks = marks - mark
                bookmarks.save(marks)
            }, dismiss = { dialog = null })
        else AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(title)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
            text = {
                if (title == R.string.player_modes) {
                    Column {
                        listOf(Mode.Read, Mode.Listen, Mode.Follow).forEach { entry ->
                            TextButton(onClick = { changeMode(entry); dialog = null }) { Text(stringResource(entry.label)) }
                        }
                    }
                } else if (title == R.string.contents) {
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(book.contents) { entry ->
                            val index = book.spine.indexOfFirst { it.path == entry.path }
                            TextButton(onClick = {
                                if (mode == Mode.Listen) { captureVisible { history.add(it); go(index, entry.fragment, continuePlaying = playing) } }
                                else visit(ReaderTarget(index, anchor = entry.fragment))
                                dialog = null
                            }) {
                                val name = entry.label.ifBlank { stringResource(R.string.section, index + 1, book.spine.size) }
                                Text(if (index == displayed.index) "$name · ${stringResource(R.string.reader_here)}" else name)
                            }
                        }
                    }
                } else if (title == R.string.speed) {
                    Column {
                        Text("${"%.2f".format(speed)}×")
                        Slider(value = speed, onValueChange = {
                            speed = it
                            prefs.edit().putFloat("speed", speed).apply()
                        }, valueRange = 0.75f..2f, steps = 4)
                    }
                } else if (title == R.string.sync_timing) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.sync_timing_hint))
                        Text(stringResource(R.string.sync_timing_value, highlightDelay), Modifier.padding(top = 8.dp))
                        fun changeTiming(value: Int) {
                            highlightDelay = value.coerceIn(-1500, 1500)
                            prefs.edit().putInt("$bookKey.highlightDelay", highlightDelay).apply()
                        }
                        Slider(
                            value = highlightDelay.toFloat(),
                            onValueChange = { changeTiming(it.roundToInt()) },
                            valueRange = -1500f..1500f,
                            steps = 59,
                        )
                        TextButton(onClick = { changeTiming(0) }) {
                            Text(stringResource(R.string.reset_timing))
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ReaderStyleEditor(prefs, defaultPaper)
                    }
                }
            })
    }
    selectedPassage?.let { (index, passage) ->
        val media by produceState<String?>(null, index, passage) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val available = book.cues(book.spine[index])
                    val ids = available.mapNotNullTo(mutableSetOf()) { it.fragment }
                    passage.ids.firstOrNull { it in ids } ?: passage.cfi?.let { book.narrationAnchor(index, it, available) }
                }.getOrNull()
            }
        }
        AlertDialog(onDismissRequest = { selectedPassage = null },
            title = { Text(stringResource(R.string.reader_passage)) },
            text = {
                Column {
                    Text(passage.label)
                    TextButton(onClick = {
                        addBookmark(ReaderTarget(index, passage.cfi, label = passage.label))
                        selectedPassage = null
                    }) { Text(stringResource(R.string.reader_add_mark)) }
                    if (media != null) TextButton(onClick = {
                        val anchor = media
                        selectedPassage = null
                        mode = Mode.Follow
                        prefs.edit().putInt("$bookKey.mode", mode.ordinal).apply()
                        inspection = null
                        controls = false
                        go(index, anchor, true)
                    }) { Text(stringResource(R.string.reader_hear_here)) }
                }
            }, confirmButton = { TextButton(onClick = { selectedPassage = null }) { Text(stringResource(R.string.close)) } })
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
