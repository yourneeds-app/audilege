package app.yourneeds.foliocue

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

internal enum class HighlightUnit(val wire: String, val label: Int) {
    Word("word", R.string.highlight_word),
    Phrase("phrase", R.string.highlight_phrase),
    Sentence("sentence", R.string.highlight_sentence),
}

internal class ReaderPage(
    context: Context,
    publication: Publication,
    private val chapter: Item,
    private val fragment: String?,
    private val previousScroll: Int,
    private val startingCfi: String?,
    private val chapterLink: (String, String?) -> Unit,
    private val position: (Int) -> Unit,
) : WebView(context) {
    private val host = "${UUID.randomUUID()}.$BOOK_HOST"
    private var initialized = false
    private var touchScrolling = false
    private var followAudio = true
    private var paged = false
    private var pageIndex = 0
    val readingOffset get() = if (paged) -1 - pageIndex else scrollY
    var onPages: (Int, Int) -> Unit = { _, _ -> }
    val chapterPath get() = chapter.path
    val isReady get() = initialized
    var onReadingActivity: () -> Unit = {}
    var onPageTap: () -> Unit = {}
    var onBrowse: () -> Unit = {}
    var onPassage: (PagePassage) -> Unit = {}
    var onTurnAtEdge: (Int) -> Unit = {}
    var onPageError: () -> Unit = {}
    private var css = ""
    private data class Mark(val anchor: String?, val unit: HighlightUnit)
    private var currentMark = Mark(null, HighlightUnit.Word)
    private var submittedMark: Mark? = null
    private var markInFlight = false
    private var timedIds: Pair<String, String>? = null
    private var closed = false
    private var readyToShow: (() -> Unit)? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val x = event.x / width.coerceAtLeast(1)
            if (paged && (x < 0.20f || x > 0.80f)) turn(if (x < 0.20f) -1 else 1)
            else onPageTap()
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                touchScrolling = true
                if (followAudio) onBrowse()
            }
            return false
        }
        override fun onLongPress(event: MotionEvent) {
            val density = resources.displayMetrics.density
            passageAt(event.x / density, event.y / density, null, onPassage)
        }
    })

    init {
        setBackgroundColor(Color.WHITE)
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame && uri.scheme == "https" && uri.host == host) {
                    chapterLink(uri.path.orEmpty().removePrefix("/"), uri.fragment)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val path = uri.path.orEmpty().removePrefix("/")
                val type = publication.mime[path]
                val stream = if (uri.scheme == "https" && uri.host == host && uri.port == -1 &&
                    type != null && (!request.isForMainFrame || path == chapter.path)) {
                    runCatching { publication.open(path) }.getOrNull()
                } else null
                if (stream == null) return WebResourceResponse("text/plain", "utf-8", 404,
                    "Not Found", emptyMap(), null)
                return WebResourceResponse(
                    if (type == "application/xhtml+xml") "text/html" else type,
                    null, 200, "OK",
                    mapOf("Content-Security-Policy" to "default-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'none'"),
                    stream,
                )
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) onPageError()
            }

            override fun onPageFinished(view: WebView, url: String) {
                evaluateJavascript(context.assets.open("reader-pages.js").bufferedReader().use { it.readText() }, null)
                evaluateJavascript(context.assets.open("reader-phrases.js").bufferedReader().use { it.readText() }, null)
                evaluateJavascript(context.assets.open("reader-highlight.js").bufferedReader().use { it.readText() }, null)
                submittedMark = null
                applyAppearance()
                postVisualStateCallback(0, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        navigateToStart()
                        initialized = true
                        timedIds?.let { (words, pauses) -> timedWords(words, pauses) }
                        onReadingActivity()
                        flushHighlight()
                        readyToShow?.invoke()
                        readyToShow = null
                    }
                })
            }
        }
        loadUrl(Uri.Builder().scheme("https").authority(host).path("/${chapter.path}").build().toString())
    }

    fun whenReady(action: () -> Unit) {
        if (initialized) action() else readyToShow = action
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            onReadingActivity()
        }
        gestures.onTouchEvent(event)
        if (paged && event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            if (kotlin.math.abs(dx) >= 40 * resources.displayMetrics.density &&
                kotlin.math.abs(dx) > kotlin.math.abs(event.y - downY) * 1.5f) {
                turn(if (dx < 0) 1 else -1)
            }
        }
        return super.onTouchEvent(event)
    }
    private var downX = 0f
    private var downY = 0f

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
            action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            if (paged) {
                turn(if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1)
                return true
            }
            touchScrolling = true
            onReadingActivity()
            if (followAudio) onBrowse()
        }
        return super.performAccessibilityAction(action, arguments)
    }

    fun setFollowing(enabled: Boolean) {
        followAudio = enabled
        if (enabled) touchScrolling = false
    }

    fun turn(direction: Int) {
        onReadingActivity()
        onBrowse()
        touchScrolling = true
        if (paged) {
            evaluateJavascript("window.audilegePages?.turn($direction)") { moved ->
                if (closed) return@evaluateJavascript
                if (moved == "false") onTurnAtEdge(direction)
                if (moved == "true") refreshPages(save = true)
            }
        } else if (!canScrollVertically(direction)) onTurnAtEdge(direction)
        else scrollBy(0, (height * 0.90f).toInt() * direction)
    }

    private fun refreshPages(save: Boolean = false) {
        if (!paged || closed) return
        evaluateJavascript("window.audilegePages?.state()") { raw ->
            val state = runCatching { org.json.JSONArray(raw) }.getOrNull()
            if (!closed && paged && state != null) {
                pageIndex = state.getInt(0)
                onPages(pageIndex, state.getInt(1))
                if (save) position(readingOffset)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && paged) post { if (!closed) {
            evaluateJavascript("window.audilegePages?.reflow()") { refreshPages() }
        } }
    }

    fun appearance(style: ReaderStyle) {
        val background = style.paper.bg
        val ink = style.paper.ink
        val font = style.font.css
        val mark = "#%06x".format(style.highlight and 0xFFFFFF)
        val markInk = "#%06x".format(style.highlightInk and 0xFFFFFF)
        paged = style.paged
        val paint = if (style.underline)
            "text-decoration: underline 0.14em $markInk !important; text-underline-offset: 0.13em;"
        else "background: $mark !important; color: $markInk !important;"
        setBackgroundColor(background.toInt())
        val foreground = "#%06x".format(ink and 0xFFFFFF)
        val paper = "#%06x".format(background and 0xFFFFFF)
        val face = if (font.isEmpty()) "" else "body, body * { font-family: $font !important; }"
        css = """
            html, body { background: $paper !important; color: $foreground !important; }
            body { padding: 12px ${style.margin}px !important; margin: 0 auto !important; max-width: 44rem; }
            body :is(p,li,blockquote), body { line-height: ${style.lineSpacing} !important; }
            body :is(p,span,div,section,h1,h2,h3,h4,li,blockquote) { color: inherit !important; }
            img { max-width: 100%; height: auto; }
            html[data-audilege-pages] { overflow: clip !important; margin: 0 !important; padding: 0 !important; }
            html[data-audilege-pages] body {
                --page-inset: max(${style.margin}px, calc((100vw - 44rem) / 2));
                box-sizing: border-box !important; width: 100vw !important; max-width: none !important;
                height: 100vh !important; min-height: 0 !important; margin: 0 !important;
                padding: 12px var(--page-inset) !important;
                column-count: 1 !important; column-fill: auto !important;
                column-gap: calc(2 * var(--page-inset)) !important;
                transform: translateX(var(--audilege-page-x, 0px)) !important;
                overflow: visible !important; touch-action: pan-y;
            }
            html[data-audilege-pages] body :is(img,svg,video) { max-height: calc(100vh - 24px) !important; object-fit: contain; }
            .foliocue-current {
                $paint border-radius: 0.16em;
                box-decoration-break: clone; -webkit-box-decoration-break: clone;
            }
            ::highlight(audilege-sentence) {
                ${if (style.underline) "text-decoration: underline 0.14em $markInk;" else "background-color: $mark; color: $markInk;"}
            }
            $face
        """.trimIndent()
        settings.textZoom = (style.size * resources.configuration.fontScale).toInt().coerceIn(50, 500)
        applyAppearance()
    }

    private fun applyAppearance() {
        evaluateJavascript("""
            (() => {
                if (!document.head) return;
                let style = document.getElementById('foliocue-page-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'foliocue-page-style';
                    document.head.appendChild(style);
                }
                const pages = window.audilegePages;
                const wasPaged = pages?.active();
                if (wasPaged || $paged) pages?.beforeStyle();
                style.textContent = ${JSONObject.quote(css)};
                document.documentElement.toggleAttribute('data-audilege-pages', $paged);
                if ($paged) pages?.reflow();
                else if (wasPaged) pages.restoreScroll();
            })()
        """.trimIndent()) { refreshPages() }
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (initialized && touchScrolling && !paged) position(top)
    }

    private fun restoreOffset() {
        if (paged) {
            val page = when {
                previousScroll == Int.MAX_VALUE -> Int.MAX_VALUE
                previousScroll < 0 -> -(previousScroll + 1)
                else -> previousScroll / height.coerceAtLeast(1)
            }
            evaluateJavascript("window.audilegePages?.show($page)") { refreshPages() }
        } else scrollTo(0, previousScroll.coerceAtLeast(0))
    }

    private fun navigateToStart() {
        if (fragment != null) {
            jump(fragment)
        } else if (startingCfi == null) {
            restoreOffset()
        } else {
            evaluateJavascript("""
                (() => {
                    const cfi = ${JSONObject.quote(startingCfi)};
                    const parts = cfi.replace(/\[(?:\^.|[^\]])*\]/g, '').split(',');
                    const steps = (parts[0] + (parts[1] || '')).match(/\/\d+(?::\d+)?/g) || [];
                    let node = document.documentElement;
                    let offset = 0;
                    for (const part of steps) {
                        const [rawStep, at] = part.slice(1).split(':');
                        const step = Number(rawStep);
                        offset = Number(at || 0);
                        if (step % 2 === 0) node = node.children[step / 2 - 1];
                        else {
                            let seen = 0;
                            let found = null;
                            for (const child of node.childNodes) {
                                if (child.nodeType === 1) seen++;
                                if (child.nodeType !== 3 || seen !== (step - 1) / 2) continue;
                                found = child;
                                if (offset <= child.length) break;
                                offset -= child.length;
                            }
                            node = found;
                        }
                        if (!node) return false;
                    }
                    if (node.nodeType === 3) {
                        const range = document.createRange();
                        range.setStart(node, Math.min(offset, node.length));
                        range.collapse(true);
                        if ($paged) window.audilegePages.reveal(range);
                        else window.scrollBy(0, range.getBoundingClientRect().top - 22);
                    } else if ($paged) window.audilegePages.reveal(node);
                    else node.scrollIntoView();
                    return true;
                })()
            """.trimIndent()) { found -> if (found != "true") restoreOffset() else refreshPages() }
        }
    }

    fun capturePosition(anchor: String?, excerpt: ((String) -> Unit)? = null, result: (String?, Double) -> Unit) {
        if (!initialized) { result(null, 0.0); return }
        val marker = anchor?.let(JSONObject::quote) ?: "null"
        evaluateJavascript("""
            (() => {
                const marker = document.getElementById($marker);
                const content = document.body.getBoundingClientRect();
                let range;
                const left = Math.max(5, ($paged ? 0 : content.left) + parseFloat(getComputedStyle(document.body).paddingLeft || '0') + 6);
                for (let y = 20; !marker && !range && y < Math.min(innerHeight, 180); y += 9) {
                    for (const x of [left, left + 40, left + 100, innerWidth / 2]) {
                        const point = document.caretRangeFromPoint?.(Math.min(x, innerWidth-2), y);
                        if (point?.startContainer.nodeType === 3 && point.startContainer.textContent.trim() &&
                            Math.abs(point.getBoundingClientRect().top - y) < 36) {range = point; break;}
                    }
                }
                let node = marker || range?.startContainer ||
                    document.elementFromPoint(Math.max(5, content.left + 30), 25);
                if (!node || !document.body.contains(node)) return null;
                const el = node.nodeType === 3 ? node.parentElement : node;
                const snippet = ${excerpt != null} ? (el.closest('p,li,h1,h2,h3,blockquote') || el).textContent
                    .slice(0,300).trim().replace(/\s+/g,' ').slice(0,110) : '';
                const steps = [];
                while (node !== document.documentElement) {
                    const parent = node.parentNode;
                    if (!parent) return null;
                    let elements = 0;
                    let offset = range?.startOffset || 0;
                    let text = 0;
                    for (const sibling of parent.childNodes) {
                        if (sibling === node) break;
                        if (sibling.nodeType === 1) { elements++; text = 0; }
                        if (sibling.nodeType === 3) text += sibling.length;
                    }
                    steps.unshift(node.nodeType === 3 ? '/' + (elements * 2 + 1) + ':' + (text + offset)
                        : '/' + ((elements + 1) * 2));
                    node = parent;
                }
                const limit = Math.max(1, document.documentElement.scrollHeight);
                const pages = $paged && window.audilegePages.state();
                return {cfi: steps.join(''), fraction: pages ? pages[0] / pages[1] : (scrollY + 25) / limit, snippet};
            })()
        """.trimIndent()) { raw ->
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            if (parsed == null) { result(null, 0.0); return@evaluateJavascript }
            excerpt?.invoke(parsed.optString("snippet"))
            val cfi = parsed.optString("cfi").takeIf { it.startsWith("/") && it.length <= 1800 }
            result(cfi, parsed.optDouble("fraction", 0.0).coerceIn(0.0, 1.0))
        }
    }

    fun jump(id: String) {
        val target = "document.getElementById(${JSONObject.quote(id)})"
        evaluateJavascript(if (paged) "window.audilegePages?.reveal($target)" else "$target?.scrollIntoView()") { refreshPages() }
    }

    fun timedWords(encodedIds: String, encodedPauses: String) {
        timedIds = encodedIds to encodedPauses
        if (!closed && initialized) {
            evaluateJavascript("window.audilegeTimedWords?.($encodedIds, $encodedPauses)", null)
            submittedMark = null
            flushHighlight()
        }
    }

    fun highlight(id: String?, unit: HighlightUnit = HighlightUnit.Word) {
        currentMark = Mark(id, unit)
        flushHighlight()
    }

    private fun flushHighlight() {
        if (closed || !initialized || markInFlight || currentMark == submittedMark) return
        submittedMark = currentMark
        markInFlight = true
        val target = currentMark.anchor?.let(JSONObject::quote) ?: "null"
        evaluateJavascript("window.audilegeHighlight?.($target, ${JSONObject.quote(currentMark.unit.wire)}, $followAudio)") {
            markInFlight = false
            flushHighlight()
        }
    }

    fun dispose() {
        closed = true
        readyToShow = null
        stopLoading()
        destroy()
    }
}

internal class Narration(
    private val context: Context,
    private val cues: List<Cue>,
    startIndex: Int,
    private val onMark: (String?, String?) -> Unit,
    private val onFailure: () -> Unit,
    private val senderGeneration: Long? = null,
    onEqualizerUnavailable: (Boolean) -> Unit = {},
) {
    private val timeline = CueTimeline(cues, startIndex)
    private val clock = ChapterClock(cues)
    val index get() = timeline.index
    val audio get() = timeline.cue.audio
    val fraction get() = fractionAt(progress())
    val anchor get() = timeline.cue.fragment
    private var player: MediaPlayer? = null
    private var remote: AirPlayTrack? = null
    private val tonePrefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private var tone = AudiobookTone.read(tonePrefs)
    private val equalizer = LocalPlaybackEqualizer(onEqualizerUnavailable)
    private val toneListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == AudiobookTone.KEY) {
            tone = AudiobookTone.read(tonePrefs)
            remote?.setTone(tone)
            val current = player
            if (current != null && prepared) equalizer.apply(current, tone)
        }
    }
    private val routed = AudioRouting.OnRoutingChangedListener { route ->
        val current = player
        if (current != null && current === route && prepared) equalizer.apply(current, tone)
    }
    val retainsAudioFile get() = senderGeneration != null
    private var prepared = false
    private var seeking = false
    private var ended = false
    private var startMs = timeline.cue.start
    private var tempo = 1f
    private var wantsPlayback = true
    var finished = false
        private set
    private var highlighted: Pair<String?, String?> = null to null
    private var highlightPositionMs = 0

    init { tonePrefs.registerOnSharedPreferenceChangeListener(toneListener) }

    fun progress(): TrackProgress {
        val ms = if (prepared && !seeking) remote?.currentPosition ?: player?.currentPosition ?: startMs else startMs
        return TrackProgress(clock.elapsed(index, ms), clock.duration)
    }

    fun fractionAt(position: TrackProgress): Double =
        if (finished) 1.0 else (position.elapsedMs.toDouble() / position.durationMs).coerceIn(0.0, 1.0)

    suspend fun attach(file: File) {
        val generation = senderGeneration
        if (generation != null) attachRemote { AirPlayTrack.local(context, generation, file, onFailure) }
        else attach { current -> file.inputStream().use { current.setDataSource(it.fd) } }
    }

    suspend fun attach(stream: AudioStream, refresh: () -> AudioStream) {
        val generation = senderGeneration
        if (generation != null) attachRemote { AirPlayTrack.online(context, generation, stream, refresh, onFailure) }
        else attach { current -> current.setDataSource(context, Uri.parse(stream.url.toString()), stream.headers) }
    }

    private fun attachRemote(create: () -> AirPlayTrack) {
        closePlayer()
        remote = create()
        remote?.setTone(tone)
        prepared = true
        remote?.seek(startMs, clock.mediaEnd(index), wantsPlayback, tempo)
    }

    private suspend fun attach(source: (MediaPlayer) -> Unit) {
        closePlayer()
        val current = MediaPlayer()
        player = current
        try {
            current.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            source(current)
            current.setOnCompletionListener { if (it === player) ended = true }
            current.setOnSeekCompleteListener {
                if (it === player) {
                    seeking = false
                    if (wantsPlayback) resumePlayer()
                }
            }
            suspendCancellableCoroutine<Unit> { ready ->
                current.setOnPreparedListener { if (ready.isActive) ready.resume(Unit) }
                current.setOnErrorListener { _, what, extra ->
                    if (ready.isActive) ready.resumeWithException(IllegalStateException("Audio $what/$extra"))
                    true
                }
                current.prepareAsync()
            }
            prepared = true
            try { current.addOnRoutingChangedListener(routed, null) } catch (_: RuntimeException) { }
            equalizer.apply(current, tone)
            current.setOnErrorListener { failed, _, _ ->
                if (failed === player) onFailure()
                true
            }
            seekPlayer()
        } catch (failure: Exception) {
            if (current === player) closePlayer()
            throw failure
        }
    }

    fun setPlaying(playing: Boolean, speed: Float) {
        wantsPlayback = playing
        tempo = speed
        remote?.let { it.setPlaying(playing, speed); return }
        val current = player ?: return
        if (!prepared) return
        if (playing && !seeking) resumePlayer()
        else if (!playing && current.isPlaying) current.pause()
    }

    private fun resumePlayer() {
        val current = player ?: return
        try {
            if (!current.isPlaying || current.playbackParams.speed != tempo) {
                current.playbackParams = PlaybackParams().allowDefaults().setSpeed(tempo)
                current.start()
            }
        } catch (_: IllegalStateException) {
            onFailure()
        }
    }

    private fun seekPlayer() {
        remote?.let { it.seek(startMs, clock.mediaEnd(index), wantsPlayback, tempo); return }
        val current = player ?: return
        ended = false
        if (current.currentPosition == startMs && !seeking) {
            if (wantsPlayback) resumePlayer()
        } else {
            if (current.isPlaying) current.pause()
            seeking = true
            current.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    fun timeForAnchor(anchor: String): Int? {
        val at = cues.indexOfFirst { it.fragment == anchor }
        return if (at < 0) null else clock.elapsed(at, cues[at].start)
    }

    /** Returns true when the new chapter time needs another media source. */
    fun seek(elapsedMs: Int): Boolean {
        val point = clock.point(elapsedMs)
        val oldAudio = audio
        timeline.seek(point.index)
        startMs = point.mediaMs
        finished = false
        highlightPositionMs = 0
        mark(null)
        if (audio != oldAudio) {
            closePlayer()
            return true
        }
        if (prepared) seekPlayer()
        return false
    }

    fun tick(delayMs: Int): Boolean {
        if (!prepared || seeking) return false
        // MediaPlayer's timestamp is an anchor in the past, while currentPosition follows its media clock.
        val position = remote?.currentPosition ?: player?.currentPosition ?: return false
        val completed = remote?.ended ?: ended
        ended = false
        when (timeline.advance(position, completed)) {
            CueAdvance.FINISHED -> {
                finished = true
                setPlaying(false, tempo)
                mark(null)
            }
            CueAdvance.NEXT_TRACK -> {
                startMs = timeline.cue.start
                mark(null)
                closePlayer()
                return true
            }
            CueAdvance.SEEK -> {
                startMs = timeline.cue.start
                highlightPositionMs = 0
                mark(null)
                seekPlayer()
            }
            CueAdvance.CONTINUE -> {
                // The platform media clock can briefly report a backwards step between polls.
                highlightPositionMs = maxOf(highlightPositionMs, position)
                val word = timeline.fragmentAt(highlightPositionMs, delayMs)
                val phrase = word ?: timeline.fragmentAt(highlightPositionMs, delayMs, gapLimitMs = 1500)
                mark(word, phrase)
            }
        }
        return false
    }

    private fun mark(word: String?, phrase: String? = word) {
        val value = word to phrase
        if (highlighted != value) {
            highlighted = value
            onMark(word, phrase)
        }
    }

    private fun closePlayer() {
        remote?.close()
        remote = null
        val current = player
        player = null
        equalizer.close()
        if (current != null) {
            try { current.removeOnRoutingChangedListener(routed) } catch (_: RuntimeException) { }
        }
        current?.release()
        highlightPositionMs = 0
        ended = false
        prepared = false
        seeking = false
    }

    fun close() {
        tonePrefs.unregisterOnSharedPreferenceChangeListener(toneListener)
        closePlayer()
    }
}
