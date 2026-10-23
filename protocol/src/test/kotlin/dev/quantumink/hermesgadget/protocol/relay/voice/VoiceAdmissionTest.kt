package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.DiscardMode
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerLimit
import dev.quantumink.hermesgadget.protocol.relay.state.PlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PrepareRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import dev.quantumink.hermesgadget.protocol.relay.state.TurnEffect
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.state.UncertainReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The atomic Task2 prepare + capture link. Every refusal leaves BOTH halves as the very input instances (identity, not equality); every success changes
 * both in one returned value; a linked capture never prepares again; a draft never submits.
 */
class VoiceAdmissionTest {
    private class Scene(
        val v: VoiceHarness,
        val token: dev.quantumink.hermesgadget.protocol.relay.state.BindingToken,
        val key: CaptureKey,
        val result: CaptureResult
    )

    private fun scene(text: String = "hello voice"): Scene {
        val v = VoiceHarness()
        val token = v.h.attach()
        val key = v.released(token)
        val result = v.transcribed(key, text)
        assertTrue(v.onResult(key, result) is ResultOutcome.TranscriptReady)
        return Scene(v, token, key, result)
    }

    private fun admit(
        s: Scene,
        entropy: RequestIdEntropy = s.v.h.entropy,
        ttl: Long = StateLimits.DEFAULT_TURN_TTL_MS,
        now: dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant = s.v.h.now()
    ): AdmissionStep = VoiceAdmission.admit(
        s.v.h.ledger, s.v.captures, s.key, s.result, s.v.h.authority, now,
        entropy, s.v.shape, ttl
    )

    private fun commit(s: Scene, step: AdmissionStep) {
        s.v.h.ledger = step.turns
        s.v.captures = step.captures
        s.v.effects.addAll(step.effects)
    }

    private fun assertNothingChanged(s: Scene, step: AdmissionStep) {
        assertSame("the turn ledger is the very input instance", s.v.h.ledger, step.turns)
        assertSame("the capture ledger is the very input instance", s.v.captures, step.captures)
        assertTrue(step.effects.isEmpty())
    }

    // -- the one atomic transition -----------------------------------------------------------------------------------------------------

    @Test fun aCurrentTranscriptPreparesTheTurnAndLinksTheCaptureInOneReturnedTransition() {
        val s = scene("  hello voice with spaces  ")
        val turnsBefore = s.v.h.ledger
        val capturesBefore = s.v.captures
        val callsBefore = s.v.h.entropy.calls
        val step = admit(s)
        val linked = step.outcome as AdmissionOutcome.Linked
        assertEquals(s.key, linked.capture)
        assertEquals(
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.MayDiscardServerCopy(s.key)),
            step.effects
        )
        assertNotEquals(turnsBefore, step.turns)
        assertEquals("exactly one id was minted", callsBefore + 1, s.v.h.entropy.calls)
        // both halves changed together
        val turn = step.turns.entry(linked.turn)!!
        assertEquals("  hello voice with spaces  ", turn.text)
        assertEquals(TurnPhase.PREPARED, turn.phase)
        assertEquals(s.v.h.serverMs + StateLimits.DEFAULT_TURN_TTL_MS, turn.expiresAtMs)
        assertEquals(s.key.conversation, linked.turn.conversation)
        assertEquals(s.v.h.serverMs, RequestIds.timestampMs(linked.turn.requestId))
        val entry = step.captures.entry(s.key)!!
        assertEquals(CaptureLifecycle.ADMITTING, entry.lifecycle)
        assertEquals(linked.turn.requestId, entry.linkedRequest)
        assertNotEquals(s.key.captureId.value, linked.turn.requestId.value)
        assertEquals(VoiceProvider.COMPLETED, entry.provider)
        assertNull(step.captures.intentOf(s.key))
        // the inputs were not touched (immutable values)
        assertEquals(0, turnsBefore.size)
        assertEquals(null, capturesBefore.entry(s.key)!!.linkedRequest)
        // the consumer persists (one write), then plans the submit: persist-before-send is Task2's own obligation
        commit(s, step)
        val plan = s.v.h.submit(linked.turn, s.token)
        assertTrue(plan is PlanOutcome.Planned)
        assertTrue(s.v.h.effects.contains(TurnEffect.PersistBeforeSend(linked.turn)))
        // and the aggregate snapshot holds the prepared turn and the link in the SAME value
        val built = (
            RelayStateSnapshot.of(
                s.v.h.ledger,
                emptyList(),
                emptyList(),
                s.v.captures
            ) as SnapshotBuild.Built
            ).snapshot
        assertEquals(1, built.ledger.turns.size)
        assertEquals(linked.turn.requestId.value, built.captures.records.single().linkedRequest)
        assertEquals("ADMITTING", built.captures.records.single().state)
    }

    private fun assertRefusedWholeThenAdmissible(
        s: Scene,
        refused: AdmissionStep,
        expected: (AdmissionRefusal) -> Unit,
        repair: () -> AdmissionStep
    ) {
        val outcome = refused.outcome as AdmissionOutcome.Refused
        expected(outcome.reason)
        assertNothingChanged(s, refused)
        assertEquals(CaptureLifecycle.AWAITING_RESULT, s.v.captures.entry(s.key)!!.lifecycle)
        assertNull(s.v.captures.entry(s.key)!!.linkedRequest)
        // control: with the cause repaired the very same capture is still admissible, so the refusal was the guard and nothing half-committed
        assertTrue(repair().outcome is AdmissionOutcome.Linked)
    }

    @Test fun aPrepareRefusalCommitsNeitherHalf() {
        val s = scene()
        val step = admit(s, ttl = 0)
        assertRefusedWholeThenAdmissible(
            s,
            step,
            {
                assertEquals(
                    PrepareRefusal.INVALID_DEADLINE,
                    (it as AdmissionRefusal.Prepare).reason
                )
            },
            { admit(s) }
        )
    }

    @Test fun aFullTurnLedgerIsTypedBackpressureAndCommitsNeitherHalf() {
        val s = scene()
        val blockers = ArrayList<dev.quantumink.hermesgadget.protocol.relay.state.TurnKey>()
        repeat(StateLimits.MAX_RETAINED_TURNS) { blockers.add(s.v.h.prepare("t$it")) }
        val step = admit(s)
        assertRefusedWholeThenAdmissible(
            s,
            step,
            { assertEquals(LedgerLimit.TURNS, (it as AdmissionRefusal.TurnLedgerFull).limit) },
            {
                s.v.h.apply(s.v.h.ledger.discard(blockers.first()))
                admit(s)
            }
        )
    }

    @Test fun aCaptureAndTurnIdKindCollisionIsARefusalNeverAReMintOrAPartialLink() {
        val s = scene()
        // the turn id the ledger would mint now is, byte for byte, this capture's id: same entropy, same server instant
        val same = StateTestSupport.CountingEntropy(100)
        val step = admit(s, entropy = same)
        assertRefusedWholeThenAdmissible(
            s,
            step,
            { assertEquals(AdmissionRefusal.IdKindCollision, it) },
            { admit(s) }
        )
        assertEquals("one mint and no second attempt", 1, same.calls)
    }

    @Test fun anAggregateThatCouldNotBeBuiltCommitsNeitherHalf() {
        val s = scene()
        s.v.shape = AggregateShape(histories = 17, deliveries = 0)
        val step = admit(s)
        assertRefusedWholeThenAdmissible(
            s,
            step,
            {
                val refusal = it as AdmissionRefusal.AggregateTooMany
                assertEquals("histories", refusal.path)
                assertEquals(16, refusal.limit)
            },
            {
                s.v.shape = AggregateShape.EMPTY
                admit(s)
            }
        )
    }

    @Test fun aClockRollbackAndAFailingEntropySeamCommitNeitherHalf() {
        val s = scene()
        val rolled = admit(s, now = StateTestSupport.raw(s.v.h.serverMs, s.v.h.nanos - 1))
        assertRefusedWholeThenAdmissible(
            s,
            rolled,
            {
                assertEquals(PrepareRefusal.CLOCK_ROLLBACK, (it as AdmissionRefusal.Prepare).reason)
            },
            { admit(s) }
        )
        val t = scene()
        val broken = admit(t, entropy = RequestIdEntropy { error("entropy down") })
        assertRefusedWholeThenAdmissible(
            t,
            broken,
            {
                val refusal = it as AdmissionRefusal.Prepare
                assertEquals(PrepareRefusal.MINT_FAILED, refusal.reason)
                assertEquals(
                    dev.quantumink.hermesgadget.protocol.relay.state.MintRefusal.ENTROPY_FAILED,
                    refusal.mint
                )
            },
            { admit(t) }
        )
    }

    // -- once only ----------------------------------------------------------------------------------------------------------------------------

    @Test fun aLinkedCaptureNeverPreparesAgainNotByRepeatNotBySettlementNotByRestore() {
        val s = scene()
        val first = admit(s)
        val turn = (first.outcome as AdmissionOutcome.Linked).turn
        commit(s, first)
        val minted = s.v.h.entropy.calls
        // a repeated or late copy of the same result
        val again = admit(s)
        assertEquals(turn, (again.outcome as AdmissionOutcome.AlreadyLinked).turn)
        assertNothingChanged(s, again)
        // after the turn settles and the capture ends, the link remains and still refuses
        val token2 = s.v.h.attach()
        val dispatch = s.v.h.planned(s.v.h.submit(turn, token2))
        s.v.h.reply(dispatch, s.v.h.receipt(turn, ReceiptStatus.TERMINAL, 1, "completed"))
        assertEquals(1, s.v.apply(s.v.captures.settleLinked(s.v.h.ledger, s.v.h.now())))
        assertEquals(CaptureEnd.TURN_SETTLED, s.v.entry(s.key).end)
        assertEquals(turn.requestId, s.v.entry(s.key).linkedRequest)
        val settled = admit(s)
        assertTrue(settled.outcome is AdmissionOutcome.AlreadyLinked)
        assertNothingChanged(s, settled)
        // a restore keeps the link and refuses too
        val snapshot = (
            RelayStateSnapshot.of(
                s.v.h.ledger,
                emptyList(),
                emptyList(),
                s.v.captures
            ) as SnapshotBuild.Built
            ).snapshot
        val restored = (
            dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore.restore(
                snapshot,
                s.v.h.now(),
                dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority.fresh()
            ) as dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult.Restored
            ).value
        val afterRestore = VoiceAdmission.admit(
            restored.ledger,
            restored.captures,
            s.key,
            s.result,
            dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority.fresh(),
            s.v.h.now(),
            s.v.h.entropy,
            s.v.shape
        )
        assertTrue(afterRestore.outcome is AdmissionOutcome.AlreadyLinked)
        assertSame(restored.ledger, afterRestore.turns)
        assertSame(restored.captures, afterRestore.captures)
        assertEquals("no id was minted for any of the repeats", minted, s.v.h.entropy.calls)
    }

    @Test fun task2UncertaintySurvivesTheLinkAndTheCaptureStaysAdmittingUntilTheTurnSettles() {
        val s = scene()
        val first = admit(s)
        val turn = (first.outcome as AdmissionOutcome.Linked).turn
        commit(s, first)
        val token2 = s.v.h.attach()
        val dispatch = s.v.h.planned(s.v.h.submit(turn, token2))
        s.v.h.timeout(dispatch)
        assertEquals(TurnPhase.UNCERTAIN, s.v.h.phase(turn))
        assertEquals(0, s.v.apply(s.v.captures.settleLinked(s.v.h.ledger, s.v.h.now())))
        // the server's `expired` is an observation, not an ending (Task2 EXPIRED_REPORTED), so the capture still waits
        val token3 = s.v.h.attach()
        val look = s.v.h.planned(s.v.h.lookup(turn, token3))
        s.v.h.fail(look, "expired")
        assertEquals(UncertainReason.EXPIRED_REPORTED, s.v.h.entry(turn).uncertainty)
        assertEquals(0, s.v.apply(s.v.captures.settleLinked(s.v.h.ledger, s.v.h.now())))
        assertEquals(CaptureLifecycle.ADMITTING, s.v.entry(s.key).lifecycle)
        // the user explicitly stops tracking: the turn leaves the ledger and the capture records that
        s.v.h.apply(s.v.h.ledger.discard(turn, DiscardMode.ABANDON_TRACKING))
        assertEquals(1, s.v.apply(s.v.captures.settleLinked(s.v.h.ledger, s.v.h.now())))
        assertEquals(CaptureEnd.TURN_GONE, s.v.entry(s.key).end)
        assertEquals(turn.requestId, s.v.entry(s.key).linkedRequest)
    }

    // -- C4: the spoken intent -------------------------------------------------------------------------------------------------------

    private fun assertDraftOnly(s: Scene, step: AdmissionStep, why: DraftReason, callsBefore: Int) {
        val draft = step.outcome as AdmissionOutcome.DraftOnly
        assertEquals(why, draft.why)
        assertEquals(s.result.transcript, draft.draft.text)
        assertSame("no turn was prepared", s.v.h.ledger, step.turns)
        assertEquals("no id was minted", callsBefore, s.v.h.entropy.calls)
        assertEquals(CaptureLifecycle.DRAFT_OFFERED, step.captures.entry(s.key)!!.lifecycle)
        assertNull(step.captures.entry(s.key)!!.linkedRequest)
        assertEquals(
            listOf(CaptureEffect.PersistAggregate, CaptureEffect.MayDiscardServerCopy(s.key)),
            step.effects
        )
        assertFalse(draft.toString().contains(s.result.transcript!!))
        commit(s, step)
        // a draft never auto-submits, however often the result is seen again
        val again = admit(s)
        assertEquals(
            AdmissionIneligible.WRONG_LIFECYCLE,
            (again.outcome as AdmissionOutcome.NotEligible).why
        )
        assertNothingChanged(s, again)
        assertEquals(
            DraftRefusal.WRONG_LIFECYCLE,
            (
                s.v.captures.offerDraft(
                    s.v.h.ledger,
                    s.key,
                    s.result
                ).result as DraftOutcome.Refused
                ).reason
        )
    }

    @Test fun withoutALiveIntentTheTranscriptIsADraftAndNothingIsPrepared() {
        val s = scene()
        s.v.apply(s.v.captures.withdrawIntents(s.key.conversation))
        val calls = s.v.h.entropy.calls
        assertDraftOnly(s, admit(s), DraftReason.NO_INTENT, calls)
    }

    @Test fun aReconnectRebindOrRetiredBindingTurnsTheTranscriptIntoADraft() {
        val rebind = scene()
        // a new attach on the same transport retires the binding the recording was released under
        rebind.v.h.attach()
        val calls = rebind.v.h.entropy.calls
        assertDraftOnly(rebind, admit(rebind), DraftReason.BINDING_CHANGED, calls)
        val retired = scene()
        retired.v.h.authority = retired.v.h.authority.retire(retired.token)
        val calls2 = retired.v.h.entropy.calls
        assertDraftOnly(retired, admit(retired), DraftReason.BINDING_CHANGED, calls2)
    }

    @Test fun theIntentWindowIsExactlyOneHundredTwentySecondsFromRelease() {
        val onTime = scene()
        onTime.v.h.advance(VoiceLimits.INTENT_WINDOW_MS)
        assertTrue(admit(onTime).outcome is AdmissionOutcome.Linked)
        val late = scene()
        late.v.h.advance(VoiceLimits.INTENT_WINDOW_MS + 1)
        val calls = late.v.h.entropy.calls
        assertDraftOnly(late, admit(late), DraftReason.INTENT_WINDOW_PASSED, calls)
    }

    @Test fun aRestoreInvalidatesTheIntentSoALateTranscriptIsOnlyEverADraft() {
        val s = scene()
        val snapshot = (
            RelayStateSnapshot.of(
                s.v.h.ledger,
                emptyList(),
                emptyList(),
                s.v.captures
            ) as SnapshotBuild.Built
            ).snapshot
        val authority = dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority.fresh()
        val restored = (
            dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore.restore(
                snapshot,
                s.v.h.now(),
                authority
            ) as dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult.Restored
            ).value
        val record = restored.captures.entry(s.key)!!
        assertEquals(CaptureLifecycle.AWAITING_RESULT, record.lifecycle)
        assertNull(
            "the intent is transient: a restore starts with none",
            restored.captures.intentOf(s.key)
        )
        val step = VoiceAdmission.admit(
            restored.ledger,
            restored.captures,
            s.key,
            s.result,
            authority,
            s.v.h.now(),
            s.v.h.entropy,
            s.v.shape
        )
        assertEquals(DraftReason.NO_INTENT, (step.outcome as AdmissionOutcome.DraftOnly).why)
        assertSame(restored.ledger, step.turns)
        assertEquals(CaptureLifecycle.DRAFT_OFFERED, step.captures.entry(s.key)!!.lifecycle)
    }

    @Test fun anEditedDraftIsANewTypedTurnWithANewIdAndTheCaptureStaysADraftUntilEnded() {
        val s = scene()
        s.v.apply(s.v.captures.withdrawIntents(s.key.conversation))
        val calls = s.v.h.entropy.calls
        assertDraftOnly(s, admit(s), DraftReason.NO_INTENT, calls)
        val typed = s.v.h.prepare("edited by the user")
        assertNotEquals(s.key.captureId.value, typed.requestId.value)
        assertEquals("a new deliberate turn minted its own id", calls + 1, s.v.h.entropy.calls)
        assertEquals(CaptureLifecycle.DRAFT_OFFERED, s.v.entry(s.key).lifecycle)
        assertNull(s.v.entry(s.key).linkedRequest)
        assertTrue(s.v.apply(s.v.captures.endDraft(s.key, s.v.h.now())))
        assertEquals(CaptureEnd.DRAFT_RESOLVED, s.v.entry(s.key).end)
        assertFalse(s.v.apply(s.v.captures.endDraft(s.key, s.v.h.now())))
    }

    @Test fun aRefusedOrIneligibleResultIsNeverAnAutomaticAdmission() {
        val s = scene()
        val before = s.v.captures
        val notTranscribed = VoiceAdmission.admit(
            s.v.h.ledger,
            before,
            s.key,
            s.v.result(
                s.key,
                CaptureState.EMPTY,
                provider = VoiceProvider.COMPLETED,
                source = TranscriptSource.CONFIGURED
            ),
            s.v.h.authority,
            s.v.h.now(),
            s.v.h.entropy,
            s.v.shape
        )
        assertEquals(
            AdmissionIneligible.NOT_TRANSCRIBED,
            (notTranscribed.outcome as AdmissionOutcome.NotEligible).why
        )
        val other = CaptureId.of("01a14d67-a300-7000-9f3a-17469850b711")!!
        val foreign =
            CaptureResult(
                other, s.key.conversation.conversationId, CaptureState.TRANSCRIBED, null,
                "x", 1L, TranscriptSource.CONFIGURED, VoiceProvider.COMPLETED, 1L
            )
        val foreignStep = VoiceAdmission.admit(
            s.v.h.ledger,
            before,
            s.key,
            foreign,
            s.v.h.authority,
            s.v.h.now(),
            s.v.h.entropy,
            s.v.shape
        )
        assertEquals(
            AdmissionIneligible.FOREIGN_RESULT,
            (foreignStep.outcome as AdmissionOutcome.NotEligible).why
        )
        val unknown = VoiceAdmission.admit(
            s.v.h.ledger,
            VoiceCaptureLedger.empty(),
            s.key,
            s.result,
            s.v.h.authority,
            s.v.h.now(),
            s.v.h.entropy,
            s.v.shape
        )
        assertEquals(
            AdmissionIneligible.UNKNOWN_CAPTURE,
            (unknown.outcome as AdmissionOutcome.NotEligible).why
        )
        for (step in listOf(notTranscribed, foreignStep)) {
            assertSame(s.v.h.ledger, step.turns)
            assertSame(before, step.captures)
        }
        // a cancelled or ended capture is not a candidate either
        val ended = scene()
        ended.v.apply(ended.v.captures.onError(ended.key, errorOf("not_found"), ended.v.h.now()))
        assertEquals(
            AdmissionIneligible.WRONG_LIFECYCLE,
            (admit(ended).outcome as AdmissionOutcome.NotEligible).why
        )
        // a stopped scope (permission_denied) admits nothing
        val frozen = scene()
        val turn = frozen.v.h.prepare()
        frozen.v.h.fail(
            frozen.v.h.planned(frozen.v.h.submit(turn, frozen.token)),
            "permission_denied"
        )
        val calls = frozen.v.h.entropy.calls
        assertEquals(
            AdmissionIneligible.SCOPE_FROZEN,
            (admit(frozen).outcome as AdmissionOutcome.NotEligible).why
        )
        assertEquals(calls, frozen.v.h.entropy.calls)
    }

    private fun errorOf(code: String) = (
        dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope.decodeFrame(
            "{\"type\":\"relay.response\",\"v\":1,\"id\":\"e1\",\"error\":{\"code\":\"$code\"}}"
        ) as dev.quantumink.hermesgadget.protocol.relay.RelayFrame.Failure
        ).error

    @Test fun independentEndpointsProfilesAndKeysDoNotShareIntentsLinksOrTurns() {
        val v = VoiceHarness()
        val tokenA = v.h.attach()
        val (scopeB, tokenB) = v.otherScope(endpoint = "endpoint-b")
        val keyA = v.released(tokenA)
        val keyB = v.begin(tokenB)
        v.apply(
            v.captures.planFinish(
                v.h.ledger,
                v.h.authority,
                tokenB,
                keyB,
                v.stream,
                v.counts(),
                v.h.now(),
                v.shape
            )
        )
        val resultA = v.transcribed(keyA, "for scope A")
        val resultB = v.transcribed(keyB, "for scope B")
        v.onResult(keyA, resultA)
        v.onResult(keyB, resultB)
        // withdrawing B's intent makes B's transcript a draft and leaves A's automatic
        v.apply(v.captures.withdrawIntents(scopeB))
        val stepB = VoiceAdmission.admit(
            v.h.ledger,
            v.captures,
            keyB,
            resultB,
            v.h.authority,
            v.h.now(),
            v.h.entropy,
            v.shape
        )
        assertTrue(stepB.outcome is AdmissionOutcome.DraftOnly)
        val stepA = VoiceAdmission.admit(
            v.h.ledger,
            v.captures,
            keyA,
            resultA,
            v.h.authority,
            v.h.now(),
            v.h.entropy,
            v.shape
        )
        val linked = stepA.outcome as AdmissionOutcome.Linked
        assertEquals(v.h.enrollment, linked.turn.conversation.enrollment)
        assertEquals("for scope A", stepA.turns.entry(linked.turn)!!.text)
        assertEquals("B's draft prepared no turn in A's ledger", 1, stepA.turns.size)
        // A's result applied to B's key is foreign
        val crossed = VoiceAdmission.admit(
            v.h.ledger,
            v.captures,
            keyB,
            resultA,
            v.h.authority,
            v.h.now(),
            v.h.entropy,
            v.shape
        )
        assertEquals(
            AdmissionIneligible.FOREIGN_RESULT,
            (crossed.outcome as AdmissionOutcome.NotEligible).why
        )
        assertEquals(RequestId.of(linked.turn.requestId.value), linked.turn.requestId)
    }
}
