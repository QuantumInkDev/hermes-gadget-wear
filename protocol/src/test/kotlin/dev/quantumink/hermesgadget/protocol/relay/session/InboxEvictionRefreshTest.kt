package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.TaskPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxEvictionRefreshTest {
    private class Disk : InboxFile {
        var s = InboxSnapshot()
        var failure: InboxWrite? = null
        override fun load() = InboxCodec.decode(InboxCodec.encode(s))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            failure?.let { return it }
            if (base != s.revision) return InboxWrite.Stale
            s = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class History : RetainedHistory {
        var failed = true
        override fun read(namespace: HistoryNamespace) = RetainedSnapshot(0)
        override fun absent(namespace: HistoryNamespace, conversationId: String?) = false
        override fun evict(namespace: HistoryNamespace, conversationId: String?) {
            if (failed) throw HistoryFailure(HistoryProblem.STORAGE)
        }
        override fun capture(page: HistoryCapture): RetainedSnapshot = error("unused")
        override fun review(
            namespace: HistoryNamespace,
            conversationId: String?
        ): HistoryEraseReview = error("unused")
        override fun reviewRetention(namespace: HistoryNamespace, days: Int): HistoryEraseReview =
            error("unused")
        override fun erase(review: HistoryEraseReview, namespace: HistoryNamespace) =
            error("unused")
        override fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot =
            error("unused")
    }
    private fun final(conversation: String = "opaque-final") = OutboxItem.Final(
        DeliveryId.of("00000000-0000-4000-8000-000000003333")!!,
        OpaqueConversationRef.of(conversation)!!,
        OpaqueEventRef.of("opaque-event")!!,
        Dto.SERVER_TIME,
        Dto.SERVER_TIME + 86_400_000,
        OutboxPayload.FinalText("opaque retained", null)
    )

    @Test fun durableExactDeletionFencesCanonicalCopyWhileUnsavedFalseAndRetainsOpaqueLookalike() {
        val steps = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResourceAsStream("/task5-inbox/completed-lost-ack/session.json")
            ).bufferedReader().use {
                it.readText()
            }
        )
            .jsonObject["steps"]!!.jsonArray.map { it.jsonObject }.filter {
            it["device"] ==
                JsonPrimitive("watch")
        }
        val info = Info.decode(
            steps.last {
                it["request"]!!.jsonObject["method"] ==
                    JsonPrimitive("relay.info")
            }
                ["response"]!!.jsonObject["result"]!!.jsonObject
        )
        val copy = steps.filter {
            it["request"]!!.jsonObject["method"] ==
                JsonPrimitive("relay.outbox.read")
        }
            .flatMap { OutboxPage.decode(it["response"]!!.jsonObject["result"]!!.jsonObject).items }
            .filterIsInstance<OutboxItem.Change>().first()
        val d = Disk()
        val r = InboxRepository(d)
        val history = History()
        var view: InboxSnapshot? = null
        val f =
            ControllerFixture(inbox = r, retainedHistory = history, inboxUpdates = { s, _ ->
                view =
                    s
            })
        val record = Seeds.record(
            f.identity,
            pinned = info.profileId
        ).copy(selectedConversationId = copy.conversationId, selectionDeleted = true)
        val n = HistoryNamespace.from(record)!!
        f.store.commit(Seeds.empty(ControllerSection(listOf(record))), 0)
        r.configure(InboxProfile(n, "box", "Profile", "Box"))
        r.accept(n, listOf(copy, final(copy.conversationId.value)), Dto.SERVER_TIME)
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(info)
        assertFalse(f.ui.unsaved)
        assertTrue(f.store.reread().section.enrollments.single().selectionDeleted)
        assertEquals(1, view!!.copies.size)
        assertTrue(view!!.copies.single().item is OutboxItem.Final)
        assertEquals(2, d.load().copies.size)
        history.failed = false
        d.failure = InboxWrite.Failed(true)
        f.controller.retryStorage()
        f.run()
        assertTrue(f.store.reread().section.enrollments.single().selectionDeleted)
        assertEquals(2, d.load().copies.size)
        assertTrue(f.ui.contentEvictionPending)
        d.failure = null
        f.controller.retryStorage()
        f.run()
        assertFalse(f.store.reread().section.enrollments.single().selectionDeleted)
        assertNull(f.store.reread().section.enrollments.single().selectedConversationId)
        assertEquals(1, d.load().copies.size)
        assertTrue(d.load().copies.single().item is OutboxItem.Final)
        assertNull(d.load().copies.single().canonicalConversation)
        assertNotNull(InboxRoute.resolve(d.load(), d.load().copies.single().handle))
    }
    private fun start(): Pair<ControllerFixture, Disk> {
        val d = Disk()
        val r = InboxRepository(d)
        val f = ControllerFixture(inbox = r)
        val record = Seeds.record(f.identity)
        f.store.commit(Seeds.empty(ControllerSection(listOf(record))), 0)
        r.configure(InboxProfile(HistoryNamespace.from(record)!!, "box", "P", "Box"))
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
            )
        )
        return f to d
    }

    @Test fun partialGapPagingKeepsAllRefreshDebtUntilACompleteBoundedRefresh() {
        val (f, _) = start()
        f.answer("relay.outbox.read", OutboxPage(emptyList(), 7).toWire())
        f.answer("relay.tasks.list", TaskPage(emptyList(), "cursor-1").toWire())
        f.answerError("relay.tasks.list", "unavailable")
        val owed = f.store.reread().snapshot.deliveries.single().obligations
        assertEquals(2, owed.size)
        assertEquals(setOf("HISTORY", "TASKS"), owed.map { it.kind }.toSet())
        f.controller.refreshInbox()
        f.run()
        f.answer("relay.outbox.read", OutboxPage(emptyList(), 7).toWire())
        f.answer("relay.tasks.list", TaskPage(emptyList(), null).toWire())
        f.answer("relay.conversations.list", Dto.page().toWire())
        assertTrue(f.store.reread().snapshot.deliveries.single().obligations.isEmpty())
        assertEquals("Current", f.controller.inboxStatus)
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
    }

    @Test fun exactExpiredPendingCopyRetiresLocalDebtWithoutAckOrReceiptRenewal() {
        val (f, d) = start()
        val body = final()
        f.answer("relay.outbox.read", OutboxPage(listOf(body), 0).toWire())
        f.transport.timeout(f.transport.next("relay.outbox.ack"))
        f.run()
        val before = f.store.reread().snapshot.deliveries.single().seen
        val age = d.load().copies.single().receivedAtMs
        f.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack"),
                serverTimeMs = Dto.SERVER_TIME + 2 * 86_400_000
            )
        )
        assertTrue(f.store.reread().snapshot.deliveries.single().pendingAcks.isEmpty())
        assertEquals(before, f.store.reread().snapshot.deliveries.single().seen)
        assertEquals(age, d.load().copies.single().receivedAtMs)
        assertEquals(body.expiresAtMs, d.load().copies.single().item.expiresAtMs)
        assertEquals(1, f.frames("relay.outbox.ack").size)
    }
}
