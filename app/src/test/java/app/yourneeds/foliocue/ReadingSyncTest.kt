package app.yourneeds.foliocue

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadingSyncTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putLong" -> { changes[args[0] as String] = args[1]; proxy }
                    "remove" -> { changes[args[0] as String] = null; proxy }
                    "apply" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        null
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getLong" -> values[args[0]] ?: args[1]
                "edit" -> editor()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    private fun place(n: Int) = JSONObject().put("source", "text").put("cfi", "epubcfi(/6/2!/4/$n)")
        .put("percentage", n)

    private class Server(var row: JSONObject = JSONObject()) : ProgressEndpoint {
        var writes = 0
        var receive: (() -> Unit)? = null
        override fun progress(): JSONObject = JSONObject(row.toString())
        override fun updateProgress(body: JSONObject): JSONObject {
            row = JSONObject(body.toString())
            writes++
            receive?.invoke()
            return JSONObject()
        }
    }

    @Test fun reconnectWritesBackwardsAndAvoidsDuplicatingAnAcknowledgedWrite() = runBlocking {
        val server = Server(place(8))
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        bridge.remember(place(2))
        assertEquals(SyncState.Synced, bridge.flush())
        assertEquals(place(2).getString("cfi"), server.row.getString("cfi"))
        bridge.remember(place(2))
        assertEquals(SyncState.Synced, bridge.flush())
        assertEquals(1, server.writes)
    }

    @Test fun secondDeviceMovedWhileOfflineOffersBothPositionsAndChecksAgainOnConfirmation() = runBlocking {
        val server = Server(place(6))
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        bridge.remember(place(4))
        server.row = place(8)
        assertEquals(SyncState.Conflict, bridge.flush())
        assertEquals(4, bridge.local!!.getInt("percentage"))
        assertEquals(8, bridge.remote!!.getInt("percentage"))
        server.row = place(10)
        assertEquals(SyncState.Conflict, bridge.flush(true))
        assertEquals(0, server.writes)
        assertEquals(SyncState.Synced, bridge.flush(true))
        assertEquals(4, server.row.getInt("percentage"))
    }

    @Test fun freshLocalMovementDuringAnUploadStaysQueued() = runBlocking {
        val server = Server()
        val bridge = ReadingSync.open(server, null, preferences(), UUID.randomUUID().toString())
        bridge.remember(place(2))
        server.receive = { bridge.remember(place(4)) }
        assertEquals(SyncState.Pending, bridge.flush())
        assertEquals(4, bridge.pending!!.getInt("percentage"))
        server.receive = null
        assertEquals(SyncState.Synced, bridge.flush())
        assertEquals(4, server.row.getInt("percentage"))
    }

    @Test fun externalChangeWhenIdleCanBeAdoptedWithoutWriting() = runBlocking {
        val server = Server(place(4))
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        server.row = place(2)
        assertEquals(SyncState.Conflict, bridge.refresh())
        bridge.discardLocal()
        assertEquals(2, bridge.resume!!.getInt("percentage"))
        assertNull(bridge.pending)
        assertEquals(0, server.writes)
    }

    @Test fun serverVersionDeterminesWhetherNarrationFieldsAreNeededForTextWrites() {
        val legacy = JSONObject().put("mediaOverlayFragment", "chapter.xhtml#first")
            .put("koreaderProgress", "locator").put("percentage", 1.0)
        val modern = JSONObject(legacy.toString()).put("textUpdatedAt", JSONObject.NULL)
        assertTrue(writePayload(place(3), legacy).has("mediaOverlayFragment"))
        val body = writePayload(place(3), modern)
        assertFalse(body.has("mediaOverlayFragment"))
        assertEquals("locator", body.getString("koreaderProgress"))
        assertTrue(samePosition(JSONObject(), JSONObject().put("percentage", 0).put("textUpdatedAt", JSONObject.NULL)))
    }

    private fun narrated(n: Int) = place(n).put("source", "narration")
        .put("mediaOverlayFragment", "chapter.xhtml#word$n").put("mediaOverlaySectionIndex", 0)

    private class RewindServer : ProgressEndpoint {
        var row = JSONObject().put("percentage", 8).put("cfi", "epubcfi(/6/2!/4/8)")
            .put("textUpdatedAt", "2026-11-24T08:00:00Z")
        val writes = mutableListOf<String>()
        var afterNarration: (() -> Unit)? = null
        override fun progress() = JSONObject(row.toString())
        override fun updateProgress(body: JSONObject): JSONObject {
            val source = body.getString("source")
            writes.add(source)
            if (source == "narration") {
                row.put("mediaOverlayFragment", body.getString("mediaOverlayFragment"))
                    .put("mediaOverlaySectionIndex", body.getInt("mediaOverlaySectionIndex"))
                    .put("narrationUpdatedAt", "2026-11-24T09:00:00Z")
                afterNarration?.invoke()
            } else {
                assertFalse(body.has("mediaOverlayFragment"))
                row.put("cfi", body.getString("cfi")).put("percentage", body.getDouble("percentage"))
                    .put("textUpdatedAt", "2026-11-24T09:00:01Z")
            }
            return JSONObject()
        }
    }

    @Test fun listeningBackwardsAlsoRepositionsTheBrowserAndRetriesHalfFinishedWrites() = runBlocking {
        val server = RewindServer()
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        val candidate = narrated(2)
        bridge.remember(candidate)
        server.afterNarration = { throw java.io.IOException("Lost reply after saving the marker") }
        try { bridge.flush(); fail("Expected offline retry") } catch (_: java.io.IOException) { }
        server.afterNarration = null
        assertEquals(SyncState.Synced, bridge.flush())
        assertEquals(candidate.getString("cfi"), server.row.getString("cfi"))
        assertEquals(candidate.getString("mediaOverlayFragment"), server.row.getString("mediaOverlayFragment"))
        assertEquals(listOf("narration", "text"), server.writes)
    }

    @Test fun listeningDoesNotOverwriteAnotherDevicesNewerTextWhileRewinding() = runBlocking {
        val server = RewindServer()
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        bridge.remember(narrated(2))
        server.afterNarration = {
            server.row.put("cfi", place(6).getString("cfi")).put("textUpdatedAt", "2026-11-24T09:00:02Z")
        }
        assertEquals(SyncState.Conflict, bridge.flush())
        assertEquals(listOf("narration"), server.writes)
        assertEquals(place(6).getString("cfi"), server.row.getString("cfi"))
    }

    @Test fun listeningContinuesDuringALostReplyAndUploadsTheNewestWord() = runBlocking {
        val server = RewindServer()
        val bridge = ReadingSync.open(server, server.progress(), preferences(), UUID.randomUUID().toString())
        bridge.remember(narrated(2))
        server.afterNarration = {
            bridge.remember(narrated(4))
            throw java.io.IOException("Lost first reply while playback was running")
        }
        try { bridge.flush(); fail("Expected offline retry") } catch (_: java.io.IOException) { }
        server.afterNarration = null
        assertEquals(SyncState.Synced, bridge.flush())
        assertEquals(narrated(4).getString("cfi"), server.row.getString("cfi"))
        assertEquals(narrated(4).getString("mediaOverlayFragment"), server.row.getString("mediaOverlayFragment"))
        assertEquals(listOf("narration", "narration", "text"), server.writes)
    }

    @Test fun acceptsTheServersOwnSentenceCfiWhenNarrationAlsoMovedText() {
        val candidate = narrated(2)
        val saved = JSONObject(candidate.toString()).put("cfi", "epubcfi(/6/2[item]!/4/2[word]/1:0)")
            .put("narrationUpdatedAt", "2026-11-24T09:00:00Z").put("textUpdatedAt", "2026-11-24T09:00:00Z")
        assertTrue(serverHasCandidate(saved, candidate))
        saved.put("textUpdatedAt", "2026-11-24T08:00:00Z")
        assertFalse(serverHasCandidate(saved, candidate))
    }

    @Test fun readingAtAnExistingTextCfiStillSupersedesMoreRecentListening() {
        val earlier = narrated(8).put("cfi", place(2).getString("cfi"))
            .put("narrationUpdatedAt", "2026-11-24T09:00:00Z").put("textUpdatedAt", "2026-11-24T08:00:00Z")
        val selected = place(2)
        assertFalse(serverHasCandidate(earlier, selected))
        val newer = JSONObject(earlier.toString()).put("textUpdatedAt", "2026-11-24T10:00:00Z")
        assertTrue(serverHasCandidate(newer, selected))
        assertFalse(samePosition(earlier, newer))
    }
}
