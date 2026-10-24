package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTerminalFix3Test {
    private fun full(h: DeliveryHarness) {
        val own = h.delivery.snapshot()
        val saved = DeliverySnapshot(
            own.scope,
            37,
            (1..4096).map {
                SeenKeySnapshot(
                    "canonical",
                    "00000000-0000-4000-8000-000000009000",
                    "00000000-0000-4000-8000-%012d".format(it),
                    123
                )
            },
            own.obligations,
            listOf("00000000-0000-4000-8000-000000090002")
        )
        h.delivery = (EnrollmentDelivery.restore(saved) as RestoreResult.Restored).value
    }
    private fun same(before: DeliverySnapshot, after: DeliverySnapshot) {
        assertEquals(before.scope, after.scope)
        assertEquals(before.seen, after.seen)
        assertEquals(before.gapBaseline, after.gapBaseline)
        assertEquals(before.obligations, after.obligations)
        assertEquals(before.pendingAcks, after.pendingAcks)
    }

    @Test fun recordedTerminalStagesAtFullCapacityButCommitCannotExpandAccounting() {
        val h = DeliveryHarness()
        full(h)
        val recorded = DeliveryHarness.events("ok-08")
        h.attach(h.bindingOf(recorded))
        val before = h.delivery.snapshot()
        val batch = h.staged(h.stage(h.planRead(), recorded))
        assertTrue(batch.conversationDeleted)
        same(before, h.delivery.snapshot())
        val committed = h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        assertTrue(committed is EventCommit.Backpressure)
        same(before, h.delivery.snapshot())
        assertTrue(h.apply(h.delivery.abortEvents(batch.batchId)))
        assertEquals(4096, h.delivery.seenCount)
    }

    @Test fun abortReadMatchesOnlyTheOutstandingRefWithoutChangingDurableState() {
        val h = DeliveryHarness()
        full(h)
        val recorded = DeliveryHarness.events("ok-07")
        h.attach(h.bindingOf(recorded))
        val before = h.delivery.snapshot()
        val read = h.planRead()
        val foreign = ReadRef(read.ref.serial, read.ref.correlation, read.ref.bindingSerial)
        assertFalse(h.apply(h.delivery.abortRead(foreign)))
        assertEquals(SessionRefusal.READ_OUTSTANDING, h.readRefusal())
        assertTrue(h.apply(h.delivery.abortRead(read.ref)))
        same(before, h.delivery.snapshot())
        val next = h.planRead()
        assertEquals(read.call.params, next.call.params)
        assertFalse(h.apply(h.delivery.abortRead(read.ref)))
    }
}
