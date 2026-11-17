package app.yourneeds.foliocue

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
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

internal class ReaderPage(
    context: Context,
    publication: Publication,
    chapter: Item,
    private val fragment: String?,
    private val previousScroll: Int,
    private val startingCfi: String?,
    private val chapterLink: (String, String?) -> Unit,
    private val position: (Int) -> Unit,
) : WebView(context) {
    private val host = "${UUID.randomUUID()}.$BOOK_HOST"
    private var initialized = false
    private var touchScrolling = false
    var onPageTap: () -> Unit = {}
    var onPageError: () -> Unit = {}
    private var css = ""
    private var currentMark: String? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            onPageTap()
            return true
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
                applyAppearance()
                postVisualStateCallback(0, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        navigateToStart()
                        initialized = true
                        highlight(currentMark)
                    }
                })
            }
        }
        loadUrl(Uri.Builder().scheme("https").authority(host).path("/${chapter.path}").build().toString())
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchScrolling = true
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    fun appearance(background: Long, ink: Long, font: String, zoom: Int, highlight: Long) {
        val mark = "#%06x".format(highlight and 0xFFFFFF)
        setBackgroundColor(background.toInt())
        val foreground = "#%06x".format(ink and 0xFFFFFF)
        val paper = "#%06x".format(background and 0xFFFFFF)
        val face = if (font.isEmpty()) "" else "body, body * { font-family: $font !important; }"
        css = """
            html, body { background: $paper !important; color: $foreground !important; }
            body { padding: 12px 5vw !important; margin: 0 auto !important; max-width: 50rem; }
            body :is(p,span,div,section,h1,h2,h3,h4,li,blockquote) { color: inherit !important; }
            img { max-width: 100%; height: auto; }
            .foliocue-current { background: $mark !important; color: #172c3d !important; }
            $face
        """.trimIndent()
        settings.textZoom = zoom
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
                style.textContent = ${JSONObject.quote(css)};
            })()
        """.trimIndent(), null)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (initialized && touchScrolling) position(top)
    }

    private fun navigateToStart() {
        if (fragment != null) {
            jump(fragment)
        } else if (startingCfi == null) {
            scrollTo(0, previousScroll)
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
                        window.scrollBy(0, range.getBoundingClientRect().top - 22);
                    } else node.scrollIntoView();
                    return true;
                })()
            """.trimIndent()) { found -> if (found != "true") scrollTo(0, previousScroll) }
        }
    }

    fun capturePosition(anchor: String?, result: (String?, Double) -> Unit) {
        if (!initialized) return
        val marker = anchor?.let(JSONObject::quote) ?: "null"
        evaluateJavascript("""
            (() => {
                const marker = document.getElementById($marker);
                const content = document.body.getBoundingClientRect();
                let range;
                for (let y = 24; !marker && !range && y < Math.min(innerHeight, 132); y += 9) {
                    const point = document.caretRangeFromPoint?.(Math.max(5, content.left + 24), y);
                    if (point?.startContainer.nodeType === 3) range = point;
                }
                let node = marker || range?.startContainer ||
                    document.elementFromPoint(Math.max(5, content.left + 30), 25);
                if (!node || !document.body.contains(node)) return null;
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
                return {cfi: steps.join(''), fraction: (scrollY + 25) / limit};
            })()
        """.trimIndent()) { raw ->
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
            val cfi = parsed.optString("cfi").takeIf { it.startsWith("/") && it.length <= 1800 }
            result(cfi, parsed.optDouble("fraction", 0.0).coerceIn(0.0, 1.0))
        }
    }

    fun jump(id: String) {
        evaluateJavascript("document.getElementById(${JSONObject.quote(id)})?.scrollIntoView()", null)
    }

    fun highlight(id: String?) {
        currentMark = id
        val target = id?.let(JSONObject::quote) ?: "null"
        evaluateJavascript("""
            (() => {
                document.querySelector('.foliocue-current')?.classList.remove('foliocue-current');
                const word = document.getElementById($target);
                if (!word) return;
                word.classList.add('foliocue-current');
                const box = word.getBoundingClientRect();
                if (box.top < 48 || box.bottom > innerHeight - 48) {
                    word.scrollIntoView({block: 'center', inline: 'nearest'});
                }
            })()
        """.trimIndent(), null)
    }
}

internal class Narration(
    chapterCues: List<Cue>,
    startIndex: Int,
    private val mark: (String?) -> Unit,
    private val trackPosition: (String) -> Unit,
    private val error: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    private val timeline = CueTimeline(chapterCues, startIndex)
    val index get() = timeline.index
    private var handlingEnd = false
    private var seeking = false
    private var tempoAfterSeek: Float? = null
    var finished = false
        private set
    private var lastMark: String? = null
    val audio: String get() = timeline.cue.audio

    suspend fun attach(file: File, tempo: Float) = attach(tempo) { current ->
        file.inputStream().use { current.setDataSource(it.fd) }
    }

    suspend fun attach(context: Context, stream: AudioStream, tempo: Float) = attach(tempo) { current ->
        current.setDataSource(context, Uri.parse(stream.url.toString()), stream.headers)
    }

    private suspend fun attach(tempo: Float, source: (MediaPlayer) -> Unit) {
        releasePlayer()
        val current = MediaPlayer()
        player = current
        try {
            current.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            source(current)
            current.setOnCompletionListener { handlingEnd = true }
            current.setOnSeekCompleteListener {
                seeking = false
                val tempoToPlay = tempoAfterSeek
                tempoAfterSeek = null
                if (tempoToPlay != null) {
                    try { play(tempoToPlay) } catch (_: IllegalStateException) { error("Audio seek") }
                }
            }
            suspendCancellableCoroutine<Unit> { prepared ->
                current.setOnPreparedListener {
                    if (prepared.isActive) prepared.resume(Unit)
                }
                current.setOnErrorListener { _, what, extra ->
                    if (prepared.isActive) prepared.resumeWithException(IllegalStateException("Audio $what/$extra"))
                    true
                }
                current.prepareAsync()
            }
            current.setOnErrorListener { _, _, _ ->
                error("Die eingebettete Tonspur konnte nicht abgespielt werden.")
                true
            }
            startAt(tempo)
        } catch (exception: Exception) {
            releasePlayer()
            throw exception
        }
    }

    private fun startAt(tempo: Float) {
        val current = player ?: return
        val start = timeline.cue.start
        updateMarker(null)
        if (current.currentPosition == start) {
            play(tempo)
        } else {
            if (current.isPlaying) current.pause()
            seeking = true
            tempoAfterSeek = tempo
            current.seekTo(start.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    fun pause() {
        tempoAfterSeek = null
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (_: IllegalStateException) {
            releasePlayer()
        }
    }

    fun play(tempo: Float) {
        if (seeking) {
            tempoAfterSeek = tempo
            return
        }
        player?.let {
            it.playbackParams = PlaybackParams().allowDefaults().setSpeed(tempo)
            it.start()
        }
    }

    fun tick(tempo: Float, highlightDelayMs: Int): Boolean {
        val current = player ?: return false
        if (seeking) return false
        val ended = handlingEnd
        handlingEnd = false
        val position = current.timestamp?.anchorMediaTimeUs?.takeIf { it >= 0 }
            ?.let { (it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: current.currentPosition
        when (timeline.advance(position, ended)) {
            CueAdvance.FINISHED -> {
                finished = true
                updateMarker(null)
                pause()
            }
            CueAdvance.NEXT_TRACK -> {
                updateMarker(null)
                releasePlayer()
                return true
            }
            CueAdvance.SEEK -> startAt(tempo)
            CueAdvance.CONTINUE -> {
                timeline.fragmentAt(position)?.let(trackPosition)
                updateMarker(timeline.fragmentAt(position, highlightDelayMs))
            }
        }
        return false
    }

    private fun updateMarker(fragment: String?) {
        if (lastMark != fragment) {
            lastMark = fragment
            mark(fragment)
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        handlingEnd = false
        seeking = false
        tempoAfterSeek = null
    }

    fun close() { releasePlayer() }
}
