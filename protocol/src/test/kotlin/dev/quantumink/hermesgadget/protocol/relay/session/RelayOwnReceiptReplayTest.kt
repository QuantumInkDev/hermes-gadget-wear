package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New real registered producer captures, replayed through the production controller and DirectConnection. */
class RelayOwnReceiptReplayTest {
    private fun replay(scenario: String) {
        OwnReplayHarness(scenario).use { h ->
            h.prepareAndEnable()
            h.connect()
            assertEquals(RelayPhase.READY, h.ui.phase)
            h.controller.create("Planning")
            h.settle()
            assertEquals(RelayPhase.SELECTED, h.ui.phase)
            val admitted = scenario in setOf("found", "missing-sdk", "incompatible")
            if (admitted) {
                h.server.holdNextResponse(
                    "relay.turn.submit"
                )
            } else {
                h.server.swallowNext("relay.turn.submit")
            }
            h.controller.send("first question") {}
            h.settle()
            val original = h.requests("relay.turn.submit").single()["params"]!!.jsonObject
            val before = h.store.reread().snapshot.ledger.turns.single()
            h.drop()
            assertEquals(TurnStatus.UNCERTAIN, h.ui.turns.single().status)
            h.connection.connect()
            h.settle()
            // A real behavioral RED on the old app: it sends legacy where the actual producer capture expects own.
            assertTrue(h.server.mismatches.toString(), h.server.mismatches.isEmpty())
            assertTrue(h.server.extra.toString(), h.server.extra.isEmpty())
            val method = if (scenario ==
                "incompatible"
            ) {
                "relay.turn.receipt"
            } else {
                "relay.turn.receipt.own"
            }
            val lookup = h.requests(method).single()["params"]!!.jsonObject
            assertEquals(setOf("conversation_id", "request_id"), lookup.keys)
            assertEquals(original["request_id"], lookup["request_id"])
            assertEquals(
                h.session.conversationId,
                lookup["conversation_id"]!!.jsonPrimitive.content
            )
            assertEquals(h.session.steps.size, h.server.consumedCount)
            val methods = h.server.seen.map { it["method"]!!.jsonPrimitive.content }
            val ownIndex = methods.indexOf(method)
            assertEquals(
                listOf("relay.info", "relay.conversations.list", "relay.conversation.attach"),
                methods.subList(ownIndex - 3, ownIndex)
            )
            val submits = h.requests("relay.turn.submit")
            assertEquals(if (scenario == "absent") 2 else 1, submits.size)
            assertEquals(submits.size, h.durableSubmits)
            val after = h.store.reread().snapshot.ledger.turns.single()
            assertEquals(before.expiresAtMs, after.expiresAtMs)
            if (scenario == "absent") {
                val retry = submits.last()["params"]!!.jsonObject
                for (field in listOf(
                    "request_id",
                    "text",
                    "expires_at_ms"
                )) {
                    assertEquals(original[field], retry[field])
                }
                assertTrue(original["binding_id"] != retry["binding_id"])
                assertEquals("first question", after.text)
            }
            if (scenario == "found" || scenario == "incompatible") {
                assertEquals(TurnStatus.FINISHED_COMPLETED, h.ui.turns.single().status)
            }
            if (scenario == "denied") {
                assertEquals(RelayPhase.STOPPED, h.ui.phase)
                assertEquals(
                    Standing.STOPPED,
                    h.store.reread().section.enrollments.single().standing
                )
                assertEquals(null, after.text)
                assertFalse(h.ui.canSend)
                assertEquals(method, methods.last())
            }
            assertTrue(
                h.requests(
                    if (scenario ==
                        "incompatible"
                    ) {
                        "relay.turn.receipt.own"
                    } else {
                        "relay.turn.receipt"
                    }
                ).isEmpty()
            )
            // Late lost response from a retired socket, addressed to its original correlation, cannot release new work.
            if (admitted) {
                val statuses = h.ui.turns.map { it.status }
                h.server.release(h.server.heldIndexes().single(), onPeer = h.fixture.latest)
                h.settle()
                assertEquals(statuses, h.ui.turns.map { it.status })
                assertEquals(1, h.requests("relay.turn.submit").size)
            }
        }
    }

    @Test(timeout = 90000)
    fun defaultReadFreshOwnAbsenceRetriesTheImmutableTurn() = replay("absent")

    @Test(timeout = 90000)
    fun admittedLostResponseOwnReceiptNeverSubmitsAgain() = replay("found")

    @Test(timeout = 90000)
    fun missingImmutableSdkJournalRefusesWithoutDuplicateSubmit() = replay("missing-sdk")

    @Test(timeout = 90000)
    fun proofWithdrawnAfterInfoNeverFallsBackToLegacy() = replay("proof-unavailable")

    @Test(timeout = 90000)
    fun actualCurrentDenialStopsAndEvicts() = replay("denied")

    @Test(timeout = 90000)
    fun oldIdentityNeverRevivesTheOriginalTurn() = replay("old-id")

    @Test(timeout = 90000)
    fun retainedExpiredIntentNeverRevivesTheOriginalTurn() = replay("expired-intent")

    @Test(timeout = 90000)
    fun incompatibleCoreHidesOwnAndLegacyFoundStillWorks() = replay("incompatible")
}
