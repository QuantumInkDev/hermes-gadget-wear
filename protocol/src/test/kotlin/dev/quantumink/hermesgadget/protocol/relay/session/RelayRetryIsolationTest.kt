package dev.quantumink.hermesgadget.protocol.relay.session

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetryIsolationTest {
    private fun begin(): ControllerFixture = ControllerFixture().also {
        it.enable()
        it.connect()
        it.listAnd(Dto.summary(), Dto.summary(Dto.otherConversation))
    }

    private fun failedAttach(fx: ControllerFixture, busy: Boolean) {
        fx.controller.select(Dto.conversation.value)
        fx.run()
        val call = fx.transport.next("relay.conversation.attach")
        if (busy) fx.transport.fail(call, "busy") else fx.transport.timeout(call)
        fx.run()
    }

    private fun selectB(fx: ControllerFixture) {
        fx.controller.select(Dto.otherConversation.value)
        fx.run()
        fx.attachTo(Dto.binding(2, Dto.otherConversation))
    }

    private fun selectionSurvives(busy: Boolean) {
        val fx = begin()
        failedAttach(fx, busy)
        selectB(fx)
        fx.advanceTime(fx.timing.backoffBaseMs)
        // No stale A retry may become the newest flow while B is displayed.
        assertEquals(2, fx.frames("relay.conversation.attach").size)
        fx.controller.send("B only")
        fx.run()
        assertEquals(
            Dto.otherConversation.value,
            fx.store.reread().snapshot.ledger.turns.single().conversationId
        )
        assertEquals(
            Dto.binding(2, Dto.otherConversation).bindingId.value,
            fx.frames("relay.turn.submit").single().params["binding_id"]!!.jsonPrimitive.content
        )
    }

    @Test fun timeoutRetryCannotReplaceBOrRedirectItsNextSend() = selectionSurvives(false)

    @Test fun busyRetryCannotReplaceBOrRedirectItsNextSend() = selectionSurvives(true)

    private fun leaveSurvives(busy: Boolean) {
        val fx = begin()
        failedAttach(fx, busy)
        fx.controller.leave()
        fx.run()
        fx.advanceTime(fx.timing.backoffBaseMs)
        assertEquals(1, fx.frames("relay.conversation.attach").size)
        assertEquals(null, fx.ui.selected)
        var sent = true
        fx.controller.send("nowhere") { sent = it }
        fx.run()
        assertFalse(sent)
    }

    @Test fun timeoutRetryCannotAttachAfterLeave() = leaveSurvives(false)

    @Test fun busyRetryCannotAttachAfterLeave() = leaveSurvives(true)

    @Test fun returnedBindingMustNameTheStillSelectedConversation() {
        val fx = begin()
        fx.controller.select(Dto.otherConversation.value)
        fx.run()
        fx.answer("relay.conversation.attach", Dto.binding(1).toWire())
        assertTrue(fx.frames("relay.history.page").isEmpty())
        var sent = true
        fx.controller.send("B only") { sent = it }
        fx.run()
        assertFalse(sent)
    }

    private fun failedCreate(fx: ControllerFixture, busy: Boolean): ScriptedTransport.Sent {
        fx.controller.create("first")
        fx.run()
        val call = fx.transport.next("relay.conversation.create")
        if (busy) fx.transport.fail(call, "busy") else fx.transport.timeout(call)
        fx.run()
        return call
    }

    private fun discarded(busy: Boolean, replacement: Boolean) {
        val fx = begin()
        val old = failedCreate(fx, busy)
        fx.controller.discardCreate()
        fx.run()
        if (replacement) {
            fx.controller.create("second")
            fx.run()
        }
        fx.advanceTime(fx.timing.backoffBaseMs)
        assertEquals(if (replacement) 2 else 1, fx.frames("relay.conversation.create").size)
        if (replacement) {
            // Even an obsolete callback cannot clear or modify the new durable intent.
            fx.transport.fail(old, "invalid_request")
            fx.run()
            assertEquals(
                "second",
                fx.store.reread().section.enrollments.single().createIntent!!.title
            )
            val current = fx.transport.open("relay.conversation.create").single()
            fx.transport.timeout(current)
            fx.run()
            fx.advanceTime(fx.timing.backoffBaseMs)
            val retried = fx.frames("relay.conversation.create").last()
            assertEquals(current.params, retried.params)
        }
    }

    @Test fun timeoutThenDiscardAllocatesNothingLater() = discarded(false, false)

    @Test fun busyThenDiscardAllocatesNothingLater() = discarded(true, false)

    @Test fun timeoutThenDiscardThenNewCreateKeepsOnlyCurrentIntent() = discarded(false, true)

    @Test fun busyThenDiscardThenNewCreateKeepsOnlyCurrentIntent() = discarded(true, true)
}
