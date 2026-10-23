package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayFrame
import dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.RestoredState
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix round 1 (review I1, I2): durable billing evidence is monotone. Written and frozen BEFORE the production fix and run on the unfixed BASE
 * (only existing public APIs, so a red result is a billing/end-state assertion, never a compile error).
 *
 * LABEL: the replies that move provider evidence backwards (`in_flight` then `not_started`) or that arrive after the server row was purged are
 * SYNTHETIC stale / reordered / late answers. The genuine producer never sends a backwards pair; these prove the native ledger does not believe one.
 */
class VoiceBillingEvidenceTest {
    private fun notFound(): RelayError = relayErrorOf("not_found")

    /** A result that keeps the capture waiting and carries exactly [provider] as its evidence (state chosen so the DTO is a valid answer). */
    private fun waiting(v: VoiceHarness, key: CaptureKey, provider: VoiceProvider): CaptureResult =
        when (provider) {
            VoiceProvider.NOT_STARTED -> v.result(key, CaptureState.QUEUED)
            VoiceProvider.IN_FLIGHT -> v.result(
                key,
                CaptureState.TRANSCRIBING,
                provider = VoiceProvider.IN_FLIGHT
            )
            VoiceProvider.COMPLETED -> v.transcribed(key, provider = VoiceProvider.COMPLETED)
            VoiceProvider.FAILED_AFTER_SEND -> v.result(
                key,
                CaptureState.TRANSCRIBED,
                null,
                VoiceProvider.FAILED_AFTER_SEND,
                TranscriptSource.LOCAL_FALLBACK,
                "hello voice"
            )
            VoiceProvider.UNKNOWN -> v.transcribed(key, provider = VoiceProvider.UNKNOWN)
        }

    private fun released(): Triple<VoiceHarness, CaptureKey, BindingToken> {
        val v = VoiceHarness()
        val token = v.h.attach()
        return Triple(v, v.released(token), token)
    }

    private fun roundTrip(v: VoiceHarness): RestoredState {
        val snapshot = (
            RelayStateSnapshot.of(
                v.h.ledger,
                emptyList(),
                emptyList(),
                v.captures
            ) as SnapshotBuild.Built
            ).snapshot
        return (
            RelayStateRestore.restore(
                snapshot,
                v.h.now(),
                RelayAuthority.fresh()
            ) as RestoreResult.Restored
            ).value
    }

    // -- I1 --------------------------------------------------------------------------------------------------------------------------

    @Test fun aMissingServerResultNeverErasesPositiveOrUnknownBillingEvidenceAfterRetention() {
        val priors =
            listOf(
                VoiceProvider.IN_FLIGHT,
                VoiceProvider.COMPLETED,
                VoiceProvider.FAILED_AFTER_SEND,
                VoiceProvider.UNKNOWN
            )
        for (prior in priors) {
            val (v, key, _) = released()
            v.onResult(key, waiting(v, key, prior))
            assertEquals(
                "$prior stays waiting",
                CaptureLifecycle.AWAITING_RESULT,
                v.entry(key).lifecycle
            )
            assertEquals(prior, v.entry(key).provider)
            // the device was offline for more than the server's 24 h row retention (design 8.4): the receipt read now answers not_found
            v.h.advance(VoiceLimits.SERVER_RETENTION_MS + 1)
            val before = v.captures
            val beforeEntry = before.entry(key)!!
            v.effects.clear()
            val advice = v.apply(v.captures.onError(key, notFound(), v.h.now()))
            val after = v.entry(key)
            assertEquals("$prior: billing evidence retained", prior, after.provider)
            assertEquals("$prior", CaptureBilling.MAY_HAVE_BEEN_BILLED, after.billing)
            assertEquals(
                "$prior: absence is not proof of no charge",
                CaptureEnd.UNKNOWN_OUTCOME,
                after.end
            )
            assertEquals(
                "$prior: the advice names the very same end",
                CaptureEnd.UNKNOWN_OUTCOME,
                advice.ended
            )
            assertEquals(CaptureLifecycle.ENDED, after.lifecycle)
            assertEquals(v.h.serverMs, after.terminalAtMs)
            assertNull(v.captures.intentOf(key))
            assertEquals(
                "$prior: the new value is owed to the store",
                listOf<CaptureEffect>(CaptureEffect.PersistAggregate),
                v.effects
            )
            // the original value was not mutated (persistent ledger)
            assertEquals(prior, beforeEntry.provider)
            assertEquals(CaptureLifecycle.AWAITING_RESULT, beforeEntry.lifecycle)
            assertNull(beforeEntry.end)
            assertEquals(CaptureLifecycle.AWAITING_RESULT, before.entry(key)!!.lifecycle)
        }
    }

    @Test fun theRetainedEvidenceSurvivesASnapshotAndARealRestoreAndNothingIsSubmitted() {
        for (prior in listOf(
            VoiceProvider.IN_FLIGHT,
            VoiceProvider.COMPLETED,
            VoiceProvider.FAILED_AFTER_SEND,
            VoiceProvider.UNKNOWN
        )) {
            val (v, key, _) = released()
            v.onResult(key, waiting(v, key, prior))
            v.h.advance(VoiceLimits.SERVER_RETENTION_MS + 1)
            v.apply(v.captures.onError(key, notFound(), v.h.now()))
            val back = roundTrip(v)
            val restored = back.captures.entry(key)!!
            assertEquals("$prior", prior, restored.provider)
            assertEquals("$prior", CaptureBilling.MAY_HAVE_BEEN_BILLED, restored.billing)
            assertEquals("$prior", CaptureEnd.UNKNOWN_OUTCOME, restored.end)
            assertEquals(CaptureLifecycle.ENDED, restored.lifecycle)
            assertEquals(v.entry(key).terminalAtMs, restored.terminalAtMs)
            // no turn exists, none is prepared, and a late transcript cannot revive the capture or send anything
            assertEquals(0, back.ledger.size)
            val late = v.transcribed(key)
            val admit = VoiceAdmission.admit(
                back.ledger,
                back.captures,
                key,
                late,
                RelayAuthority.fresh(),
                v.h.now(),
                v.h.entropy,
                v.shape
            )
            assertTrue("$prior $admit", admit.outcome is AdmissionOutcome.NotEligible)
            assertSame(back.ledger, admit.turns)
            assertSame(back.captures, admit.captures)
            assertTrue(admit.effects.isEmpty())
            assertEquals(0, v.h.entropy.calls)
        }
    }

    @Test fun withoutStrongerLocalEvidenceAMissingResultStillMeansNothingWasSealed() {
        // control 1: no evidence at all (finish may have left, nothing ever answered)
        val (v, key, _) = released()
        v.h.advance(VoiceLimits.SERVER_RETENTION_MS + 1)
        val advice = v.apply(v.captures.onError(key, notFound(), v.h.now()))
        assertEquals(CaptureEnd.NOT_SEALED, advice.ended)
        assertEquals(CaptureEnd.NOT_SEALED, v.entry(key).end)
        assertEquals(VoiceProvider.NOT_STARTED, v.entry(key).provider)
        assertEquals(CaptureBilling.NOT_BILLED, v.entry(key).billing)
        // control 2: the last evidence was `not_started` (queued)
        val (w, wKey, _) = released()
        w.onResult(wKey, waiting(w, wKey, VoiceProvider.NOT_STARTED))
        val wAdvice = w.apply(w.captures.onError(wKey, notFound(), w.h.now()))
        assertEquals(CaptureEnd.NOT_SEALED, wAdvice.ended)
        assertEquals(CaptureEnd.NOT_SEALED, w.entry(wKey).end)
        assertEquals(VoiceProvider.NOT_STARTED, w.entry(wKey).provider)
        assertEquals(CaptureBilling.NOT_BILLED, w.entry(wKey).billing)
        // control 3: other lifecycles and other errors are untouched by the repair
        val (x, xKey, _) = released()
        val unchanged = x.captures
        x.apply(x.captures.onError(xKey, relayErrorOf("temporarily_unavailable"), x.h.now()))
        assertSame(unchanged, x.captures)
    }

    private fun relayErrorOf(code: String): RelayError = (
        RelayEnvelope.decodeFrame(
            "{\"type\":\"relay.response\",\"v\":1,\"id\":\"e1\",\"error\":{\"code\":\"$code\"}}"
        ) as RelayFrame.Failure
        ).error

    // -- I2 --------------------------------------------------------------------------------------------------------------------------

    /**
     * The independent expectation (written from the design's billing-truth rules, not from the implementation): evidence only moves forward.
     * `unknown` is sticky both ways; `in_flight` and every concrete answer are never replaced by the weaker `not_started` / `in_flight`;
     * `not_started` yields to anything stronger; an earlier concrete answer (`completed` or `failed_after_send`) is kept over a later concrete one.
     */
    private val expected: Map<Pair<VoiceProvider, VoiceProvider>, VoiceProvider> = run {
        val ns = VoiceProvider.NOT_STARTED
        val inflight = VoiceProvider.IN_FLIGHT
        val done = VoiceProvider.COMPLETED
        val failed = VoiceProvider.FAILED_AFTER_SEND
        val unknown = VoiceProvider.UNKNOWN
        mapOf(
            (ns to ns) to ns, (ns to inflight) to inflight, (ns to done) to done,
            (ns to failed) to failed, (ns to unknown) to unknown,
            (inflight to ns) to inflight, (inflight to inflight) to inflight,
            (inflight to done) to done, (inflight to failed) to failed,
            (inflight to unknown) to unknown,
            (done to ns) to done, (done to inflight) to done, (done to done) to done,
            (done to failed) to done, (done to unknown) to unknown,
            (failed to ns) to failed, (failed to inflight) to failed, (failed to done) to failed,
            (failed to failed) to failed,
            (failed to unknown) to unknown,
            (unknown to ns) to unknown, (unknown to inflight) to unknown,
            (unknown to done) to unknown, (unknown to failed) to unknown,
            (unknown to unknown) to unknown
        )
    }

    @Test fun theMergeMatrixIsExactlyTheIndependentFiveByFiveTableIncludingTheNullRow() {
        assertEquals(25, expected.size)
        for ((pair, want) in expected) {
            assertEquals(
                "merge(${pair.first}, ${pair.second})",
                want,
                VoiceCaptureLedger.mergeProvider(pair.first, pair.second)
            )
        }
        for (incoming in VoiceProvider.entries) {
            assertEquals("null row", incoming, VoiceCaptureLedger.mergeProvider(null, incoming))
        }
    }

    @Test fun anInFlightRecordIsNeverDowngradedByAStaleNotStartedAnswerOnThePublicPath() {
        // SYNTHETIC reordered answer: in_flight first, then a stale queued/not_started one
        val (v, key, _) = released()
        v.onResult(key, waiting(v, key, VoiceProvider.IN_FLIGHT))
        v.effects.clear()
        val outcome = v.onResult(key, waiting(v, key, VoiceProvider.NOT_STARTED))
        assertTrue(outcome is ResultOutcome.Progress)
        assertEquals(VoiceProvider.IN_FLIGHT, v.entry(key).provider)
        assertEquals(CaptureBilling.MAY_HAVE_BEEN_BILLED, v.entry(key).billing)
        assertTrue("nothing changed, nothing is owed", v.effects.isEmpty())
        assertEquals(VoiceProvider.IN_FLIGHT, roundTrip(v).captures.entry(key)!!.provider)
    }

    @Test fun everyOrderedPairOfAnswersLeavesTheStoredAndRestoredBillingAtTheIndependentTable() {
        for ((pair, want) in expected) {
            val (v, key, _) = released()
            v.onResult(key, waiting(v, key, pair.first))
            v.onResult(key, waiting(v, key, pair.second))
            assertEquals("answers ${pair.first} then ${pair.second}", want, v.entry(key).provider)
            assertEquals(
                "billing for ${pair.first} then ${pair.second}",
                if (want ==
                    VoiceProvider.NOT_STARTED
                ) {
                    CaptureBilling.NOT_BILLED
                } else {
                    CaptureBilling.MAY_HAVE_BEEN_BILLED
                },
                v.entry(key).billing
            )
            assertEquals("restored", want, roundTrip(v).captures.entry(key)!!.provider)
        }
    }
}
