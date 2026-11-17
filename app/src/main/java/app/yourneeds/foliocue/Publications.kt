package app.yourneeds.foliocue

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

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

internal class Publication(file: File) {
    private val manifest: Map<String, Item>
    val title: String
    val spine: List<Item>
    val contents: List<Section>
    val mime: Map<String, String>
    private val archive = file

    init {
        ZipFile(file).use { zip ->
            val container = xml(zip.getInputStream(zip.getEntry("META-INF/container.xml")
                ?: error("Kein EPUB-Container.")))
            val packagePath = container.descendants("rootfile").first().getAttribute("full-path")
            val opf = xml(zip.getInputStream(zip.getEntry(packagePath) ?: error("EPUB-Paket fehlt.")))
            title = opf.descendants("title").firstOrNull()?.textContent?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
            val entries = opf.children("manifest").single().children("item")
            manifest = entries.mapNotNull { element ->
                resource(packagePath, element.getAttribute("href"))?.let {
                    element.getAttribute("id") to Item(it.path.removePrefix("/"),
                        element.getAttribute("media-type"), element.getAttribute("media-overlay"))
                }
            }.toMap()
            mime = manifest.values.associate { it.path to it.type }
            spine = opf.children("spine").single().children("itemref")
                .mapNotNull { manifest[it.getAttribute("idref")] }
            require(spine.isNotEmpty()) { "Das EPUB enthält keine lesbaren Kapitel." }
            val nav = entries.firstOrNull { "nav" in it.getAttribute("properties").split(Regex("\\s+")) }
                ?.let { manifest[it.getAttribute("id")] }
            contents = nav?.let { item ->
                runCatching {
                    xml(zip.getInputStream(zip.getEntry(item.path))).descendants("nav")
                        .firstOrNull { it.getAttributeNS("http://www.idpf.org/2007/ops", "type") == "toc" }
                        ?.descendants("a")?.mapNotNull { anchor ->
                            resource(item.path, anchor.getAttribute("href"))?.let { uri ->
                                Section(uri.path.removePrefix("/"), anchor.textContent.trim(), uri.fragment)
                            }
                        }?.filter { section -> spine.any { it.path == section.path } }
                }.getOrNull()
            }.orEmpty().ifEmpty { spine.map { item -> Section(item.path, "") } }
        }
    }

    fun open(path: String): InputStream? {
        if (path !in mime) return null
        val zip = ZipFile(archive)
        val entry = zip.getEntry(path)
        if (entry == null) {
            zip.close()
            return null
        }
        return object : java.io.FilterInputStream(zip.getInputStream(entry)) {
            override fun close() { try { super.close() } finally { zip.close() } }
        }
    }

    fun cues(chapter: Item): List<Cue> {
        val overlay = manifest[chapter.overlay] ?: return emptyList()
        val stream = open(overlay.path) ?: return emptyList()
        return xml(stream).descendants("par").mapNotNull { par ->
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
    }
}
