package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [RelayController] over the real codec (in-memory bytes), the real ledger/assembly/delivery state and a
 * scripted transport whose replies are decoded by the real envelope and DTO decoders. Fixtures are synthetic wire shapes
 * built from the DTOs (the captured server sessions are replayed in [RelayReplaySessionTest]).
 */
class RelayControllerFlowTest {
    @Test fun enableIsExplicitAndStockStaysUntouchedUntilThen() {
        val fx = ControllerFixture()
        fx.prepare()
        assertEquals(RelayPhase.OFF, fx.ui.phase)
        assertFalse(fx.ui.enabled)
        assertEquals(0, fx.store.attempts)
        fx.controller.setEnabled(true)
        fx.run()
        assertTrue(fx.ui.enabled)
        assertEquals(1, fx.store.commits)
        // Enabling stored the controller section and an EMPTY aggregate with explicit (empty) captures, version 2.
        val doc = fx.store.reread()
        assertEquals(2, doc.snapshot.version)
        assertTrue(doc.snapshot.captures.records.isEmpty())
        assertTrue(doc.section.enrollments.single().enabled)
    }

    @Test fun firstNegotiationPinsTheProfileAndListsConversations() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        val record = fx.store.reread().section.enrollments.single()
        assertEquals(Dto.profile, record.pinnedProfileId)
        assertEquals(0, record.reviewEpoch)
        assertEquals(Standing.ACTIVE, record.standing)
        assertEquals(listOf("send:relay.conversations.list"), fx.log.filter { it.startsWith("send") })
        fx.listAnd(Dto.summary())
        assertEquals(RelayPhase.READY, fx.ui.phase)
        assertEquals(1, fx.ui.conversations.size)
        assertFalse(fx.ui.micAvailable)
    }

    @Test fun createIdentityAndTitleAreDurableBeforeTheFrameAndRetriedUnchanged() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd()
        fx.log.clear()
        fx.controller.create("Planning")
        fx.run()
        // The intent is committed BEFORE relay.conversation.create is written.
        assertEquals(listOf("commit", "send:relay.conversation.create"), fx.log)
        val first = fx.frames("relay.conversation.create").single()
        val requestId = first.params["request_id"]!!.jsonPrimitive.content
        assertEquals("Planning", first.params["title"]!!.jsonPrimitive.content)
        assertEquals(requestId, fx.store.reread().section.enrollments.single().createIntent!!.requestId.value)
        // The answer is lost with the connection; the SAME id and title are sent again after reconnecting.
        fx.disconnect()
        fx.connect()
        fx.listAnd()
        val second = fx.frames("relay.conversation.create").last()
        assertEquals(2, fx.frames("relay.conversation.create").size)
        assertEquals(requestId, second.params["request_id"]!!.jsonPrimitive.content)
        assertEquals("Planning", second.params["title"]!!.jsonPrimitive.content)
        // Never a second conversation: the retry that finally answers selects the one id.
        fx.answer("relay.conversation.create", Dto.summary(Dto.conversation, "Planning").toWire())
        assertEquals(Dto.conversation, fx.store.reread().section.enrollments.single().selectedConversationId)
        assertEquals(null, fx.store.reread().section.enrollments.single().createIntent)
        assertEquals(RelayPhase.ATTACHING, fx.ui.phase)
        assertEquals(2, fx.frames("relay.conversation.create").size)
    }

    @Test fun deliberateSelectAttachesThenReadsHistoryAndTasksAndShowsTheTranscript() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        val binding = Dto.binding(1, lastSequence = 7)
        fx.attachTo(
            binding,
            Dto.history(
                Dto.message(1, HistoryRole.USER, "first question"),
                Dto.message(2, HistoryRole.ASSISTANT, "first answer")
            )
        )
        assertEquals(RelayPhase.SELECTED, fx.ui.phase)
        assertEquals(listOf("first question", "first answer"), fx.ui.transcript.map { it.text })
        assertEquals(0, fx.ui.tasksWorking)
        // Attach, history, tasks — in that order — and the event baseline is the binding's last sequence.
        val order = fx.log.filter { it.startsWith("send:relay.conversation.attach") ||
            it.startsWith("send:relay.history") || it.startsWith("send:relay.tasks") }
        assertEquals(
            listOf("send:relay.conversation.attach", "send:relay.history.page", "send:relay.tasks.list"),
            order
        )
        assertTrue(fx.ui.canSend)
        // Only the conversation IDENTITY is stored, never the binding.
        val stored = fx.store.reread().section.enrollments.single()
        assertEquals(Dto.conversation, stored.selectedConversationId)
    }

    @Test fun aTypedTurnIsStoredBeforeItIsWrittenAndSettlesFromItsReceipt() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.attachTo(Dto.binding(1))
        fx.log.clear()
        var accepted = false
        fx.controller.send("hello relay") { accepted = it }
        fx.run()
        assertTrue(accepted)
        assertEquals(listOf("commit", "send:relay.turn.submit"), fx.log)
        val submit = fx.frames("relay.turn.submit").single()
        val requestId = RequestId.of(submit.params["request_id"]!!.jsonPrimitive.content)!!
        assertEquals("hello relay", submit.params["text"]!!.jsonPrimitive.content)
        // The stored turn is already marked as possibly sent (that is what persist-before-send means).
        val turn = fx.store.reread().snapshot.ledger.turns.single()
        assertTrue(turn.sent)
        assertEquals("hello relay", turn.text)
        fx.transport.reply(submit, Dto.receipt(requestId, ReceiptStatus.QUEUED).toWire())
        fx.run()
        assertEquals(TurnStatus.QUEUED, fx.ui.turns.single().status)
        // An admission receipt is not a final answer: the transcript is unchanged until the message arrives.
        assertTrue(fx.ui.transcript.isEmpty())
    }

    @Test fun eventsDeliverTheAnswerWithoutDuplicatesAndMoveOnlyTheCommittedCursor() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        val binding = Dto.binding(1, lastSequence = 4)
        fx.attachTo(binding)
        fx.advanceTime(fx.timing.pollIdleMs)
        val read = fx.transport.next("relay.events.read")
        assertEquals(4L, read.params["after_sequence"]!!.jsonPrimitive.content.toLong())
        assertEquals(binding.replayEpoch, read.params["replay_epoch"]!!.jsonPrimitive.content)
        val answer = Dto.event(5, binding, Dto.messageEvent(9, HistoryRole.ASSISTANT, "the answer", 5))
        fx.transport.reply(read, Dto.events(binding, answer).toWire())
        fx.run()
        assertEquals(listOf("the answer"), fx.ui.transcript.map { it.text })
        // The same page again (a repeated read) adds nothing and does not duplicate.
        fx.advanceTime(fx.timing.pollIdleMs)
        val again = fx.transport.next("relay.events.read")
        assertEquals(5L, again.params["after_sequence"]!!.jsonPrimitive.content.toLong())
        fx.transport.reply(again, Dto.events(binding).toWire())
        fx.run()
        assertEquals(1, fx.ui.transcript.size)
        assertNotNull(fx.store.reread().snapshot.deliveries.single().seen.singleOrNull())
    }
}
