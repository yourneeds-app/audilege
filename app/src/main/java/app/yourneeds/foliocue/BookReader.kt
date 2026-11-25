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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
    val cfi: String? = null, val scroll: Int? = null, val textNavigation: Boolean = false, val serverSeconds: Double? = null,
    val elapsedMs: Int? = null)

private data class ListenJump(val start: Int, val direction: Int, val anchor: String? = null)
private sealed interface SectionAudio {
    data object Loading : SectionAudio
    data object Failed : SectionAudio
    data class Ready(val index: Int?, val cues: List<Cue>, val durationMs: Int) : SectionAudio
}

@Composable
internal fun BookReader(
    activity: MainActivity, file: File, book: Publication, initialMode: Mode?,
    accent: Accent, online: OnlineBook?, serverProgress: JSONObject?,
    covers: CoverStore, ownerScope: kotlinx.coroutines.CoroutineScope, resumeSession: Boolean = false,
    backLabel: Int = R.string.back, close: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    val bookKey = keyOf(file.absolutePath)
    var mode by remember {
        mutableStateOf(initialMode ?: if (book.spine.any { it.overlay.isNotBlank() }) {
            Mode.entries.getOrElse(prefs.getInt("$bookKey.mode", 0)) { Mode.Follow }
        } else Mode.Read)
    }
    fun stored(key: String) = prefs.getString("$bookKey.$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    val previousBaseline = remember { stored("sync.base") }
    val sync = remember { online?.let { ReadingSync.open(it, serverProgress, prefs, bookKey) } }
    fun savedPosition() = sync?.resume ?: serverProgress ?: stored("passage")
    fun savedPlace() = savedPosition()?.let { it.place(book, mode) ?: ReadingPlace(0, null, null) }
    fun canUseCheckpoint(selected: JSONObject): Boolean {
        val chapter = selected.optInt("mediaOverlaySectionIndex", -1)
        return prefs.contains("$bookKey.elapsed.$chapter") &&
            canRestoreLocalAudio(selected, stored("passage"), stored("audioServer") ?: previousBaseline)
    }
    val initialPlace = remember {
        if (AudilegePlayback.activeBook?.file == file && (resumeSession ||
                AudilegePlayback.outputId != null || AudilegePlayback.connectingOutput != null)) {
            return@remember ReadingPlace(AudilegePlayback.chapter, null, AudilegePlayback.anchor)
        }
        savedPlace()
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
    var bookmarkRevision by remember(bookKey) { mutableIntStateOf(0) }
    var bookmarkPending by remember(bookKey) { mutableStateOf(false) }
    val bookmarkLock = remember(bookKey) { kotlinx.coroutines.sync.Mutex() }
    var selectedPassage by remember { mutableStateOf<Pair<Int, PagePassage>?>(null) }
    var highlightDelay by remember { mutableIntStateOf(prefs.getInt("$bookKey.highlightDelay", 0).coerceIn(-1500, 1500)) }
    var sentenceMark by remember { mutableStateOf(prefs.getBoolean("highlightSentence", false)) }
    var speed by remember { mutableFloatStateOf(prefs.getFloat("speed", 1f).coerceIn(0.75f, 2f)) }
    var controls by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Int?>(null) }
    val playback = AudilegePlayback
    val openedAudio = remember(file, online) { OpenBook(file, book, Mode.Listen, online, serverProgress) }
    val attached = playback.activeBook?.file == file
    val playing = attached && playback.playing
    val scope = rememberCoroutineScope()
    var syncState by remember { mutableStateOf(sync?.state) }
    var retryingSync by remember { mutableStateOf(false) }
    var conflictWriteFailed by remember { mutableStateOf(false) }
    var movement by remember(location) { mutableIntStateOf(0) }
    var lastAnchor by remember(location) { mutableStateOf<String?>(location.anchor) }
    var chapterFailed by remember(displayed) { mutableStateOf(false) }
    var view by remember { mutableStateOf<ReaderPage?>(null) }
    var pages by remember(displayed) { mutableStateOf(0 to 1) }
    val audioError = attached && playback.failed
    var chosenAudioStart by remember { mutableStateOf<Location?>(null) }
    var listenJump by remember(book, mode) { mutableStateOf<ListenJump?>(null) }
    var audioRetry by remember { mutableIntStateOf(0) }
    var readingInteraction by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    TrackReadingActivity(activity, openedAudio, mode == Mode.Read && view?.isReady == true &&
        !chapterFailed && dialog == null && selectedPassage == null, readingInteraction, ownerScope)


    suspend fun syncPending(force: Boolean = false, userInitiated: Boolean = false) {
        val bridge = sync ?: return
        if (!force && bridge.state == SyncState.Conflict) {
            syncState = SyncState.Conflict
            return
        }
        if (userInitiated && retryingSync) return
        if (userInitiated) retryingSync = true
        try {
            if (force) bridge.keepLocal()
            if (bridge.pending != null) bridge.flush(force) else bridge.refresh()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (force) conflictWriteFailed = true
        } finally {
            syncState = bridge.state
            if (syncState != SyncState.Conflict) conflictWriteFailed = false
            if (userInitiated) retryingSync = false
        }
    }

    fun stage(candidate: JSONObject?) {
        if (candidate == null) return
        val percent = candidate.optDouble("percentage", Double.NaN)
        val editor = prefs.edit().putString("$bookKey.passage", candidate.toString())
        if (percent.isFinite()) editor.putFloat("$bookKey.shelfPercent", percent.toFloat())
        val place = candidate.place(book, Mode.Read)
        if (place?.cfi != null && candidate.optString("source") == "text") {
            editor.putString("$bookKey.cfi.${place.chapter}", place.cfi)
        }
        editor.apply()
        if (sync == null || sync.pending?.toString() == candidate.toString()) return
        sync.remember(candidate)
        syncState = sync.state
    }

    fun stagePlayback() {
        if (playback.failed || playback.loading) return
        val active = playback.activeBook?.takeIf { it.file == file } ?: return
        stage(active.publication.progressPayload(playback.chapter, null, playback.anchor, playback.fraction, true, active.mode))
    }

    fun startAudio() {
        val chosen = chosenAudioStart
        chosenAudioStart = null
        if (chosen != null) {
            playback.start(context, openedAudio.copy(mode = mode), chosen.index, chosen.anchor, false, sync, chosen.cfi, chosen.serverSeconds, chosen.elapsedMs)
            return
        }
        // The media notification can stop playback while this reader still holds its opening location.
        val selected = savedPosition()
        val saved = selected?.let { it.place(book, mode) ?: ReadingPlace(0, null, null) }
        val index = saved?.chapter ?: prefs.getInt("$bookKey.chapter", 0).coerceIn(book.spine.indices)
        val useLocal = selected == null || (saved?.anchor != null && canUseCheckpoint(selected))
        val seconds = if (useLocal) null else selected?.optDouble("positionSeconds")?.takeIf(Double::isFinite)
        playback.start(context, openedAudio.copy(mode = mode), index, saved?.anchor, useLocal, sync, saved?.cfi, seconds)
    }

    fun go(index: Int, anchor: String? = null, continuePlaying: Boolean = false) {
        if (index !in book.spine.indices) return
        if (!continuePlaying && index == location.index && anchor != null && mode == Mode.Follow) {
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
            playback.start(context, openedAudio.copy(mode = mode), index, anchor, false, sync)
        } else if (playback.activeBook?.file == file) playback.stop()
        location = Location(index, anchor, location.revision + 1, textNavigation = mode == Mode.Read)
        if (!continuePlaying && mode != Mode.Read) chosenAudioStart = location
        prefs.edit().putInt("$bookKey.chapter", index).apply()
    }

    fun captureVisible(done: (ReaderTarget) -> Unit) {
        val current = displayed
        val page = view
        val fallback = ReaderTarget(current.index, current.cfi, current.anchor, offset = page?.readingOffset ?: 0)
        if (mode == Mode.Listen) {
            val label = playback.progress?.elapsedMs?.let { ms ->
                val s = ms / 1000
                "%d:%02d".format(s / 60, s % 60)
            }.orEmpty()
            done(fallback.copy(anchor = playback.anchor.takeIf { attached } ?: lastAnchor ?: current.anchor, label = label,
                audioMs = playback.progress?.elapsedMs?.takeIf { attached }))
        } else if (page == null) done(fallback)
        else {
            val restored = mode == Mode.Read && movement == 0 && !current.textNavigation &&
                (current.anchor != null || current.cfi != null)
            var text = ""
            page.capturePosition(current.anchor.takeIf { restored }, excerpt = { text = it }) { cfi, _ ->
                done(if (restored) fallback.copy(label = text)
                    else fallback.copy(cfi = cfi ?: current.cfi, anchor = null, label = text))
            }
        }
    }
    fun navigate(target: ReaderTarget) {
        if (target.chapter !in book.spine.indices) return
        if (target.audioMs != null && mode == Mode.Listen) {
            if (attached) playback.selectChapter(target.chapter, target.anchor, target.audioMs)
            else {
                location = Location(target.chapter, target.anchor, displayed.revision + 1, elapsedMs = target.audioMs)
                chosenAudioStart = location
            }
            return
        }
        val next = Location(target.chapter, target.anchor, displayed.revision + 1, target.cfi, target.offset,
            textNavigation = true)
        if (mode == Mode.Read) {
            location = next
            inspection = null
            prefs.edit().putInt("$bookKey.chapter", target.chapter).apply()
        } else {
            inspection = next
            if (mode == Mode.Listen) {
                if (attached) stagePlayback()
                playback.setListenEligible(false)
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
        val duplicate = marks.any { old ->
            old.chapter == target.chapter && when {
                target.audioMs != null -> old.audioMs == target.audioMs
                target.cfi != null -> target.cfi == old.cfi
                target.anchor != null -> target.anchor == old.anchor
                else -> old.cfi == null && old.anchor == null && old.offset == target.offset
            }
        }
        if (duplicate) return
        marks = bookmarks.add(target.copy(syncKey = java.util.UUID.randomUUID().toString()))
        bookmarkRevision++
    }
    fun saveNow(done: () -> Unit = {}) {
        if (syncState == SyncState.Conflict) { done(); return }
        val activeChapter = location.index
        val page = view
        if (mode == Mode.Read && (movement > 0 || location.textNavigation) && page != null) {
            page.capturePosition(null) { cfi, fraction ->
                stage(book.progressPayload(activeChapter, cfi ?: location.cfi, null, fraction, false))
                ownerScope.launch { syncPending() }
                done()
            }
        } else {
            if (mode != Mode.Read) stagePlayback()
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
            playback.setListenEligible(entry == Mode.Listen)
            mode = entry
            prefs.edit().putInt("$bookKey.mode", entry.ordinal).apply()
            if (entry != Mode.Listen) controls = false
        }
        if (entry == Mode.Read) {
            if (attached) stagePlayback()
            val next = inspection ?: if (attached && playback.anchor != null) {
                Location(playback.chapter, playback.anchor, location.revision + 1)
            } else location.copy(revision = location.revision + 1)
            if (attached) playback.pause()
            inspection = null
            location = next
            store()
        } else if (previous == Mode.Read || inspection != null) {
            captureVisible { target ->
                inspection = null
                store()
                location = Location(target.chapter, target.anchor, location.revision + 1, target.cfi, target.offset)
                playback.start(context, openedAudio.copy(mode = entry), target.chapter, target.anchor, false, sync, target.cfi)
            }
        } else {
            if (previous == Mode.Listen && attached && playback.outputId != null) {
                stagePlayback()
                location = Location(playback.chapter, playback.anchor, location.revision + 1)
            }
            store()
            if (entry == Mode.Listen && attached && playback.progress == null)
                playback.start(context, openedAudio.copy(mode = entry), location.index, location.anchor, false, sync, location.cfi)
        }
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

    val jump = listenJump
    val findAudio = mode == Mode.Listen && (!attached || jump != null)
    val requestedIndex = location.index
    val sectionAudio by key(book, chapter, findAudio, jump, audioRetry) {
        produceState<SectionAudio>(SectionAudio.Loading) {
            value = withContext(Dispatchers.IO) {
                try {
                    val index = if (findAudio) book.findNarratedSection(jump?.start ?: requestedIndex, jump?.direction ?: 1)
                        else requestedIndex
                    val cues = index?.let { book.cues(book.spine[it]) }.orEmpty()
                    val duration = if (cues.isEmpty()) 0 else ChapterClock(cues).duration
                    SectionAudio.Ready(index, cues, duration)
                } catch (cancel: CancellationException) { throw cancel }
                  catch (_: Exception) { SectionAudio.Failed }
            }
        }
    }
    val readyAudio = sectionAudio as? SectionAudio.Ready
    val chapterCues = readyAudio?.takeIf { it.index == location.index }?.cues.orEmpty()
    LaunchedEffect(mode, findAudio, readyAudio, jump) {
        if (mode != Mode.Listen || !findAudio || readyAudio == null) return@LaunchedEffect
        val index = readyAudio.index
        if (index != null && jump != null) {
            go(index, jump.anchor.takeIf { index == jump.start }, continuePlaying = playing)
        } else if (index != null && index != location.index) {
            // Preparing a paused player is not reading activity and does not move server progress.
            location = Location(index, revision = location.revision + 1)
        }
        if (jump != null) listenJump = null
    }
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
        val leavingSpeaker = mode != Mode.Listen && (playback.outputId != null || playback.connectingOutput != null)
        playback.setListenEligible(mode == Mode.Listen)
        if (!leavingSpeaker && initialMode != null && mode != Mode.Read) {
            val active = playback.activeBook?.file == file
            if (!active) startAudio()
            else if (!resumeSession && !playback.playing) {
                val saved = savedPlace()
                if (saved?.chapter == playback.chapter && saved.anchor != null && saved.anchor == playback.anchor &&
                    savedPosition()?.let(::canUseCheckpoint) == true)
                    playback.resume()
                else startAudio()
            }
        }
        if (mode == Mode.Read && playback.activeBook?.file == file) playback.pause()
    }
    LaunchedEffect(attached, playback.chapter) {
        if (attached && mode != Mode.Read && playback.chapter != location.index) {
            location = Location(playback.chapter, playback.anchor, location.revision + 1)
        }
    }
    LaunchedEffect(view, mode, displayed.index) {
        val page = view ?: return@LaunchedEffect
        val item = book.spine[displayed.index]
        if (mode == Mode.Follow && page.chapterPath == item.path) {
            val encoded = withContext(Dispatchers.IO) {
                try { org.json.JSONArray(book.cues(item).mapNotNull { it.fragment }).toString() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            if (encoded != null) page.timedWords(encoded)
        }
    }
    LaunchedEffect(view, mode, attached, displayed.index, location.index, sentenceMark) {
        snapshotFlow { playback.marker }.collect { fragment ->
            if (attached && mode == Mode.Follow && displayed.index == location.index) view?.highlight(fragment, sentenceMark)
            else view?.highlight(null)
            if (attached) playback.anchor?.let { lastAnchor = it }
        }
    }
    LaunchedEffect(highlightDelay) { playback.highlightDelay = highlightDelay }
    LaunchedEffect(speed) { if (playback.activeBook?.file == file) playback.changeSpeed(speed) }
    LaunchedEffect(playback.speed, attached) { if (attached && speed != playback.speed) speed = playback.speed }
    LaunchedEffect(chapterFailed, audioError, mode, sectionAudio) {
        if (chapterFailed || audioError || (mode == Mode.Follow && (sectionAudio == SectionAudio.Failed ||
                readyAudio?.cues?.isEmpty() == true))) controls = true
    }
    LaunchedEffect(view, movement, location, mode) {
        if (mode != Mode.Read || view == null ||
            (movement == 0 && !location.textNavigation)) return@LaunchedEffect
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
            stagePlayback()
        }
    }

    val pageMode = mode == Mode.Read && style.paged
    LaunchedEffect(view, style, mode) { view?.appearance(style.copy(paged = pageMode)) }
    LaunchedEffect(view, mode, inspection) { view?.setFollowing(mode == Mode.Follow && inspection == null) }

    val colors = if (mode == Mode.Listen) MaterialTheme.colorScheme else appearanceColors(paper, accent)
    val readerMenu = buildList {
        if (book.spine.any { it.overlay.isNotBlank() })
            add(MenuItem(R.string.player_modes) { dialog = R.string.player_modes })
        if (mode == Mode.Listen || mode == Mode.Follow)
            add(MenuItem(R.string.eq_title) { dialog = R.string.eq_title })
    } + listOf(
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
    AppVolumeFeedback()
    SystemBarAppearance(activity)
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.then(if (controls || mode == Mode.Listen) Modifier.safeDrawingPadding() else Modifier)) {
            if (controls || mode == Mode.Listen) {
                FolioBar(stringResource(if (mode == Mode.Listen) R.string.player_title else mode.label),
                    back = ::leaveReader, backLabel = backLabel, overflow = readerMenu) {
                    if (mode == Mode.Listen) ListenOutputButton()
                    if (mode == Mode.Listen) IconButton(onClick = { dialog = R.string.contents }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.List, stringResource(R.string.contents))
                    }
                    if (mode != Mode.Listen) TextButton(onClick = { dialog = R.string.settings }) {
                        Text("Aa", Modifier.semantics { contentDescription = context.getString(R.string.design_reading) })
                    }
                }
            }
            if (mode == Mode.Listen) {
                ListeningArtwork(book, online, file, covers, location.index, Modifier.weight(1f), podcast = OfflineBooks.isPodcast(prefs, file))
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
                                    page.onPages = { index, total -> pages = index to total }
                                    page.onReadingActivity = { readingInteraction = android.os.SystemClock.elapsedRealtime() }
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
                if (syncState == SyncState.Unavailable) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp)) {
                        Text(stringResource(R.string.sync_retry_automatic), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(enabled = !retryingSync, onClick = { scope.launch { syncPending(userInitiated = true) } }) {
                            Text(stringResource(R.string.sync_retry))
                        }
                    }
                } else if (syncState == SyncState.Conflict && sync != null) {
                    Text(stringResource(R.string.sync_conflict), Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium)
                    SyncPlaceLabel(book, mode, sync.local, sync.localChangedAt, R.string.reader_local_place, displayed.index)
                    SyncPlaceLabel(book, mode, sync.remote, sync.remoteChangedAt, R.string.reader_remote_place, 0)
                    if (conflictWriteFailed) Text(stringResource(R.string.sync_choice_failed),
                        Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(enabled = !retryingSync, onClick = {
                            val place = sync.remote?.place(book, mode)
                            sync.discardLocal()
                            syncState = SyncState.Synced
                            conflictWriteFailed = false
                            if (attached) playback.stop(savePosition = false)
                            inspection = null
                            location = Location(place?.chapter ?: 0, place?.anchor, location.revision + 1, place?.cfi, 0,
                                serverSeconds = sync.remote?.optDouble("positionSeconds")?.takeIf(Double::isFinite))
                            chosenAudioStart = location
                            val edit = prefs.edit().putInt("$bookKey.chapter", location.index)
                                .putString("$bookKey.passage", sync.remote?.toString())
                                .remove("$bookKey.cue.${location.index}").remove("$bookKey.elapsed.${location.index}")
                                .remove("$bookKey.replayUntil.${location.index}").remove("$bookKey.audioServer")
                            if (place == null) edit.putInt("$bookKey.scroll.0", 0).remove("$bookKey.cfi.0")
                                .putFloat("$bookKey.shelfPercent", 0f)
                            edit.apply()
                        }) { Text(stringResource(R.string.sync_remote)) }
                        TextButton(enabled = !retryingSync, onClick = {
                            if (sync.local?.place(book,mode) != null) scope.launch { syncPending(force = true, userInitiated = true) }
                            else if (mode != Mode.Read && attached && playback.anchor != null) {
                                stagePlayback()
                                scope.launch { syncPending(force = true, userInitiated = true) }
                            } else view?.capturePosition(null) { cfi, part ->
                                stage(book.progressPayload(displayed.index,cfi,null,part,false))
                                scope.launch { syncPending(force = true, userInitiated = true) }
                            }
                        }) {
                            Text(stringResource(R.string.sync_local))
                        }
                    }
                }
                if (chapterFailed) TextButton(onClick = {
                    val retry = displayed.copy(revision = displayed.revision + 1)
                    if (inspection != null) inspection = retry else location = retry
                }) { Text(stringResource(R.string.stream_retry)) }
                if (mode != Mode.Read && sectionAudio == SectionAudio.Failed) {
                    TextButton(onClick = {
                        audioRetry++
                        if (attached && playback.failed) playback.resume()
                    }) { Text(stringResource(R.string.narration_retry)) }
                } else if (audioError) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.audio_error), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = playback::resume) { Text(stringResource(R.string.narration_retry)) }
                    }
                } else if (mode == Mode.Listen && jump == null &&
                    (if (attached) playback.noNarration else readyAudio != null && readyAudio.index == null)) {
                    Text(stringResource(R.string.no_further_narration), Modifier.padding(horizontal = 16.dp))
                } else if (mode == Mode.Follow && readyAudio?.cues?.isEmpty() == true) {
                    Text(stringResource(R.string.no_audio), Modifier.padding(horizontal = 16.dp))
                }
                val lookupInProgress = findAudio && sectionAudio == SectionAudio.Loading
                val canGoBack = if (mode == Mode.Listen && attached) playback.canChapterBy(-1) else if (mode == Mode.Listen)
                    (location.index - 1 downTo 0).any(book::mayHaveNarration) else displayed.index > 0
                val canGoForward = if (mode == Mode.Listen && attached) playback.canChapterBy(1) else if (mode == Mode.Listen)
                    (location.index + 1 until book.spine.size).any(book::mayHaveNarration) else displayed.index < book.spine.lastIndex
                ReaderDock(
                    mode, displayed.index, canGoBack || (pageMode && pages.first > 0),
                    canGoForward || (pageMode && pages.first + 1 < pages.second), playback.progress.takeIf { attached },
                    readyAudio?.takeIf { it.index == location.index }?.durationMs ?: 0, speed, playing,
                    (attached && playback.loading) || lookupInProgress,
                    playing || (attached && (playback.progress != null || audioError)) || chapterCues.isNotEmpty(),
                    onPrevious = {
                        if (pageMode) view?.turn(-1)
                        else if (mode == Mode.Listen && attached) playback.chapterBy(-1)
                        else if (mode == Mode.Listen) listenJump = ListenJump(location.index - 1, -1)
                        else visit(ReaderTarget(displayed.index - 1))
                    },
                    onNext = {
                        if (pageMode) view?.turn(1)
                        else if (mode == Mode.Listen && attached) playback.chapterBy(1)
                        else if (mode == Mode.Listen) listenJump = ListenJump(location.index + 1, 1)
                        else visit(ReaderTarget(displayed.index + 1))
                    },
                    onContents = { dialog = R.string.contents },
                    pages = pages.takeIf { pageMode },
                    audioAfterChapterMs = if (mode == Mode.Listen && book.spine.size > 1)
                        book.narrationAfter(if (attached) playback.chapter else location.index) else null,
                    onUndoJump = if (mode == Mode.Listen && attached && playback.canUndoJump) playback::undoJump else null,
                    onSpeed = { dialog = R.string.speed },
                    onRepeat = if (mode == Mode.Follow && chapterCues.isNotEmpty()) ::repeatSentence else null,
                    onPlay = {
                        if (attached) {
                            if (playing) saveNow()
                            playback.toggle()
                        } else startAudio()
                        if (playback.playing && mode == Mode.Follow) controls = false
                    },
                )
            }
        }
    }

    LaunchedEffect(dialog == R.string.reader_bookmarks,bookmarkRevision,online) {
        if (online != null && online.catalog.supportsSharedBookmarks) {
            bookmarkLock.lock()
            try {
                marks = bookmarks.synchronize(online,book)
                bookmarkPending=false
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                bookmarkPending=true
            } finally { bookmarkLock.unlock() }
        }
    }

    dialog?.let { title ->
        val choose: (ReaderTarget) -> Unit = { target -> dialog = null; visit(target) }
        if (title == R.string.eq_title) AudiobookEqualizerDialog(prefs) { dialog = null }
        else if (title == R.string.reader_search) ReaderSearchDialog(book, choose) { dialog = null }
        else if (title == R.string.reader_bookmarks) ReaderBookmarksDialog(book, marks,
            add = { captureVisible(::addBookmark) }, open = choose, remove = { mark ->
                marks = bookmarks.remove(mark)
                bookmarkRevision++
            }, pending = bookmarkPending, retry = { bookmarkRevision++ }, dismiss = { dialog = null })
        else AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(title)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.close)) } },
            text = {
                AppVolumeFeedback()
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
                                if (mode == Mode.Listen) {
                                    captureVisible { history.add(it) }
                                    if (attached) playback.selectChapter(index, entry.fragment)
                                    else listenJump = ListenJump(index, 1, entry.fragment)
                                }
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
                        Choice(stringResource(R.string.highlight_unit), listOf(R.string.highlight_word, R.string.highlight_sentence),
                            if (sentenceMark) 1 else 0) { selected ->
                            sentenceMark = selected == 1
                            prefs.edit().putBoolean("highlightSentence", sentenceMark).apply()
                        }
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
                AppVolumeFeedback()
                Column {
                    Text(passage.label)
                    TextButton(onClick = {
                        addBookmark(ReaderTarget(index, passage.cfi, label = passage.label))
                        selectedPassage = null
                    }) { Text(stringResource(R.string.reader_add_mark)) }
                    if (media != null) TextButton(onClick = {
                        val anchor = media
                        selectedPassage = null
                        playback.setListenEligible(false)
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
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.appVolumeKeys()) {
            AppVolumeFeedback()
            options.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { select(index); expanded = false })
            }
        }
    }
}
