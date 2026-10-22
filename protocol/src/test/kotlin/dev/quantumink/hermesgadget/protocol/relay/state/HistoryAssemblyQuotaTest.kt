package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local assembly quotas (POLICY, not wire facts), commit/abort and resume points, all at their exact boundaries. */
class HistoryAssemblyQuotaTest {
    private val f = HistoryHarness.Companion

    /** A fragment is cut at a nominal 8,192 bytes, moved forward by at most three continuation bytes. */
    private val maxFragmentBytes =
        StateLimits.SEGMENT_NOMINAL_BYTES + StateLimits.SEGMENT_BOUNDARY_SLACK_BYTES

    private fun segments(n: Int, count: Int, text: (Int) -> String): List<HistoryMessage> =
        (0 until count).map { f.fragment(n, it, count, text(it)) }

    private fun singles(from: Int, count: Int) = (from until from + count).map { f.single(it) }

    @Test fun theSegmentQuotaIsExactAndAnOverQuotaMessageIsRefusedAbsentNotTruncatedOrComplete() {
        val h = HistoryHarness()
        val max = StateLimits.MAX_MESSAGE_SEGMENTS
        val ok = h.accepted(
            h.feed(
                f.page(
                    f.cursor(1),
                    *segments(1, max) {
                        "s$it|"
                    }.toTypedArray()
                )
            )
        )
        assertEquals(1, ok.emitted.size)
        assertEquals(max, ok.emitted.single().segmentCount)
        // The wire allows a segment_count up to Int.MAX_VALUE: only the first fragment is needed to refuse.
        h.accepted(h.feed(f.page(f.cursor(2), f.fragment(2, 0, max + 1, "first"))))
        assertFalse("nothing is allocated for a refused message", h.assembly.hasOpenMessage)
        assertEquals(listOf(max + 1), h.assembly.refusedMessages.map { it.declaredSegments })
        assertEquals(RefusalReason.TOO_MANY_SEGMENTS, h.assembly.refusedMessages.single().reason)
        // Its later fragments arrive in order and are consumed without allocation, then the listing carries on.
        val rest = h.accepted(
            h.feed(
                f.page(
                    null,
                    *((1..max).map { f.fragment(2, it, max + 1, "x") } + f.single(3, "after"))
                        .toTypedArray()
                )
            )
        )
        assertEquals(listOf("after"), rest.emitted.map { it.text })
        assertEquals(max, rest.newFragments - 1)
        assertEquals(0, rest.duplicateFragments)
        assertTrue(rest.listingComplete)
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, rest.completeness)
        val huge = HistoryHarness()
        // Only the first fragment is needed to refuse it; the listing then continues with the rest of that message
        huge.accepted(huge.feed(f.page(f.cursor(1), f.fragment(9, 0, Int.MAX_VALUE, "x"))))
        assertEquals(Int.MAX_VALUE, huge.assembly.refusedMessages.single().declaredSegments)
    }

    @Test fun theByteQuotaOfOneMessageIsExactToTheByte() {
        val max = StateLimits.MAX_MESSAGE_SEGMENTS
        // 127 segments of 8,195 bytes (a boundary may move up to three bytes) plus a last one that lands on 1 MiB.
        val last = StateLimits.MAX_MESSAGE_UTF8_BYTES - 127 * maxFragmentBytes
        assertEquals(7_811, last)
        val exact = HistoryHarness()
        val atLimit = exact.accepted(
            exact.feed(
                f.page(
                    null,
                    *segments(1, max) {
                        "a".repeat(
                            if (it <
                                127
                            ) {
                                maxFragmentBytes
                            } else {
                                last
                            }
                        )
                    }.toTypedArray()
                )
            )
        )
        assertEquals(
            StateLimits.MAX_MESSAGE_UTF8_BYTES,
            atLimit.emitted.single().text.toByteArray().size
        )
        assertEquals(ListingCompleteness.COMPLETE, atLimit.completeness)

        val over = HistoryHarness()
        val past = over.accepted(
            over.feed(
                f.page(
                    null,
                    *segments(1, max) {
                        "a".repeat(
                            if (it <
                                127
                            ) {
                                maxFragmentBytes
                            } else {
                                last + 1
                            }
                        )
                    }.toTypedArray()
                )
            )
        )
        assertTrue(past.emitted.isEmpty())
        assertEquals(RefusalReason.TOO_MANY_BYTES, over.assembly.refusedMessages.single().reason)
        assertEquals(ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS, past.completeness)
        assertFalse(over.assembly.hasOpenMessage)
    }

    @Test fun refusalsAreBoundedAndTheNextOneInvalidatesTheListing() {
        val max = StateLimits.MAX_REFUSED_MESSAGES
        val ok = HistoryHarness()

        // Each refused message is read to its end before the next one begins
        fun refusedMessages(count: Int) = (1..count).flatMap { n ->
            (0..StateLimits.MAX_MESSAGE_SEGMENTS).map {
                f.fragment(n, it, StateLimits.MAX_MESSAGE_SEGMENTS + 1, "x")
            }
        }

        // in pages within the 400-row structural bound of a history page
        fun feedAll(h: HistoryHarness, count: Int): PageOutcome? {
            var last: PageOutcome? = null
            for ((page, items) in refusedMessages(
                count
            ).chunked(RelayLimits.HISTORY_PAGE_ROWS).withIndex()) {
                last = h.feed(f.page(f.cursor(page + 1), *items.toTypedArray()))
                if (last is PageOutcome.Invalidated) return last
            }
            return last
        }
        assertTrue(feedAll(ok, max) is PageOutcome.Accepted)
        assertEquals(max, ok.assembly.refusedMessages.size)
        val past = HistoryHarness()
        assertEquals(
            HistoryInvalidation.TOO_MANY_REFUSED,
            past.invalidated(feedAll(past, max + 1)!!)
        )
    }

    @Test fun theUncommittedMessageQuotaIsBackpressureThatConsumesNothingUntilCommit() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 86).toTypedArray())))
        h.accepted(h.feed(f.page(f.cursor(2), *singles(87, 86).toTypedArray())))
        assertEquals(172, h.assembly.stagedMessages.size)
        val plan = h.plan()
        val page = f.page(null, *singles(173, 85).toTypedArray()) // would make 257 staged
        val before = h.assembly
        val outcome = h.accept(plan, page)
        assertEquals(HistoryLimit.UNCOMMITTED_MESSAGES, (outcome as PageOutcome.Backpressure).limit)
        assertEquals(172, h.assembly.stagedMessages.size)
        assertTrue(
            "the request stays outstanding so the SAME answer can be applied later",
            h.assembly.hasOutstandingRequest
        )
        assertEquals(before.knownCount, h.assembly.knownCount)
        assertEquals(StateLimits.MAX_UNCOMMITTED_MESSAGES, 256)
        // Commit what the consumer stored, then the very same page is consumed.
        h.apply(h.assembly.commit(172))
        val again = h.accepted(h.accept(plan, page))
        assertEquals(85, again.emitted.size)
        assertEquals((173L..257L).toList(), again.emitted.map { it.ordinal })
        assertEquals(ListingCompleteness.COMPLETE, again.completeness)
    }

    @Test fun exactlyTheQuotaOfUncommittedMessagesIsAccepted() {
        val h = HistoryHarness()
        val outcome = h.accepted(
            h.feed(f.page(null, *singles(1, StateLimits.MAX_UNCOMMITTED_MESSAGES).toTypedArray()))
        )
        assertEquals(StateLimits.MAX_UNCOMMITTED_MESSAGES, outcome.emitted.size)
    }

    @Test fun theUncommittedByteQuotaIsExactAndAFifthMebibyteMessageWaitsForACommit() {
        val h = HistoryHarness()
        val block = "a".repeat(StateLimits.SEGMENT_NOMINAL_BYTES)
        fun mebibyte(n: Int) =
            segments(n, StateLimits.MAX_MESSAGE_SEGMENTS) { block }.toTypedArray()
        for (n in 1..4) h.accepted(h.feed(f.page(f.cursor(n), *mebibyte(n))))
        assertEquals(4, h.assembly.stagedMessages.size)
        assertEquals(
            StateLimits.MAX_UNCOMMITTED_UTF8_BYTES.toLong(),
            4L * StateLimits.MAX_MESSAGE_UTF8_BYTES
        )
        val plan = h.plan()
        val page = f.page(null, *mebibyte(5))
        assertEquals(
            HistoryLimit.UNCOMMITTED_BYTES,
            (h.accept(plan, page) as PageOutcome.Backpressure).limit
        )
        assertFalse(
            "a refused page leaves no half-assembled message behind",
            h.assembly.hasOpenMessage
        )
        h.apply(h.assembly.commit(1)) // frees one mebibyte
        assertEquals(1, h.accepted(h.accept(plan, page)).emitted.size)
        assertEquals(4, h.assembly.stagedMessages.size)
    }

    @Test fun theKnownIdentityQuotaIsBackpressureUntilCommittedIdentitiesArePruned() {
        val h = HistoryHarness()
        var n = 1
        var cursor = 1
        while (n <= StateLimits.MAX_KNOWN_MESSAGES) {
            h.accepted(h.feed(f.page(f.cursor(cursor++), *singles(n, 256).toTypedArray())))
            h.apply(h.assembly.commit((n + 255).toLong()))
            n += 256
        }
        assertEquals(StateLimits.MAX_KNOWN_MESSAGES, h.assembly.knownCount)
        val plan = h.plan()
        val page = f.page(f.cursor(cursor), f.single(n))
        assertEquals(
            HistoryLimit.KNOWN_MESSAGES,
            (h.accept(plan, page) as PageOutcome.Backpressure).limit
        )
        assertEquals(
            StateLimits.MAX_KNOWN_MESSAGES - 100,
            h.apply(h.assembly.pruneCommittedKnown(100))
        )
        assertEquals(100, h.assembly.knownCount)
        assertEquals(1, h.accepted(h.accept(plan, page)).emitted.size)
        assertEquals(0, h.apply(h.assembly.pruneCommittedKnown(1_000)))
    }

    @Test fun commitRefusesNothingStagedStaleAndBeyondStagedAndNeverGoesBackwards() {
        val h = HistoryHarness()
        assertEquals(
            CommitRefusal.NOTHING_STAGED,
            (h.apply(h.assembly.commit(1)) as HistoryAssembly.CommitOutcome.Refused).reason
        )
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 3).toTypedArray())))
        fun refusal(through: Long) =
            (h.apply(h.assembly.commit(through)) as HistoryAssembly.CommitOutcome.Refused).reason
        assertEquals(CommitRefusal.STALE, refusal(0))
        assertEquals(CommitRefusal.STALE, refusal(-4))
        assertEquals(CommitRefusal.BEYOND_STAGED, refusal(4))
        assertEquals(0, h.assembly.committedThrough)
        val ok = h.apply(h.assembly.commit(2)) as HistoryAssembly.CommitOutcome.Committed
        assertEquals(2, ok.count)
        assertEquals(CommitRefusal.STALE, refusal(2))
        assertEquals(CommitRefusal.STALE, refusal(1))
        assertEquals(listOf(3L), h.assembly.stagedMessages.map { it.ordinal })
    }

    @Test fun theResumePointIsTheLatestCleanBoundaryAtOrBeforeTheCommitAndNeverAMidPageGuess() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 2).toTypedArray())))
        h.accepted(h.feed(f.page(f.cursor(2), *singles(3, 2).toTypedArray())))
        h.apply(h.assembly.commit(2))
        assertEquals(f.cursor(1), h.assembly.resumePoint!!.cursor)
        // Inside page two there is no clean boundary, so the resume point stays.
        h.apply(h.assembly.commit(3))
        assertEquals(f.cursor(1), h.assembly.resumePoint!!.cursor)
        h.apply(h.assembly.commit(4))
        assertEquals(f.cursor(2), h.assembly.resumePoint!!.cursor)
        assertFalse(h.assembly.resumePoint!!.atEnd)
    }

    @Test fun abortRewindsToTheCommittedBoundaryAndRefetchSkipsCommittedMessages() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 2).toTypedArray())))
        h.accepted(h.feed(f.page(f.cursor(2), *singles(3, 2).toTypedArray())))
        h.apply(h.assembly.commit(2))
        assertEquals(2, h.apply(h.assembly.abortUncommitted()))
        assertTrue(h.assembly.stagedMessages.isEmpty())
        assertEquals(2, h.assembly.committedThrough)
        val plan = h.plan()
        assertEquals(f.cursor(1), plan.request.cursor)
        val refetched = h.accepted(h.accept(plan, f.page(null, *singles(3, 2).toTypedArray())))
        assertEquals(listOf(3L, 4L), refetched.emitted.map { it.ordinal })
        assertEquals(ListingStatus.COMPLETE, h.assembly.status)
    }

    @Test fun anAbortWithoutAResumePointRestartsTheSameChainAndDedupsCommittedMessages() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 3).toTypedArray())))
        h.apply(h.assembly.commit(2)) // mid-page: committed, but no clean boundary
        assertNull(h.assembly.resumePoint)
        h.apply(h.assembly.abortUncommitted())
        assertTrue(h.plan().request.isFirstPage)
        // The same chain: no new beginning was claimed.
        assertEquals(1, h.assembly.provenance.generation)
    }

    @Test fun refetchingAfterAnAbortDoesNotEmitCommittedMessagesAgain() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 3).toTypedArray())))
        h.apply(h.assembly.commit(2))
        h.apply(h.assembly.abortUncommitted())
        val again = h.accepted(h.feed(f.page(null, *singles(1, 3).toTypedArray())))
        assertEquals(2, again.duplicateFragments)
        assertEquals(listOf(3L), again.emitted.map { it.ordinal })
    }

    @Test fun aMessageStillBeingAssembledAtAPageEndLeavesNoBoundaryAndSurvivesNoCommit() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1), f.fragment(2, 0, 2, "half"))))
        h.apply(h.assembly.commit(1))
        assertNull(
            "page one ends mid-message, so there is no clean place to resume",
            h.assembly.resumePoint
        )
        assertTrue(h.assembly.hasOpenMessage)
        h.apply(h.assembly.abortUncommitted())
        assertFalse(h.assembly.hasOpenMessage)
    }

    @Test fun aCompleteListingCommittedToTheEndHasNothingLeftToAbort() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(null, *singles(1, 2).toTypedArray())))
        h.apply(h.assembly.commit(2))
        assertTrue(h.assembly.resumePoint!!.atEnd)
        assertEquals(0, h.apply(h.assembly.abortUncommitted()))
        assertEquals(ListingStatus.COMPLETE, h.assembly.status)
    }

    @Test fun abortingACompleteButUncommittedListingReopensItForARefetch() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 2).toTypedArray())))
        h.accepted(h.feed(f.page(null, *singles(3, 1).toTypedArray())))
        assertEquals(ListingStatus.COMPLETE, h.assembly.status)
        assertEquals(3, h.apply(h.assembly.abortUncommitted()))
        assertEquals(ListingStatus.ACTIVE, h.assembly.status)
        assertTrue(h.plan().request.isFirstPage)
    }

    @Test fun anExplicitRestartDropsEverythingOfTheOldChainAndRecordsWhy() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), *singles(1, 2).toTypedArray())))
        h.apply(h.assembly.commit(1))
        h.assembly = h.assembly.restart(HistoryInvalidation.GAP_EVENT)
        val provenance = h.assembly.provenance
        assertEquals(2, provenance.generation)
        assertEquals(ListingStart.REBUILD, provenance.start)
        assertEquals(HistoryInvalidation.GAP_EVENT, provenance.after)
        assertEquals(0, h.assembly.knownCount)
        assertEquals(0, h.assembly.committedThrough)
        assertNull(h.assembly.resumePoint)
        assertTrue(h.assembly.stagedMessages.isEmpty())
        // The rebuilt listing re-reads content that may have changed: nothing is skipped as "already known".
        val reread = h.accepted(h.feed(f.page(null, *singles(1, 2).toTypedArray())))
        assertEquals(2, reread.emitted.size)
        assertEquals(1L, reread.emitted.first().ordinal)
    }
}
