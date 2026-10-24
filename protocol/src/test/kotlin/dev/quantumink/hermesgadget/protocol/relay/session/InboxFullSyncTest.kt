package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxFullSyncTest {
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        var failure: InboxWrite? = null
        var promote = false
        override fun load() = InboxCodec.decode(InboxCodec.encode(state))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (next.profiles.any { it.lastSyncMs > 10 } && failure != null) {
                if (promote) state = InboxCodec.decode(InboxCodec.encode(next))
                return failure!!
            }
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class Owner(complete: Boolean = true) {
        val disk = Disk()
        val repository = InboxRepository(disk)
        var published: InboxSnapshot? = null
        val f = ControllerFixture(inbox = repository, inboxUpdates = { s, _ -> published = s })
        val namespace = HistoryNamespace.from(Seeds.record(f.identity))!!
        init {
            f.store.commit(Seeds.empty(ControllerSection(listOf(Seeds.record(f.identity)))), 0)
            repository.configure(InboxProfile(namespace, "sync", "Sync", "Box", lastSyncMs = 10))
            f.prepare()
            f.controller.observeInboxOnly(true)
            f.run()
            val methods = Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
            f.connect(Dto.info(methods = if (complete) methods else methods - "relay.tasks.list"))
            f.answer("relay.outbox.read", OutboxPage(emptyList(), 7).toWire())
            if (complete) f.answer("relay.tasks.list", TaskPage(emptyList(), null).toWire())
        }
        fun finish() = f.answer("relay.conversations.list", Dto.page().toWire())
        fun time() = repository.snapshot().profiles.single().lastSyncMs
    }

    @Test fun partialRequiredSourceRetainsFullSyncTimeAndRefreshDebt() {
        val h = Owner(complete = false)
        h.finish()
        assertEquals("Partial refresh must keep last confirmed full sync", 10, h.time())
        assertTrue(h.f.controller.inboxStatus.startsWith("Partial"))
        assertTrue(h.f.store.reread().snapshot.deliveries.single().obligations.isNotEmpty())
        assertEquals(10, h.published!!.profiles.single().lastSyncMs)
    }

    @Test fun completeConfirmedAggregateAdvancesFullSyncTime() {
        val h = Owner()
        h.finish()
        assertEquals(Dto.SERVER_TIME, h.time())
        assertEquals("Current", h.f.controller.inboxStatus)
        assertTrue(h.f.store.reread().snapshot.deliveries.single().obligations.isEmpty())
    }

    private fun aggregate(result: StoreCommit) {
        val h = Owner()
        h.f.store.failAll = result
        h.finish()
        assertEquals(10, h.time())
        assertTrue(h.f.controller.inboxStatus.startsWith("Partial"))
        assertTrue(h.f.store.reread().snapshot.deliveries.single().obligations.isNotEmpty())
    }

    @Test fun refusedAggregateCannotAdvanceFullSync() =
        aggregate(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "controlled"))

    @Test fun staleAggregateCannotAdvanceFullSync() = aggregate(StoreCommit.Stale)

    @Test fun failedAggregateCannotAdvanceFullSync() =
        aggregate(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun uncertainAggregateCannotAdvanceFullSync() =
        aggregate(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true))

    private fun display(result: InboxWrite, promote: Boolean = false) {
        val h = Owner()
        h.disk.failure = result
        h.disk.promote = promote
        h.finish()
        assertEquals(10, h.time())
        assertFalse(h.f.controller.inboxStatus == "Current")
        assertEquals(10, h.published!!.profiles.single().lastSyncMs)
        h.disk.failure = null
        h.f.controller.refreshInbox()
        h.f.run()
        h.f.answer("relay.outbox.read", OutboxPage(emptyList(), 7).toWire())
        h.f.answer("relay.tasks.list", TaskPage(emptyList(), null).toWire())
        h.finish()
        assertEquals(Dto.SERVER_TIME, h.time())
        assertEquals("Current", h.f.controller.inboxStatus)
    }

    @Test fun refusedTimestampCannotPublishFullSync() = display(InboxWrite.Refused)

    @Test fun staleTimestampCannotPublishFullSync() = display(InboxWrite.Stale)

    @Test fun failedTimestampCannotPublishFullSync() = display(InboxWrite.Failed(false))

    @Test fun uncertainTimestampCannotPublishFullSync() = display(InboxWrite.Failed(true))

    @Test fun promotedUncertainTimestampRetainsPreviousConfirmedPublication() =
        display(InboxWrite.Failed(true), promote = true)

    @Test fun synchronousUnavailableRefreshCompletesExactlyOnceWithoutAdvancingTime() {
        val disk = Disk()
        val repository = InboxRepository(disk)
        val f = ControllerFixture(inbox = repository)
        val record = Seeds.record(f.identity)
        val namespace = HistoryNamespace.from(record)!!
        f.store.commit(Seeds.empty(ControllerSection(listOf(record))), 0)
        repository.configure(InboxProfile(namespace, "sync", "Sync", "Box", lastSyncMs = 10))
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(Dto.info(methods = listOf("relay.info")))
        var completed = 0
        f.controller.refreshInbox { completed++ }
        f.run()
        assertEquals(1, completed)
        assertTrue(f.controller.inboxStatus.startsWith("Partial"))
        assertEquals(10, repository.snapshot().profiles.single().lastSyncMs)
        assertTrue(f.transport.sent.isEmpty())
    }
}
