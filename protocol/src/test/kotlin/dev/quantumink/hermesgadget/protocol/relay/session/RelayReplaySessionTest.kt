package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPLAY of sessions captured from the registered adapter (`relay-task3-v1`): the server side is the recording, the client side
 * is the production [RelayController] over the production [dev.quantumink.hermesgadget.protocol.DirectConnection] (real
 * handshake, router, bounds, generations) over an in-memory socket factory. Every request the client writes must equal the
 * recorded request (everything but the correlation id). This is not a live integration.
 */
class RelayReplaySessionTest {
    private fun ReplayHarness.assertClean() {
        assertTrue(server.mismatches.toString(), server.mismatches.isEmpty())
    }

    private fun ReplayHarness.start() {
        prepareAndEnable()
        connect()
        assertEquals(RelayPhase.READY, ui.phase)
        controller.create("Planning")
        settle()
        assertEquals(RelayPhase.SELECTED, ui.phase)
        assertClean()
    }

    private fun ReplayHarness.send(text: String) {
        controller.send(text) {}
        settle()
    }

    @Test(timeout = 90000) fun deliberateCreateAttachAndTwoTurnsOfOneLineageThenReconnectReceiptFirstAfterALostResponse() {
        ReplayHarness("task3-lineage.json").use { h ->
            h.start()
            val conversation = h.session.conversationId
            assertEquals(conversation, h.store.reread().section.enrollments.single().selectedConversationId!!.value)
            // The create carried the durable id the entropy seam minted and the immutable title.
            assertEquals(h.session.mintPlan[0], h.requests("relay.conversation.create").single()["params"].toString().let {
                Regex("\"request_id\":\"([^\"]+)\"").find(it)!!.groupValues[1]
            })
            // Turn 1: stored first, written, admitted; the events carry the answer AND a gap, so the session attaches again.
            h.send("first question")
            assertEquals(TurnStatus.QUEUED, h.ui.turns.single().status)
            h.advanceController(h.timing.pollActiveMs)
            h.assertClean()
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.single().status)
            assertEquals(listOf("first question", "first answer", "first answer"), h.ui.transcript.map { it.text })
            // Turn 2 in the SAME conversation: a second admission of the same lineage.
            h.send("second question")
            h.advanceController(h.timing.pollActiveMs)
            h.assertClean()
            assertEquals(6, h.ui.transcript.size)
            assertEquals(2, h.ui.turns.count { it.status == TurnStatus.FINISHED_COMPLETED })
            // A reconnect (a new transport): the whole lineage is in the history read after the fresh attach.
            h.reconnect()
            h.assertClean()
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertEquals(
                listOf("first question", "second question"),
                h.ui.transcript.filter { it.role == dev.quantumink.hermesgadget.protocol.relay.HistoryRole.USER }.map { it.text }
            )
            // Turn 3: admitted and executed by the server, but its response is LOST.
            h.server.holdNextResponse("relay.turn.submit")
            h.send("third question")
            val held = h.server.heldIndexes().single()
            // The socket drops before the response arrives.
            h.drop()
            assertEquals(TurnStatus.UNCERTAIN, h.ui.turns.last().status)
            // The reconnect attaches, then asks for the receipt FIRST: no second submit exists anywhere.
            h.connection.connect()
            h.settle()
            h.assertClean()
            assertEquals(3, h.requests("relay.turn.submit").size)
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.last().status)
            assertEquals(9, h.ui.transcript.size)
            val receiptAt = h.server.seen.indexOfLast { it["method"]!!.jsonPrimitive.content == "relay.turn.receipt" }
            val attachAt = h.server.seen.indexOfLast { it["method"]!!.jsonPrimitive.content == "relay.conversation.attach" }
            val historyAt = h.server.seen.indexOfLast { it["method"]!!.jsonPrimitive.content == "relay.history.page" }
            assertTrue(attachAt < receiptAt && receiptAt < historyAt)
            // The lost response now arrives on the NEW socket under an old correlation: ignored, nothing changes.
            val before = h.ui.turns.map { it.status }
            h.server.release(held, onPeer = h.fixture.latest)
            h.settle()
            assertEquals(before, h.ui.turns.map { it.status })
            assertEquals(9, h.ui.transcript.size)
            assertTrue(h.server.extra.isEmpty())
            h.assertClean()
        }
    }

    @Test(timeout = 90000) fun aTurnThatNeverArrivedIsDeniedThenProbedAndSubmittedAgainWithTheSameIdTextAndExpiry() {
        ReplayHarness("task3-absent-denied.json").use { h ->
            h.start()
            h.server.swallowNext("relay.turn.submit")
            h.send("first question")
            h.drop()
            assertEquals(TurnStatus.UNCERTAIN, h.ui.turns.single().status)
            h.connection.connect()
            h.settle()
            h.assertClean()
            // receipt (denied) -> a read probe of the same conversation -> the SAME request again
            val methods = h.server.seen.map { it["method"]!!.jsonPrimitive.content }.takeLast(7)
            assertEquals(
                listOf(
                    "relay.conversations.list", "relay.conversation.attach", "relay.turn.receipt",
                    "relay.history.page", "relay.turn.submit", "relay.history.page"
                ),
                methods.takeLast(6)
            )
            val submits = h.requests("relay.turn.submit")
            assertEquals(2, submits.size)
            val first = submits[0]["params"] as kotlinx.serialization.json.JsonObject
            val second = submits[1]["params"] as kotlinx.serialization.json.JsonObject
            for (member in listOf("request_id", "text", "expires_at_ms")) assertEquals(member, first[member], second[member])
            assertNotEquals(first["binding_id"], second["binding_id"])
            assertEquals(h.session.mintPlan[1], first["request_id"]!!.jsonPrimitive.content)
            assertEquals(1, h.ui.turns.size)
            assertEquals(TurnStatus.QUEUED, h.ui.turns.single().status)
            // The scope was NOT stopped by the denial: the probe proved read authority.
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertEquals(Standing.ACTIVE, h.store.reread().section.enrollments.single().standing)
            h.advanceController(h.timing.pollActiveMs)
            h.assertClean()
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.single().status)
            assertTrue(h.ui.transcript.any { it.text == "first answer" })
        }
    }

    @Test(timeout = 90000) fun aTurnThatNeverArrivedIsNotFoundWithTheSharedReceiptGrantAndSubmittedAgainWithoutAProbe() {
        ReplayHarness("task3-absent-not-found.json").use { h ->
            h.start()
            h.server.swallowNext("relay.turn.submit")
            h.send("first question")
            h.reconnect()
            h.assertClean()
            val methods = h.server.seen.map { it["method"]!!.jsonPrimitive.content }
            assertEquals(
                listOf("relay.turn.receipt", "relay.turn.submit", "relay.history.page"),
                methods.takeLast(3)
            )
            val submits = h.requests("relay.turn.submit")
            assertEquals(2, submits.size)
            assertEquals(submits[0]["params"]!!.toString().substringAfter("request_id"), submits[1]["params"]!!.toString().substringAfter("request_id").let { it })
            assertEquals(TurnStatus.QUEUED, h.ui.turns.single().status)
            assertTrue(h.requests("relay.history.page").none { (it["params"] as kotlinx.serialization.json.JsonObject)["limit"]!!.jsonPrimitive.content == "1" })
        }
    }

    @Test(timeout = 90000) fun anEventGapAndAnOverflowEachRefreshByAFreshAttachAndNothingIsSubmittedAgain() {
        ReplayHarness("task3-gap.json").use { h ->
            h.start()
            h.send("first question")
            h.advanceController(h.timing.pollActiveMs)
            h.assertClean()
            val attaches = h.requests("relay.conversation.attach").size
            assertEquals(2, attaches) // the select and the re-attach after the gap
            assertEquals(3, h.ui.transcript.size)
            // The overflow: the next idle poll returns snapshot_required with a gap and the binding is retired.
            h.advanceController(h.timing.pollIdleMs)
            h.assertClean()
            assertEquals(3, h.requests("relay.conversation.attach").size)
            assertEquals(1, h.requests("relay.turn.submit").size)
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertEquals(3, h.ui.transcript.size)
            assertTrue(h.server.extra.isEmpty())
        }
    }

    @Test(timeout = 90000) fun responsesMayArriveOutOfOrderAndTheEndStateIsTheSame() {
        ReplayHarness("task3-lineage.json").use { h ->
            h.start()
            h.send("first question")
            h.advanceController(h.timing.pollActiveMs)
            h.send("second question")
            h.advanceController(h.timing.pollActiveMs)
            h.assertClean()
            // On the reconnect the list answer is held back until attach and history have been answered.
            h.server.holdNextResponse("relay.conversations.list")
            h.reconnect()
            h.assertClean()
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertEquals(6, h.ui.transcript.size)
            val held = h.server.heldIndexes().single()
            h.server.release(held)
            h.settle()
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertEquals(1, h.ui.conversations.size)
            assertEquals(6, h.ui.transcript.size)
            assertFalse(h.ui.turns.any { it.status == TurnStatus.UNCERTAIN })
        }
    }

    @Test(timeout = 90000) fun afterProcessLossNothingIsSentByItselfAndTheUserResumesWithTheReceiptFirst() {
        val store = MemoryStore()
        var resumeAt = 0
        var minted = 0
        val first = ReplayHarness("task3-lineage.json", store)
        first.use { h ->
            h.start()
            h.send("first question")
            h.advanceController(h.timing.pollActiveMs)
            h.send("second question")
            h.advanceController(h.timing.pollActiveMs)
            h.reconnect()
            h.server.holdNextResponse("relay.turn.submit")
            h.send("third question")
            h.drop()
            assertEquals(TurnStatus.UNCERTAIN, h.ui.turns.last().status)
            h.assertClean()
            resumeAt = h.server.consumedCount
            minted = 4
        }
        // A NEW process: new controller, new connection, same encrypted state. It restores with the fresh server time of the
        // first info, lists, and then waits: no attach, no receipt, no submit until the user chooses the conversation.
        ReplayHarness("task3-lineage.json", store, startAt = resumeAt, mintStart = minted).use { h ->
            h.controller.prepare(h.identity)
            h.settle()
            assertTrue(h.ui.enabled)
            h.connect()
            assertEquals(RelayPhase.READY, h.ui.phase)
            assertEquals(h.session.conversationId, h.ui.resumable!!.id)
            assertTrue(h.requests("relay.conversation.attach").isEmpty())
            assertTrue(h.requests("relay.turn.receipt").isEmpty())
            assertTrue(h.requests("relay.turn.submit").isEmpty())
            val view = h.ui.turns.last()
            assertEquals(TurnStatus.UNCERTAIN, view.status)
            assertEquals("third question", view.preview)
            h.controller.select(h.session.conversationId)
            h.settle()
            h.assertClean()
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            assertTrue(h.requests("relay.turn.submit").isEmpty())
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.last().status)
            assertEquals(9, h.ui.transcript.size)
        }
    }

    @Test(timeout = 90000) fun aTimeoutOnALiveSocketReadsInfoAttachesAgainAndAsksTheReceiptBeforeAnythingElse() {
        ReplayHarness("task3-timeout.json").use { h ->
            h.start()
            h.server.holdNextResponse("relay.turn.submit")
            h.send("first question")
            val held = h.server.heldIndexes().single()
            // The socket stays up but the response never comes: the call times out and the turn is uncertain.
            h.advanceConnection(16_000)
            assertEquals(TurnStatus.UNCERTAIN, h.ui.turns.single().status)
            assertEquals(1, h.requests("relay.turn.submit").size)
            // After the backoff the controller reads info again, attaches again (a binding NEWER than the uncertainty)
            // and asks the receipt first: still exactly one submit.
            h.advanceController(1_000)
            h.assertClean()
            val methods = h.server.seen.map { it["method"]!!.jsonPrimitive.content }
            assertEquals(
                listOf(
                    "relay.info", "relay.conversations.list", "relay.conversation.attach", "relay.turn.receipt",
                    "relay.history.page"
                ),
                methods.takeLast(5)
            )
            assertEquals(1, h.requests("relay.turn.submit").size)
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.single().status)
            assertEquals(3, h.ui.transcript.size)
            // The response finally arrives under its old correlation: it names no outstanding call and changes nothing.
            h.server.release(held)
            h.settle()
            assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.single().status)
            assertEquals(3, h.ui.transcript.size)
            h.assertClean()
        }
    }
}
