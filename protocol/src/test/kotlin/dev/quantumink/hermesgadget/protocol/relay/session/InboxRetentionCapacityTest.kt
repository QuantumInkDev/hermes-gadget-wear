package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InboxRetentionCapacityTest {
    private val n = HistoryNamespace.from(Seeds.record(ControllerFixture().identity))!!
    private class Disk : InboxFile {
        var s = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(s))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != s.revision) return InboxWrite.Stale
            s = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private fun item(id: Int, text: String = "reply", event: String = "event-$id") =
        OutboxItem.Final(
            DeliveryId.of("00000000-0000-4000-8000-%012d".format(id))!!,
            OpaqueConversationRef.of(Dto.conversation.value)!!,
            OpaqueEventRef.of(event)!!,
            Dto.SERVER_TIME,
            Dto.SERVER_TIME + 86_400_000,
            OutboxPayload.FinalText(text, null)
        )

    @Test fun actualLegal4096RowsSixteenProfilesReachByteCapBeforeAnyStructuralLimit() {
        val profiles = (0..15).map { p ->
            val endpoint = "wss://box-$p.example.invalid/gadget"
            InboxProfile(
                n.copy(
                    endpointKey = EnrollmentRecord.endpointKey(endpoint),
                    endpointUrl = endpoint
                ),
                "box-$p",
                "Profile",
                "Box"
            )
        }
        fun candidate(length: Int) = InboxSnapshot(
            profiles = profiles,
            copies = (1..4096).map {
                InboxCopy(
                    profiles[it % 16].namespace,
                    item(it, "x".repeat(length)),
                    Dto.SERVER_TIME,
                    "%064x".format(it),
                    false
                )
            }
        )
        var lo = 0
        var hi = 4096
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            try {
                InboxCodec.encode(candidate(mid))
                lo = mid
            } catch (
                e: InboxFailure
            ) {
                assertEquals(InboxProblem.CAPACITY, e.problem)
                hi = mid
            }
        }
        val legal = candidate(lo)
        val bytes = InboxCodec.encode(legal)
        assertEquals(4096, legal.copies.size)
        assertEquals(16, legal.profiles.size)
        assertTrue(bytes.size <= InboxCodec.MAX_BYTES)
        assertTrue(InboxCodec.MAX_BYTES - bytes.size < 4096)
        assertEquals(4096, InboxCodec.decode(bytes).copies.size)
        try {
            InboxCodec.encode(candidate(hi))
            fail("exact next legal row-size must exceed byte cap")
        } catch (
            e: InboxFailure
        ) {
            assertEquals(InboxProblem.CAPACITY, e.problem)
        }
    }

    @Test fun pendingAckProtectsBodyAndBackwardClockCannotRenewAgeOrEventAlert() {
        val d = Disk()
        val r = InboxRepository(d)
        r.configure(InboxProfile(n, "profile", "Profile", "Box", completionAlerts = true))
        val at = Dto.SERVER_TIME
        assertTrue(r.accept(n, listOf(item(1)), at).single().alert)
        val saved = r.snapshot().copies.single()
        r.retention(0)
        r.sweep(n, at + 1000, listOf(item(1).deliveryId.value))
        assertEquals(saved.receivedAtMs, r.snapshot().copies.single().receivedAtMs)
        r.sweep(n, at + 1000, emptyList())
        assertTrue(r.snapshot().copies.isEmpty())
        assertEquals(1, r.snapshot().suppressions.size)
        assertNull(InboxRoute.resolve(r.snapshot(), saved.handle))
        val duplicate = r.accept(n, listOf(item(2, event = "event-1")), at - 1000, at)
        assertFalse(duplicate.single().alert)
        assertEquals(at, duplicate.single().receivedAtMs)
        r.sweep(n, at + 8 * 86_400_000L, emptyList())
        assertTrue(r.snapshot().copies.isEmpty())
        assertTrue(r.snapshot().suppressions.isEmpty())
        assertTrue(r.snapshot().profiles.single().highWaterMs >= at + 8 * 86_400_000L)
        assertEquals(at + 86_400_000, saved.item.expiresAtMs)
    }

    @Test fun sameEventAcrossNewDeliveryRetainsOneDurableAlertDecisionAfterOsFailure() {
        val d = Disk()
        val r = InboxRepository(d)
        var attempts = 0
        val f = ControllerFixture(inbox = r, inboxAlerts = {
            if (it.isNotEmpty()) {
                assertEquals(1, d.load().copies.count { it.alert })
                assertTrue(d.load().copies.isNotEmpty())
                attempts++
                error("OS publication refused after durable attempt reservation")
            }
        })
        val owner = HistoryNamespace.from(Seeds.record(f.identity))!!
        f.store.commit(Seeds.empty(ControllerSection(listOf(Seeds.record(f.identity)))), 0)
        r.configure(InboxProfile(owner, "profile", "Profile", "Box", completionAlerts = true))
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
            )
        )
        f.answer("relay.outbox.read", OutboxPage(listOf(item(1)), 0).toWire())
        assertEquals(1, attempts)
        assertEquals(1, f.frames("relay.outbox.ack").size)
        f.transport.timeout(f.transport.next("relay.outbox.ack"))
        f.run()
        f.controller.refreshInbox()
        f.run()
        f.answer(
            "relay.outbox.ack",
            kotlinx.serialization.json.buildJsonObject {
                put(
                    "acknowledged",
                    kotlinx.serialization.json.JsonArray(
                        listOf(kotlinx.serialization.json.JsonPrimitive(item(1).deliveryId.value))
                    )
                )
            }
        )
        f.answer("relay.outbox.read", OutboxPage(listOf(item(2, event = "event-1")), 0).toWire())
        assertEquals(1, attempts)
        assertEquals(2, d.load().copies.size)
        assertEquals(1, d.load().copies.count { it.alert })
        assertEquals(3, f.frames("relay.outbox.ack").size)
    }
}
