package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review I2: the in-order continuation fragments of a message that was refused locally (over the segment or byte
 * quota) are cursor progress, tracked with a bounded expected index, so a long refused message can no longer stall
 * the whole listing. Out-of-order, missing or mismatched fragments still invalidate it; no silent completeness.
 *
 * The streams are source-derived constructed inputs, not captured traffic: `relay.history.page` accepts `limit` 1,
 * consumes at most that many fragments per page and bounds one page envelope at 65,536 bytes (SDK `relay_history.py`),
 * so a legal `limit` 1 stream puts exactly one fragment on each cursored page. Every page built here is checked
 * against the byte and depth bounds of its `relay.response` envelope.
 */
class HistoryRefusedProgressTest {
    private val f = HistoryHarness.Companion
    private val segmentLimit = StateLimits.MAX_MESSAGE_SEGMENTS

    /** A refused message declares more than the local segment quota. */
    private val declared = segmentLimit + 72

    private fun text(bytes: Int) = "x".repeat(bytes)

    private fun depth(element: JsonElement): Int = when (element) {
        is JsonObject -> 1 + (element.values.maxOfOrNull { depth(it) } ?: 0)
        is JsonArray -> 1 + (element.maxOfOrNull { depth(it) } ?: 0)
        else -> 1
    }

    /** The whole `relay.response` frame of [page] must fit the SDK page bound and the parser depth. */
    private fun legal(page: HistoryPage): HistoryPage {
        val frame = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("relay.response"),
                "v" to JsonPrimitive(1),
                "id" to JsonPrimitive("c-000001"),
                "result" to page.toWire()
            )
        ).toString()
        assertTrue(frame.toByteArray().size <= RelayLimits.HISTORY_PAGE_BYTES)
        assertTrue(depth(page.toWire()) + 1 <= RelayLimits.DEPTH)
        return page
    }

    private fun one(next: String?, item: HistoryMessage) = legal(f.page(next, item))

    private fun feedOne(h: HistoryHarness, next: String?, item: HistoryMessage): PageOutcome =
        h.accept(h.plan(1), one(next, item))

    private fun segment(n: Int, index: Int, count: Int = declared, bytes: Int = 8_192) =
        f.fragment(n, index, count, text(bytes))

    /** Feeds fragments `from until to` of message [n], one per cursored page, cursors numbered from [cursor]. */
    private fun feedRange(
        h: HistoryHarness,
        n: Int,
        from: Int,
        to: Int,
        cursor: Int,
        count: Int = declared,
        bytes: Int = 8_192
    ): Int {
        var next = cursor
        for (index in from until to) {
            val outcome = feedOne(h, f.cursor(next++), segment(n, index, count, bytes))
            assertTrue("fragment $index: $outcome", outcome is PageOutcome.Accepted)
        }
        return next
    }

    @Test fun aRefusedMessageOverSixtyFourLimitOnePagesProgressesAndEndsWithLocalRefusals() {
        val h = HistoryHarness()
        val next = feedRange(h, 1, 0, declared, 1)
        assertTrue(
            "more cursored pages than the stall limit",
            declared > StateLimits.MAX_CONSECUTIVE_EMPTY_PAGES
        )
        assertFalse(h.assembly.hasOpenMessage)
        assertEquals(RefusalReason.TOO_MANY_SEGMENTS, h.assembly.refusedMessages.single().reason)
        val last = h.accepted(feedOne(h, null, f.single(2, "after")))
        assertEquals(listOf("after"), last.emitted.map { it.text })
        assertTrue(last.listingComplete)
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, last.completeness)
        assertEquals(ListingStatus.COMPLETE, h.assembly.status)
        assertTrue(next > declared)
    }

    @Test fun everyInOrderContinuationOfARefusedMessageCountsAsNewProgressNotADuplicate() {
        val h = HistoryHarness()
        for (index in 0 until 4) {
            val outcome = h.accepted(feedOne(h, f.cursor(index + 1), segment(1, index)))
            assertEquals("fragment $index", 1, outcome.newFragments)
            assertEquals("fragment $index", 0, outcome.duplicateFragments)
        }
    }

    @Test fun aSkippedFragmentOfARefusedMessageInvalidatesTheListing() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 2, 1)
        val outcome = feedOne(h, f.cursor(3), segment(1, 3))
        assertEquals(HistoryInvalidation.MISSING_SEGMENT, h.invalidated(outcome))
    }

    @Test fun anOutOfOrderFirstContinuationOfARefusedMessageInvalidatesTheListing() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 1, 1)
        val outcome = feedOne(h, f.cursor(2), segment(1, 5))
        assertEquals(HistoryInvalidation.MISSING_SEGMENT, h.invalidated(outcome))
    }

    @Test fun aRepeatedAlreadySeenFragmentOfARefusedMessageIsAnIdempotentDuplicate() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 3, 1)
        val outcome = h.accepted(feedOne(h, f.cursor(4), segment(1, 1)))
        assertEquals(1, outcome.duplicateFragments)
        assertEquals(0, outcome.newFragments)
        // Order is still tracked: the next NEW fragment is index 3.
        assertEquals(1, h.accepted(feedOne(h, f.cursor(5), segment(1, 3))).newFragments)
    }

    @Test fun mismatchedMetadataOnARefusedMessageInvalidatesTheListing() {
        val role = HistoryHarness()
        feedRange(role, 1, 0, 1, 1)
        val otherRole = f.fragment(1, 1, declared, text(8_192), role = HistoryRole.USER)
        assertEquals(
            HistoryInvalidation.MISMATCHED_METADATA,
            role.invalidated(feedOne(role, f.cursor(2), otherRole))
        )
        val time = HistoryHarness()
        feedRange(time, 1, 0, 1, 1)
        val otherTime = f.fragment(1, 1, declared, text(8_192), createdAtMs = 9_999L)
        assertEquals(
            HistoryInvalidation.MISMATCHED_METADATA,
            time.invalidated(feedOne(time, f.cursor(2), otherTime))
        )
        val count = HistoryHarness()
        feedRange(count, 1, 0, 1, 1)
        assertEquals(
            HistoryInvalidation.MISMATCHED_METADATA,
            count.invalidated(feedOne(count, f.cursor(2), segment(1, 1, declared + 1)))
        )
    }

    @Test fun anotherMessageBeginningBeforeARefusedOneIsCompleteInvalidatesTheListing() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 3, 1)
        val outcome = feedOne(h, f.cursor(4), f.single(2, "intruder"))
        assertEquals(HistoryInvalidation.INTERLEAVED, h.invalidated(outcome))
    }

    @Test fun aListingEndingInsideARefusedMessageIsIncompleteNotCompleteWithRefusals() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 3, 1)
        val outcome = feedOne(h, null, segment(1, 3))
        assertEquals(HistoryInvalidation.INCOMPLETE_AT_END, h.invalidated(outcome))
    }

    @Test fun aByteRefusedMessageKeepsItsOrderAfterTheRefusalToo() {
        // 16 KiB fragments pass the 1 MiB message quota at the 65th one (index 64): refused mid-message.
        val count = 100
        val bytes = 16_384
        val h = HistoryHarness()
        val next = feedRange(h, 1, 0, 66, 1, count, bytes)
        assertEquals(RefusalReason.TOO_MANY_BYTES, h.assembly.refusedMessages.single().reason)
        assertFalse(h.assembly.hasOpenMessage)
        val skipped = feedOne(h, f.cursor(next), segment(1, 67, count, bytes))
        assertEquals(HistoryInvalidation.MISSING_SEGMENT, h.invalidated(skipped))
        val ok = HistoryHarness()
        val after = feedRange(ok, 1, 0, count, 1, count, bytes)
        val done = ok.accepted(feedOne(ok, null, f.single(2, "after")))
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, done.completeness)
        assertTrue(after > count)
    }

    @Test fun pagesOfOnlyRepeatedFragmentsStillStallTheListing() {
        val h = HistoryHarness()
        var next = feedRange(h, 1, 0, declared, 1)
        for (page in 0 until StateLimits.MAX_CONSECUTIVE_EMPTY_PAGES) {
            val outcome = feedOne(h, f.cursor(next++), segment(1, 3))
            assertTrue("repeat $page: $outcome", outcome is PageOutcome.Accepted)
        }
        val outcome = feedOne(h, f.cursor(next), segment(1, 3))
        assertEquals(HistoryInvalidation.STALLED, h.invalidated(outcome))
    }

    @Test fun aRepeatedCursorStillMeansNoProgress() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 2, 1)
        val outcome = feedOne(h, f.cursor(2), segment(1, 2))
        assertEquals(HistoryInvalidation.NO_PROGRESS, h.invalidated(outcome))
    }

    @Test fun aPageEndingInsideARefusedMessageIsNotACleanResumePoint() {
        val h = HistoryHarness()
        h.accepted(feedOne(h, f.cursor(1), f.single(1, "kept")))
        feedRange(h, 2, 0, 70, 2)
        assertEquals(1, h.assembly.stagedMessages.size)
        h.apply(h.assembly.commit(1))
        assertEquals(f.cursor(1), h.assembly.snapshot().resumeCursor)
    }

    @Test fun afterARestoreAHalfReadRefusedMessageIsReadAgainFromItsFirstFragmentAndProgresses() {
        val before = HistoryHarness()
        before.accepted(feedOne(before, f.cursor(1), f.single(1, "kept")))
        feedRange(before, 2, 0, 70, 2)
        before.apply(before.assembly.commit(1))
        val saved = before.assembly.snapshot()
        assertEquals(f.cursor(1), saved.resumeCursor)
        val restored = (HistoryAssembly.restore(saved) as RestoreResult.Restored).value
        val h = HistoryHarness(before.scope)
        h.assembly = restored
        val next = feedRange(h, 2, 0, declared, 2)
        val done = h.accepted(feedOne(h, null, f.single(3, "after")))
        assertEquals(listOf("after"), done.emitted.map { it.text })
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, done.completeness)
        assertNotNull(h.assembly.refusedMessages.singleOrNull())
        assertTrue(next > declared)
    }

    @Test fun afterAnAbortAHalfReadRefusedMessageIsReadAgainAndProgresses() {
        val h = HistoryHarness()
        feedRange(h, 1, 0, 70, 1)
        h.apply(h.assembly.abortUncommitted())
        feedRange(h, 1, 0, declared, 1)
        val done = h.accepted(feedOne(h, null, f.single(2, "after")))
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, done.completeness)
    }

    @Test fun theRefusedQuotaIsAPermanentPolicyInvalidationThatARebuildReproduces() {
        // 65 refused messages of 129 one-byte fragments each, 50 fragments per legal page.
        fun stream(): List<HistoryMessage> =
            (1..StateLimits.MAX_REFUSED_MESSAGES + 1).flatMap { n ->
                (0 until segmentLimit + 1).map { f.fragment(n, it, segmentLimit + 1, "x") }
            }

        fun run(h: HistoryHarness): HistoryInvalidation? {
            var cursor = 1
            for (items in stream().chunked(50)) {
                val outcome = h.accept(
                    h.plan(50),
                    legal(f.page(f.cursor(cursor++), *items.toTypedArray()))
                )
                if (outcome is PageOutcome.Invalidated) return outcome.reason
            }
            return null
        }

        val first = HistoryHarness()
        assertEquals(HistoryInvalidation.TOO_MANY_REFUSED, run(first))
        first.assembly = first.assembly.restart()
        assertEquals(HistoryInvalidation.TOO_MANY_REFUSED, run(first))
    }
}
