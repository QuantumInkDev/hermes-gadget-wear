package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotSelectionFix2Test {
    private val identity = ControllerFixture().identity
    private val record = Seeds.record(identity, selected = true)
    private val owner = HistoryNamespace.from(record)!!
    private fun bytes(section: ControllerSection) =
        (SnapshotCodec.encode(Seeds.empty(section)) as EncodeResult.Encoded).bytes
    private fun section(bytes: ByteArray) =
        (SnapshotCodec.decode(bytes) as DecodeResult.Decoded).document.section
    private fun legacy3(current: ByteArray, count: Int) = (
        current.copyOfRange(
            0,
            current.size - 4 - count
        ) +
            current.copyOfRange(current.size - 4, current.size)
        ).also { it[5] = 3 }
    private fun intent(core: Boolean?, status: MetadataStatus = MetadataStatus.PENDING) =
        MetadataIntent(
            owner, Dto.otherConversation,
            RequestId.of(
                "01a14d67-a300-7000-8000-000000000001"
            )!!,
            1, "immutable", null, Dto.SERVER_TIME, status, core
        )

    @Test fun everyEnrollmentHasAFixedWidthExactDeletionRepresentationAtAllCollectionLimits() {
        val records = (1..16).map {
            Seeds.record(
                EnrollmentIdentity(identity.endpointUrl, "phone:owned-$it"),
                selected = true
            )
        }
        val old = (1..64).map {
            HistoryEviction(
                owner,
                "00000000-0000-4000-8000-%012d".format(it),
                EvictionKind.CACHE_ONLY
            )
        }
        val emergency = HistoryEviction(owner, Dto.otherConversation.value, EvictionKind.DELETED)
        val intents = (1..16).map {
            intent(
                null
            ).copy(
                conversationId = dev.quantumink.hermesgadget.protocol.relay.ConversationId.of(
                    "00000000-0000-4000-9000-%012d".format(it)
                )!!,
                requestId = RequestId.of("01a14d67-a300-7000-8000-%012d".format(it))!!
            )
        }
        val before = ControllerSection(records, intents, old, emergency)
        val marked =
            ControllerSection(
                records.map {
                    it.copy(selectionDeleted = true)
                },
                intents,
                old,
                emergency
            )
        assertEquals(bytes(before).size, bytes(marked).size)
        assertEquals(marked, section(bytes(marked)))
        val exact =
            HistoryEviction(
                HistoryNamespace.from(records.first())!!,
                Dto.conversation.value,
                EvictionKind.DELETED
            )
        val cleared = marked.cleared(exact)
        assertFalse(cleared.enrollments.first().selectionDeleted)
        assertEquals(null, cleared.enrollments.first().selectedConversationId)
        assertTrue(cleared.enrollments.drop(1).all { it.selectionDeleted })
        assertEquals(old, cleared.evictions)
        assertEquals(emergency, cleared.emergencyDeletion)
        assertTrue(
            marked.cleared(exact.copy(kind = EvictionKind.CACHE_ONLY)).enrollments.all {
                it.selectionDeleted
            }
        )
    }

    @Test fun immutableMetadataRetirementCannotExpandItsReservedResultBytes() {
        val before = bytes(ControllerSection(listOf(record), listOf(intent(null))))
        for (core in listOf(null, false, true)) {
            val expected =
                ControllerSection(listOf(record), listOf(intent(core, MetadataStatus.RETIRED)))
            val after = bytes(expected)
            assertEquals(before.size, after.size)
            assertEquals(expected, section(after))
        }
    }

    @Test fun legacyTwoAndThreeKeepMetadataAndNeverInventSelectionDeletion() {
        for (format in listOf(2, 3)) {
            val expected = ControllerSection(listOf(record), listOf(intent(true)))
            var old = legacy3(bytes(expected), 1)
            if (format ==
                2
            ) {
                old =
                    (
                        old.copyOfRange(
                            0,
                            old.size - 5
                        ) + old.copyOfRange(old.size - 4, old.size)
                        ).also {
                        it[5] =
                            2
                    }
            }
            assertEquals(expected, section(old))
            // Actual legacy layout: absent core result has only the presence byte, not format4's reserved false value.
            val valueAt = old.size - 4 - (if (format == 3) 1 else 0) - 2 - 1
            old[valueAt - 1] = 0
            val absent = old.copyOfRange(0, valueAt) + old.copyOfRange(valueAt + 1, old.size)
            assertEquals(ControllerSection(listOf(record), listOf(intent(null))), section(absent))
        }
    }

    @Test fun markedIdentityRequiresItsExactNamespaceAndTarget() {
        var refused = false
        try {
            record.copy(selectedConversationId = null, selectionDeleted = true)
        } catch (
            _: IllegalArgumentException
        ) {
            refused =
                true
        }
        assertTrue(refused)
        val marked = ControllerSection(listOf(record.copy(selectionDeleted = true)))
        val wrong = HistoryEviction(owner, Dto.otherConversation.value, EvictionKind.DELETED)
        assertEquals(marked, marked.cleared(wrong))
    }

    @Test fun invalidOrUnboundSelectionFlagsFailClosedOnDecode() {
        val marked = bytes(ControllerSection(listOf(record.copy(selectionDeleted = true))))
        marked[marked.size - 5] = 2
        assertTrue(SnapshotCodec.decode(marked) is DecodeResult.Rejected)
        for (unbound in listOf(
            record.copy(selectedConversationId = null),
            record.copy(pinnedProfileId = null)
        )) {
            val malformed = bytes(ControllerSection(listOf(unbound)))
            malformed[malformed.size - 5] = 1
            assertTrue(SnapshotCodec.decode(malformed) is DecodeResult.Rejected)
        }
    }

    private fun restoreReservation(refusal: StoreCommit, promoted: Boolean = false) {
        val store = MemoryStore()
        store.bytes = legacy3(bytes(ControllerSection(listOf(record))), 1)
        store.initialised = true
        store.sequence = 1
        val f = ControllerFixture(store = store)
        f.prepare()
        f.connect()
        f.listAnd(Dto.summary())
        val before = store.bytes!!.copyOf()
        store.failAll = refusal
        store.landOnFailure = promoted
        f.controller.select(Dto.conversation.value)
        f.run()
        assertTrue(
            "legacy selection cannot subscribe before format4 reservation commits",
            f.frames("relay.conversation.attach").isEmpty()
        )
        assertFalse(f.ui.canSend)
        if (!promoted) assertArrayEquals(before, store.bytes)
        assertEquals(
            Dto.conversation,
            store.reread().section.enrollments.single().selectedConversationId
        )
        store.failAll = null
        f.controller.select(Dto.conversation.value)
        f.run()
        assertEquals(1, f.frames("relay.conversation.attach").size)
        f.attachTo(Dto.binding(1))
        assertTrue(f.ui.canSend)
        assertEquals(4, store.bytes!![5].toInt())
    }

    @Test fun failedLegacyReservationRefusesSubscriptionAndRecovers() =
        restoreReservation(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun refusedLegacyReservationRefusesSubscriptionAndRecovers() =
        restoreReservation(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun staleLegacyReservationRefusesSubscriptionAndRecovers() =
        restoreReservation(StoreCommit.Stale)

    @Test fun uncertainLegacyReservationDoesNotAdmitBeforeConfirmation() =
        restoreReservation(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true), true)

    @Test fun deterministicAggregateRefusalWithdrawsAnAlreadyActiveObserver() {
        val f = ControllerFixture()
        f.enable()
        f.connect()
        f.listAnd(Dto.summary())
        f.controller.select(Dto.conversation.value)
        f.run()
        f.attachTo(Dto.binding(1))
        assertTrue(f.ui.canSend)
        f.controller.refreshConversations()
        f.run()
        val delayed = f.transport.next("relay.conversations.list")
        var accepted = false
        f.controller.send("valid current pending turn") { accepted = it }
        f.run()
        assertTrue(accepted)
        val submit = f.transport.next("relay.turn.submit")
        val id = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        f.store.failAll = StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document")
        f.transport.reply(submit, Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        f.run()
        assertTrue(f.ui.unsaved)
        assertFalse(f.ui.canSend)
        assertEquals(1, f.frames("relay.turn.submit").size)
        var again = true
        f.controller.send("no future send after unstorable observation") { again = it }
        f.run()
        assertFalse(again)
        assertEquals(1, f.frames("relay.turn.submit").size)
        f.transport.fail(delayed, "permission_denied", true)
        f.run()
        assertEquals(Standing.ACTIVE, f.store.reread().section.enrollments.single().standing)
        assertFalse(f.ui.canSend)
    }
}
