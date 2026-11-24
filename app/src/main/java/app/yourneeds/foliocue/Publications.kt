package app.yourneeds.foliocue

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

private fun archiveEntry(file: File, path: String): InputStream? {
    val zip = ZipFile(file)
    try {
        val entry = zip.getEntry(path)
        if (entry != null) return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                try { super.close() } finally { zip.close() }
            }
        }
    } catch (failure: Exception) {
        zip.close()
        throw failure
    }
    zip.close()
    return null
}

internal fun xml(input: InputStream): Element {
    val bytes = input.use { stream ->
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(result.size() + count <= 32 * 1024 * 1024) { "XML exceeds 32 MiB." }
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }
    val charset = when {
        bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16
        bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16
        bytes.firstOrNull() == 0.toByte() -> Charsets.UTF_16BE
        bytes.getOrNull(1) == 0.toByte() -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
    val source = bytes.toString(charset).removePrefix("\uFEFF")
        .replace(Regex("<!DOCTYPE\\s+html\\s*>", RegexOption.IGNORE_CASE), "")
    require(!source.contains("<!DOCTYPE", ignoreCase = true)) { "XML document types are unsupported." }
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External XML resource") }
    return builder.parse(InputSource(StringReader(source))).documentElement
}

internal fun Element.children(name: String) = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }.filter { it.localName == name }

private fun Element.descendants(name: String) = getElementsByTagNameNS("*", name).let { nodes ->
    (0 until nodes.length).map { nodes.item(it) as Element }
}

internal const val BOOK_HOST = "epub.invalid"

internal fun resource(base: String, href: String): URI? = runCatching {
    val origin = URI("https", BOOK_HOST, "/$base", null)
    val uri = origin.resolve(href).normalize()
    require(uri.scheme == "https" && uri.rawAuthority == BOOK_HOST && uri.rawQuery == null)
    require(uri.path.split('/').none { it == "." || it == ".." || '\\' in it })
    uri
}.getOrNull()

internal data class Item(val path: String, val type: String, val overlay: String)
internal data class Cue(val fragment: String?, val audio: String, val start: Int, val end: Int)
internal data class Section(val path: String, val label: String, val fragment: String? = null)

internal fun clock(value: String): Int? {
    val text = value.trim().removePrefix("npt=")
    val seconds = if (':' in text) text.split(':').map { it.toDoubleOrNull() ?: return null }
        .takeIf { it.size in 2..3 }?.fold(0.0) { total, next -> total * 60 + next } ?: return null
    else {
        val units = listOf("min" to 60.0, "ms" to 0.001, "h" to 3600.0, "s" to 1.0)
        val unit = units.firstOrNull { text.endsWith(it.first) }
        (text.removeSuffix(unit?.first.orEmpty()).toDoubleOrNull() ?: return null) * (unit?.second ?: 1.0)
    }
    return (seconds * 1000).takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() }
        ?.let { kotlin.math.round(it).toInt() }
}

internal data class AudioStream(val url: URI, val headers: Map<String, String>)

internal class Publication(
    fallbackTitle: String,
    private val resourceStream: (String) -> InputStream?,
    val audioStream: ((String) -> AudioStream)? = null,
) {
    constructor(file: File) : this(file.nameWithoutExtension, { path -> archiveEntry(file, path) })
    val cover: String?
    val author: String
    val description: String
    val narrationDurationSeconds: Long?
    val packageSpineStep: Int
    private val manifest: Map<String, Item>
    val title: String
    val spine: List<Item>
    val contents: List<Section>
    val mime: Map<String, String>
    private val sharedOverlays: Set<String>
    private val overlayDurations = mutableMapOf<String, Long>()
    private val pendingCues = mutableMapOf<Item, FutureTask<List<Cue>>>()
    private val recentCues = object : LinkedHashMap<Item, List<Cue>>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Item, List<Cue>>?) = size > 2
    }
    private val narratedChapters = object : LinkedHashMap<Int, Map<String, String>>(3, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<String, String>>?) = size > 2
    }
    private val sectionStarts = object : LinkedHashMap<String, Map<String, Long>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Long>>?) = size > 3
    }
    init {
        val container = xml(resourceStream("META-INF/container.xml") ?: error("EPUB container"))
        val packagePath = container.descendants("rootfile").first().getAttribute("full-path")
        val opf = xml(resourceStream(packagePath) ?: error("EPUB package"))
        title = opf.descendants("title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank { fallbackTitle }
        author = opf.descendants("creator").joinToString(", ") { it.textContent.trim() }
        description = opf.descendants("description").firstOrNull()?.textContent?.trim().orEmpty()
        val entries = opf.children("manifest").single().children("item")
        manifest = entries.mapNotNull { element ->
            resource(packagePath, element.getAttribute("href"))?.let {
                element.getAttribute("id") to Item(it.path.removePrefix("/"),
                    element.getAttribute("media-type"), element.getAttribute("media-overlay"))
            }
        }.toMap()
        mime = manifest.values.associate { it.path to it.type }
        val spineElement = opf.children("spine").single()
        val mediaDurations = opf.descendants("meta").filter { it.getAttribute("property") == "media:duration" }
        narrationDurationSeconds = mediaDurations.firstOrNull { it.getAttribute("refines").isBlank() }
            ?.let { clock(it.textContent.trim()) }?.takeIf { it > 0 }?.let { (it + 500L) / 1000L }
        mediaDurations.forEach { meta ->
            val target = meta.getAttribute("refines").removePrefix("#")
            if (target.isNotBlank()) clock(meta.textContent.trim())?.let { overlayDurations[target] = it.toLong() }
        }
        packageSpineStep = (0 until opf.childNodes.length).map { opf.childNodes.item(it) }
            .filterIsInstance<Element>().indexOf(spineElement).let { (it + 1) * 2 }
        val coverId = opf.descendants("meta").firstOrNull { it.getAttribute("name") == "cover" }
            ?.getAttribute("content")
        cover = (entries.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
            ?.getAttribute("id") ?: coverId)?.let { manifest[it]?.path }
        spine = spineElement.children("itemref").map { manifest[it.getAttribute("idref")] ?: error("EPUB spine reference") }
        sharedOverlays = spine.filter { it.overlay.isNotEmpty() }.groupingBy { it.overlay }
            .eachCount().filterValues { it > 1 }.keys
        require(spine.isNotEmpty()) { "Das EPUB enthält keine lesbaren Kapitel." }
        val nav = entries.firstOrNull { "nav" in it.getAttribute("properties").split(Regex("\\s+")) }
            ?.let { manifest[it.getAttribute("id")] }
        contents = nav?.let { item ->
            runCatching {
                xml(resourceStream(item.path) ?: error("EPUB navigation")).descendants("nav")
                    .firstOrNull { it.getAttributeNS("http://www.idpf.org/2007/ops", "type") == "toc" }
                    ?.descendants("a")?.mapNotNull { anchor ->
                        resource(item.path, anchor.getAttribute("href"))?.let { uri ->
                            Section(uri.path.removePrefix("/"), anchor.textContent.trim(), uri.fragment)
                        }
                    }?.filter { section -> spine.any { it.path == section.path } }
            }.getOrNull()
        }.orEmpty().ifEmpty { spine.map { item -> Section(item.path, "") } }
    }

    fun open(path: String): InputStream? = if (path in mime) resourceStream(path) else null

    fun prepareNarrationLocations(chapter: Int, cues: List<Cue>) {
        if (cues.isEmpty() || synchronized(narratedChapters) { narratedChapters.containsKey(chapter) }) return
        val ids = cues.mapNotNullTo(mutableSetOf()) { it.fragment }
        val root = open(spine[chapter].path)?.let(::xml) ?: return
        val locations = narrationLocations(root, ids)
        synchronized(narratedChapters) { narratedChapters[chapter] = locations }
    }

    fun narratedCfi(chapter: Int, anchor: String?): String? =
        synchronized(narratedChapters) { narratedChapters[chapter]?.get(anchor) }

    fun cues(chapter: Item): List<Cue> {
        val request = synchronized(recentCues) {
            recentCues[chapter]?.let { return it }
            pendingCues.getOrPut(chapter) { FutureTask { readCues(chapter) } }
        }
        request.run()
        try {
            val result = request.get()
            synchronized(recentCues) { recentCues[chapter] = result }
            return result
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } finally {
            synchronized(recentCues) { if (request.isDone) pendingCues.remove(chapter, request) }
        }
    }

    private fun readCues(chapter: Item): List<Cue> {
        val overlay = manifest[chapter.overlay] ?: return emptyList()
        val stream = open(overlay.path) ?: return emptyList()
        val cues = xml(stream).descendants("par").mapNotNull { par ->
            val text = par.children("text").firstOrNull() ?: return@mapNotNull null
            val audio = par.children("audio").firstOrNull() ?: return@mapNotNull null
            val target = resource(overlay.path, text.getAttribute("src")) ?: return@mapNotNull null
            val sound = resource(overlay.path, audio.getAttribute("src"))?.path?.removePrefix("/") ?: return@mapNotNull null
            if (target.path.removePrefix("/") != chapter.path || mime[sound]?.startsWith("audio/") != true) return@mapNotNull null
            val start = audio.getAttribute("clipBegin").ifBlank { "0" }.let(::clock) ?: return@mapNotNull null
            val end = clock(audio.getAttribute("clipEnd")) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Cue(target.fragment, sound, start, end)
        }
        val starts = HashMap<String, Long>()
        var elapsed = 0L
        for (cue in cues) {
            cue.fragment?.let { starts.putIfAbsent(it, elapsed) }
            elapsed += cue.end - cue.start
        }
        synchronized(sectionStarts) {
            sectionStarts[chapter.path] = starts
            // Some EPUBs split one SMIL across several spine documents.
            if (chapter.overlay !in sharedOverlays) overlayDurations[chapter.overlay] = elapsed
        }
        return cues
    }

    /** BookOrbit counts elapsed clip durations across all sections, independent of per-track timestamps. */
    fun narrationSeconds(index: Int, anchor: String): Double? = synchronized(sectionStarts) {
        val within = sectionStarts[spine[index].path]?.get(anchor) ?: return null
        var total = within
        for (i in 0 until index) {
            val chapter = spine[i]
            if (chapter.overlay.isEmpty()) continue
            if (chapter.overlay in sharedOverlays) return null
            total += overlayDurations[chapter.overlay] ?: return null
        }
        total / 1000.0
    }
}
