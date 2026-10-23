package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure capture ledger: every client state of design §8.4, the persist-before-start/finish obligations, bounds and invalidation. */
class VoiceCaptureLedgerTest {
    private fun ready(): Triple<
        VoiceHarness,
        dev.quantumink.hermesgadget.protocol.relay.state.BindingToken,
        CaptureKey
        > {
        val v = VoiceHarness()
        val token = v.h.attach()
        return Triple(v, token, v.begin(token))
    }

    // -- press: begin ------------------------------------------------------------------------------------------------------------------

    @Test fun aPressMintsADistinctTypedIdFromServerTimeAndRecordsRecordingWithAPersistObligation() {
        val v = VoiceHarness()
        val token = v.h.attach()
        val turn = v.h.prepare()
        val key = v.begin(token)
        val entry = v.entry(key)
        assertEquals(CaptureLifecycle.RECORDING, entry.lifecycle)
        assertEquals(listOf<CaptureEffect>(CaptureEffect.PersistAggregate), v.effects)
        assertEquals(v.h.serverMs, entry.createdAtMs)
        assertEquals(entry.createdAtMs, RequestIds.timestampMs(RequestId.of(key.captureId.value)!!))
        assertNotEquals(turn.requestId.value, key.captureId.value)
        assertEquals(token.serial, v.captures.intentOf(key)!!.bindingSerial)
        assertNull(v.captures.intentOf(key)!!.releasedAtMs)
        assertEquals(CaptureBilling.NOT_BILLED, entry.billing)
        assertEquals(1, v.captureEntropy.calls)
    }

    @Test fun anIdCollisionWithATurnIdIsARefusalNeverAReMintOrAPartialRecord() {
        val v = VoiceHarness()
        val token = v.h.attach()
        v.h.prepare() // entropy seed 1 at this exact instant
        val before = v.captures
        val same = StateTestSupport.CountingEntropy(1)
        val step = v.captures.begin(v.h.ledger, v.h.authority, token, v.h.now(), same, v.shape)
        val outcome = step.result as BeginOutcome.Refused
        assertEquals(BeginRefusal.ID_COLLISION, outcome.reason)
        assertSame(before, step.ledger)
        assertTrue(step.effects.isEmpty())
        assertEquals("one mint, no second attempt", 1, same.calls)
    }

    @Test fun beginNeedsAnActiveScopeTheCurrentRelayBindingAndAMonotonicClock() {
        val v = VoiceHarness()
        val old = v.h.attach()
        val current = v.h.attach() // a new attach retires the first
        fun outcome(token: dev.quantumink.hermesgadget.protocol.relay.state.BindingToken) =
            v.captures.begin(
                v.h.ledger,
                v.h.authority,
                token,
                v.h.now(),
                v.captureEntropy,
                v.shape
            ).result as BeginOutcome.Refused
        assertEquals(BeginRefusal.BINDING_NOT_CURRENT, outcome(old).reason)
        // a refused-by-server scope: permission_denied stops it, profile_changed needs review
        for ((code, expected) in listOf(
            "permission_denied" to BeginRefusal.SCOPE_STOPPED,
            "profile_changed" to BeginRefusal.SCOPE_NEEDS_REVIEW
        )) {
            val w = VoiceHarness()
            val token = w.h.attach()
            val key = w.h.prepare()
            val dispatch = w.h.planned(w.h.submit(key, token))
            w.h.fail(dispatch, code)
            assertEquals(
                expected,
                (
                    w.captures.begin(
                        w.h.ledger,
                        w.h.authority,
                        token,
                        w.h.now(),
                        w.captureEntropy,
                        w.shape
                    ).result as BeginOutcome.Refused
                    ).reason
            )
        }
        v.begin(current)
        val earlier = StateTestSupport.raw(v.h.serverMs, v.h.nanos - 1)
        val rolled = v.captures.begin(
            v.h.ledger,
            v.h.authority,
            current,
            earlier,
            v.captureEntropy,
            v.shape
        ).result as BeginOutcome.Refused
        assertEquals(BeginRefusal.CLOCK_ROLLBACK, rolled.reason)
    }

    @Test fun aSnapshotThatCannotBeBuiltRefusesTheNewCaptureWholeBeforeAnythingExists() {
        val v = VoiceHarness()
        val token = v.h.attach()
        val before = v.captures
        v.shape = AggregateShape(histories = 17, deliveries = 0)
        val step = v.captures.begin(
            v.h.ledger,
            v.h.authority,
            token,
            v.h.now(),
            v.captureEntropy,
            v.shape
        )
        val refused = step.result as BeginOutcome.AggregateTooMany
        assertEquals("histories", refused.path)
        assertEquals(16, refused.limit)
        assertSame(before, step.ledger)
        assertTrue(step.effects.isEmpty())
        // control: the same press with a buildable aggregate succeeds, so the refusal above was the aggregate guard
        v.shape = AggregateShape(histories = 16, deliveries = 16)
        assertTrue(
            v.captures.begin(
                v.h.ledger,
                v.h.authority,
                token,
                v.h.now(),
                v.captureEntropy,
                v.shape
            ).result is BeginOutcome.Began
        )
    }

    // -- the calls and persist-before-send --------------------------------------------------------------------------------------------

    @Test fun startIsPlannedFromALiveRecordingWithPersistBeforeStartAndExactParams() {
        val (v, token, key) = ready()
        v.effects.clear()
        val plan = v.start(token, key) as CapturePlan.Planned
        assertEquals(RelayMethod.VOICE_CAPTURE_START, plan.call.method)
        assertEquals(listOf<CaptureEffect>(CaptureEffect.PersistBeforeStart(key)), v.effects)
        assertEquals(token.bindingId.value, plan.call.params["binding_id"]!!.toString().trim('"'))
        assertEquals(
            key.conversation.conversationId.value,
            plan.call.params["conversation_id"]!!.toString().trim('"')
        )
        assertEquals(CaptureLifecycle.RECORDING, v.entry(key).lifecycle)
    }

    @Test fun startAndFinishAreRefusedWithoutLiveAudioTheCurrentBindingOrTheRightLifecycle() {
        val (v, token, key) = ready()
        val newer = v.h.attach()
        assertEquals(
            PlanRefusal.BINDING_NOT_CURRENT,
            (v.start(token, key) as CapturePlan.Refused).reason
        )
        assertEquals(
            PlanRefusal.BINDING_NOT_CURRENT,
            (v.finish(token, key) as CapturePlan.Refused).reason
        )
        // the newer binding is current but the recording was made under the older one: its intent names the older serial
        assertEquals(
            PlanRefusal.BINDING_NOT_CURRENT,
            (v.start(newer, key) as CapturePlan.Refused).reason
        )
        val w = VoiceHarness()
        val t = w.h.attach()
        val k = w.begin(t)
        w.apply(w.captures.withdrawIntents(k.conversation))
        assertEquals(PlanRefusal.AUDIO_NOT_LIVE, (w.start(t, k) as CapturePlan.Refused).reason)
        assertEquals(PlanRefusal.AUDIO_NOT_LIVE, (w.finish(t, k) as CapturePlan.Refused).reason)
        val x = VoiceHarness()
        val u = x.h.attach()
        val j = x.released(u)
        assertEquals(PlanRefusal.WRONG_LIFECYCLE, (x.finish(u, j) as CapturePlan.Refused).reason)
        assertEquals(PlanRefusal.WRONG_LIFECYCLE, (x.start(u, j) as CapturePlan.Refused).reason)
        val unknown =
            CaptureKey(j.conversation, CaptureId.of("01a14d67-a300-7000-9f3a-17469850b711")!!)
        assertEquals(
            PlanRefusal.UNKNOWN_CAPTURE,
            (x.finish(u, unknown) as CapturePlan.Refused).reason
        )
    }

    @Test fun finishingIsPersistedBeforeTheFinishCallAndCarriesTheUploadsOwnCounts() {
        val (v, token, key) = ready()
        v.effects.clear()
        val plan = v.finish(token, key, v.counts(13, 330)) as CapturePlan.Planned
        assertEquals(RelayMethod.VOICE_CAPTURE_FINISH, plan.call.method)
        assertEquals(CaptureLifecycle.FINISHING, v.entry(key).lifecycle)
        assertEquals(
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.PersistBeforeFinish(key)),
            v.effects
        )
        assertEquals("13", plan.call.params["frames"].toString())
        // the stock floor, from the real assembly
        assertEquals("250", plan.call.params["duration_ms"].toString())
        assertEquals(v.h.serverMs, v.captures.intentOf(key)!!.releasedAtMs)
        assertEquals(CaptureBilling.MAY_HAVE_BEEN_BILLED, v.entry(key).billing)
    }

    @Test fun aRecordingThatIsTooShortOrEmptyIsNeverFinished() {
        val (v, token, key) = ready()
        assertEquals(
            PlanRefusal.NO_AUDIO,
            (v.finish(token, key, null) as CapturePlan.Refused).reason
        )
        val short = v.counts(6)
        assertTrue(short.tooShort)
        assertEquals(
            PlanRefusal.TOO_SHORT,
            (v.finish(token, key, short) as CapturePlan.Refused).reason
        )
        assertEquals(CaptureLifecycle.RECORDING, v.entry(key).lifecycle)
        assertTrue(v.finish(token, key) is CapturePlan.Planned)
    }

    @Test fun snapshotTooManyRefusesTheFinishBeforeAnySendCanBePlannedAndTheUserCancelsInstead() {
        val (v, token, key) = ready()
        v.effects.clear()
        val before = v.captures
        v.shape = AggregateShape(histories = 0, deliveries = 17)
        val step = v.captures.planFinish(
            v.h.ledger,
            v.h.authority,
            token,
            key,
            v.stream,
            v.counts(),
            v.h.now(),
            v.shape
        )
        val refused = step.result as CapturePlan.Refused
        assertEquals(PlanRefusal.SNAPSHOT_TOO_MANY, refused.reason)
        assertEquals("deliveries", refused.path)
        assertEquals(16, refused.limit)
        assertSame("no record changed, nothing dropped", before, step.ledger)
        assertTrue(step.effects.none { it is CaptureEffect.PersistBeforeFinish })
        // control: with a buildable aggregate the very same call plans, so the refusal came from the aggregate guard
        v.shape = AggregateShape.EMPTY
        assertTrue(v.finish(token, key) is CapturePlan.Planned)
        // and the refused path ends in a cancel with a discard, not a finish
        val (w, wToken, wKey) = ready()
        w.shape = AggregateShape(histories = 17, deliveries = 0)
        assertTrue(w.finish(wToken, wKey) is CapturePlan.Refused)
        assertTrue(w.apply(w.captures.cancel(wKey, w.h.now())))
        assertEquals(CaptureLifecycle.CANCELLED, w.entry(wKey).lifecycle)
        assertTrue(w.effects.contains(CaptureEffect.SendDiscard(wKey)))
        assertTrue(w.effects.none { it is CaptureEffect.PersistBeforeFinish })
    }

    @Test fun receiptReadsAndDiscardsArePlannedOnlyForCapturesWhoseStateAllowsThem() {
        val (v, token, key) = ready()
        assertEquals(
            PlanRefusal.WRONG_LIFECYCLE,
            (v.captures.planResult(key, 0) as CapturePlan.Refused).reason
        )
        // a recording can drop its uploaded audio
        assertTrue(v.captures.planDiscard(key) is CapturePlan.Planned)
        v.finish(token, key)
        val result = v.captures.planResult(key, 20_000) as CapturePlan.Planned
        assertEquals(RelayMethod.VOICE_CAPTURE_RESULT, result.call.method)
        assertTrue(runCatching { v.captures.planResult(key, 20_001) }.isFailure)
        assertEquals(
            PlanRefusal.UNKNOWN_CAPTURE,
            (VoiceCaptureLedger.empty().planResult(key, 0) as CapturePlan.Refused).reason
        )
        v.onResult(
            key,
            v.result(
                key,
                CaptureState.EMPTY,
                provider = VoiceProvider.COMPLETED,
                source = TranscriptSource.CONFIGURED
            )
        )
        assertEquals(
            PlanRefusal.WRONG_LIFECYCLE,
            (v.captures.planDiscard(key) as CapturePlan.Refused).reason
        )
        assertEquals(
            PlanRefusal.WRONG_LIFECYCLE,
            (v.captures.planResult(key, 0) as CapturePlan.Refused).reason
        )
    }

    // -- local endings ----------------------------------------------------------------------------------------------------------------

    @Test fun cancelLoseAndReconcileEndOnlyRecordingsAndNeverBillAnything() {
        val (v, token, key) = ready()
        assertTrue(v.apply(v.captures.cancel(key, v.h.now())))
        assertEquals(CaptureLifecycle.CANCELLED, v.entry(key).lifecycle)
        assertEquals(CaptureBilling.NOT_BILLED, v.entry(key).billing)
        assertEquals(v.h.serverMs, v.entry(key).terminalAtMs)
        assertNull(v.captures.intentOf(key))
        assertFalse(
            "a terminal record is not cancelled twice",
            v.apply(v.captures.cancel(key, v.h.now()))
        )
        val released = v.released(token)
        assertFalse(
            "once finish may have left the server owns it",
            v.apply(v.captures.cancel(released, v.h.now()))
        )
        assertFalse(v.apply(v.captures.lose(released, v.h.now())))
        val w = VoiceHarness()
        val t = w.h.attach()
        val a = w.begin(t)
        val b = w.begin(t)
        w.apply(w.captures.withdrawIntents(a.conversation)) // a restart left these without audio
        val count = w.apply(w.captures.reconcileRecordings(w.h.now()))
        assertEquals(2, count)
        assertEquals(CaptureLifecycle.LOST, w.entry(a).lifecycle)
        assertEquals(CaptureLifecycle.LOST, w.entry(b).lifecycle)
        assertEquals(0, w.apply(w.captures.reconcileRecordings(w.h.now())))
    }

    // -- results ----------------------------------------------------------------------------------------------------------------------

    @Test fun everyServerStateMovesTheCaptureAsTheTableSays() {
        data class Row(
            val state: CaptureState,
            val reason: CaptureFailure?,
            val provider: VoiceProvider,
            val sealed: Long?,
            val end: CaptureEnd?,
            val source: TranscriptSource?
        )
        val rows = listOf(
            Row(
                CaptureState.EMPTY,
                null,
                VoiceProvider.COMPLETED,
                1,
                CaptureEnd.EMPTY,
                TranscriptSource.CONFIGURED
            ),
            Row(
                CaptureState.TOO_LONG,
                null,
                VoiceProvider.COMPLETED,
                1,
                CaptureEnd.TRANSCRIPT_TOO_LONG,
                TranscriptSource.CONFIGURED
            ),
            Row(
                CaptureState.FAILED,
                CaptureFailure.PROVIDER_FAILED,
                VoiceProvider.FAILED_AFTER_SEND,
                1,
                CaptureEnd.FAILED,
                null
            ),
            Row(
                CaptureState.FAILED,
                CaptureFailure.STT_UNAVAILABLE,
                VoiceProvider.NOT_STARTED,
                1,
                CaptureEnd.FAILED,
                null
            ),
            Row(
                CaptureState.FAILED,
                CaptureFailure.INTERRUPTED,
                VoiceProvider.NOT_STARTED,
                1,
                CaptureEnd.FAILED,
                null
            ),
            Row(
                CaptureState.FAILED,
                CaptureFailure.TOO_SHORT,
                VoiceProvider.NOT_STARTED,
                null,
                CaptureEnd.PRE_SEAL_FAILED,
                null
            ),
            Row(
                CaptureState.DISCARDED,
                null,
                VoiceProvider.COMPLETED,
                1,
                CaptureEnd.DISCARDED,
                null
            ),
            Row(
                CaptureState.EXPIRED,
                null,
                VoiceProvider.COMPLETED,
                1,
                CaptureEnd.EXPIRED,
                TranscriptSource.CONFIGURED
            ),
            Row(CaptureState.REVOKED, null, VoiceProvider.COMPLETED, 1, CaptureEnd.REVOKED, null),
            Row(
                CaptureState.UNKNOWN,
                null,
                VoiceProvider.UNKNOWN,
                1,
                CaptureEnd.UNKNOWN_OUTCOME,
                null
            )
        )
        for (row in rows) {
            val (v, token, _) = ready()
            val key = v.captures.entries.first().key
            v.finish(token, key)
            v.effects.clear()
            val outcome = v.onResult(
                key,
                v.result(key, row.state, row.reason, row.provider, row.source, null, row.sealed)
            )
            assertTrue("${row.state} $outcome", outcome is ResultOutcome.Ended)
            assertEquals(row.end, (outcome as ResultOutcome.Ended).reason)
            val entry = v.entry(key)
            assertEquals(CaptureLifecycle.ENDED, entry.lifecycle)
            assertEquals(row.end, entry.end)
            assertEquals(row.reason, entry.failure)
            assertEquals(v.h.serverMs, entry.terminalAtMs)
            assertEquals(listOf<CaptureEffect>(CaptureEffect.PersistAggregate), v.effects)
            assertNull("an ended capture has no intent", v.captures.intentOf(key))
            assertEquals(
                if (row.provider ==
                    VoiceProvider.NOT_STARTED
                ) {
                    CaptureBilling.NOT_BILLED
                } else {
                    CaptureBilling.MAY_HAVE_BEEN_BILLED
                },
                entry.billing
            )
        }
    }

    @Test fun progressStatesKeepTheCaptureWaitingAndUpdateOnlyTheBillingEvidence() {
        val (v, token, _) = ready()
        val key = v.captures.entries.first().key
        v.finish(token, key)
        v.effects.clear()
        assertEquals(
            CaptureState.QUEUED,
            (v.onResult(key, v.result(key, CaptureState.QUEUED)) as ResultOutcome.Progress).state
        )
        assertEquals(CaptureLifecycle.AWAITING_RESULT, v.entry(key).lifecycle)
        assertEquals(1, v.effects.size) // FINISHING -> AWAITING_RESULT is durable
        v.effects.clear()
        v.onResult(key, v.result(key, CaptureState.QUEUED))
        assertTrue("an identical progress answer persists nothing", v.effects.isEmpty())
        v.onResult(key, v.result(key, CaptureState.PREPARING))
        assertTrue(v.effects.isEmpty())
        v.onResult(
            key,
            v.result(key, CaptureState.TRANSCRIBING, provider = VoiceProvider.IN_FLIGHT)
        )
        assertEquals(VoiceProvider.IN_FLIGHT, v.entry(key).provider)
        assertEquals(1, v.effects.size)
        assertEquals(CaptureBilling.MAY_HAVE_BEEN_BILLED, v.entry(key).billing)
        assertNotNull(v.captures.intentOf(key)) // still waiting: the intent is kept
        assertTrue(v.onResult(key, v.transcribed(key)) is ResultOutcome.TranscriptReady)
        assertEquals(CaptureLifecycle.AWAITING_RESULT, v.entry(key).lifecycle)
    }

    @Test fun billingEvidenceIsStickyAndNeverDowngradedToNotBilled() {
        val merge = { current: VoiceProvider?, incoming: VoiceProvider ->
            VoiceCaptureLedger.mergeProvider(current, incoming)
        }
        assertEquals(VoiceProvider.IN_FLIGHT, merge(null, VoiceProvider.IN_FLIGHT))
        assertEquals(
            VoiceProvider.COMPLETED,
            merge(VoiceProvider.IN_FLIGHT, VoiceProvider.COMPLETED)
        )
        assertEquals(
            VoiceProvider.COMPLETED,
            merge(VoiceProvider.COMPLETED, VoiceProvider.NOT_STARTED)
        )
        assertEquals(
            VoiceProvider.COMPLETED,
            merge(VoiceProvider.COMPLETED, VoiceProvider.IN_FLIGHT)
        )
        assertEquals(VoiceProvider.UNKNOWN, merge(VoiceProvider.COMPLETED, VoiceProvider.UNKNOWN))
        assertEquals(VoiceProvider.UNKNOWN, merge(VoiceProvider.UNKNOWN, VoiceProvider.COMPLETED))
        assertEquals(VoiceProvider.UNKNOWN, merge(VoiceProvider.UNKNOWN, VoiceProvider.NOT_STARTED))
        assertEquals(
            VoiceProvider.FAILED_AFTER_SEND,
            merge(VoiceProvider.NOT_STARTED, VoiceProvider.FAILED_AFTER_SEND)
        )
        assertEquals(
            VoiceProvider.COMPLETED,
            merge(VoiceProvider.COMPLETED, VoiceProvider.FAILED_AFTER_SEND)
        )
    }

    @Test fun foreignLateAndFrozenResultsAreIgnoredBeforeAnyStateChanges() {
        val (v, token, key) = ready()
        v.finish(token, key)
        val before = v.captures
        // another capture id, or the right id under another conversation
        val otherId = CaptureId.of("01a14d67-a300-7000-9f3a-17469850b711")!!
        val foreignId =
            CaptureResult(
                otherId, key.conversation.conversationId, CaptureState.EMPTY, null, null,
                null, TranscriptSource.CONFIGURED, VoiceProvider.COMPLETED, 1
            )
        val foreignConversation = CaptureResult(
            key.captureId,
            dev.quantumink.hermesgadget.protocol.relay.ConversationId.of(
                "fc25a8ad-dc63-4ed7-bc95-2066ab0172c2"
            )!!,
            CaptureState.EMPTY, null, null, null, TranscriptSource.CONFIGURED,
            VoiceProvider.COMPLETED, 1
        )
        for (foreign in listOf(foreignId, foreignConversation)) {
            val step = v.captures.onResult(v.h.ledger, key, foreign, v.h.now())
            assertEquals(IgnoredResult.FOREIGN_RESULT, (step.result as ResultOutcome.Ignored).why)
            assertSame(before, step.ledger)
        }
        assertEquals(
            IgnoredResult.UNKNOWN_CAPTURE,
            (
                VoiceCaptureLedger.empty().onResult(
                    v.h.ledger,
                    key,
                    v.result(key, CaptureState.EMPTY, provider = VoiceProvider.COMPLETED),
                    v.h.now()
                ).result as ResultOutcome.Ignored
                ).why
        )
        // a late answer for a capture that already ended, was cancelled or is linked changes nothing
        v.onResult(
            key,
            v.result(
                key,
                CaptureState.EMPTY,
                provider = VoiceProvider.COMPLETED,
                source = TranscriptSource.CONFIGURED
            )
        )
        val ended = v.captures
        val late = v.captures.onResult(
            v.h.ledger,
            key,
            v.result(key, CaptureState.UNKNOWN, provider = VoiceProvider.UNKNOWN),
            v.h.now()
        )
        assertEquals(IgnoredResult.NOT_AWAITING, (late.result as ResultOutcome.Ignored).why)
        assertSame(ended, late.ledger)
        // a frozen scope: permission_denied on a turn stops it; no result is processed under it
        val w = VoiceHarness()
        val t = w.h.attach()
        val k = w.released(t)
        val turn = w.h.prepare()
        w.h.fail(w.h.planned(w.h.submit(turn, t)), "permission_denied")
        val frozen = w.captures.onResult(w.h.ledger, k, w.result(k, CaptureState.QUEUED), w.h.now())
        assertEquals(IgnoredResult.SCOPE_FROZEN, (frozen.result as ResultOutcome.Ignored).why)
        assertSame(w.captures, frozen.ledger)
    }

    @Test fun notFoundOfAResultReadMeansNothingWasSealedAndAStaleBindingWithdrawsTheIntent() {
        val (v, token, key) = ready()
        v.finish(token, key)
        val advice = v.apply(v.captures.onError(key, relayError("not_found"), v.h.now()))
        assertEquals(CaptureEnd.NOT_SEALED, advice.ended)
        assertEquals(CaptureBilling.NOT_BILLED, v.entry(key).billing)
        assertEquals(CaptureLifecycle.ENDED, v.entry(key).lifecycle)
        val (w, wToken, wKey) = ready()
        w.finish(wToken, wKey)
        for (code in listOf(
            "stale_binding",
            "stale_epoch",
            "permission_denied",
            "profile_changed"
        )) {
            val x = VoiceHarness()
            val xt = x.h.attach()
            val xk = x.released(xt)
            assertNotNull(x.captures.intentOf(xk))
            x.apply(x.captures.onError(xk, relayError(code), x.h.now()))
            assertNull(code, x.captures.intentOf(xk))
            assertEquals(code, CaptureLifecycle.FINISHING, x.entry(xk).lifecycle)
        }
        for (code in listOf("busy", "rate_limited", "temporarily_unavailable", "conflict")) {
            val x = VoiceHarness()
            val xt = x.h.attach()
            val xk = x.released(xt)
            val before = x.captures
            x.apply(x.captures.onError(xk, relayError(code), x.h.now()))
            assertSame(code, before, x.captures)
        }
    }

    private fun relayError(code: String) =
        dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope.decodeFrame(
            "{\"type\":\"relay.response\",\"v\":1,\"id\":\"e1\",\"error\":{\"code\":\"$code\"}}"
        ).let { (it as dev.quantumink.hermesgadget.protocol.relay.RelayFrame.Failure).error }

    // -- invalidation and independence -----------------------------------------------------------------------------------------------

    @Test fun hideSelectionChangeAndBindingLossWithdrawOnlyTheirOwnIntentsAndKeepTheRecords() {
        val v = VoiceHarness()
        val token = v.h.attach()
        val a = v.released(token)
        assertEquals(1, v.apply(v.captures.withdrawIntents(v.h.enrollment)))
        assertNull(v.captures.intentOf(a))
        assertEquals(CaptureLifecycle.FINISHING, v.entry(a).lifecycle)
        assertEquals(0, v.apply(v.captures.withdrawIntents(v.h.enrollment)))
        val w = VoiceHarness()
        val t1 = w.h.attach()
        val k1 = w.begin(t1)
        val t2 = w.h.attach() // reconnect: a new binding retires the first
        val k2 = w.begin(t2)
        assertEquals(1, w.apply(w.captures.bindingLost(w.h.enrollment, t1.serial)))
        assertNull(w.captures.intentOf(k1))
        assertNotNull(w.captures.intentOf(k2))
        assertEquals(1, w.apply(w.captures.bindingLost(w.h.enrollment)))
        assertEquals(2, w.captures.size)
    }

    @Test fun twoEnrollmentsWithEqualTextualIdsAreIndependentScopes() {
        val v = VoiceHarness()
        val tokenA = v.h.attach()
        val (scopeB, tokenB) = v.otherScope()
        val (scopeC, tokenC) = v.otherScope(endpoint = "endpoint-a", enrollmentKey = "phone-key-2")
        val (scopeD, tokenD) = v.otherScope(
            endpoint = "endpoint-a",
            enrollmentKey = "phone-key-1",
            epoch = 1
        )
        val keys = listOf(tokenA, tokenB, tokenC, tokenD).map { v.begin(it) }
        assertEquals(4, keys.toSet().size)
        assertEquals(setOf(v.h.enrollment, scopeB, scopeC, scopeD).size, 4)
        assertEquals(1, v.captures.entriesFor(scopeB).size)
        // a result bound to scope A's conversation cannot move scope B's capture, whatever the textual ids
        val foreign = v.result(keys[0], CaptureState.QUEUED)
        v.apply(
            v.captures.planFinish(
                v.h.ledger,
                v.h.authority,
                tokenB,
                keys[1],
                v.stream,
                v.counts(),
                v.h.now(),
                v.shape
            )
        )
        val step = v.captures.onResult(v.h.ledger, keys[1], foreign, v.h.now())
        assertTrue(step.result is ResultOutcome.Ignored)
        // retiring one scope removes only its records and intents
        assertEquals(1, v.apply(v.captures.withdrawIntents(scopeB)))
        val retired = v.apply(v.captures.retireScope(scopeC))
        assertEquals(listOf(keys[2]), retired)
        assertNull(v.captures.entry(keys[2]))
        assertNotNull(v.captures.entry(keys[0]))
        assertNotNull(v.captures.intentOf(keys[0]))
        assertNotNull(v.captures.entry(keys[3]))
        assertTrue(v.apply(v.captures.retireScope(scopeC)).isEmpty())
    }

    // -- bounds and housekeeping -------------------------------------------------------------------------------------------------------

    @Test fun sixteenPerEnrollmentAndSixtyFourInTotalRefuseTheNextPressAndNeverEvict() {
        val v = VoiceHarness()
        val scopes =
            listOf(v.h.enrollment to v.h.attach()) +
                (1..3).map { v.otherScope(endpoint = "endpoint-$it") }
        val all = ArrayList<CaptureKey>()
        for ((scope, token) in scopes) {
            repeat(VoiceLimits.CAPTURES_PER_ENROLLMENT) {
                val key = v.begin(token)
                // terminal records stay: nothing is evicted to make room
                assertTrue(v.apply(v.captures.cancel(key, v.h.now())))
                all.add(key)
            }
            val step = v.captures.begin(
                v.h.ledger,
                v.h.authority,
                token,
                v.h.now(),
                v.captureEntropy,
                v.shape
            )
            assertEquals(
                CaptureLimit.PER_ENROLLMENT,
                (step.result as BeginOutcome.Backpressure).limit
            )
            assertSame(v.captures, step.ledger)
            assertEquals(scope, v.captures.entriesFor(scope).first().key.conversation.enrollment)
        }
        assertEquals(64, v.captures.size)
        assertEquals(64, all.size)
        assertTrue(all.all { v.captures.entry(it) != null })
        // a fifth enrollment hits the TOTAL, not its own 16
        val (_, fifth) = v.otherScope(endpoint = "endpoint-5")
        val total = v.captures.begin(
            v.h.ledger,
            v.h.authority,
            fifth,
            v.h.now(),
            v.captureEntropy,
            v.shape
        )
        assertEquals(CaptureLimit.TOTAL, (total.result as BeginOutcome.Backpressure).limit)
        // dismiss frees exactly one explicit slot; a non-terminal record can never be dismissed
        assertTrue(v.apply(v.captures.dismiss(all.first())))
        assertEquals(63, v.captures.size)
        assertTrue(
            v.captures.begin(
                v.h.ledger,
                v.h.authority,
                fifth,
                v.h.now(),
                v.captureEntropy,
                v.shape
            ).result is BeginOutcome.Began
        )
    }

    @Test fun terminalRecordsLeaveOnlyByPruneAfterTwentyFourHoursOrAnExplicitDismiss() {
        val (v, token, key) = ready()
        val live = v.begin(token)
        assertTrue(v.apply(v.captures.cancel(key, v.h.now())))
        assertFalse("a recording is never dismissed", v.apply(v.captures.dismiss(live)))
        v.h.advance(VoiceLimits.TERMINAL_RETENTION_MS - 1)
        assertEquals(0, v.apply(v.captures.prune(v.h.now())))
        v.h.advance(1)
        assertEquals(1, v.apply(v.captures.prune(v.h.now())))
        assertNull(v.captures.entry(key))
        assertNotNull("the open recording is untouched by the prune", v.captures.entry(live))
        assertTrue(v.effects.last() == CaptureEffect.PersistAggregate)
    }

    @Test fun recordsHoldNoAudioStreamBindingWaveformOrPlaybackState() {
        val names =
            CaptureEntry::class.java.declaredFields.map { it.name }.toSet() +
                CaptureRecordSnapshot::class.java.declaredFields.map { it.name }
        val forbidden =
            listOf(
                "audio", "pcm", "opus", "stream", "socket", "binding", "level", "waveform",
                "playback", "token", "transcript", "text", "draft"
            )
        for (name in names) assertFalse(name, forbidden.any { name.lowercase().contains(it) })
        // the intent is transient and carries only a serial and a time, never authority
        assertEquals(
            setOf("bindingSerial", "releasedAtMs"),
            CaptureIntent::class.java.declaredFields.map {
                it.name
            }.toSet()
        )
    }
}
