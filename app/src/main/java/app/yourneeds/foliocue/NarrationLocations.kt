package app.yourneeds.foliocue

import org.w3c.dom.Element
import org.w3c.dom.Node

/** Content-document CFIs at the start of each overlay target, in the EPUB's own DOM. */
internal fun narrationLocations(root: Element, anchors: Set<String>): Map<String, String> {
    val locations = mutableMapOf<String, String>()
    fun visit(element: Element, path: String): String? {
        var firstText: String? = null
        var elements = 0
        var textOffset = 0
        for (index in 0 until element.childNodes.length) {
            val child = element.childNodes.item(index)
            when (child.nodeType) {
                Node.ELEMENT_NODE -> {
                    elements++
                    textOffset = 0
                    val descendant = visit(child as Element, "$path/${2 * elements}")
                    if (firstText == null) firstText = descendant
                }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val value = child.nodeValue.orEmpty()
                    if (firstText == null) {
                        val offset = value.indexOfFirst { !it.isWhitespace() }
                        if (offset >= 0) firstText = "$path/${2 * elements + 1}:${textOffset + offset}"
                    }
                    textOffset += value.length
                }
            }
        }
        val id = element.getAttribute("id")
        if (id in anchors && path.isNotEmpty()) locations.putIfAbsent(id, firstText ?: path)
        return firstText
    }
    visit(root, "")
    return locations
}
