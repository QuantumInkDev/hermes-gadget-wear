package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxWriterEpochTest {
    private class Disk : InboxFile {
        var s = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(s))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != s.revision) return InboxWrite.Stale
            s = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private val item = OutboxItem.Final(
        DeliveryId.of("00000000-0000-4000-8000-000000009999")!!,
        OpaqueConversationRef.of("opaque-final")!!,
        OpaqueEventRef.of("event")!!,
        Dto.SERVER_TIME,
        Dto.SERVER_TIME + 86_400_000,
        OutboxPayload.FinalText("retained", null)
    )

    @Test fun inFlightCommitSettlesBeforePreparedHandoverButWithdrawnOwnerCannotPublishOrAck() {
        val writer = AtomicLong()
        val disk = Disk()
        val f = ControllerFixture(inbox = InboxRepository(disk), writerEpoch = writer::get)
        val record = Seeds.record(f.identity)
        f.store.commit(Seeds.empty(ControllerSection(listOf(record))), 0)
        InboxRepository(
            disk
        ).configure(InboxProfile(HistoryNamespace.from(record)!!, "box", "P", "Box"))
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        val info = Dto.info(
            methods =
            Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
        )
        f.connect(info)
        val publications = f.states.size
        f.store.afterCommit = {
            writer.incrementAndGet()
            f.store.afterCommit = {}
        }
        f.answer("relay.outbox.read", OutboxPage(listOf(item), 0).toWire())
        assertEquals("withdrawn writer must not publish after IO", publications, f.states.size)
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
        assertEquals(
            listOf(item.deliveryId.value),
            f.store.reread().snapshot.deliveries.single().pendingAcks
        )
        assertEquals(item.toWire(), disk.load().copies.single().item.toWire())
        f.controller.prepare(f.identity, 77)
        f.run()
        f.connect(info)
        assertEquals(1, f.frames("relay.outbox.ack").size)
        assertFalse(f.ui.unsaved)
    }

    @Test fun oldScheduledPollCannotPublishWriteOrSendBeforeReplacementPreparation() {
        val writer = AtomicLong()
        val f = ControllerFixture(writerEpoch = writer::get)
        f.enable()
        f.connect()
        f.listAnd(Dto.summary())
        f.controller.select(Dto.conversation.value)
        f.run()
        f.attachTo(Dto.binding(1))
        val publications = f.states.size
        val attempts = f.store.attempts
        val sends = f.transport.sent.size
        assertTrue(f.scheduler.pendingTimers > 0)
        writer.incrementAndGet()
        f.advanceTime(10_000)
        assertEquals(publications, f.states.size)
        assertEquals(attempts, f.store.attempts)
        assertEquals(sends, f.transport.sent.size)
        f.controller.prepare(f.identity, 78)
        f.run()
        assertEquals(78, f.ui.foregroundSession)
    }
}
