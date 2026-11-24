package app.yourneeds.foliocue

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node

internal data class PagePassage(val label: String, val ids: List<String>, val cfi: String?)

internal fun ReaderPage.passageAt(x: Float, y: Float, anchor: String?, result: (PagePassage) -> Unit) {
    val marker = anchor?.let(JSONObject::quote) ?: "null"
    evaluateJavascript("""
        (() => {
            const marker = document.getElementById($marker);
            const range = marker ? null : document.caretRangeFromPoint($x, $y);
            const hit = marker || range?.startContainer;
            if (!hit) return null;
            const element = hit.nodeType === 3 ? hit.parentElement : hit;
            if (!element || !document.body.contains(element)) return null;
            const block = element.closest('p,h1,h2,h3,h4,li,blockquote,figcaption') || element;
            const nodes = [];
            let n, at = 0;
            const walk = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            while (n = walk.nextNode()) {
                if (n === hit || (marker && marker.contains(n) && !nodes.some(v => marker.contains(v)))) {
                    at = nodes.reduce((sum, t) => sum + t.length, 0) + (range?.startOffset || 0);
                }
                nodes.push(n);
            }
            const full = block.textContent || '';
            let start = 0, end = full.length;
            if (typeof Intl.Segmenter === 'function') {
                const part = new Intl.Segmenter(document.documentElement.lang || undefined, {granularity:'sentence'}).segment(full).containing(at);
                if (part) { start = part.index; end = start + part.segment.length; }
            } else {
                const prior = full.slice(0, at).match(/[.!?…][”"»)]*\s+/g);
                if (prior?.length) start = full.slice(0, at).lastIndexOf(prior[prior.length - 1]) + prior[prior.length - 1].length;
                const after = full.slice(at).search(/[.!?…](?:\s|$)/);
                if (after >= 0) end = at + after + 1;
            }
            while (start < end && /\s/.test(full[start])) start++;
            let pos = 0;
            let first = nodes.find(t => { pos += t.length; return pos > start; }) || hit;
            let firstOffset = Math.max(0, start - (pos - (first.length || 0)));
            const ids = [];
            for (let el = first.nodeType === 3 ? first.parentElement : first; el && el !== document.body; el=el.parentElement) {
                if (el.id) ids.push(el.id);
            }
            if (!ids.length && element.id) ids.push(element.id);
            let node = first, steps = [];
            while (node !== document.documentElement && node?.parentNode) {
                const parent=node.parentNode;
                let count=0, offset=0;
                for (const sibling of parent.childNodes) {
                    if (sibling===node) break;
                    if (sibling.nodeType===1) {count++;offset=0;}
                    if (sibling.nodeType===3) offset+=sibling.length;
                }
                steps.unshift(node.nodeType===3 ? '/'+(count*2+1)+':'+(firstOffset+offset) : '/'+(count*2+2));
                node=parent;
            }
            return {label:full.slice(start,end).trim().slice(0,200), ids, cfi:steps.join('')};
        })()
    """.trimIndent()) { raw ->
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
        val ids = data.optJSONArray("ids")
        result(PagePassage(data.optString("label"), (0 until (ids?.length() ?: 0)).map { ids!!.getString(it) },
            data.optString("cfi").ifBlank { null }))
    }
}

internal data class ReaderTarget(
    val chapter: Int,
    val cfi: String? = null,
    val anchor: String? = null,
    val label: String = "",
    val offset: Int = 0,
)

internal class ReaderBookmarks(private val prefs: SharedPreferences, key: String) {
    private val field = "$key.bookmarks"
    fun load(): List<ReaderTarget> = runCatching {
        val rows = JSONArray(prefs.getString(field, "[]"))
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            ReaderTarget(row.getInt("chapter"), row.optString("cfi").ifBlank { null },
                row.optString("anchor").ifBlank { null }, row.optString("label"), row.optInt("offset"))
        }
    }.getOrDefault(emptyList())

    fun save(rows: List<ReaderTarget>) {
        val json = JSONArray()
        rows.forEach { row -> json.put(JSONObject().put("chapter", row.chapter).put("cfi", row.cfi)
            .put("anchor", row.anchor).put("label", row.label).put("offset", row.offset)) }
        prefs.edit().putString(field, json.toString()).apply()
    }
}

private val breakTags = setOf("body", "p", "div", "section", "article", "aside", "li", "br", "td", "tr",
    "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "dt", "dd", "pre", "figcaption")

internal fun Publication.narrationAnchor(chapter: Int, cfi: String, cues: List<Cue>): String? {
    val item = spine.getOrNull(chapter) ?: return null
    if (cues.isEmpty()) return null
    val ids = cues.mapNotNullTo(mutableSetOf()) { it.fragment }
    val root = open(item.path)?.let(::xml) ?: return null
    return narrationAnchor(root, cfi, ids)
}

internal fun firstCueOfSentence(root: Element, active: String, ids: Set<String>): String? {
    val all = root.getElementsByTagName("*")
    val mark = (0 until all.length).asSequence().map { all.item(it) as Element }
        .firstOrNull { it.getAttribute("id") == active } ?: return null
    val block = generateSequence(mark) { it.parentNode as? Element }.firstOrNull { it.localName in breakTags } ?: mark
    val plain = StringBuilder()
    val stops = mutableListOf<Pair<Int, String>>()
    var at = 0
    fun walk(node: Node) {
        if (node is Element) {
            val id = node.getAttribute("id")
            if (id == active) at = plain.length
            if (id in ids) stops += plain.length to id
        }
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) plain.append(node.nodeValue)
        else for (i in 0 until node.childNodes.length) walk(node.childNodes.item(i))
    }
    walk(block)
    if (plain.isEmpty()) return active
    val language = root.getAttribute("lang").ifBlank { root.getAttribute("xml:lang") }
    val breaks = java.text.BreakIterator.getSentenceInstance(java.util.Locale.forLanguageTag(language))
    breaks.setText(plain.toString())
    val start = breaks.preceding((at + 1).coerceAtMost(plain.length)).coerceAtLeast(0)
    return stops.firstOrNull { it.first in start..at }?.second ?: active
}

internal fun narrationAnchor(root: Element, cfi: String, ids: Set<String>): String? {
    val clean = cfi.replace(Regex("""\[(?:\^.|[^\]])*\]"""), "").split(',')
    val point = clean.first() + clean.getOrElse(1) { "" }
    var node: Node = root
    for (step in Regex("""/(\d+)(?::(\d+))?""").findAll(point)) {
        val number = step.groupValues[1].toIntOrNull() ?: return null
        var before = 0
        var remaining = step.groupValues[2].toIntOrNull() ?: 0
        var found: Node? = null
        for (i in 0 until node.childNodes.length) {
            val child = node.childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                before++
                if (number == before * 2) { found = child; break }
            } else if (child.nodeType in listOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE) && number == before * 2 + 1) {
                val length = child.nodeValue.orEmpty().length
                if (remaining <= length) { found = child; break }
                remaining -= length
            }
        }
        node = found ?: return null
    }
    val element = (node as? Element) ?: (node.parentNode as? Element) ?: return null
    var parent: Element? = element
    while (parent != null) {
        parent.getAttribute("id").takeIf { it in ids }?.let { return it }
        parent = parent.parentNode as? Element
    }
    val all = root.getElementsByTagName("*")
    var before: String? = null
    var reached = element === root
    for (i in 0 until all.length) {
        val el = all.item(i) as Element
        if (el === element) reached = true
        val id = el.getAttribute("id")
        if (id in ids) {
            if (reached) return id
            before = id
        }
    }
    return before
}

// Use the EPUB's text nodes and CFI offsets so a result survives font and viewport changes.
internal fun searchChapter(root: Element, chapter: Int, query: String, limit: Int = 100): List<ReaderTarget> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty() || limit <= 0) return emptyList()
    val pattern = Regex(words.joinToString("[\\s\\p{Z}]+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    val hits = mutableListOf<ReaderTarget>()
    data class Run(val start: Int, val path: String)
    val runs = mutableListOf<Run>()
    val text = StringBuilder()
    fun flush() {
        if (text.isNotEmpty()) {
            for (match in pattern.findAll(text)) {
                if (hits.size >= limit) break
                val at = match.range.first
                val run = runs.lastOrNull { it.start <= at } ?: continue
                val before = (at - 42).coerceAtLeast(0)
                val after = (match.range.last + 58).coerceAtMost(text.length)
                val snippet = text.substring(before, after).replace(Regex("[\\s\\p{Z}]+"), " ").trim()
                hits += ReaderTarget(chapter, "${run.path}:${at - run.start}", label = snippet)
            }
        }
        text.clear()
        runs.clear()
    }
    fun visit(node: Element, path: String) {
        val name = node.localName ?: node.tagName
        if (name in setOf("head","script","style","noscript","svg")) return
        if (name in breakTags) flush()
        var elementCount = 0
        var siblingText = 0
        for (i in 0 until node.childNodes.length) {
            if (hits.size >= limit) return
            val child = node.childNodes.item(i)
            when (child.nodeType) {
                Node.ELEMENT_NODE -> {
                    elementCount++
                    siblingText = 0
                    visit(child as Element, "$path/${elementCount * 2}")
                }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val value = child.nodeValue.orEmpty()
                    if (value.isNotEmpty()) {
                        runs += Run(text.length - siblingText, "$path/${elementCount * 2 + 1}")
                        text.append(value)
                        siblingText += value.length
                    }
                }
            }
        }
        if (name in breakTags) flush()
    }
    visit(root, "")
    flush()
    return hits
}
