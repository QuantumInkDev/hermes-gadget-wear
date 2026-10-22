package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** History assembly against the RECORDED `relay.history.page` / `relay.existing.history.page` frames. */
class HistoryAssemblyVectorTest {
    private val relay = RelayMethod.HISTORY_PAGE
    private val existing = RelayMethod.EXISTING_HISTORY_PAGE

    @Test fun aRecordedTwoPageChainAssemblesInAuthenticatedPageOrderAndAdvancesOnlyOnCommit() {
        val first = HistoryHarness.captured(relay, "ok-13")
        val second = HistoryHarness.captured(relay, "ok-12")
        // The two captured exchanges really are one chain: the second request carries the first answer's cursor.
        val issued = first.result["next_cursor"]!!.jsonPrimitive.content
        assertEquals(issued, HistoryHarness.requestedCursor(second))
        assertNull(HistoryHarness.requestedCursor(first))
        val h = HistoryHarness(HistoryHarness.scopeOf(first))

        val plan1 = h.plan(limit = 1)
        assertTrue(plan1.request.isFirstPage)
        assertEquals(RelayMethod.HISTORY_PAGE, plan1.call.method)
        assertEquals(
            mapOf(
                "conversation_id" to h.scope.conversation.conversationId.value,
                "limit" to "1"
            ),
            plan1.call.params.mapValues { (it.value as JsonPrimitive).content }
        )
        val one = h.accepted(h.accept(plan1, HistoryPage.decode(first.result)))
        assertEquals(1, one.emitted.size)
        assertEquals(ListingCompleteness.INCOMPLETE, one.completeness)
        // Staged only: nothing advanced yet.
        assertEquals(0, h.assembly.committedThrough)
        assertNull(h.assembly.resumePoint)

        val plan2 = h.plan(limit = 1)
        assertEquals(issued, plan2.request.cursor)
        assertEquals(issued, plan2.call.params["cursor"]!!.jsonPrimitive.content)
        val two = h.accepted(h.accept(plan2, HistoryPage.decode(second.result)))
        assertEquals(ListingCompleteness.COMPLETE, two.completeness)
        val ordered = h.assembly.stagedMessages
        assertEquals(listOf(1L, 2L), ordered.map { it.ordinal })
        assertEquals("4ac08203-7fd5-541b-98cb-4999a750101d", ordered[0].messageId.value)
        assertEquals("2ad869d9-5394-561b-8b3e-cb552f955453", ordered[1].messageId.value)
        assertEquals(listOf("equal", "equal"), ordered.map { it.text })

        // Commit the first: the resume point is the clean boundary after page one, i.e. the cursor it issued.
        val committed = h.apply(h.assembly.commit(1)) as HistoryAssembly.CommitOutcome.Committed
        assertEquals(1, committed.count)
        assertEquals(issued, h.assembly.resumePoint!!.cursor)
        assertFalse(h.assembly.resumePoint!!.atEnd)
        h.apply(h.assembly.commit(2))
        assertTrue(h.assembly.resumePoint!!.atEnd)
        assertEquals(ListingStatus.COMPLETE, h.assembly.provenance.status)
        assertEquals(ListingStart.BEGINNING, h.assembly.provenance.start)
        assertEquals(1, h.assembly.provenance.generation)
    }

    @Test fun anEmptyCursoredPageIsProgressNotTheEndAndTheRecordedExpiryInvalidatesHonestly() {
        val empty = HistoryHarness.captured(relay, "ok-08")
        val expired = HistoryHarness.captured(relay, "error-snapshot-required-04")
        val cursor = empty.result["next_cursor"]!!.jsonPrimitive.content
        assertEquals(cursor, HistoryHarness.requestedCursor(expired))
        assertEquals("snapshot_required", expired.error["code"]!!.jsonPrimitive.content)
        val h = HistoryHarness(HistoryHarness.scopeOf(empty))

        val outcome = h.accepted(h.feed(HistoryPage.decode(empty.result)))
        assertTrue(outcome.emitted.isEmpty())
        assertFalse(outcome.listingComplete)
        assertEquals(ListingCompleteness.INCOMPLETE, outcome.completeness)
        assertEquals(ListingStatus.ACTIVE, h.assembly.status)
        val next = h.plan()
        assertEquals(cursor, next.request.cursor) // the empty page's cursor IS the next position
        // The recorded server answer to that very cursor: it expired.
        val invalidated = h.error(next, RelayErrorCode.SNAPSHOT_REQUIRED)
        assertEquals(
            HistoryInvalidation.SNAPSHOT_REQUIRED,
            (invalidated as PageOutcome.Invalidated).reason
        )
        assertEquals(ListingStatus.INVALIDATED, h.assembly.status)
        assertTrue(
            h.effects.contains(
                HistoryEffect.RebuildRequired(h.scope, HistoryInvalidation.SNAPSHOT_REQUIRED)
            )
        )
        assertEquals(HistoryPlanRefusal.NOT_ACTIVE, h.planRefusal())
        // A new beginning is claimed only by an explicit restart that records why.
        h.assembly = h.assembly.restart()
        val provenance = h.assembly.provenance
        assertEquals(2, provenance.generation)
        assertEquals(ListingStart.REBUILD, provenance.start)
        assertEquals(HistoryInvalidation.SNAPSHOT_REQUIRED, provenance.after)
        assertEquals(ListingStatus.ACTIVE, provenance.status)
        assertTrue(h.plan().request.isFirstPage)
    }

    @Test fun aRecordedEmptyHistoryWithNoCursorIsACompleteEmptyListing() {
        val none = HistoryHarness.captured(relay, "ok-07")
        val h = HistoryHarness(HistoryHarness.scopeOf(none))
        val outcome = h.accepted(h.feed(HistoryPage.decode(none.result)))
        assertEquals(ListingCompleteness.COMPLETE, outcome.completeness)
        assertTrue(outcome.emitted.isEmpty())
        assertEquals(ListingStatus.COMPLETE, h.assembly.status)
    }

    @Test fun recordedContinuationFragmentsOfAMessageThatNeverBeganAreNeverCalledAMessage() {
        val case = HistoryHarness.captured(relay, "ok-14")
        val page = HistoryPage.decode(case.result)
        assertEquals(listOf(16, 17, 18), page.items.map { it.segmentIndex })
        assertEquals(listOf(8193, 8190, 5544), page.items.map { it.text.toByteArray().size })
        val h = HistoryHarness(HistoryHarness.scopeOf(case))
        val outcome = h.feed(page)
        assertEquals(HistoryInvalidation.UNEXPECTED_FRAGMENT, h.invalidated(outcome))
        assertTrue(h.assembly.stagedMessages.isEmpty())
        assertFalse(h.assembly.hasOpenMessage)
    }

    @Test fun aNineteenSegmentMultibyteMessageAssemblesAcrossPagesAndIsNotCappedAtEightKiB() {
        // The last page is RECORDED: segments 16..18 of 19, the first one 8,193 bytes (a boundary moved past a
        // continuation byte). The first sixteen segments are a constructed model that reuses the recorded text.
        val case = HistoryHarness.captured(relay, "ok-14")
        val tail = HistoryPage.decode(case.result)
        val model = tail.items.first()
        val filler = tail.items[1].text
        val prefix = (0 until 16).map {
            HistoryMessage(model.messageId, model.role, filler, model.createdAtMs, it, 19)
        }
        val h = HistoryHarness(HistoryHarness.scopeOf(case))
        val cursor = HistoryHarness.requestedCursor(case)!!
        val first = h.accepted(h.feed(HistoryPage(prefix, cursor)))
        assertTrue(first.emitted.isEmpty())
        assertTrue(h.assembly.hasOpenMessage) // nothing is called complete early
        assertEquals(0, h.assembly.stagedMessages.size)
        val plan = h.plan()
        assertEquals(cursor, plan.request.cursor) // the recorded request cursor
        val done = h.accepted(h.accept(plan, tail))
        assertEquals(ListingCompleteness.COMPLETE, done.completeness)
        val message = done.emitted.single()
        assertEquals(19, message.segmentCount)
        val expectedBytes = 16 * 8190 + 8193 + 8190 + 5544
        assertEquals(expectedBytes, message.text.toByteArray().size)
        assertTrue(
            "a whole canonical message is not an 8192-byte object",
            expectedBytes > 8_192 * 18
        )
        assertEquals(filler.repeat(16) + tail.items.joinToString("") { it.text }, message.text)
    }

    @Test fun existingHistoryUsesItsOwnMethodAndIsIndependentOfTheRelayStreamWithTheSameIds() {
        val case = HistoryHarness.captured(existing, "ok-04")
        val page = HistoryPage.decode(case.result)
        val relayScope = HistoryHarness.scopeOf(case, HistorySource.RELAY)
        val existingScope = HistoryHarness.scopeOf(case, HistorySource.EXISTING)
        assertNotEquals(relayScope, existingScope)
        val e = HistoryHarness(existingScope)
        val plan = e.plan()
        assertEquals(RelayMethod.EXISTING_HISTORY_PAGE, plan.call.method)
        assertEquals(6, e.accepted(e.accept(plan, page)).emitted.size)
        val r = HistoryHarness(relayScope)
        val relayPlan = r.plan()
        assertEquals(RelayMethod.HISTORY_PAGE, relayPlan.call.method)
        // Nothing the existing stream learned is visible to the relay stream with the very same message ids.
        assertEquals(0, r.assembly.knownCount)
        assertEquals(6, e.assembly.knownCount)
        val again = r.accepted(r.accept(relayPlan, page))
        assertEquals(6, again.emitted.size)
    }

    @Test fun recordedErrorsMapToTheContractsActionsNeverToSuccess() {
        val cases = mapOf(
            "error-temporarily-unavailable-06" to RelayErrorCode.TEMPORARILY_UNAVAILABLE,
            "error-projection-too-large-03" to RelayErrorCode.PROJECTION_TOO_LARGE,
            "error-permission-denied-02" to RelayErrorCode.PERMISSION_DENIED,
            "error-not-found-01" to RelayErrorCode.NOT_FOUND,
            "error-stale-epoch-05" to RelayErrorCode.STALE_EPOCH
        )
        for ((suffix, code) in cases) {
            val case = HistoryHarness.captured(relay, suffix)
            assertEquals(suffix, code.wire, case.error["code"]!!.jsonPrimitive.content)
            val h = HistoryHarness(HistoryHarness.scopeOf(case))
            val plan = h.plan()
            val outcome = h.error(plan, code)
            when (code) {
                RelayErrorCode.TEMPORARILY_UNAVAILABLE -> {
                    assertEquals(code, (outcome as PageOutcome.RetrySameRequest).code)
                    assertEquals(ListingStatus.ACTIVE, h.assembly.status)
                    assertTrue(h.plan().request.isFirstPage) // the same position is requested again
                }
                RelayErrorCode.PROJECTION_TOO_LARGE ->
                    assertEquals(HistoryInvalidation.SERVER_REFUSED, h.invalidated(outcome))
                RelayErrorCode.PERMISSION_DENIED -> {
                    assertEquals(HistoryInvalidation.SCOPE_REVOKED, h.invalidated(outcome))
                    assertTrue(
                        h.effects.contains(
                            HistoryEffect.EvictScopeContent(h.scope.conversation.enrollment)
                        )
                    )
                }
                RelayErrorCode.NOT_FOUND -> {
                    assertEquals(code, (outcome as PageOutcome.Failed).code)
                    assertEquals(ListingStatus.ACTIVE, h.assembly.status)
                }
                else -> assertEquals(HistoryInvalidation.SNAPSHOT_REQUIRED, h.invalidated(outcome))
            }
        }
    }
}
