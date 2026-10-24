package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.DeliveryId
import dev.quantumink.hermesgadget.protocol.relay.OpaqueConversationRef
import dev.quantumink.hermesgadget.protocol.relay.OpaqueEventRef
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InboxRepositoryTest {
    private val owner = HistoryNamespace.from(Seeds.record(ControllerFixture().identity))!!
    private fun item(n: Int = 1) = OutboxItem.Final(
        DeliveryId.of("00000000-0000-4000-8000-%012d".format(n))!!,
        OpaqueConversationRef.of(Dto.conversation.value)!!,
        OpaqueEventRef.of("opaque-event-$n")!!,
        1000,
        86_401_000,
        OutboxPayload.FinalText("retained reply", null)
    )
    private class Disk : InboxFile {
        var state = InboxSnapshot()
        var failure: InboxWrite? = null
        override fun load() = state
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite {
            failure?.let { return it }
            if (base != state.revision) return InboxWrite.Stale
            state = InboxCodec.decode(InboxCodec.encode(next))
            return InboxWrite.Committed
        }
    }
    private fun repo(disk: Disk): InboxRepository = InboxRepository(disk).also {
        it.configure(InboxProfile(owner, "box/profile", "Profile", "Box"))
    }

    @Test fun exactDuplicateKeepsReceiptRouteAndOneNotificationDecision() {
        val d = Disk()
        val r = repo(d)
        val first = r.accept(owner, listOf(item()), 2000)
        assertEquals(1, first.size)
        val saved = r.snapshot().copies.single()
        assertTrue(r.accept(owner, listOf(item()), 3000).isEmpty())
        val again = r.snapshot().copies.single()
        assertEquals(2000, again.receivedAtMs)
        assertEquals(saved.handle, again.handle)
        assertEquals(item(), again.item)
        assertTrue(r.confirmCopies(owner, listOf(item().deliveryId.value)))
        assertFalse(r.confirmCopies(owner, listOf("00000000-0000-4000-8000-999999999999")))
    }

    @Test fun failedOrUncertainWritesNeverConfirmCopyDurability() {
        for (failure in listOf(
            InboxWrite.Refused,
            InboxWrite.Failed(false),
            InboxWrite.Failed(true),
            InboxWrite.Stale
        )) {
            val d = Disk()
            val r = repo(d)
            d.failure = failure
            try {
                r.accept(owner, listOf(item()), 2000)
                fail("expected exact storage refusal")
            } catch (e: InboxFailure) {
                assertEquals(InboxProblem.WRITE, e.problem)
            }
            assertTrue(d.state.copies.isEmpty())
            d.failure = null
            assertEquals(1, r.accept(owner, listOf(item()), 2001).size)
        }
    }

    @Test fun sameIdsAcrossBoxesHaveIndependentCopiesAndRoutes() {
        val d = Disk()
        val r = repo(d)
        val other = owner.copy(
            endpointUrl = "wss://other.example.invalid/gadget",
            endpointKey = EnrollmentRecord.endpointKey("wss://other.example.invalid/gadget")
        )
        r.configure(InboxProfile(other, "other/profile", "Profile", "Other box"))
        r.accept(owner, listOf(item()), 2000)
        r.accept(other, listOf(item()), 2000)
        assertEquals(2, r.snapshot().copies.size)
        assertEquals(2, r.snapshot().copies.map { it.handle }.toSet().size)
        r.evict(owner, null)
        assertEquals(other, r.snapshot().copies.single().namespace)
    }

    @Test fun opaqueUuidLookingFinalIsStillNotCanonical() {
        val d = Disk()
        val r = repo(d)
        r.accept(owner, listOf(item()), 2000)
        assertNull(r.snapshot().copies.single().canonicalConversation)
    }

    @Test fun unsupportedOrCorruptBytesRefuseWithoutAnEmptyFallback() {
        val unsupported = InboxCodec.encode(InboxSnapshot()).also {
            java.nio.ByteBuffer.wrap(it).putInt(4, 99)
        }
        for ((bytes, expected) in listOf(
            byteArrayOf() to InboxProblem.CORRUPT,
            unsupported to InboxProblem.UNSUPPORTED
        )) {
            try {
                InboxCodec.decode(bytes)
                fail("expected codec refusal")
            } catch (e: InboxFailure) {
                assertEquals(expected, e.problem)
            }
        }
    }

    @Test fun unreadDecisionSurvivesRestartAndDuplicateWithoutChangingPayloadOrAlert() {
        val d = Disk()
        val r = repo(d)
        r.accept(owner, listOf(item()), 2000)
        val first = r.snapshot().copies.single()
        assertTrue(first.unread)
        r.markRead(owner, first.handle)
        val restart = InboxRepository(d)
        assertFalse(restart.snapshot().copies.single().unread)
        restart.accept(owner, listOf(item()), 3000)
        val again = restart.snapshot().copies.single()
        assertFalse(again.unread)
        assertEquals(first.item.toWire(), again.item.toWire())
        assertEquals(first.receivedAtMs, again.receivedAtMs)
        assertEquals(first.handle, again.handle)
        assertEquals(first.alert, again.alert)
    }
}
