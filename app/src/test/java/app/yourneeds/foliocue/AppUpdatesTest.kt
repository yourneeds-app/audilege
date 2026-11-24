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
}
