package app.yourneeds.foliocue

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal class ReaderPage(
    context: Context,
    publication: Publication,
    chapter: Item,
    private val fragment: String?,
    private val previousScroll: Int,
    private val chapterLink: (String, String?) -> Unit,
    private val position: (Int) -> Unit,
) : WebView(context) {
    private val host = "${UUID.randomUUID()}.$BOOK_HOST"
    private var initialized = false
    var onPageTap: () -> Unit = {}
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

            override fun onPageFinished(view: WebView, url: String) {
                applyAppearance()
                postVisualStateCallback(0, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (fragment != null) jump(fragment) else scrollTo(0, previousScroll)
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
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    fun appearance(background: Long, ink: Long, font: String, zoom: Int) {
        setBackgroundColor(background.toInt())
        val foreground = "#%06x".format(ink and 0xFFFFFF)
        val paper = "#%06x".format(background and 0xFFFFFF)
        val face = if (font.isEmpty()) "" else "body, body * { font-family: $font !important; }"
        css = """
            html, body { background: $paper !important; color: $foreground !important; }
            body { padding: 12px 5vw !important; margin: 0 auto !important; max-width: 50rem; }
            body :is(p,span,div,section,h1,h2,h3,h4,li,blockquote) { color: inherit !important; }
            img { max-width: 100%; height: auto; }
            .foliocue-current { background: #ffe072 !important; color: #172c3d !important; }
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
        if (initialized) position(top)
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
    private val chapterCues: List<Cue>,
    startIndex: Int,
    private val mark: (String?) -> Unit,
    private val error: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    var index = startIndex.coerceIn(chapterCues.indices)
        private set
    private var handlingEnd = false
    private var seeking = false
    var finished = false
        private set
    val audio: String get() = chapterCues[index].audio

    fun attach(file: File, tempo: Float, playing: Boolean) {
        releasePlayer()
        val current = MediaPlayer()
        player = current
        try {
            current.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            current.setDataSource(file.absolutePath)
            current.setOnErrorListener { _, _, _ ->
                error("Die eingebettete Tonspur konnte nicht abgespielt werden.")
                true
            }
            current.setOnCompletionListener { handlingEnd = true }
            current.setOnSeekCompleteListener { seeking = false }
            current.prepare()
            startAt(index, tempo, playing)
        } catch (exception: Exception) {
            releasePlayer()
            throw exception
        }
    }

    private fun startAt(target: Int, tempo: Float, playing: Boolean) {
        val current = player ?: return
        seeking = true
        current.seekTo(chapterCues[target].start.toLong(), MediaPlayer.SEEK_CLOSEST)
        if (playing) {
            current.playbackParams = current.playbackParams.setSpeed(tempo)
            current.start()
        }
        mark(chapterCues[target].fragment)
    }

    fun pause() { player?.takeIf { it.isPlaying }?.pause() }

    fun play(tempo: Float) {
        player?.let {
            it.playbackParams = it.playbackParams.setSpeed(tempo)
            it.start()
        }
    }

    fun tick(tempo: Float): Boolean {
        val current = player ?: return false
        if (seeking) return false
        val position = current.currentPosition
        var changed = false
        while (position >= chapterCues[index].end || handlingEnd) {
            handlingEnd = false
            val previous = chapterCues[index]
            if (index + 1 == chapterCues.size) {
                finished = true
                pause()
                return false
            }
            index++
            val next = chapterCues[index]
            if (next.audio != previous.audio) {
                releasePlayer()
                return true
            }
            if (next.start < previous.start || next.start - previous.end > 100 ||
                position < next.start - 100) {
                startAt(index, tempo, true)
                return false
            }
            changed = true
        }
        if (changed) mark(chapterCues[index].fragment)
        return false
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        handlingEnd = false
        seeking = false
    }

    fun close() { releasePlayer() }
}
