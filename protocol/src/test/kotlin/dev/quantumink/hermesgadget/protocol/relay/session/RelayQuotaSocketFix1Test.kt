package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual DirectConnection/RelayChannel/Peer acceptance; this cache double only keeps accepted old obligations pending.
 * The encrypted cache, exact bytes and pending typed/voice/metadata preservation are independently exercised in mobile.
 */
class RelayQuotaSocketFix1Test {
    private val pendingCache = object : RetainedHistory {
        override fun read(namespace: HistoryNamespace) = RetainedSnapshot(0)
        override fun capture(page: HistoryCapture) =
            RetainedSnapshot(1, threads = listOf(page.thread))
        override fun absent(namespace: HistoryNamespace, conversationId: String?) = false
        override fun evict(namespace: HistoryNamespace, conversationId: String?): Unit =
            throw HistoryFailure(HistoryProblem.STORAGE)
        override fun review(
            namespace: HistoryNamespace,
            conversationId: String?
        ): HistoryEraseReview = error("unused")
        override fun reviewRetention(namespace: HistoryNamespace, days: Int): HistoryEraseReview =
            error("unused")
        override fun erase(review: HistoryEraseReview, namespace: HistoryNamespace) =
            error("unused")
        override fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot =
            error("unused")
    }
    private fun setup(h: Fix1SocketHarness, full: Boolean) {
        val profile = ProfileId.of(
            h.session.steps.first {
                it.method == "relay.info"
            }.response["result"]!!.jsonObject["profile_id"]!!.jsonPrimitive.content
        )!!
        val record = EnrollmentRecord.fresh(
            h.identity.endpointUrl,
            h.identity.enrollmentLabel
        ).copy(enabled = true, pinnedProfileId = profile)
        val ns = HistoryNamespace.from(record)!!
        val old = if (full) {
            (1..64).map {
                HistoryEviction(
                    ns,
                    "00000000-0000-4000-8000-%012d".format(it),
                    EvictionKind.CACHE_ONLY
                )
            }
        } else {
            emptyList()
        }
        assertTrue(
            h.store.commit(
                Seeds.empty(ControllerSection(listOf(record), evictions = old)),
                0
            ) is StoreCommit.Committed
        )
        h.prepareAndEnable()
        h.connect()
        h.controller.create("Planning")
        h.settle()
        assertEquals(RelayPhase.SELECTED, h.ui.phase)
        assertTrue(h.ui.canSend)
    }

    @Test fun atCapacityDenialPreventsAnyFutureActualSocketSubmitOrAck() {
        Fix1SocketHarness(
            "integrated",
            resourceRoot = "/relay-task4-v1",
            retained = pendingCache
        ).use { h ->
            setup(h, true)
            h.server.holdNextResponse("relay.conversation.update")
            h.controller.editMetadata(h.session.conversationId, "  Trip \u200b plans  ", null)
            h.settle()
            val sent = h.requests("relay.conversation.update").single()
            val response = buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", sent["id"]!!.jsonPrimitive.content)
                put("error", buildJsonObject { put("code", "permission_denied") })
            }
            h.fixture.peers.last().deliver(RelayVectors.asciiJson(response))
            var escaped: Throwable? = null
            try {
                h.settle()
            } catch (failure: Throwable) {
                escaped = failure
            }
            val before = h.fixture.peers.sumOf { it.relayTexts.size }
            h.server.swallowNext("relay.turn.submit")
            var accepted = true
            h.controller.send("must not reach socket") { accepted = it }
            h.settle()
            assertFalse("at-cap denial escaped=$escaped and future send was accepted", accepted)
            assertEquals(before, h.fixture.peers.sumOf { it.relayTexts.size })
            assertEquals(0, h.durableSubmits)
            assertEquals(0, h.requests("relay.events.ack").size)
            assertEquals(Standing.STOPPED, h.store.reread().section.enrollments.single().standing)
            assertEquals(64, h.store.reread().section.evictions.size)
            assertEquals(null, escaped)
            assertEquals(1, h.durableMetadata)
        }
    }

    @Test fun aFreshCurrentSocketStillAcceptsADurableTypedTurn() {
        Fix1SocketHarness(
            "integrated",
            resourceRoot = "/relay-task4-v1",
            retained = pendingCache
        ).use { h ->
            setup(h, false)
            h.server.swallowNext("relay.turn.submit")
            var accepted = false
            h.controller.send("valid current typed control") { accepted = it }
            h.settle()
            assertTrue(accepted)
            assertEquals(1, h.durableSubmits)
            assertEquals(1, h.requests("relay.turn.submit").size)
        }
    }
}
