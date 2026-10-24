package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPage
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxCapacityRecoveryTest {
    private class Disk : InboxFile {
        var value = InboxSnapshot()
        override fun load() = InboxCodec.decode(InboxCodec.encode(value))
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            if (base != value.revision) return InboxWrite.Stale
            value = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private class HeldRemovals : RetainedHistory {
        override fun read(namespace: HistoryNamespace) = RetainedSnapshot(0)
        override fun absent(namespace: HistoryNamespace, conversationId: String?) = false
        override fun evict(namespace: HistoryNamespace, conversationId: String?): Unit =
            throw HistoryFailure(HistoryProblem.STORAGE)
        override fun capture(page: HistoryCapture): RetainedSnapshot = error("no capture")
        override fun review(
            namespace: HistoryNamespace,
            conversationId: String?
        ): HistoryEraseReview = error("no review")
        override fun reviewRetention(namespace: HistoryNamespace, days: Int): HistoryEraseReview =
            error("no review")
        override fun erase(review: HistoryEraseReview, namespace: HistoryNamespace) =
            error("no erase")
        override fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot =
            error("no retention change")
    }
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
    private fun item() = OutboxItem.Final(
        DeliveryId.of(id(9000))!!,
        OpaqueConversationRef.of("standalone")!!,
        OpaqueEventRef.of("new-event")!!,
        Dto.SERVER_TIME,
        Dto.SERVER_TIME + 86_400_000,
        OutboxPayload.FinalText("capacity recovered", null)
    )
    private class Run(val f: ControllerFixture, val disk: Disk, val before: RelayDocument)
    private fun start(eligible: Boolean, fullSections: Boolean = false): Run {
        val disk = Disk()
        val repository = InboxRepository(disk)
        val f = ControllerFixture(inbox = repository, retainedHistory = HeldRemovals())
        val seed = Seeds.withCaptureHistoryAndDelivery(f.identity)
        val n = HistoryNamespace.from(seed.section.enrollments.single())!!
        val records = seed.section.enrollments + if (fullSections) {
            (1..15).map {
                Seeds.record(
                    EnrollmentIdentity("wss://box-$it.example.invalid/gadget", "phone:other-$it"),
                    selected = true
                ).copy(selectionDeleted = true)
            }
        } else {
            emptyList()
        }
        val intents = if (fullSections) {
            (1..16).map {
                MetadataIntent(
                    n, ConversationId.of(id(100 + it))!!,
                    RequestId.of(
                        "01a14d67-a300-7000-8000-%012d".format(it)
                    )!!,
                    1, "pending-$it", null,
                    Dto.SERVER_TIME,
                    if (it ==
                        16
                    ) {
                        MetadataStatus.RETIRED
                    } else {
                        MetadataStatus.PENDING
                    },
                    if (it % 3 == 0) {
                        true
                    } else if (it % 3 == 1) {
                        false
                    } else {
                        null
                    }
                )
            }
        } else {
            emptyList()
        }
        val removals = if (fullSections) {
            (1..64).map { HistoryEviction(n, id(it), EvictionKind.CACHE_ONLY) }
        } else {
            emptyList()
        }
        val emergency = if (fullSections) {
            HistoryEviction(
                n,
                id(800),
                EvictionKind.DELETED
            )
        } else {
            null
        }
        val section = ControllerSection(records, intents, removals, emergency)
        val own = EnrollmentDelivery.begin(Seeds.scope(f.identity)).snapshot()
        val seen = (1..4096).map {
            SeenKeySnapshot(
                "canonical",
                Dto.otherConversation.value,
                id(10000 + it),
                if (eligible && it == 1) Dto.SERVER_TIME - 8 * 86_400_000L else Dto.SERVER_TIME
            )
        }
        val full = DeliverySnapshot(own.scope, 37, seen, own.obligations, emptyList())
        val s = seed.snapshot
        val initial = RelayDocument(
            section,
            RelayStateSnapshot(
                s.version,
                s.ledger,
                s.histories,
                s.deliveries + full,
                s.captures
            )
        )
        val encoded = SnapshotCodec.encode(initial) as EncodeResult.Encoded
        assertTrue(encoded.bytes.size < StoreBounds.PLAINTEXT_CAP_BYTES)
        assertTrue(f.store.commit(initial, 0) is StoreCommit.Committed)
        repository.configure(InboxProfile(n, "box", "Profile", "Box"))
        f.prepare()
        f.controller.observeInboxOnly(true)
        f.run()
        f.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS + listOf("relay.outbox.read", "relay.outbox.ack")
            )
        )
        return Run(f, disk, f.store.reread())
    }
    private fun sameUnrelated(
        before: RelayDocument,
        after: RelayDocument,
        identity: EnrollmentIdentity
    ) {
        val scope = EnrollmentDelivery.begin(Seeds.scope(identity)).snapshot().scope
        fun withoutOwn(s: RelayStateSnapshot) = RelayStateSnapshot(
            s.version,
            s.ledger,
            s.histories,
            s.deliveries.filterNot { it.scope == scope },
            s.captures
        )
        val a = SnapshotCodec.encode(
            RelayDocument(before.section, withoutOwn(before.snapshot))
        ) as EncodeResult.Encoded
        val b = SnapshotCodec.encode(
            RelayDocument(after.section, withoutOwn(after.snapshot))
        ) as EncodeResult.Encoded
        assertArrayEquals(a.bytes, b.bytes)
    }

    @Test fun eligiblePruneRestagesSameReplyAtLegalSixteenSixteenSixtyFourAndEmergency() {
        val r = start(true, true)
        val f = r.f
        assertEquals(16, r.before.section.enrollments.size)
        assertEquals(16, r.before.section.intents.size)
        assertEquals(64, r.before.section.evictions.size)
        assertNotNull(r.before.section.emergencyDeletion)
        assertEquals(15, r.before.section.enrollments.count { it.selectionDeleted })
        f.answer("relay.outbox.read", OutboxPage(listOf(item()), 37).toWire())
        val after = f.store.reread()
        val own = after.snapshot.deliveries.single {
            it.scope.enrollmentKey ==
                f.identity.enrollmentLabel
        }
        assertEquals(4096, own.seen.size)
        assertFalse(own.seen.any { it.event == id(10001) })
        assertTrue(
            own.seen.filter { it.kind == "canonical" }.all {
                it.committedAtMs ==
                    Dto.SERVER_TIME
            }
        )
        assertEquals(listOf(item().deliveryId.value), own.pendingAcks)
        assertEquals(1, f.frames("relay.outbox.read").size)
        assertEquals(1, f.frames("relay.outbox.ack").size)
        assertEquals(item().toWire(), r.disk.load().copies.single().item.toWire())
        sameUnrelated(r.before, after, f.identity)
        assertFalse(
            f.transport.methods().any {
                it in
                    listOf(
                        "relay.conversation.attach",
                        "relay.conversation.create",
                        "relay.turn.submit",
                        "relay.prompt.respond",
                        "relay.conversation.update"
                    )
            }
        )
    }

    @Test fun recentFullTableMakesNoRoomAndDoesNotSpinOrWriteUnchangedState() {
        val r = start(false)
        val f = r.f
        val bytes = f.store.bytes!!.copyOf()
        val attempts = f.store.attempts
        f.answer("relay.outbox.read", OutboxPage(listOf(item()), 37).toWire())
        assertEquals("Partial / outbox capacity", f.controller.inboxStatus)
        f.advanceTime(60_000)
        assertEquals(attempts, f.store.attempts)
        assertArrayEquals(bytes, f.store.bytes)
        assertTrue(r.disk.load().copies.isEmpty())
        assertEquals(1, f.frames("relay.outbox.read").size)
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
    }

    @Test fun failedPruneCannotDiscardSeenOrSendAndDeliberateRetryCanRecover() {
        val r = start(true)
        val f = r.f
        val bytes = f.store.bytes!!.copyOf()
        f.store.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
        f.answer("relay.outbox.read", OutboxPage(listOf(item()), 37).toWire())
        assertArrayEquals(bytes, f.store.bytes)
        assertTrue(r.disk.load().copies.isEmpty())
        assertTrue(f.frames("relay.outbox.ack").isEmpty())
        f.store.failAll = null
        f.controller.refreshInbox()
        f.run()
        f.answer("relay.outbox.read", OutboxPage(listOf(item()), 37).toWire())
        assertEquals(2, f.frames("relay.outbox.read").size)
        assertEquals(1, f.frames("relay.outbox.ack").size)
        sameUnrelated(r.before, f.store.reread(), f.identity)
    }
}
