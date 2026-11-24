package app.yourneeds.foliocue

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningStatsSyncTest {
    private val time = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 11, 24)
    private fun total(account: JSONObject) = listeningSummary(cloudListeningData(account), today).total
    private fun add(account: JSONObject, duration: Long, end: Long = time) =
        queueListening(account, 41, 8, "Fictional book", "Fixture author", end, duration, ZoneId.of("UTC"))

    private class Endpoint : ListeningStatisticsEndpoint {
        override val statisticsIdentity = "fixture-account"
        val rows = linkedMapOf<String, ListeningEvent>()
        var loseReply = false
        var afterSave: (() -> Unit)? = null
        override fun statisticsBooks() = listOf(StatisticsBook(41, "Fictional book", "Fixture author"))
        override fun saveStatisticsSession(event: ListeningEvent) {
            event.payload()
            rows.putIfAbsent(event.id, event)
            afterSave?.also { afterSave = null }?.invoke()
            if (loseReply) { loseReply = false; throw IOException("Simulated lost reply") }
        }
        override fun statisticsSessions(bookId: Long, page: Int): ListeningSessionPage {
            val items = rows.values.filter { it.bookId == bookId }.mapIndexed { index, event ->
                RemoteListeningSession(index + 1L, event.fileId, event.start, event.end, event.millis, "listen")
            }
            return ListeningSessionPage(page, items.size, items)
        }
    }

    @Test fun lostReplyRetriesTheSameIdAndTwoDevicesSeeOneServerSession() {
        val first = JSONObject()
        add(first, 30_000)
        val queued = queuedListening(first)
        val endpoint = Endpoint().apply { loseReply = true }
        try { synchronizeListening(endpoint, queued, true); fail("Expected transport failure") } catch (_: IOException) { }
        assertEquals(30_000L, total(first))
        assertEquals(queued, queuedListening(first))
        val second = JSONObject()
        commitListeningSync(second, synchronizeListening(endpoint, emptyList(), true), time)
        assertEquals(30_000L, total(second))
        commitListeningSync(first, synchronizeListening(endpoint, queuedListening(first), true), time)
        assertEquals(1, endpoint.rows.size)
        assertEquals(total(second), total(first))
        assertTrue(queuedListening(first).isEmpty())
    }

    @Test fun newListeningDuringUploadIsNotOverwrittenAndLaterConverges() {
        val account = JSONObject()
        add(account, 30_000)
        val snapshot = queuedListening(account)
        val endpoint = Endpoint().apply { afterSave = { add(account, 45_000, time + 45_000) } }
        commitListeningSync(account, synchronizeListening(endpoint, snapshot, false), time)
        assertEquals(75_000L, total(account))
        assertEquals(1, queuedListening(account).size)
        commitListeningSync(account, synchronizeListening(endpoint, queuedListening(account), true), time + 60_000)
        assertEquals(75_000L, total(account))
        assertTrue(queuedListening(account).isEmpty())
        val tablet = JSONObject()
        commitListeningSync(tablet, synchronizeListening(endpoint, emptyList(), true), time)
        assertEquals(total(account), total(tablet))
    }

    @Test fun independentPhoneAndTabletSessionsMergeAndRepeatedRefreshDoesNotAddTime() {
        val phone = JSONObject()
        val tablet = JSONObject()
        add(phone, 30_000)
        add(tablet, 40_000, time + 40_000)
        val endpoint = Endpoint()
        commitListeningSync(phone, synchronizeListening(endpoint, queuedListening(phone), true), time)
        commitListeningSync(tablet, synchronizeListening(endpoint, queuedListening(tablet), true), time)
        repeat(2) {
            commitListeningSync(phone, synchronizeListening(endpoint, queuedListening(phone), true), time)
            commitListeningSync(tablet, synchronizeListening(endpoint, queuedListening(tablet), true), time)
        }
        assertEquals(70_000L, total(phone))
        assertEquals(total(phone), total(tablet))
        assertEquals(2, endpoint.rows.size)
    }

    @Test fun shortIntervalsAndSubsecondRemaindersStayDurableUntilServerMinimum() {
        val account = JSONObject()
        add(account, 9000)
        sealListeningDrafts(account)
        assertTrue(queuedListening(account).isEmpty())
        add(account, 2100, time + 2100)
        sealListeningDrafts(account)
        val queued = queuedListening(account)
        assertEquals(11_000L, queued.single().millis)
        assertEquals(11_100L, total(account))
        val restored = JSONObject(account.toString())
        assertEquals(queued, queuedListening(restored))
        commitListeningSync(restored, synchronizeListening(Endpoint(), queued, true), time)
        assertEquals(11_100L, total(restored))
        assertTrue(queuedListening(restored).isEmpty())
    }

    @Test fun legacyHistoryAndOtherServerAccountsRemainSeparate() {
        val root = JSONObject()
        addListening(root, "old-book", "Old local book", "Fixture", time, 50_000, ZoneId.of("UTC"))
        val first = statisticsAccount(root, "account-one")
        add(first, 30_000)
        val second = statisticsAccount(root, "account-two")
        assertEquals(30_000L, total(first))
        assertEquals(0L, total(second))
        assertEquals(50_000L, listeningSummary(root, today).total)
        assertEquals(1, root.getJSONObject("books").length())
    }

    @Test fun unconfirmedOrWrongKindServerRowsNeverClearPendingWork() {
        val account = JSONObject()
        add(account, 30_000)
        val queued = queuedListening(account)
        val event = queued.single()
        val endpoint = object : ListeningStatisticsEndpoint {
            override val statisticsIdentity = "test"
            override fun statisticsBooks() = listOf(StatisticsBook(41, "Fixture", ""))
            override fun saveStatisticsSession(event: ListeningEvent) { }
            override fun statisticsSessions(bookId: Long, page: Int) = ListeningSessionPage(page, 1,
                listOf(RemoteListeningSession(1, event.fileId, event.start, event.end, event.millis, "read")))
        }
        try { synchronizeListening(endpoint, queued, true); fail("Unconfirmed upload accepted") } catch (_: IOException) { }
        assertEquals(queued, queuedListening(account))
        assertEquals(30_000L, total(account))
    }

    @Test fun readingAndUnknownMediaAreKeptSeparatelyFromAudiobooks() {
        val endpoint = object : ListeningStatisticsEndpoint {
            override val statisticsIdentity = "test"
            override fun statisticsBooks() = listOf(StatisticsBook(41, "Fixture", ""))
            override fun saveStatisticsSession(event: ListeningEvent) { }
            override fun statisticsSessions(bookId: Long, page: Int) = ListeningSessionPage(page, 3, listOf(
                RemoteListeningSession(1, 8, time - 30_000, time, 30_000, "read"),
                RemoteListeningSession(2, 8, time - 30_000, time, 30_000, null),
                RemoteListeningSession(3, 8, time - 30_000, time, 30_000, "listen")))
        }
        val account = JSONObject()
        commitListeningSync(account, synchronizeListening(endpoint, emptyList(), true), time)
        assertEquals(90_000L, total(account))
        assertEquals(30_000L, listeningSummary(serverActivityData(account, "listen"), today).total)
        assertEquals(30_000L, listeningSummary(serverActivityData(account, "read"), today).total)
        assertEquals(30_000L, listeningSummary(serverActivityData(account, "unknown"), today).total)
    }

    @Test fun paginationChangesRejectTheSnapshotRatherThanPublishingPartialTotals() {
        val endpoint = object : ListeningStatisticsEndpoint {
            override val statisticsIdentity = "test"
            override fun statisticsBooks() = listOf(StatisticsBook(41, "Fixture", ""))
            override fun saveStatisticsSession(event: ListeningEvent) { }
            override fun statisticsSessions(bookId: Long, page: Int) = ListeningSessionPage(page, if (page == 1) 2 else 3,
                listOf(RemoteListeningSession(page.toLong(), 8, time - 10_000, time, 10_000, "listen")))
        }
        try { synchronizeListening(endpoint, emptyList(), true); fail("Partial history accepted") } catch (_: IOException) { }
    }

    @Test fun mergedBookAliasesDoNotCountTheSameServerHistoryTwice() {
        val endpoint = object : ListeningStatisticsEndpoint {
            override val statisticsIdentity = "test"
            override fun statisticsBooks() = listOf(StatisticsBook(41, "Original fixture", ""), StatisticsBook(42, "Merged fixture", ""))
            override fun saveStatisticsSession(event: ListeningEvent) { }
            override fun statisticsSessions(bookId: Long, page: Int) = ListeningSessionPage(page, 1,
                listOf(RemoteListeningSession(501, 8, time - 30_000, time, 30_000, "listen")))
        }
        val account = JSONObject()
        commitListeningSync(account, synchronizeListening(endpoint, emptyList(), true), time)
        assertEquals(30_000L, total(account))
        assertEquals(1, account.getJSONObject("serverBooks").length())
    }
}
