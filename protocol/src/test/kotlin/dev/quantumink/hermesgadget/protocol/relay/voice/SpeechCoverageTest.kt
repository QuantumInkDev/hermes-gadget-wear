package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.state.ConversationScope
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact range coverage over the unchanged PURE range vectors (derived from the pure engine, NOT wire captures): every vector walks to the end of its
 * text with exact concatenation, no gaps and no overlaps, in both styles and on the R4 pathological clusters; interrupted ranges resume at their own
 * start; receipts are not playback; the ledger is memory-only and bounded with typed backpressure.
 */
class SpeechCoverageTest {
    private class Vector(
        val name: String,
        val style: String,
        val text: String,
        val sha: String,
        val ranges: List<JsonObject>,
        val chunks: List<JsonObject>
    ) {
        val length: Int = text.codePointCount(0, text.length)

        fun slice(start: Int, end: Int): String =
            text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }

    private val vectors: List<Vector> by lazy {
        VoiceVectors.json("v3-range-vectors.json")["cases"]!!.jsonArray.map {
            val c = it.jsonObject
            Vector(
                c["name"]!!.jsonPrimitive.content,
                c["style"]!!.jsonPrimitive.content,
                c["text"]!!.jsonPrimitive.content,
                c["source_sha256"]!!.jsonPrimitive.content,
                c["ranges"]!!.jsonArray.map { r -> r.jsonObject },
                c["chunks_of_first_range"]!!.jsonArray.map { r -> r.jsonObject }
            )
        }
    }

    private val scope: ConversationScope =
        ConversationScope(StateTestSupport.enrollment, StateTestSupport.attached(0).conversationId)
    private var counter = 0

    private fun speechId(): SpeechId =
        SpeechId.of("01a14d67-a300-7000-8000-%012d".format(++counter))!!

    private fun message(n: Int = 1): MessageId =
        MessageId.of("6e87dff2-3cf6-5fb9-91b4-%012d".format(n))!!

    private fun source(sha: String, end: Int, total: Int = end, limited: Boolean = false) =
        SpeechSource(SourceDigest.of(sha)!!, total, end, limited)

    private fun issue(
        mid: MessageId,
        source: SpeechSource,
        start: Int,
        end: Int,
        speech: SpeechId = speechId(),
        state: SpeechState = SpeechState.PREPARING,
        cut: Boolean = false,
        chunks: List<SpeechChunk>? = null,
        provider: VoiceProvider = VoiceProvider.NOT_STARTED,
        stream: Int? = if (state == SpeechState.STREAMING) 1 else null
    ): SpeechResult = SpeechResult(
        speech, mid, state, null,
        stream?.let {
            VoiceStream.of(it)
        },
        source,
        SpeechRange(
            start,
            end,
            1,
            cut,
            end == source.speakableEndCp
        ),
        chunks, null, provider
    )

    private fun SpeechCoverage.issued(result: SpeechResult): SpeechCoverage {
        val step = onIssued(scope, result)
        assertTrue(step.outcome.toString(), step.outcome is CoverageOutcome.Issued)
        return step.coverage
    }

    private fun SpeechCoverage.played(
        mid: MessageId,
        speech: SpeechId
    ): Pair<SpeechCoverage, CoverageOutcome.Covered> {
        val step = onPlayed(scope, mid, speech)
        return step.coverage to (step.outcome as CoverageOutcome.Covered)
    }

    private fun key(mid: MessageId) = CoverageKey(scope, mid)

    // -- exact concatenation over the PURE vectors -------------------------------------------------------------------------------------

    @Test fun thereAreTwentyEightPureVectorsInBothStylesIncludingTheR4Clusters() {
        assertEquals(28, vectors.size)
        assertEquals(setOf("concise", "full"), vectors.map { it.style }.toSet())
        assertEquals(14, vectors.map { it.name }.toSet().size)
        assertEquals(
            10,
            vectors.count {
                it.name.startsWith("r4_")
            }
        ) // five pathological clusters, both styles
        // supplementary characters: code-point offsets are NOT UTF-16 indices (these texts are longer in UTF-16 than in code points)
        for (name in listOf(
            "emoji_zwj",
            "skin_tone",
            "regional_indicator",
            "r4_zwj_chain_over_cap",
            "r4_skin_tone_run_over_cap",
            "r4_regional_indicator_run_over_cap"
        )) {
            assertTrue(name, vectors.filter { it.name == name }.all { it.text.length > it.length })
        }
    }

    @Test fun everyVectorWalksToTheEndWithExactConcatenationNoGapsAndNoOverlaps() {
        val mid = message()
        for (vector in vectors) {
            var coverage = SpeechCoverage.empty()
            val src = source(vector.sha, vector.length)
            val built = StringBuilder()
            var previousEnd = 0
            for ((index, range) in vector.ranges.withIndex()) {
                val start = range["start_cp"]!!.jsonPrimitive.int
                val end = range["end_cp"]!!.jsonPrimitive.int
                assertEquals(
                    "${vector.name}/${vector.style} range $index starts where the last ended: no gap, no overlap",
                    previousEnd,
                    start
                )
                assertTrue("${vector.name} progress", end > start)
                val id = speechId()
                coverage =
                    coverage.issued(
                        issue(
                            mid,
                            src,
                            start,
                            end,
                            id,
                            cut = range["cut_inside_unit"]!!.jsonPrimitive.boolean
                        )
                    )
                val (next, covered) = coverage.played(mid, id)
                coverage = next
                assertEquals(
                    "${vector.name}/${vector.style} frontier after range $index",
                    end,
                    covered.frontier
                )
                assertEquals(index == vector.ranges.lastIndex, covered.complete)
                built.append(vector.slice(start, end))
                previousEnd = end
            }
            assertEquals("${vector.name}/${vector.style}", vector.text, built.toString())
            assertTrue(coverage.isComplete(key(mid)))
            assertEquals(vector.length, coverage.frontier(key(mid)))
            assertEquals(listOf(0 until vector.length), coverage.covered(key(mid)))
            val next = coverage.continuation(key(mid))!!
            assertTrue(next.exhausted)
            assertEquals(vector.length, next.startCp)
            assertEquals(
                vector.ranges.map {
                    it["end_cp"]!!.jsonPrimitive.int
                },
                coverage.boundaries(key(mid))
            )
        }
    }

    @Test fun everyRangeHonoursTheStyleCapsAndTheR4PathologicalClustersAreCutInsideTheUnit() {
        val caps = VoiceVectors.json("v3-range-vectors.json")["caps"]!!.jsonObject
        assertEquals(450, caps["client_chunk_code_points"]!!.jsonPrimitive.int)
        for (vector in vectors) {
            for (range in vector.ranges) {
                val start = range["start_cp"]!!.jsonPrimitive.int
                val end = range["end_cp"]!!.jsonPrimitive.int
                val slice = vector.slice(start, end)
                val cap = if (vector.style == "concise") 600 else 3_000
                assertTrue("${vector.name}/${vector.style} $start..$end", end - start <= cap)
                assertTrue(slice.length <= 4_096)
                assertEquals(end - start, slice.codePointCount(0, slice.length))
                if (vector.style ==
                    "concise"
                ) {
                    assertTrue(range["sentences"]!!.jsonPrimitive.int <= 3)
                }
            }
            if (vector.name.startsWith("r4_")) {
                // a cluster longer than a cap is cut inside the unit (ruling R4), never skipped, and the exact concatenation is proven above
                val firstCut = vector.ranges.first()["cut_inside_unit"]!!.jsonPrimitive.boolean
                assertEquals("${vector.name}/${vector.style}", vector.style == "concise", firstCut)
            }
        }
    }

    @Test fun clientChunksOfTheFirstRangeTileItAndEachIsAtMostFourHundredFiftyCodePoints() {
        val mid = message(2)
        for (vector in vectors) {
            var cursor = vector.ranges.first()["start_cp"]!!.jsonPrimitive.int
            val end = vector.ranges.first()["end_cp"]!!.jsonPrimitive.int
            val chunks = vector.chunks.map { c ->
                val s = c["start_cp"]!!.jsonPrimitive.int
                val e = c["end_cp"]!!.jsonPrimitive.int
                assertEquals("${vector.name} chunks are consecutive", cursor, s)
                assertTrue(e - s in 1..450)
                cursor = e
                // exact code-point text: the DTO checks count == end - start
                SpeechChunk(s, e, vector.slice(s, e))
            }
            assertEquals(
                "${vector.name}/${vector.style} chunks cover the range exactly",
                end,
                cursor
            )
            val result = SpeechResult(
                speechId(), mid, SpeechState.TEXT, null, null, source(vector.sha, vector.length),
                SpeechRange(
                    0,
                    end,
                    1,
                    false,
                    end == vector.length
                ),
                chunks, null,
                VoiceProvider.NOT_STARTED
            )
            assertEquals(vector.slice(0, end), result.chunks!!.joinToString("") { it.text })
        }
    }

    @Test fun clientModeIsCoveredOnlyWhenEveryChunkPlayed() {
        val vector = vectors.first { it.name == "overlong_ascii" && it.style == "full" }
        val mid = message(3)
        val end = vector.ranges.first()["end_cp"]!!.jsonPrimitive.int
        val chunks = vector.chunks.map {
            SpeechChunk(
                it["start_cp"]!!.jsonPrimitive.int,
                it["end_cp"]!!.jsonPrimitive.int,
                vector.slice(it["start_cp"]!!.jsonPrimitive.int, it["end_cp"]!!.jsonPrimitive.int)
            )
        }
        assertEquals(3, chunks.size)
        val id = speechId()
        var coverage = SpeechCoverage.empty().issued(
            SpeechResult(
                id, mid, SpeechState.TEXT, null, null, source(vector.sha, vector.length),
                SpeechRange(
                    0,
                    end,
                    1,
                    false,
                    end == vector.length
                ),
                chunks, null, VoiceProvider.NOT_STARTED
            )
        )
        val early = coverage.onPlayed(scope, mid, id)
        assertEquals(
            CoverageRefusal.CHUNKS_PENDING,
            (early.outcome as CoverageOutcome.Refused).reason
        )
        assertSame(coverage, early.coverage)
        assertEquals(
            CoverageRefusal.UNKNOWN_CHUNK,
            (coverage.onChunkPlayed(scope, mid, id, 17).outcome as CoverageOutcome.Refused).reason
        )
        for ((index, chunk) in chunks.withIndex()) {
            val step = coverage.onChunkPlayed(scope, mid, id, chunk.startCp)
            assertEquals(
                chunks.size - index - 1,
                (step.outcome as CoverageOutcome.ChunkPlayed).remaining
            )
            coverage = step.coverage
            assertEquals("a played chunk alone is not coverage", 0, coverage.frontier(key(mid)))
            assertEquals(
                CoverageRefusal.UNKNOWN_CHUNK,
                (
                    coverage.onChunkPlayed(
                        scope,
                        mid,
                        id,
                        chunk.startCp
                    ).outcome as CoverageOutcome.Refused
                    ).reason
            )
        }
        val (done, covered) = coverage.played(mid, id)
        assertEquals(end, covered.frontier)
        assertEquals(end, done.frontier(key(mid)))
        // a server-mode attempt has no chunks to play
        val server = speechId()
        val s2 = done.issued(issue(mid, source(vector.sha, vector.length), 0, end, server))
        assertEquals(
            CoverageRefusal.UNKNOWN_CHUNK,
            (s2.onChunkPlayed(scope, mid, server, end).outcome as CoverageOutcome.Refused).reason
        )
    }

    // -- interruption and resume --------------------------------------------------------------------------------------------------------

    @Test fun anInterruptedRangeStaysUncoveredAndResumesAtItsOwnStartForEveryRangeOfEveryVector() {
        val mid = message(4)
        var resumes = 0
        for (vector in vectors) {
            val src = source(vector.sha, vector.length)
            for (index in vector.ranges.indices) {
                var coverage = SpeechCoverage.empty()
                var built = ""
                for (earlier in vector.ranges.take(index)) {
                    val id = speechId()
                    coverage =
                        coverage.issued(
                            issue(
                                mid,
                                src,
                                earlier["start_cp"]!!.jsonPrimitive.int,
                                earlier["end_cp"]!!.jsonPrimitive.int,
                                id
                            )
                        ).played(mid, id).first
                    built +=
                        vector.slice(
                            earlier["start_cp"]!!.jsonPrimitive.int,
                            earlier["end_cp"]!!.jsonPrimitive.int
                        )
                }
                val range = vector.ranges[index]
                val start = range["start_cp"]!!.jsonPrimitive.int
                val end = range["end_cp"]!!.jsonPrimitive.int
                val interrupted = speechId()
                coverage = coverage.issued(issue(mid, src, start, end, interrupted))
                val step = coverage.onInterrupted(
                    scope,
                    mid,
                    interrupted,
                    InterruptReason.FOCUS_LOSS
                )
                assertEquals(start, (step.outcome as CoverageOutcome.Interrupted).resumeStart)
                coverage = step.coverage
                val next = coverage.continuation(key(mid))!!
                assertEquals(
                    "${vector.name}/${vector.style} #$index never past the interrupted range",
                    start,
                    next.startCp
                )
                assertEquals(start, coverage.frontier(key(mid)))
                assertEquals(end, next.interruptedEndCp)
                assertEquals(InterruptReason.FOCUS_LOSS, next.interruptedBecause)
                assertFalse(next.exhausted)
                assertTrue(
                    "nothing of the interrupted range is covered",
                    coverage.covered(key(mid)).none {
                        it.first <
                            end &&
                            it.last >= start
                    }
                )
                // the deliberate resume re-speaks that range ONLY, then the walk continues to the exact end
                val resumeId = speechId()
                var resumed = coverage.issued(issue(mid, src, start, end, resumeId))
                resumed = resumed.played(mid, resumeId).first
                built += vector.slice(start, end)
                for (later in vector.ranges.drop(index + 1)) {
                    val id = speechId()
                    resumed =
                        resumed.issued(
                            issue(
                                mid,
                                src,
                                later["start_cp"]!!.jsonPrimitive.int,
                                later["end_cp"]!!.jsonPrimitive.int,
                                id
                            )
                        ).played(mid, id).first
                    built +=
                        vector.slice(
                            later["start_cp"]!!.jsonPrimitive.int,
                            later["end_cp"]!!.jsonPrimitive.int
                        )
                }
                assertEquals(
                    "${vector.name}/${vector.style} resume at #$index reproduces the text exactly",
                    vector.text,
                    built
                )
                assertTrue(resumed.isComplete(key(mid)))
                resumes++
            }
        }
        assertEquals(vectors.sumOf { it.ranges.size }, resumes)
        assertEquals(49, resumes)
    }

    @Test fun anUncoveredIntervalIsNeverAdvancedPastEvenWhenALaterRangeFullyPlayed() {
        val vector = vectors.first { it.name == "r4_zwj_chain_over_cap" && it.style == "concise" }
        assertTrue(vector.ranges.size >= 4)
        val mid = message(5)
        val src = source(vector.sha, vector.length)
        fun range(i: Int) = vector.ranges[i]["start_cp"]!!.jsonPrimitive.int to
            vector.ranges[i]["end_cp"]!!.jsonPrimitive.int
        val ids = vector.ranges.map { speechId() }
        var coverage = SpeechCoverage.empty()
        for (i in 0..2) {
            coverage =
                coverage.issued(issue(mid, src, range(i).first, range(i).second, ids[i]))
        }
        coverage = coverage.played(mid, ids[1]).first
        assertEquals("range 1 played but range 0 is a gap", 0, coverage.frontier(key(mid)))
        assertEquals(0, coverage.continuation(key(mid))!!.startCp)
        assertEquals(listOf(range(1).first until range(1).second), coverage.covered(key(mid)))
        coverage = coverage.played(mid, ids[2]).first
        // adjacent intervals merge
        assertEquals(listOf(range(1).first until range(2).second), coverage.covered(key(mid)))
        assertEquals(0, coverage.frontier(key(mid)))
        assertFalse(coverage.isComplete(key(mid)))
        val (healed, covered) = coverage.played(mid, ids[0])
        assertEquals(range(2).second, covered.frontier)
        assertEquals(listOf(0 until range(2).second), healed.covered(key(mid)))
    }

    @Test fun aReceiptACompletedStateOrAnEndFrameIsNeverPlaybackCoverage() {
        val mid = message(6)
        val src = source("a".repeat(64), 100)
        val completed =
            SpeechResult(
                speechId(), mid, SpeechState.COMPLETED, null,
                VoiceStream.of(
                    1
                ),
                src, SpeechRange(0, 100, 2, false, true), null, null, VoiceProvider.COMPLETED
            )
        val coverage = SpeechCoverage.empty().issued(completed)
        assertEquals(0, coverage.frontier(key(mid)))
        assertEquals(emptyList<IntRange>(), coverage.covered(key(mid)))
        assertFalse(coverage.isComplete(key(mid)))
        // states that were not issued for playback never enter the ledger
        val refusedStates = listOf(
            SpeechResult(
                speechId(), mid, SpeechState.STOPPED, null,
                VoiceStream.of(
                    1
                ),
                src, SpeechRange(0, 100, 2, false, true), null, null, VoiceProvider.COMPLETED
            ),
            SpeechResult(
                speechId(), mid, SpeechState.ABORTED, SpeechReason.STALE,
                VoiceStream.of(
                    1
                ),
                src, SpeechRange(0, 100, 2, false, true), null, null, VoiceProvider.COMPLETED
            ),
            SpeechResult(
                speechId(), mid, SpeechState.UNKNOWN, null, null, src,
                SpeechRange(
                    0,
                    100,
                    2,
                    false,
                    true
                ),
                null, null, VoiceProvider.UNKNOWN
            ),
            SpeechResult(
                speechId(), mid, SpeechState.OFF, null, null, null, null, null, null,
                VoiceProvider.NOT_STARTED
            ),
            SpeechResult(
                speechId(), null, SpeechState.NO_FINAL, null, null, null, null, null, null,
                VoiceProvider.NOT_STARTED
            )
        )
        for (result in refusedStates) {
            val step = SpeechCoverage.empty().onIssued(scope, result)
            assertEquals(
                result.toString(),
                CoverageRefusal.NOT_FOR_PLAYBACK,
                (step.outcome as CoverageOutcome.Refused).reason
            )
            assertEquals(0, step.coverage.size)
        }
    }

    // -- limited sources, source and scope changes -----------------------------------------------------------------------------------------

    @Test fun aLimitedSourceEndsAtTheProcessedPrefixAndNeverReadsTotalAsTheUnreadTail() {
        val mid = message(7)
        // R3: total_cp is the processed prefix (equal to speakable_end_cp); an older answer with a larger total must not move the end either
        for (total in listOf(3_000, 262_000)) {
            val src = source("c".repeat(64), 3_000, total = total, limited = true)
            val first = speechId()
            var coverage = SpeechCoverage.empty().issued(issue(mid, src, 0, 3_000, first))
            val (done, covered) = coverage.played(mid, first)
            coverage = done
            assertTrue(
                "complete at the speakable end, whatever total_cp says (total=$total)",
                covered.complete
            )
            val next = coverage.continuation(key(mid))!!
            assertTrue(next.exhausted && next.limited)
            assertEquals(3_000, next.speakableEndCp)
            assertEquals(3_000, next.startCp)
        }
        assertEquals(262_000, source("c".repeat(64), 262_000, limited = true).totalCp)
    }

    @Test fun aChangedSourceOrScopeInvalidatesStaleCoverageAndAContradictoryServerIsRefused() {
        val mid = message(8)
        val first = source("a".repeat(64), 100)
        val id = speechId()
        var coverage = SpeechCoverage.empty().issued(
            issue(mid, first, 0, 40, id)
        ).played(mid, id).first
        assertEquals(40, coverage.frontier(key(mid)))
        // the same message under another digest: the earlier coverage described text that no longer exists
        val changed = source("b".repeat(64), 100)
        val step = coverage.onIssued(scope, issue(mid, changed, 0, 50))
        assertTrue((step.outcome as CoverageOutcome.Issued).sourceChanged)
        coverage = step.coverage
        assertEquals(0, coverage.frontier(key(mid)))
        assertEquals(emptyList<IntRange>(), coverage.covered(key(mid)))
        assertEquals(listOf(50), coverage.boundaries(key(mid)))
        assertEquals(changed.sha256, coverage.continuation(key(mid))!!.source)
        // the same digest with another speakable end is a contradiction, not a change
        val liar = coverage.onIssued(scope, issue(mid, source("b".repeat(64), 120), 0, 60))
        assertEquals(
            CoverageRefusal.SOURCE_INCONSISTENT,
            (liar.outcome as CoverageOutcome.Refused).reason
        )
        assertSame(coverage, liar.coverage)
        val limitedLiar = coverage.onIssued(
            scope,
            issue(mid, source("b".repeat(64), 100, limited = true), 0, 60)
        )
        assertEquals(
            CoverageRefusal.SOURCE_INCONSISTENT,
            (limitedLiar.outcome as CoverageOutcome.Refused).reason
        )
        // scope: another enrollment, another conversation, another message are other keys; invalidation is exact
        val otherScope = StateTestSupport.enrollment.copy(reviewEpoch = 1)
        val otherConversation = ConversationScope(otherScope, scope.conversationId)
        val both = coverage.onIssued(otherConversation, issue(mid, changed, 0, 50)).coverage
        assertEquals(2, both.size)
        val withoutFirst = both.invalidate(StateTestSupport.enrollment)
        assertEquals(1, withoutFirst.size)
        assertNull(withoutFirst.frontier(key(mid)))
        assertEquals(0, withoutFirst.frontier(CoverageKey(otherConversation, mid)))
        assertEquals(1, both.invalidate(scope).size)
        assertEquals(1, both.invalidate(otherConversation).size)
        assertEquals(1, both.forget(key(mid)).size)
        assertSame(both, both.forget(key(message(99))))
        assertSame(both, both.invalidate(StateTestSupport.enrollment.copy(reviewEpoch = 7)))
    }

    @Test fun issuedBoundariesAndSpeechIdsAreEnforced() {
        val mid = message(9)
        val src = source("a".repeat(64), 100)
        val id = speechId()
        val coverage = SpeechCoverage.empty().issued(issue(mid, src, 0, 40, id))
        val repeat = coverage.onIssued(scope, issue(mid, src, 0, 40, id))
        assertTrue((repeat.outcome as CoverageOutcome.Issued).sourceChanged.not())
        assertSame("a receipt read of the same speech is idempotent", coverage, repeat.coverage)
        assertEquals(
            CoverageRefusal.SPEECH_ID_CONFLICT,
            (
                coverage.onIssued(
                    scope,
                    issue(mid, src, 0, 41, id)
                ).outcome as CoverageOutcome.Refused
                ).reason
        )
        assertEquals(
            CoverageRefusal.START_NOT_AN_ISSUED_BOUNDARY,
            (
                coverage.onIssued(
                    scope,
                    issue(mid, src, 7, 50)
                ).outcome as CoverageOutcome.Refused
                ).reason
        )
        // 40 is an issued boundary (the end of the issued range); 0 always is
        assertTrue(
            coverage.onIssued(scope, issue(mid, src, 40, 80)).outcome is CoverageOutcome.Issued
        )
        assertTrue(
            coverage.onIssued(scope, issue(mid, src, 0, 20)).outcome is CoverageOutcome.Issued
        )
        // unknown attempts are refused
        assertEquals(
            CoverageRefusal.UNKNOWN_ATTEMPT,
            (coverage.onPlayed(scope, mid, speechId()).outcome as CoverageOutcome.Refused).reason
        )
        assertEquals(
            CoverageRefusal.UNKNOWN_ATTEMPT,
            (
                coverage.onInterrupted(
                    scope,
                    message(77),
                    id,
                    InterruptReason.USER_STOP
                ).outcome as CoverageOutcome.Refused
                ).reason
        )
    }

    // -- bounds ---------------------------------------------------------------------------------------------------------------------------------

    @Test fun everyBoundIsATypedBackpressureThatDropsAndEvictsNothing() {
        // messages
        var coverage = SpeechCoverage.empty()
        for (i in 1..VoiceLimits.COVERAGE_MESSAGES) {
            coverage =
                coverage.issued(issue(message(i), source("a".repeat(64), 100), 0, 10))
        }
        val extra = coverage.onIssued(
            scope,
            issue(message(1_000), source("a".repeat(64), 100), 0, 10)
        )
        assertEquals(CoverageLimit.MESSAGES, (extra.outcome as CoverageOutcome.Backpressure).limit)
        assertSame(coverage, extra.coverage)
        assertEquals(32, coverage.size)
        // attempts per message
        val mid = message(2_000)
        val src = source("d".repeat(64), 1_000)
        var attempts = SpeechCoverage.empty()
        for (i in 0 until VoiceLimits.COVERAGE_ATTEMPTS) {
            attempts =
                attempts.issued(issue(mid, src, 0, 10 + i))
        }
        val ninth = attempts.onIssued(scope, issue(mid, src, 0, 500))
        assertEquals(CoverageLimit.ATTEMPTS, (ninth.outcome as CoverageOutcome.Backpressure).limit)
        assertSame(attempts, ninth.coverage)
        // disjoint played intervals: ranges 0-2 (unplayed), 2-4 (played), 4-6 (unplayed), 6-8 (played) ... leave a gap before every played one
        val m3 = message(3_000)
        val big = source("e".repeat(64), 10_000)
        var scattered = SpeechCoverage.empty()
        var position = 0
        var intervals = 0
        var refusal: CoverageStep? = null
        while (refusal == null) {
            val gap = speechId()
            scattered = scattered.issued(issue(m3, big, position, position + 2, gap))
            val id = speechId()
            scattered = scattered.issued(issue(m3, big, position + 2, position + 4, id))
            val step = scattered.onPlayed(scope, m3, id)
            if (step.outcome is CoverageOutcome.Backpressure) {
                refusal = step
            } else {
                scattered =
                    step.coverage
            }
            if (refusal == null) intervals++
            position += 4
            scattered = scattered.onInterrupted(scope, m3, gap, InterruptReason.REPLACED).coverage
        }
        assertEquals(VoiceLimits.COVERAGE_INTERVALS, intervals)
        assertEquals(
            CoverageLimit.INTERVALS,
            (refusal.outcome as CoverageOutcome.Backpressure).limit
        )
        assertEquals(VoiceLimits.COVERAGE_INTERVALS, scattered.covered(key(m3)).size)
        // boundaries
        val m4 = message(4_000)
        val huge = source("f".repeat(64), 100_000)
        var walk = SpeechCoverage.empty()
        var at = 0
        var boundaryRefusal: CoverageStep? = null
        while (boundaryRefusal == null) {
            val id = speechId()
            val step = walk.onIssued(scope, issue(m4, huge, at, at + 1, id))
            if (step.outcome is CoverageOutcome.Backpressure) {
                boundaryRefusal = step
            } else {
                walk = step.coverage.played(m4, id).first
                at++
            }
        }
        assertEquals(
            CoverageLimit.BOUNDARIES,
            (boundaryRefusal.outcome as CoverageOutcome.Backpressure).limit
        )
        assertEquals(VoiceLimits.COVERAGE_BOUNDARIES, walk.boundaries(key(m4)).size)
    }

    @Test fun theCoverageLedgerIsMemoryOnlyAndNotPartOfAnySnapshot() {
        assertEquals(
            setOf("messages"),
            SpeechCoverage::class.java.declaredFields.map {
                it.name
            }.filter {
                !it.startsWith("$") &&
                    it != "Companion"
            }.toSet()
        )
        val snapshotFields = RelayStateSnapshot::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(
            snapshotFields.any {
                it.lowercase().contains("coverage") ||
                    it.lowercase().contains("range")
            }
        )
        assertFalse(
            SpeechCoverage::class.java.declaredMethods.any {
                it.name.lowercase().contains("snapshot") ||
                    it.name.lowercase().contains("restore")
            }
        )
    }
}
