package app.yourneeds.foliocue

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal data class ProgressReceipt(val position: JSONObject, val superseded: Boolean)

// BookOrbit's grouped API exchanges nested locations. Keep Audilege's durable outbox compatible
// with positions saved by earlier versions and with servers that expose only the per-file route.
private val groups = mapOf(
    "text" to mapOf("cfi" to "cfi", "page" to "pageNumber"),
    "narration" to mapOf("seconds" to "positionSeconds", "fragment" to "mediaOverlayFragment",
        "sectionIndex" to "mediaOverlaySectionIndex"),
    "kobo" to mapOf("source" to "koboLocationSource", "type" to "koboLocationType",
        "value" to "koboLocationValue", "contentPercent" to "koboContentSourceProgressPercent"),
)

private fun JSONObject.forFile(fileId: Long): JSONObject {
    if (getLong("fileId") != fileId) throw IOException("Unexpected BookOrbit edition")
    return this
}

private fun JSONObject.flatPosition(fileId: Long): JSONObject {
    forFile(fileId)
    val percent = getDouble("percentage")
    if (percent !in 0.0..100.0) throw IOException("Invalid BookOrbit percentage")
    val flat = JSONObject().put("percentage", percent).put("updatedAt", get("updatedAt"))
        .put("koreaderProgress", get("koreader"))
    groups.forEach { (name, fields) ->
        val group = getJSONObject(name)
        fields.forEach { (wire, local) -> flat.put(local, group.get(wire)) }
        if (name == "text" || name == "narration") flat.put("${name}UpdatedAt", group.get("updatedAt"))
        if (name == "narration") flat.put("narrationPercentage", group.get("percentage"))
    }
    return flat
}

private fun JSONObject.onlyEntry(key: String): JSONObject {
    val entries = getJSONArray(key)
    if (entries.length() != 1) throw IOException("Expected one BookOrbit progress entry")
    return entries.getJSONObject(0)
}

internal fun JSONObject.groupedPosition(fileId: Long) = onlyEntry("items").flatPosition(fileId)

internal fun JSONObject.groupedReceipt(fileId: Long): ProgressReceipt {
    val result = onlyEntry("results").forFile(fileId)
    val status = result.getString("status")
    if (status != "saved" && status != "superseded") throw IOException("Unrecognized BookOrbit progress result")
    return ProgressReceipt(result.getJSONObject("progress").flatPosition(fileId), status == "superseded")
}

internal fun groupedProgressWrite(fileId: Long, body: JSONObject, previous: JSONObject): JSONObject {
    val write = JSONObject().put("fileId", fileId).put("kind", body.getString("source"))
        .put("percentage", body.getDouble("percentage"))
    // The server rejects our write if another reader saved since the GET. Use the server's
    // last update, never the phone's wall clock. An untouched row has no timestamp yet.
    write.put("editedAt", if (previous.isNull("updatedAt")) "1970-01-01T00:00:00.000Z" else previous.getString("updatedAt"))
    groups.forEach { (name, fields) ->
        val group = JSONObject()
        fields.forEach { (wire, local) -> if (body.has(local)) group.put(wire, body.get(local)) }
        if (group.length() > 0) write.put(name, group)
    }
    if (body.has("koreaderProgress")) write.put("koreader", body.get("koreaderProgress"))
    return JSONObject().put("writes", JSONArray().put(write))
}
