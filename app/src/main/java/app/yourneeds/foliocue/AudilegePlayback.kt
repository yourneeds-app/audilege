package app.yourneeds.foliocue

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.*
import java.io.File
import kotlinx.coroutines.*

/** The reader, mini-player and Android media session share one media clock and one set of commands. */
internal object AudilegePlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var focus: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private var job: Job? = null
    private var load: Job? = null
    private var chapterSearch: Job? = null
    private var retryStep: Int? = null
    private var narration: Narration? = null
    private var routeJob: Job? = null
    private var routeRevision = 0L
    private var switchingToPhone = false
    private var heldAnchor: String? = null
    private var heldFraction = 0.0
    private data class ChapterRequest(val index: Int, val anchor: String?, val resumeSaved: Boolean, val cfi: String?,
        val elapsed: Int?, val serverSeconds: Double? = null)
    private var requestedChapter: ChapterRequest? = null
    private var listenMode by mutableStateOf(false)
    val listenEligible get() = listenMode
    var outputName by mutableStateOf<String?>(null)
        private set
    var outputId by mutableStateOf<String?>(null)
        private set
    var connectingOutput by mutableStateOf<String?>(null)
        private set
    var outputError by mutableStateOf<Int?>(null)
        private set
    var outputVolume by mutableIntStateOf(25)
        private set
    var localEqualizerUnavailable by mutableStateOf(false)
        private set
    private var sync: ReadingSync? = null
    private val flushing = mutableSetOf<ReadingSync>()
    private var readerVisible = false
    var activeBook by mutableStateOf<OpenBook?>(null)
        private set
    var chapter by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var noNarration by mutableStateOf(false)
        private set
    var marker by mutableStateOf<String?>(null)
        private set
    val anchor get() = narration?.anchor ?: heldAnchor
    val fraction get() = narration?.fraction ?: heldFraction
    var progress by mutableStateOf<TrackProgress?>(null)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var highlightDelay = 0
    var sleepEndChapter by mutableStateOf(false)
        private set
    var sleepUntil by mutableLongStateOf(0L)
        private set
    val skipSeconds: Int get() = prefs?.getInt("skipSeconds", 15)?.coerceIn(5,120) ?: 15
    private val prefs get() = context?.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private var lastPersist = 0L
    private var lastCheckpointMs = -1
    private var rewindOnPlay = false
    private var replayUntilMs = 0
    private val listeningMeter = ListeningMeter()
    private var listeningStats: ListeningStats? = null
    private var pendingListeningMs = 0L
    private var statisticsSync: Job? = null
    private var lastStatisticsSync = 0L
    var service: PlaybackService? = null
    var startPending = false

    fun connect(book: OpenBook, context: Context, sync: ReadingSync?) {
        this.context = context.applicationContext
        if (listeningStats == null) listeningStats = ListeningStats(context.applicationContext)
        if (activeBook != null && activeBook?.file != book.file) stop()
        this.sync = sync
        readerVisible = true
    }

    fun readerVisible(visible: Boolean) { readerVisible = visible }

    fun setListenEligible(eligible: Boolean) {
        if (listenMode && !eligible && chapterSearch?.isActive == true) {
            chapterSearch?.cancel()
            loading = false
            if (playing && narration?.finished == false) narration?.setPlaying(true, speed) else pause()
        }
        listenMode = eligible
        if (!eligible) localMode()
    }

    fun localMode() {
        if (outputId != null || connectingOutput != null || switchingToPhone) leaveRoute(null, resumeOnPhone = false)
    }

    fun changeOutputVolume(value: Int) {
        if (outputId == null) return
        AirPlaySender.setVolume(value)
        outputVolume = AirPlaySender.volume
        service?.refresh(force = true)
    }

    fun selectOutput(speaker: AirPlaySpeaker?) {
        if (speaker == null) {
            outputError = null
            if (outputId != null || connectingOutput != null) leaveRoute(null, resumeOnPhone = playing)
            return
        }
        val ctx = context ?: return
        if (!listenEligible || outputId == speaker.id) return
        val revision = ++routeRevision
        routeJob?.cancel()
        freezeChapter()
        switchingToPhone = false
        outputError = null
        outputId = null
        outputName = null
        connectingOutput = speaker.name
        loading = activeBook != null
        service?.refresh(force = true)
        routeJob = scope.launch {
            val connected = try { AirPlaySender.connect(ctx, speaker) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
            if (revision != routeRevision) return@launch
            if (!connected) {
                leaveRoute(R.string.route_connection_failed, resumeOnPhone = false)
                return@launch
            }
            outputId = speaker.id
            outputName = speaker.name
            outputVolume = AirPlaySender.volume
            connectingOutput = null
            reopenRequested()
            service?.refresh(force = true)
            while (isActive && revision == routeRevision) {
                delay(200)
                if (!AirPlaySender.connected) {
                    leaveRoute(R.string.route_lost, resumeOnPhone = false)
                    return@launch
                }
            }
        }
    }

    private fun freezeChapter() {
        val current = narration
        if (current != null) {
            heldAnchor = current.anchor
            heldFraction = current.fraction
            progress = current.progress()
            checkpoint()
            requestedChapter = ChapterRequest(chapter, heldAnchor, false, null, progress?.elapsedMs)
        }
        job?.cancel()
        load?.cancel()
        chapterSearch?.cancel()
        current?.close()
        narration = null
    }

    private fun reopenRequested() {
        requestedChapter?.let { openChapter(it.index, it.anchor, it.resumeSaved, it.cfi, it.elapsed, it.serverSeconds) }
    }

    private fun leaveRoute(reason: Int?, resumeOnPhone: Boolean) {
        val revision = ++routeRevision
        routeJob?.cancel()
        freezeChapter()
        if (!resumeOnPhone) {
            playing = false
            abandonFocus()
        }
        switchingToPhone = true
        connectingOutput = null
        outputName = null
        outputId = null
        outputError = reason
        loading = activeBook != null
        service?.refresh(force = true)
        routeJob = scope.launch {
            try { AirPlaySender.disconnect() } finally {
                if (revision == routeRevision) {
                    switchingToPhone = false
                    reopenRequested()
                    service?.refresh(force = true)
                }
            }
        }
    }

    fun start(context: Context, book: OpenBook, index: Int, anchor: String?, resumeSaved: Boolean, bridge: ReadingSync?,
        cfi: String? = null, serverSeconds: Double? = null) {
        if (index !in book.publication.spine.indices) return
        this.context = context.applicationContext
        if (listeningStats == null) listeningStats = ListeningStats(context.applicationContext)
        if (activeBook != null && activeBook?.file != book.file) {
            val visible = readerVisible
            stop()
            readerVisible = visible
        }
        sync = bridge
        setListenEligible(book.mode == Mode.Listen)
        activeBook = book.copy(mode = Mode.Listen, resumeSession = true)
        speed = prefs?.getFloat("speed", 1f)?.coerceIn(0.75f, 2f) ?: 1f
        highlightDelay = prefs?.getInt("${keyOf(book.file.absolutePath)}.highlightDelay",0) ?: 0
        if (!requestFocus()) {
            failed = true
            playing = false
            return
        }
        playing = true
        if (!requestService()) return
        openChapter(index, anchor, resumeSaved, cfi, serverSeconds = serverSeconds)
    }

    private fun openChapter(index: Int, anchor: String?, resumeSaved: Boolean, cfi: String? = null, elapsedOverride: Int? = null,
        serverSeconds: Double? = null) {
        flushListeningStats()
        listeningMeter.reset()
        val opened = activeBook ?: return
        val ctx = context ?: return
        job?.cancel()
        load?.cancel()
        chapterSearch?.cancel()
        retryStep = null
        narration?.close()
        narration = null
        requestedChapter = ChapterRequest(index, anchor, resumeSaved, cfi, elapsedOverride, serverSeconds)
        heldAnchor = anchor
        if (elapsedOverride == null) {
            heldFraction = 0.0
            rewindOnPlay = false
            replayUntilMs = 0
        }
        marker = null
        if (elapsedOverride == null) progress = null
        failed = false
        noNarration = false
        loading = true
        chapter = index
        if (connectingOutput != null || switchingToPhone) return
        val remoteGeneration = outputId?.let { AirPlaySender.generation }
        if (remoteGeneration != null) AirPlaySender.metadata(opened.publication.title, opened.publication.author)
        lastCheckpointMs = -1
        val key = keyOf(opened.file.absolutePath)
        val publication = opened.publication
        val scan = listenMode
        job = scope.launch {
            try {
                val target = if (scan) withContext(Dispatchers.IO) { publication.findNarratedSection(index, 1) } else index
                if (target != index && scan && !listenMode) {
                    openChapter(index, anchor, resumeSaved, cfi, elapsedOverride, serverSeconds)
                    return@launch
                }
                if (target != null && target != index) {
                    // An anchor, CFI and elapsed time belong to their original spine document.
                    openChapter(target, null, false)
                    return@launch
                }
                val marks = if (target == null) emptyList() else withContext(Dispatchers.IO) {
                    publication.cues(publication.spine[index]).also { cues ->
                        // Narration still works if a damaged XHTML resource has no usable text locator.
                        try { publication.prepareNarrationLocations(index, cues) } catch (_: Exception) { }
                    }
                }
                if (marks.isEmpty()) {
                    loading = false
                    noNarration = true
                    pause()
                    return@launch
                }
                prefs?.edit()?.putInt("$key.chapter", index)?.apply()
                val resolvedAnchor = anchor ?: cfi?.let { withContext(Dispatchers.IO) { publication.narrationAnchor(index,it,marks) } }
                val marked = resolvedAnchor?.let { target -> marks.indexOfFirst { it.fragment == target }.takeIf { it >= 0 } }
                val saved = prefs?.getInt("$key.cue.$index", 0) ?: 0
                val fallback = if (resumeSaved && anchor == null && cfi == null) saved else 0
                val engine = Narration(ctx, marks, marked ?: fallback, { marker = it }, ::onFailure, remoteGeneration) {
                    localEqualizerUnavailable = it
                }
                narration = engine
                if (elapsedOverride != null) {
                    engine.seek(elapsedOverride)
                } else if (serverSeconds != null && anchor != null && marked != null) {
                    val cue = marks[marked]
                    elapsedWithinCue(serverSeconds, publication.narrationSeconds(index, anchor),
                        cue.end - cue.start, engine.progress().elapsedMs)?.let(engine::seek)
                } else if (resumeSaved && cfi == null) {
                    val exact = matchingAudioElapsed(prefs?.getInt("$key.elapsed.$index", -1) ?: -1,
                        marks.getOrNull(saved)?.fragment, anchor, cfi)
                    if (exact != null) {
                        replayUntilMs = prefs?.getInt("$key.replayUntil.$index", 0) ?: 0
                        engine.seek(resumePosition(exact))
                    } else if (marked != null) {
                        engine.seek(resumePosition(engine.progress().elapsedMs))
                    }
                }
                progress = engine.progress()
                attach(opened, engine)
                var lastUI = 0L
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (sleepUntil != 0L && now >= sleepUntil) {
                        sleep(0)
                        pause()
                    }
                    if (playing && !loading && !failed && !engine.finished) {
                        if (engine.tick(highlightDelay)) attach(opened, engine)
                        if (engine.finished) {
                            checkpoint()
                            if (sleepEndChapter) {
                                sleep(0)
                                pause()
                            } else stepChapter(1, completed = true)
                        }
                        if (now - lastPersist >= 5000) checkpoint()
                    }
                    if (now - lastUI >= 250) {
                        progress = engine.progress()
                        sampleListeningStats(now)
                        lastUI = now
                        service?.refresh()
                    }
                    delay(if (playing && !loading && opened.mode == Mode.Follow) 16L else if (playing && !loading) 24L else 200L)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { if (isActive) chapterFailure() }
        }
    }

    private fun attach(opened: OpenBook, engine: Narration) {
        val ctx = context ?: return
        load?.cancel()
        loading = true
        load = scope.launch {
            try {
                engine.setPlaying(playing, speed)
                val source = opened.publication.audioStream
                val path = engine.audio
                if (source != null) {
                    val stream = withContext(Dispatchers.IO) { source(path) }
                    engine.attach(stream) { source(path) }
                } else {
                    val part = File.createTempFile("audilege-audio-", ".part", ctx.cacheDir)
                    var retained = false
                    try {
                        withContext(Dispatchers.IO) {
                            val input = opened.publication.open(path) ?: error("Missing audio file")
                            input.use { from -> part.outputStream().use { to -> from.copyTo(to) } }
                        }
                        engine.attach(part)
                        retained = engine.retainsAudioFile
                    } finally { if (!retained) part.delete() }
                }
                engine.setPlaying(playing, speed)
                loading = false
                service?.refresh(force = true)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { if (isActive && narration === engine) onFailure() }
        }
    }

    private fun onFailure() {
        if (outputId != null) {
            leaveRoute(if (AirPlaySender.connected) R.string.route_audio_failed else R.string.route_lost, resumeOnPhone = false)
            return
        }
        chapterFailure()
    }

    private fun chapterFailure() {
        failed = true
        noNarration = false
        loading = false
        pause()
    }

    fun toggle() { if (playing) pause() else resume() }

    private fun resumePosition(positionMs: Int): Int {
        val rewind = prefs?.getInt("resumeRewindSeconds", 0) ?: 0
        val target = resumeAfterPauseMs(positionMs, replayUntilMs, rewind)
        replayUntilMs = if (rewind > 0) maxOf(positionMs, replayUntilMs) else 0
        return target
    }

    fun resume() {
        val opened = activeBook ?: return
        if (playing) return
        if (!requestFocus()) { failed = true; return }
        playing = true
        if (!requestService()) return
        if (connectingOutput != null || switchingToPhone || chapterSearch?.isActive == true) {
            if (rewindOnPlay) requestedChapter?.let { request ->
                request.elapsed?.let { requestedChapter = request.copy(elapsed = resumePosition(it)) }
            }
            rewindOnPlay = false
            service?.refresh(force = true)
            return
        }
        val step = retryStep
        if (failed && step != null) {
            stepChapter(step, completed = narration?.finished == true)
        } else if (narration?.finished == true && canChapterBy(1)) {
            stepChapter(1, completed = true)
        } else if (failed || (narration == null && !loading) || narration?.finished == true) {
            val completed = narration?.finished == true
            val engine = narration
            if (failed && engine == null && requestedChapter != null) reopenRequested()
            else if (failed && engine != null && !completed)
                openChapter(chapter, engine.anchor, false, elapsedOverride = progress?.elapsedMs)
            else openChapter(chapter, null, !completed)
        } else narration?.let { engine ->
            if (rewindOnPlay) {
                val position = engine.progress().elapsedMs
                val target = resumePosition(position)
                if (target != position) {
                    if (engine.seek(target)) attach(opened, engine)
                    progress = engine.progress()
                }
            }
            engine.setPlaying(true, speed)
        }
        rewindOnPlay = false
        service?.refresh(force = true)
    }

    fun pause(releaseFocus: Boolean = true) {
        val wasPlaying = playing
        sampleListeningStats(SystemClock.elapsedRealtime())
        listeningMeter.reset()
        if (playing) rewindOnPlay = releaseFocus && !loading && !failed && narration?.finished == false
        playing = false
        runCatching { narration?.setPlaying(false, speed) }
        runCatching { narration?.progress() }.getOrNull()?.let { progress = it }
        if (releaseFocus) abandonFocus()
        checkpoint(syncNow = true, wasPlaying = wasPlaying)
        requestStatisticsSync(force = true)
        service?.refresh(force = true)
    }

    fun changeSpeed(value: Float) {
        speed = value.coerceIn(0.75f, 2f)
        prefs?.edit()?.putFloat("speed", speed)?.apply()
        if (playing && !loading) narration?.setPlaying(true, speed)
        service?.refresh(force = true)
    }

    fun seekAnchor(anchor: String): Boolean {
        if (loading || failed || job?.isActive != true) return false
        val elapsed = narration?.timeForAnchor(anchor) ?: return false
        seekTo(elapsed)
        if (!playing) resume()
        return true
    }

    fun seekTo(elapsedMs: Int) {
        val engine = narration ?: return
        if (failed || loading) return
        val opened = activeBook ?: return
        rewindOnPlay = false
        replayUntilMs = 0
        sampleListeningStats(SystemClock.elapsedRealtime())
        listeningMeter.reset()
        if (engine.seek(elapsedMs)) attach(opened, engine)
        marker = engine.anchor
        progress = engine.progress()
        checkpoint()
        service?.refresh(force = true)
    }

    fun skip(direction: Int) = seekTo((progress?.elapsedMs ?: 0) + direction * skipSeconds * 1000)

    fun canChapterBy(direction: Int): Boolean {
        val book = activeBook?.publication ?: return false
        if (loading) return false
        if (direction != 1 && direction != -1) return false
        val start = chapter + direction
        if (!listenMode) return start in book.spine.indices
        val candidates = if (direction == 1) start..book.spine.lastIndex else start downTo 0
        return candidates.any(book::mayHaveNarration)
    }

    fun chapterBy(delta: Int) = stepChapter(delta, completed = false)

    fun selectChapter(index: Int, anchor: String?) {
        val book = activeBook?.publication ?: return
        if (index !in book.spine.indices) return
        checkpoint()
        openChapter(index, anchor, false)
    }

    private fun stepChapter(direction: Int, completed: Boolean) {
        val book = activeBook?.publication ?: return
        if (!canChapterBy(direction)) {
            if (completed) pause()
            return
        }
        checkpoint()
        val start = chapter + direction
        if (!listenMode) {
            openChapter(start, null, false)
            return
        }
        chapterSearch?.cancel()
        narration?.setPlaying(false, speed)
        loading = true
        failed = false
        chapterSearch = scope.launch {
            try {
                val target = withContext(Dispatchers.IO) { book.findNarratedSection(start, direction) }
                loading = false
                retryStep = null
                if (target != null) openChapter(target, null, false)
                else if (completed) pause()
                else if (playing) narration?.setPlaying(true, speed)
                service?.refresh(force = true)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                  retryStep = direction
                  chapterFailure()
              }
        }
    }

    fun sleep(minutes: Int) {
        sleepEndChapter = minutes < 0
        sleepUntil = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    private fun checkpoint(syncNow: Boolean = false, wasPlaying: Boolean = playing) {
        flushListeningStats()
        if (failed || loading) return
        val book = activeBook ?: return
        val engine = narration ?: return
        val key = keyOf(book.file.absolutePath)
        val p = runCatching { engine.progress() }.getOrNull() ?: return
        // Stopping an already paused session must preserve any reading done since the pause.
        if (!wasPlaying && lastCheckpointMs == p.elapsedMs) return
        lastCheckpointMs = p.elapsedMs
        lastPersist = SystemClock.elapsedRealtime()
        val chapterFraction = engine.fractionAt(p)
        val candidate = book.publication.progressPayload(chapter, null, engine.anchor, chapterFraction, true, book.mode)
        prefs?.edit()?.also {
            if (candidate != null) it.putString("$key.passage", candidate.toString())
            sync?.remote?.let { remote -> it.putString("$key.audioServer", remote.toString()) }
        }
            ?.putInt("$key.chapter",chapter)?.putInt("$key.cue.$chapter", engine.index)
            ?.putInt("$key.elapsed.$chapter",p.elapsedMs)
            ?.putInt("$key.replayUntil.$chapter", replayUntilMs.takeIf { it > p.elapsedMs } ?: 0)
            ?.putFloat("$key.shelfPercent", ((chapter + chapterFraction) * 100 / book.publication.spine.size).toFloat().coerceIn(0f,100f))
            ?.apply()
        if (syncNow || !readerVisible) {
            val bridge = sync ?: return
            candidate?.let {
                bridge.remember(it)
                if (flushing.add(bridge)) scope.launch {
                    try {
                        do {
                            val submitted = bridge.pending ?: break
                            val result = bridge.flush()
                        } while (result == SyncState.Pending && bridge.pending !== submitted)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // The pending position is durable and retried by the next checkpoint or reconnect.
                    } finally { flushing.remove(bridge) }
                }
            }
        }
    }

    fun stop(savePosition: Boolean = true) {
        sampleListeningStats(SystemClock.elapsedRealtime())
        if (savePosition) checkpoint(syncNow = true) else flushListeningStats()
        requestStatisticsSync(force = true)
        listeningMeter.reset()
        abandonFocus()
        job?.cancel()
        load?.cancel()
        chapterSearch?.cancel()
        chapterSearch = null
        retryStep = null
        job = null
        load = null
        narration?.close()
        narration = null
        val wasRouted = outputId != null || connectingOutput != null || switchingToPhone
        ++routeRevision
        routeJob?.cancel()
        routeJob = null
        listenMode = false
        switchingToPhone = false
        outputId = null
        outputName = null
        connectingOutput = null
        outputError = null
        requestedChapter = null
        rewindOnPlay = false
        replayUntilMs = 0
        heldAnchor = null
        heldFraction = 0.0
        if (wasRouted) scope.launch { AirPlaySender.disconnect() }
        sync = null
        readerVisible = false
        activeBook = null
        startPending = false
        playing = false
        failed = false
        noNarration = false
        loading = false
        marker = null
        progress = null
        sleep(0)
        service?.stopSession()
    }

    private fun sampleListeningStats(now: Long) {
        val position = runCatching { narration?.progress()?.elapsedMs }.getOrNull() ?: 0
        pendingListeningMs += listeningMeter.sample(now, position, playing && !loading && !failed, speed)
    }

    private fun flushListeningStats() {
        val book = activeBook ?: return
        if (pendingListeningMs > 0) listeningStats?.record(book, pendingListeningMs)
        pendingListeningMs = 0
        requestStatisticsSync()
    }

    private fun requestStatisticsSync(force: Boolean = false) {
        val book = activeBook ?: return
        val endpoint = book.online?.catalog ?: return
        val store = listeningStats ?: return
        val profile = book.file.parentFile?.name ?: return
        val now = SystemClock.elapsedRealtime()
        if (statisticsSync?.isActive == true || (!force && now - lastStatisticsSync < 60_000)) return
        lastStatisticsSync = now
        statisticsSync = scope.launch { store.synchronize(profile, endpoint, discover = false) }
    }

    private fun requestService(): Boolean {
        val ctx = context ?: return false
        if (service?.foreground == true) return true
        try {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
            startPending = true
            return true
        } catch (_: IllegalStateException) {
            onFailure()
            return false
        }
    }

    private fun requestFocus(): Boolean {
        val ctx = context ?: return false
        val manager = ctx.getSystemService(AudioManager::class.java)
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeAfterFocus = playing || resumeAfterFocus
                        pause(releaseFocus = false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocus) {
                        resumeAfterFocus = false
                        resume()
                    }
                }
            }.build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    private fun abandonFocus() {
        resumeAfterFocus = false
        val old = focus ?: return
        focus = null
        context?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(old)
    }
}
