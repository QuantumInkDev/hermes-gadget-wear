package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applied server evidence must survive ordinary writer handover; disk failure is not process
 * loss.
 */
class InboxHandoverTest {
    private fun observed(): ControllerFixture {
        val f = ControllerFixture()
        f.enable()
        f.connect()
        f.listAnd(Dto.summary())
        f.controller.select(Dto.conversation.value)
        f.run()
        f.attachTo(Dto.binding(1))
        f.controller.send("retain this admitted observation")
        f.run()
        val submit = f.frames("relay.turn.submit").single()
        val id = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        f.store.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
        f.transport.reply(submit, Dto.receipt(id, ReceiptStatus.QUEUED).toWire())
        f.run()
        assertTrue(f.ui.unsaved)
        return f
    }

    @Test fun differentIdentityCannotDiscardAnAppliedUnsavedObservation() {
        val f = observed()
        val bytes = f.store.bytes!!.copyOf()
        val requests = f.transport.sent.size
        f.controller.prepare(
            EnrollmentIdentity("wss://other.example.invalid/gadget", "phone:other"),
            55
        )
        f.run()
        assertEquals("handover must defer under the original owner", f.identity, f.ui.identity)
        assertTrue(f.ui.unsaved)
        assertEquals(1, f.ui.turns.size)
        assertArrayEquals(bytes, f.store.bytes)
        assertEquals(requests, f.transport.sent.size)
    }

    @Test fun sameIdentityHideAndReturnRetainsAppliedUnsavedState() {
        val f = observed()
        val bytes = f.store.bytes!!.copyOf()
        f.controller.hide()
        f.run()
        f.controller.prepare(f.identity, 56)
        f.run()
        assertEquals(f.identity, f.ui.identity)
        assertTrue(f.ui.unsaved)
        assertEquals(1, f.ui.turns.size)
        assertArrayEquals(bytes, f.store.bytes)
    }
}
