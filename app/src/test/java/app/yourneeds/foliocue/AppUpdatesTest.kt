package app.yourneeds.foliocue

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, vararg assets: String): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it) }))

    @Test fun numericVersionComparisonAndOldChannelAssets() {
        assertTrue(newerVersion("0.10.0", "0.9.9"))
        assertFalse(newerVersion("0.10.0", "0.10.0"))
        assertFalse(newerVersion("0.10.0-beta", "0.9.0"))
        assertEquals(Release("0.3.2", "$RELEASES/download/v0.3.2/Audilege-0.3.2-debug.apk"),
            parseRelease(release("v0.3.2", "source.zip", "Audilege-0.3.2-debug.apk")))
    }

    @Test fun prefersReleaseApkAndRejectsAnUninstallableUpdate() {
        assertEquals("$RELEASES/download/v1.1.0/Audilege-1.1.0.apk",
            parseRelease(release("v1.1.0", "Audilege-1.1.0-debug.apk", "Audilege-1.1.0.apk")).link)
        assertTrue(runCatching { parseRelease(release("v1.1.0", "other.apk")) }.isFailure)
        assertTrue(runCatching { parseRelease(release("v1.1.0", "Audilege-1.1.0.apk").put("prerelease", true)) }.isFailure)
    }

    @Test fun readsIntegrityMetadataFromTheSelectedApk() {
        val response = release("v1.2.0", "source.zip", "Audilege-1.2.0.apk")
        val files = response.getJSONArray("assets")
        files.getJSONObject(0).put("size", 300).put("digest", "sha256:" + "ab".repeat(32))
        val apk = files.getJSONObject(1).put("size", 4200).put("digest", "sha256:" + "CC".repeat(32))
        val parsed = parseRelease(response)
        assertEquals(4200L, parsed.size)
        assertEquals("cc".repeat(32), parsed.sha256)
        apk.put("digest", JSONObject.NULL)
        assertNull(parseRelease(response).sha256)
        apk.put("digest", "sha256:corrupt")
        assertTrue(runCatching { parseRelease(response) }.isFailure)
    }
}
