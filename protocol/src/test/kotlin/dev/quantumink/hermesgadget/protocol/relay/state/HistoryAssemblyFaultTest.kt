package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Duplicate, mismatched, missing and out-of-order fragments, cursors and stale replies (tiny deterministic models). */
class HistoryAssemblyFaultTest {
    private val f = HistoryHarness.Companion

    private fun three(n: Int) = listOf(
        f.fragment(n, 0, 3, "aa"),
        f.fragment(n, 1, 3, "bb"),
        f.fragment(n, 2, 3, "cc")
    )

    @Test fun aMessageIsEmittedOnlyWhenEveryIndexIsPresentAndConsistent() {
        val h = HistoryHarness()
        val (a, b, c) = three(1)
        assertTrue(h.accepted(h.feed(f.page(f.cursor(1), a))).emitted.isEmpty())
        assertTrue(h.assembly.hasOpenMessage)
        assertTrue(h.accepted(h.feed(f.page(f.cursor(2), b))).emitted.isEmpty())
        val done = h.accepted(h.feed(f.page(null, c)))
        assertEquals("aabbcc", done.emitted.single().text)
        assertEquals(3, done.emitted.single().segmentCount)
        assertFalse(h.assembly.hasOpenMessage)
        assertEquals(ListingCompleteness.COMPLETE, done.completeness)
    }

    @Test fun anIdenticalDuplicateFragmentIsIdempotentInsideAPageAndAcrossPages() {
        val h = HistoryHarness()
        val (a, b, c) = three(1)
        val inPage = h.accepted(h.feed(f.page(null, a, b, b, c)))
        assertEquals(1, inPage.duplicateFragments)
        assertEquals(3, inPage.newFragments)
        assertEquals("aabbcc", inPage.emitted.single().text)
        val g = HistoryHarness()
        g.accepted(g.feed(f.page(f.cursor(1), a, b)))
        // The page boundary repeats the last fragment of the previous page.
        val replay = g.accepted(g.feed(f.page(null, b, c)))
        assertEquals(1, replay.duplicateFragments)
        assertEquals("aabbcc", replay.emitted.single().text)
    }

    @Test fun faultyFragmentsRefuseCompletenessAndRequireARebuildNeverAGuess() {
        val (a, b, c) = three(1)
        val cases = mapOf(
            "different text under a repeated index" to
                (
                    listOf(
                        a,
                        b,
                        f.fragment(1, 1, 3, "XX")
                    ) to HistoryInvalidation.MISMATCHED_DUPLICATE
                    ),
            "a different count" to
                (listOf(a, f.fragment(1, 1, 4, "bb")) to HistoryInvalidation.MISMATCHED_METADATA),
            "a different role" to
                (
                    listOf(a, f.fragment(1, 1, 3, "bb", HistoryRole.USER)) to
                        HistoryInvalidation.MISMATCHED_METADATA
                    ),
            "a different timestamp" to
                (
                    listOf(a, f.fragment(1, 1, 3, "bb", createdAtMs = 5)) to
                        HistoryInvalidation.MISMATCHED_METADATA
                    ),
            "a skipped index" to (listOf(a, c) to HistoryInvalidation.MISSING_SEGMENT),
            "a continuation that never began" to
                (listOf(b) to HistoryInvalidation.UNEXPECTED_FRAGMENT),
            "the next message before this one is complete" to
                (listOf(f.fragment(1, 0, 2, "aa"), f.single(2)) to HistoryInvalidation.INTERLEAVED)
        )
        for ((name, case) in cases) {
            val h = HistoryHarness()
            val outcome = h.feed(f.page(f.cursor(1), *case.first.toTypedArray()))
            assertEquals(name, case.second, h.invalidated(outcome))
            assertEquals(name, ListingStatus.INVALIDATED, h.assembly.status)
            assertTrue(name, h.assembly.stagedMessages.isEmpty())
            assertFalse(name, h.assembly.hasOpenMessage)
            assertTrue(
                name,
                h.effects.contains(HistoryEffect.RebuildRequired(h.scope, case.second))
            )
            assertEquals(name, ListingStatus.INVALIDATED, h.assembly.provenance.status)
            assertEquals(name, case.second, h.assembly.provenance.invalidation)
        }
    }

    @Test fun aListingThatEndsMidMessageIsIncompleteNeverCompleteOrTruncated() {
        val h = HistoryHarness()
        val (a, b, _) = three(1)
        val outcome = h.feed(f.page(null, a, b)) // next_cursor null while segment 2 is still due
        assertEquals(HistoryInvalidation.INCOMPLETE_AT_END, h.invalidated(outcome))
        assertTrue(h.assembly.stagedMessages.isEmpty())
    }

    @Test fun anInvalidationDiscardsWhatWasAlreadyStagedFromTheSameChain() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1), f.single(2))))
        assertEquals(2, h.assembly.stagedMessages.size)
        val outcome = h.feed(f.page(f.cursor(2), f.fragment(3, 1, 2, "orphan")))
        assertEquals(2, (outcome as PageOutcome.Invalidated).discardedStaged)
        assertTrue(h.assembly.stagedMessages.isEmpty())
        // The old chain's staged messages cannot be committed any more.
        val refused = h.apply(h.assembly.commit(2)) as HistoryAssembly.CommitOutcome.Refused
        assertEquals(CommitRefusal.NOTHING_STAGED, refused.reason)
    }

    @Test fun canonicalOrderIsTheAuthenticatedPageOrderNeverIdsOrTimestamps() {
        val h = HistoryHarness()
        // Descending ids and descending timestamps: sorting either would reverse them.
        val page = f.page(
            null,
            f.fragment(9, 0, 1, "nine", createdAtMs = 900),
            f.fragment(3, 0, 1, "three", createdAtMs = 300),
            f.fragment(7, 0, 1, "seven", createdAtMs = 100)
        )
        val outcome = h.accepted(h.feed(page))
        assertEquals(listOf("nine", "three", "seven"), outcome.emitted.map { it.text })
        assertEquals(listOf(1L, 2L, 3L), outcome.emitted.map { it.ordinal })
    }

    @Test fun aReplayedStagedMessageIsDetectedByteForByteAndACommittedOneByItsMetadata() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1, "original"))))
        val replay = h.accepted(h.feed(f.page(f.cursor(2), f.single(1, "original"))))
        assertEquals(1, replay.duplicateFragments)
        assertTrue(replay.emitted.isEmpty())
        assertEquals(1, h.assembly.stagedMessages.size)
        val changed = h.feed(f.page(f.cursor(3), f.single(1, "edited")))
        assertEquals(HistoryInvalidation.MISMATCHED_DUPLICATE, h.invalidated(changed))

        val g = HistoryHarness()
        g.accepted(g.feed(f.page(f.cursor(1), f.single(1, "original"))))
        g.apply(g.assembly.commit(1))
        // After the commit only identity and metadata are kept: a repeat is skipped, a metadata change is refused.
        val skipped = g.accepted(g.feed(f.page(f.cursor(2), f.single(1, "original"))))
        assertEquals(1, skipped.duplicateFragments)
        assertTrue(skipped.emitted.isEmpty())
        val wrongRole = g.feed(
            f.page(f.cursor(3), f.fragment(1, 0, 1, "original", HistoryRole.USER))
        )
        assertEquals(HistoryInvalidation.MISMATCHED_METADATA, g.invalidated(wrongRole))
    }

    @Test fun anOpenMessageSurvivesEmptyButCursoredPagesInBetween() {
        val h = HistoryHarness()
        val (a, b, c) = three(1)
        h.accepted(h.feed(f.page(f.cursor(1), a)))
        for (n in 2..5) assertTrue(h.accepted(h.feed(f.page(f.cursor(n)))).emitted.isEmpty())
        assertTrue(h.assembly.hasOpenMessage)
        val done = h.accepted(h.feed(f.page(null, b, c)))
        assertEquals("aabbcc", done.emitted.single().text)
    }

    @Test fun aCursorEchoedBackIsNoProgressAndNeverALoop() {
        val echo = HistoryHarness()
        echo.accepted(echo.feed(f.page(f.cursor(1))))
        assertEquals(
            HistoryInvalidation.NO_PROGRESS,
            echo.invalidated(echo.feed(f.page(f.cursor(1))))
        )
        assertEquals(ListingStatus.INVALIDATED, echo.assembly.status)
    }

    @Test fun aCycleWithinTheCursorMemoryIsRefusedAndTheMemoryHasAnExactLocalBound() {
        for ((cycle, detected) in listOf(
            StateLimits.CURSOR_RING to true,
            StateLimits.CURSOR_RING + 1 to false
        )) {
            val h = HistoryHarness()
            for (n in 1..cycle) h.accepted(h.feed(f.page(f.cursor(n))))
            val outcome = h.feed(f.page(f.cursor(1)))
            if (detected) {
                assertEquals(HistoryInvalidation.NO_PROGRESS, h.invalidated(outcome))
            } else {
                assertTrue(
                    "cycle $cycle is beyond the local memory",
                    outcome is PageOutcome.Accepted
                )
            }
        }
    }

    @Test fun consecutiveEmptyPagesAreBoundedExactlyAndNewContentResetsTheCount() {
        val h = HistoryHarness()
        for (n in 1..StateLimits.MAX_CONSECUTIVE_EMPTY_PAGES) {
            assertTrue(h.feed(f.page(f.cursor(n))) is PageOutcome.Accepted)
        }
        val stalled = h.feed(f.page(f.cursor(1_000)))
        assertEquals(HistoryInvalidation.STALLED, h.invalidated(stalled))

        val g = HistoryHarness()
        var next = 1
        repeat(3) { round ->
            for (n in 1..StateLimits.MAX_CONSECUTIVE_EMPTY_PAGES) {
                assertTrue(g.feed(f.page(f.cursor(next++))) is PageOutcome.Accepted)
            }
            // A page with new content resets the count: a long hidden stretch is allowed again afterwards.
            assertEquals(
                1,
                g.accepted(g.feed(f.page(f.cursor(next++), f.single(100 + round)))).emitted.size
            )
        }
    }

    @Test fun repliesToAnythingButTheOutstandingRequestAreIgnoredBeforeAnyStateChange() {
        val h = HistoryHarness()
        val first = h.plan()
        assertEquals(HistoryPlanRefusal.REQUEST_OUTSTANDING, h.planRefusal())
        h.apply(h.assembly.onTimeout(first.request))
        val second = h.plan()
        val before = h.assembly
        val late = before.accept(first.request, f.page(null, f.single(1)))
        assertTrue(late.result is PageOutcome.Ignored)
        assertSame(before, late.assembly)
        h.accepted(h.accept(second, f.page(f.cursor(1), f.single(2))))
        // The same answer applied twice is ignored the second time.
        val twice = h.assembly.accept(second.request, f.page(f.cursor(1), f.single(2)))
        assertEquals(HistoryIgnore.STALE_REQUEST, (twice.result as PageOutcome.Ignored).reason)
        // A request from before an explicit restart belongs to another generation.
        val third = h.plan()
        h.assembly = h.assembly.restart(HistoryInvalidation.GAP_EVENT)
        val stale = h.assembly.accept(third.request, f.page(null, f.single(3)))
        assertTrue(stale.result is PageOutcome.Ignored)
        assertTrue(h.assembly.stagedMessages.isEmpty())
        assertEquals(HistoryInvalidation.GAP_EVENT, h.assembly.provenance.after)
    }

    @Test fun transientAndUnknownErrorsNeverChangeWhatWasAssembledAndAreNeverCalledSuccess() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1))))
        for (code in listOf(
            RelayErrorCode.BUSY,
            RelayErrorCode.RATE_LIMITED,
            RelayErrorCode.TEMPORARILY_UNAVAILABLE
        )) {
            val plan = h.plan()
            assertEquals(f.cursor(1), plan.request.cursor)
            assertEquals(code, (h.error(plan, code) as PageOutcome.RetrySameRequest).code)
        }
        val plan = h.plan()
        val unknown = h.error(plan, RelayErrorCode.UNKNOWN)
        assertTrue(unknown is PageOutcome.Failed)
        assertEquals(1, h.assembly.stagedMessages.size)
        assertEquals(ListingStatus.ACTIVE, h.assembly.status)
        assertNull(h.assembly.resumePoint)
    }

    @Test fun planningRefusesBadLimitsAndNonActiveListings() {
        val h = HistoryHarness()
        assertEquals(HistoryPlanRefusal.INVALID_LIMIT, h.planRefusal(0))
        assertEquals(HistoryPlanRefusal.INVALID_LIMIT, h.planRefusal(51))
        assertTrue(h.plan(50).request.limit == 50)
        val done = HistoryHarness()
        done.accepted(done.feed(f.page(null, f.single(1))))
        assertEquals(HistoryPlanRefusal.NOT_ACTIVE, done.planRefusal())
    }

    @Test fun nothingPrintsMessageTextCursorsOrScopeLabels() {
        val h = HistoryHarness()
        val plan = h.plan()
        val out = h.accepted(h.accept(plan, f.page(f.cursor(7), f.single(1, "very private words"))))
        h.feed(f.page(null, f.fragment(2, 0, 2, "half-written secret")))
        val printed = listOf(
            h.assembly.toString(),
            h.scope.toString(),
            plan.toString(),
            plan.request.toString(),
            plan.call.toString(),
            out.toString(),
            out.emitted.single().toString(),
            h.assembly.provenance.toString(),
            h.assembly.resumePoint.toString(),
            h.effects.toString()
        ).joinToString("\n")
        for (secret in listOf("private", "secret", f.cursor(7), "endpoint-a", "phone-key-1")) {
            assertFalse(secret, printed.contains(secret))
        }
    }
}
