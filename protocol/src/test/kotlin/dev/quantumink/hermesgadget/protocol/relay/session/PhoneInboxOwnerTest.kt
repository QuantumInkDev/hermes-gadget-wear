package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInboxOwnerTest {
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        var refusal: InboxWrite? = null
        override fun load() = InboxCodec.decode(InboxCodec.encode(state))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            refusal?.let { return it }
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private fun start(disk: Disk): ControllerFixture {
        val f = ControllerFixture(inbox = InboxRepository(disk))
        f.enable()
        f.connect() // foreground review pins this enrollment first
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
            )
        )
        return f
    }
    private fun copy() = OutboxItem.Final(
        DeliveryId.of("00000000-0000-4000-8000-000000000111")!!,
        OpaqueConversationRef.of(Dto.conversation.value)!!,
        OpaqueEventRef.of("opaque-event")!!,
        Dto.SERVER_TIME,
        Dto.SERVER_TIME + 86_400_000,
        OutboxPayload.FinalText("durable reply", null)
    )
    private fun page() = OutboxPage(listOf(copy()), 0).toWire()

    @Test fun copyAndCompleteAggregatePrecedeAckAndHeadlessNeverAttaches() {
        val d = Disk()
        val f = start(d)
        f.answer("relay.outbox.read", page())
        assertEquals(
            "durable reply",
            (d.state.copies.single().item.payload as OutboxPayload.FinalText).text
        )
        val ack = f.transport.next("relay.outbox.ack")
        val delivery = f.store.reread().snapshot.deliveries.single()
        assertEquals(listOf(copy().deliveryId.value), delivery.pendingAcks)
        assertEquals(
            copy().deliveryId.value,
            ack.params["delivery_ids"]!!.jsonArray.single().jsonPrimitive.content
        )
        assertFalse(
            f.transport.methods().any {
                it in
                    listOf(
                        "relay.conversation.attach",
                        "relay.conversation.create",
                        "relay.turn.submit",
                        "relay.prompt.respond"
                    )
            }
        )
    }

    @Test fun everyUnconfirmedAggregateResultRetainsCopyAndOwedAckWithoutSend() {
        for (failure in listOf(
            StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false),
            StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true),
            StoreCommit.Stale,
            StoreCommit.Refused(EncodeRefusal.TOO_MANY, "test")
        )) {
            val d = Disk()
            val f = start(d)
            f.store.failAll = failure
            f.answer("relay.outbox.read", page())
            assertEquals(1, d.state.copies.size)
            assertTrue(f.frames("relay.outbox.ack").isEmpty())
            assertTrue(f.ui.unsaved)
            f.store.failAll = null
            f.controller.refreshInbox()
            f.run()
            assertTrue(f.frames("relay.outbox.ack").isNotEmpty())
        }
    }

    @Test fun refusedCopyNeverCommitsDeliveryOrSendsAckAndLateReplyCannotPublish() {
        val d = Disk()
        val f = start(d)
        d.refusal = InboxWrite.Failed(true)
        f.answer("relay.outbox.read", page())
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
        assertTrue(f.store.reread().snapshot.deliveries.isEmpty())
        d.refusal = null
        f.controller.refreshInbox()
        f.run()
        val held = f.transport.next("relay.outbox.read")
        f.controller.hide()
        f.run()
        f.transport.reply(held, page())
        f.run()
        assertTrue(d.state.copies.isEmpty())
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
    }
}
