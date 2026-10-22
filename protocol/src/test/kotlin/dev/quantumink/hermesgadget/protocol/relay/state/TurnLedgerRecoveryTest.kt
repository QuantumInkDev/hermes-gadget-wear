package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ExistingBinding
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayEnvelope
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeout, stale authority, deadline and late replies: attach, then receipt FIRST, then at most the same id. */
class TurnLedgerRecoveryTest {
    private fun h() = LedgerHarness()

    @Test fun timeoutThenReattachThenReceiptThenTheSameIdIsResubmittedWithTheSameContent() {
        val h = h()
        val key = h.prepare("do exactly this", ttlMs = 120_000)
        val original = h.entry(key)
        val first = h.token1(key)
        assertEquals(TurnPhase.SUBMITTING, h.phase(key))
        h.timeout(first.second)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertEquals(UncertainReason.TIMEOUT, h.entry(key).uncertainty)
        assertEquals(TurnStatus.UNCERTAIN, h.entry(key).status)
        // Receipt FIRST: the same-id resubmit is not even offered before a lookup, and the old binding is too old.
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, first.first)))
        assertEquals(PlanRefusal.FRESH_ATTACH_REQUIRED, h.refusal(h.lookup(key, first.first)))
        val second = h.attach()
        assertEquals(PlanRefusal.BINDING_NOT_CURRENT, h.refusal(h.lookup(key, first.first)))
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, second)))
        val lookup = h.planned(h.lookup(key, second))
        assertEquals(TurnPhase.LOOKING_UP, h.phase(key))
        assertEquals(PlanRefusal.CALL_OUTSTANDING, h.refusal(h.submit(key, second)))
        assertEquals(
            mapOf(
                "conversation_id" to key.conversation.conversationId.value,
                "request_id" to key.requestId.value
            ),
            lookup.call.params.mapValues { (it.value as JsonPrimitive).content }
        )
        h.fail(lookup, "not_found")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        // Now, and only now, the SAME turn goes out again, on the new binding, under a new correlation id.
        val resubmit = h.planned(h.submit(key, second))
        val params = resubmit.call.params.mapValues { (it.value as JsonPrimitive).content }
        assertEquals(key.requestId.value, params["request_id"])
        assertEquals(original.expiresAtMs.toString(), params["expires_at_ms"])
        assertEquals("do exactly this", params["text"])
        assertEquals(second.bindingId.value, params["binding_id"])
        assertNotEquals(first.second.ref.correlation, resubmit.ref.correlation)
        assertEquals(original.fingerprint, h.entry(key).fingerprint)
        assertEquals(original.expiresAtMs, h.entry(key).expiresAtMs)
        h.reply(resubmit, h.receipt(key, ReceiptStatus.QUEUED))
        assertEquals(TurnPhase.QUEUED, h.phase(key))
        assertEquals(
            listOf("relay.turn.submit", "relay.turn.receipt", "relay.turn.submit"),
            h.dispatched
        )
        assertEquals("a reconnect must never mint an id", 1, h.entropy.calls)
        assertEquals(1, h.ledger.size)
    }

    @Test fun aReceiptFoundBeforeAnyResubmitSettlesTheTurnAndNothingIsEverResent() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        val token = h.attach()
        val lookup = h.planned(h.lookup(key, token))
        h.reply(lookup, h.receipt(key, ReceiptStatus.STARTED, generation = 1))
        assertEquals(TurnPhase.STARTED, h.phase(key))
        assertEquals(PlanRefusal.ALREADY_ADMITTED, h.refusal(h.submit(key, token)))
        h.reply(
            h.planned(h.lookup(key, token)),
            h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed")
        )
        assertEquals(TurnStatus.FINISHED_COMPLETED, h.entry(key).status)
        assertEquals(PlanRefusal.ALREADY_ADMITTED, h.refusal(h.submit(key, token)))
        assertEquals(PlanRefusal.NOTHING_TO_LOOK_UP, h.refusal(h.lookup(key, token)))
        assertEquals(
            listOf("relay.turn.submit", "relay.turn.receipt", "relay.turn.receipt"),
            h.dispatched
        )
    }

    @Test fun localExpiryIsNotEvidenceTheTurnStaysUnknownUntilALookupProvesOtherwise() {
        val h = h()
        val key = h.prepare(ttlMs = 60_000)
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        h.advance(61_000)
        val entry = h.entry(key)
        assertTrue(entry.deadlinePassed(h.now()))
        assertEquals(TurnPhase.UNCERTAIN, entry.phase)
        assertEquals(TurnStatus.UNCERTAIN, entry.status)
        assertTrue(entry.mayHaveReachedServer)
        assertNull(entry.closure)
        val token = h.attach()
        // Even past the deadline the answer is still "look up first", not "expired".
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, token)))
        // And a retained receipt stays readable after the freshness window.
        h.reply(
            h.planned(h.lookup(key, token)),
            h.receipt(key, ReceiptStatus.STARTED, generation = 1)
        )
        assertEquals(TurnPhase.STARTED, h.phase(key))
    }

    @Test fun anAbsentAnswerNeverClosesTheTurnAndOnlyTheEstimateRefusesAResendAtTheExactBoundary() {
        for ((afterMs, resendAllowed) in listOf(
            59_999L to true,
            60_000L to false,
            60_001L to false
        )) {
            val h = h()
            val key = h.prepare(ttlMs = 60_000)
            val (_, submit) = h.token1(key)
            h.timeout(submit)
            h.advance(afterMs)
            val token = h.attach()
            h.fail(h.planned(h.lookup(key, token)), "not_found")
            assertEquals("at +$afterMs", TurnPhase.ABSENT_SO_FAR, h.phase(key))
            assertNull(h.entry(key).closure)
            val resend = h.submit(key, token)
            if (resendAllowed) {
                assertTrue("at +$afterMs", resend is PlanOutcome.Planned)
            } else {
                assertEquals("at +$afterMs", PlanRefusal.DEADLINE_PASSED, h.refusal(resend))
            }
        }
    }

    @Test fun aLookupSentBeforeTheDeadlineAndAnsweredAfterItDoesNotProveAnything() {
        val h = h()
        val key = h.prepare(ttlMs = 60_000)
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        val token = h.attach()
        val early = h.planned(h.lookup(key, token))
        h.advance(70_000) // the answer arrives late
        h.fail(early, "not_found")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        // A resend would be refused by the estimate.
        assertEquals(PlanRefusal.DEADLINE_PASSED, h.refusal(h.submit(key, token)))
        // A lookup planned after the estimated deadline that finds nothing is again only an observation.
        h.fail(h.planned(h.lookup(key, token)), "not_found")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        assertNull(h.entry(key).closure)
    }

    @Test fun theServersOwnExpiredAnswerIsProofWhateverTheLocalClockSays() {
        val h = h()
        val key = h.prepare(ttlMs = 60_000)
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        val token = h.attach()
        // Before the local deadline: the server clock is ahead of the estimate.
        h.fail(h.planned(h.lookup(key, token)), "expired")
        assertEquals(
            TurnClosure.NotAdmitted(NotAdmittedProof.SERVER_EXPIRED),
            h.entry(key).closure
        )
        assertEquals(TurnStatus.NOT_ADMITTED, h.entry(key).status)
        // A submit answered `expired` is the same proof.
        val h2 = h()
        val k2 = h2.prepare()
        val s2 = h2.planned(h2.submit(k2, h2.attach()))
        h2.fail(s2, "expired")
        assertEquals(TurnClosure.NotAdmitted(NotAdmittedProof.SERVER_EXPIRED), h2.entry(k2).closure)
    }

    @Test fun staleAuthorityKeepsTheTurnUncertainRequestsAFreshViewAndMintsNothing() {
        for ((code, snapshot) in listOf(
            "stale_epoch" to false,
            "stale_binding" to false,
            "snapshot_required" to false,
            "busy" to true // a fresh-view flag alongside another code also demands a fresh view
        )) {
            val h = h()
            val key = h.prepare()
            val (_, submit) = h.token1(key)
            val outcome = h.fail(submit, code, snapshot)
            assertTrue(code, outcome is ReplyOutcome.Applied)
            val entry = h.entry(key)
            assertEquals(code, UncertainReason.STALE_AUTHORITY, entry.uncertainty)
            assertEquals(code, TurnPhase.UNCERTAIN, entry.phase)
            assertTrue(h.effects.contains(TurnEffect.ReattachRequired(h.enrollment)))
            assertTrue(h.effects.contains(TurnEffect.RefreshHistoryAndTasks(h.conversation)))
            assertEquals(1, h.ledger.size)
            assertEquals(1, h.entropy.calls)
            assertEquals(ScopeStatus.ACTIVE, h.ledger.scopeStatus(h.enrollment))
        }
    }

    @Test fun transientUnavailabilityPreservesIdentityAndUncertaintyAndIsNotRevocation() {
        for (code in listOf("busy", "rate_limited", "temporarily_unavailable")) {
            val h = h()
            val key = h.prepare("keep me")
            val before = h.entry(key)
            val (token, submit) = h.token1(key)
            h.fail(submit, code)
            val entry = h.entry(key)
            assertEquals(code, UncertainReason.TRANSIENT_ERROR, entry.uncertainty)
            assertEquals(code, before.fingerprint, entry.fingerprint)
            assertEquals(code, "keep me", entry.text)
            assertEquals(code, ScopeStatus.ACTIVE, h.ledger.scopeStatus(h.enrollment))
            assertTrue(
                code,
                h.effects.none {
                    it is TurnEffect.EvictScopeContent ||
                        it is TurnEffect.RequireUserReview
                }
            )
            // Even a transient answer to a submit needs attach, then the receipt, before a resend.
            assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, token)))
            assertEquals(PlanRefusal.FRESH_ATTACH_REQUIRED, h.refusal(h.lookup(key, token)))
        }
    }

    @Test fun aTransientAnswerToALookupLeavesWhatWasKnownUntouched() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        val token = h.attach()
        h.fail(h.planned(h.lookup(key, token)), "not_found")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        h.fail(h.planned(h.lookup(key, token)), "temporarily_unavailable")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key)) // the earlier fact stands
        // A stale answer, however, invalidates it: attach again, look up again.
        h.fail(h.planned(h.lookup(key, token)), "stale_epoch")
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertEquals(PlanRefusal.FRESH_ATTACH_REQUIRED, h.refusal(h.lookup(key, token)))
    }

    @Test fun permissionDeniedStopsTheScopeEvictsContentKeepsIdentityAndIsNotMistakenForAnExpiry() {
        val h = h()
        val key = h.prepare("private words")
        val (_, submit) = h.token1(key)
        h.fail(submit, "permission_denied")
        assertEquals(ScopeStatus.STOPPED, h.ledger.scopeStatus(h.enrollment))
        assertTrue(h.ledger.scopeRecord(h.enrollment)!!.contentEvictionOwed)
        assertTrue(h.effects.contains(TurnEffect.EvictScopeContent(h.enrollment)))
        assertTrue(h.effects.contains(TurnEffect.InvalidateRuntimeAuthority(h.enrollment)))
        // The turn may have reached the server: it stays uncertain, never closed, never "expired".
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertNull(h.entry(key).closure)
        val token = h.attach()
        assertEquals(PlanRefusal.SCOPE_STOPPED, h.refusal(h.lookup(key, token)))
        assertEquals(PlanRefusal.SCOPE_STOPPED, h.refusal(h.submit(key, token)))
        val refusedNew = h.ledger.prepare(TurnDraft(h.conversation, "new"), h.now(), h.entropy)
        assertEquals(
            PrepareRefusal.SCOPE_STOPPED,
            (refusedNew.result as PrepareOutcome.Refused).reason
        )
        // Eviction removes the text but keeps the identity, fingerprint and uncertainty.
        val fingerprint = h.entry(key).fingerprint
        assertEquals(1, h.apply(h.ledger.evictContent(h.enrollment)))
        assertNull(h.entry(key).text)
        assertEquals(fingerprint, h.entry(key).fingerprint)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertFalse(h.ledger.scopeRecord(h.enrollment)!!.contentEvictionOwed)
        // Reactivation needs the matching user decision.
        assertFalse(h.apply(h.ledger.reactivate(h.enrollment, Reactivation.USER_REVIEWED)))
        assertTrue(h.apply(h.ledger.reactivate(h.enrollment, Reactivation.USER_REAUTHORIZED)))
        assertNull(h.ledger.scopeRecord(h.enrollment))
        h.fail(h.planned(h.lookup(key, h.attach())), "not_found")
        assertEquals(PlanRefusal.CONTENT_EVICTED, h.refusal(h.submit(key, h.attach())))
    }

    @Test fun anOwedEvictionSurvivesReactivationUntilItIsDone() {
        val h = h()
        val key = h.prepare("x")
        h.fail(h.planned(h.submit(key, h.attach())), "permission_denied")
        assertTrue(h.apply(h.ledger.reactivate(h.enrollment, Reactivation.USER_REAUTHORIZED)))
        assertEquals(ScopeStatus.ACTIVE, h.ledger.scopeStatus(h.enrollment))
        assertTrue(h.ledger.scopeRecord(h.enrollment)!!.contentEvictionOwed)
        assertEquals(1, h.apply(h.ledger.evictContent(h.enrollment)))
        assertNull(h.ledger.scopeRecord(h.enrollment))
    }

    @Test fun aProfileChangeInvalidatesTheProfileBindingAndDemandsReviewWithoutEvictingOrSending() {
        val h = h()
        val key = h.prepare("held for review")
        val (_, submit) = h.token1(key)
        h.fail(submit, "profile_changed")
        assertEquals(ScopeStatus.NEEDS_REVIEW, h.ledger.scopeStatus(h.enrollment))
        assertTrue(h.effects.contains(TurnEffect.RequireUserReview(h.enrollment)))
        assertTrue(h.effects.contains(TurnEffect.InvalidateRuntimeAuthority(h.enrollment)))
        assertTrue(h.effects.none { it is TurnEffect.EvictScopeContent })
        assertEquals("held for review", h.entry(key).text)
        assertEquals(PlanRefusal.SCOPE_NEEDS_REVIEW, h.refusal(h.submit(key, h.attach())))
        // The user reviewed the changed identity: the consumer moves to the next review epoch, an independent scope.
        val reviewed = h.enrollment.copy(reviewEpoch = h.enrollment.reviewEpoch + 1)
        val fresh = h.ledger.prepare(
            TurnDraft(ConversationScope(reviewed, h.conversation.conversationId), "after review"),
            h.now(),
            h.entropy
        )
        h.apply(fresh)
        assertEquals(2, h.ledger.size)
        assertEquals(ScopeStatus.NEEDS_REVIEW, h.ledger.scopeStatus(h.enrollment))
        assertEquals(ScopeStatus.ACTIVE, h.ledger.scopeStatus(reviewed))
        // The old scope's turn never moves to the new one.
        assertEquals(1, h.ledger.entriesFor(h.enrollment).size)
        assertEquals(1, h.ledger.entriesFor(reviewed).size)
    }

    @Test fun definitiveRefusalsCloseTheTurnAndAreNeverRetried() {
        for (code in listOf("invalid_request", "unsupported")) {
            val h = h()
            val key = h.prepare()
            val (token, submit) = h.token1(key)
            h.fail(submit, code)
            assertEquals(TurnPhase.REFUSED, h.phase(key))
            assertEquals(TurnStatus.REFUSED, h.entry(key).status)
            assertEquals(PlanRefusal.ALREADY_CLOSED, h.refusal(h.submit(key, token)))
            assertEquals(PlanRefusal.ALREADY_CLOSED, h.refusal(h.lookup(key, token)))
        }
    }

    @Test fun anAnswerThatSettlesNothingHoldsTheTurnForAPersonAndStillAllowsALookup() {
        for (code in listOf("conflict", "not_found", "projection_too_large", "some_future_code")) {
            val h = h()
            val key = h.prepare()
            val (_, submit) = h.token1(key)
            h.fail(submit, code)
            val entry = h.entry(key)
            assertEquals(code, TurnHold.UNEXPECTED_ERROR, entry.hold)
            assertEquals(code, TurnPhase.UNCERTAIN, entry.phase)
            assertNull(code, entry.closure)
            val token = h.attach()
            assertEquals(code, PlanRefusal.HELD, h.refusal(h.submit(key, token)))
            // A read-only lookup is still allowed after a fresh attach, and finding the receipt releases the hold.
            h.reply(h.planned(h.lookup(key, token)), h.receipt(key, ReceiptStatus.QUEUED))
            assertNull(code, h.entry(key).hold)
            assertEquals(code, TurnPhase.QUEUED, h.phase(key))
        }
    }

    @Test fun aHeldTurnIsNotResubmittedEvenAfterAnAbsentLookup() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        h.fail(submit, "conflict")
        val token = h.attach()
        h.fail(h.planned(h.lookup(key, token)), "not_found")
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        assertEquals(PlanRefusal.HELD, h.refusal(h.submit(key, token)))
    }

    @Test fun anUncorrelatedOrInvalidFrameIsALinkFailureNotAnAnswer() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        val wrongCorrelation = submit.call.interpret(
            h.correlation(),
            RelayEnvelope.decodeFrame(
                """{"type":"relay.response","v":1,"id":"someone-else","error":{"code":"expired"}}"""
            )
        )
        assertTrue(wrongCorrelation is RelayOutcome.Invalid)
        val outcome = h.apply(h.ledger.onReply(submit.ref, wrongCorrelation))
        assertTrue(outcome is ReplyOutcome.Applied)
        assertEquals(UncertainReason.LINK_FAILURE, h.entry(key).uncertainty)
        // The misattributed `expired` proved nothing about this turn.
        assertNull(h.entry(key).closure)
        val h2 = h()
        val k2 = h2.prepare()
        val s2 = h2.planned(h2.submit(k2, h2.attach()))
        val uncorrelated = s2.call.interpret(
            s2.ref.correlation,
            RelayEnvelope.decodeFrame(
                """{"type":"relay.response","v":1,"error":{"code":"expired"}}"""
            )
        )
        assertTrue(uncorrelated is RelayOutcome.Uncorrelated)
        h2.apply(h2.ledger.onReply(s2.ref, uncorrelated))
        assertEquals(UncertainReason.LINK_FAILURE, h2.entry(k2).uncertainty)
        assertEquals(TurnPhase.UNCERTAIN, h2.phase(k2))
        assertNotEquals(TurnPhase.NOT_ADMITTED, h2.phase(k2))
    }

    @Test fun aLateReplyToAnOlderCallIsIgnoredBeforeAnyStateChange() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        h.timeout(submit)
        val token = h.attach()
        val lookup = h.planned(h.lookup(key, token))
        val before = h.ledger
        // The reply to the long-gone submit arrives now: nothing outstanding names it.
        val late = h.ledger.onReply(
            submit.ref,
            h.success(submit, h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed"))
        )
        assertTrue(late.result is ReplyOutcome.Ignored)
        assertSame(before, late.ledger)
        assertTrue(late.effects.isEmpty())
        assertEquals(TurnPhase.LOOKING_UP, h.phase(key))
        // A second delivery of the lookup's own answer is ignored the same way once it was applied.
        h.reply(lookup, h.receipt(key, ReceiptStatus.QUEUED))
        val duplicate = h.ledger.onReply(
            lookup.ref,
            h.success(lookup, h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed"))
        )
        assertTrue(duplicate.result is ReplyOutcome.Ignored)
        assertEquals(TurnPhase.QUEUED, h.phase(key))
        assertTrue(h.ledger.onTimeout(lookup.ref).result is ReplyOutcome.Ignored)
    }

    @Test fun aReusedCorrelationIdIsRefusedForAnyLaterCall() {
        val h = h()
        val key = h.prepare()
        val token = h.attach()
        val reused = h.correlation()
        val first = h.planned(h.submit(key, token, reused))
        h.timeout(first)
        val token2 = h.attach()
        assertEquals(PlanRefusal.CORRELATION_REUSED, h.refusal(h.lookup(key, token2, reused)))
        // A fresh correlation is fine, and a late reply under the reused id still names only the first call.
        val lookup = h.planned(h.lookup(key, token2))
        assertNotEquals(first.ref.correlation, lookup.ref.correlation)
        assertEquals(first.ref.correlation, reused)
    }

    @Test fun theCorrelationMemoryIsBoundedAndForgetsExactlyTheOldest() {
        val h = h()
        val key = h.prepare()
        val token = h.attach()
        val oldest = h.correlation()
        h.timeout(h.planned(h.submit(key, token, oldest)))
        val fresh = h.attach()
        // Phase ABSENT_SO_FAR: further lookups need no new attach.
        h.fail(h.planned(h.lookup(key, fresh)), "not_found")
        repeat(StateLimits.MAX_REMEMBERED_CORRELATIONS - 2) {
            h.fail(h.planned(h.lookup(key, fresh)), "temporarily_unavailable")
        }
        // oldest + 1 lookup + (limit - 2) lookups = limit calls remembered, so `oldest` is still remembered.
        assertEquals(PlanRefusal.CORRELATION_REUSED, h.refusal(h.lookup(key, fresh, oldest)))
        h.fail(h.planned(h.lookup(key, fresh)), "temporarily_unavailable")
        // One more call pushes the oldest out of the window of exactly MAX_REMEMBERED_CORRELATIONS.
        h.planned(h.lookup(key, fresh, oldest))
    }

    @Test fun anInFlightSubmitBecomesUncertainAtTransportLossAndAnInFlightLookupIsSimplyDropped() {
        val h = h()
        val a = h.prepare("a")
        val b = h.prepare("b")
        val token = h.attach()
        h.planned(h.submit(a, token))
        h.timeout(h.planned(h.submit(b, token)))
        val token2 = h.attach()
        val lookup = h.planned(h.lookup(b, token2))
        val lost = h.apply(h.ledger.bindingLost(h.enrollment))
        assertEquals(2, lost)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(a))
        assertEquals(UncertainReason.TRANSPORT_LOST, h.entry(a).uncertainty)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(b)) // its lookup was dropped, not answered
        assertTrue(
            h.ledger.onReply(
                lookup.ref,
                h.success(lookup, h.receipt(b, ReceiptStatus.QUEUED))
            ).result is ReplyOutcome.Ignored
        )
        assertTrue(h.effects.contains(TurnEffect.ReattachRequired(h.enrollment)))
        assertEquals(0, h.apply(h.ledger.bindingLost(h.enrollment)))
    }

    @Test fun lossOfOneEnrollmentsTransportLeavesAnotherEnrollmentsCallsAlone() {
        val h = h()
        val phoneTurn = h.prepare("phone turn")
        h.planned(h.submit(phoneTurn, h.attach()))
        val watch = EnrollmentScope(StateTestSupport.scope, "watch-key-1", 0)
        h.apply(
            h.ledger.prepare(
                TurnDraft(ConversationScope(watch, h.conversation.conversationId), "watch turn"),
                h.now(),
                h.entropy
            )
        )
        val watchTurn = h.ledger.entriesFor(watch).single().key
        val watchStep = h.authority.attach(watch, StateTestSupport.attached(0))
        h.authority = watchStep.authority
        val plan = h.apply(
            h.ledger.planSubmit(watchTurn, h.authority, watchStep.token, h.now(), h.correlation())
        )
        assertEquals(watch, h.planned(plan).ref.scope)
        assertEquals(1, h.apply(h.ledger.bindingLost(watch)))
        assertEquals(TurnPhase.SUBMITTING, h.phase(phoneTurn))
        assertEquals(TurnPhase.UNCERTAIN, h.phase(watchTurn))
        assertEquals(UncertainReason.TRANSPORT_LOST, h.entry(watchTurn).uncertainty)
        assertNull(h.entry(phoneTurn).uncertainty)
    }

    @Test fun discardingNeverDropsATurnTheServerMayStillHoldUnlessTheUserAbandonsIt() {
        val h = h()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        val refused = h.apply(h.ledger.discard(key))
        assertEquals(TurnPhase.SUBMITTING, (refused as DiscardOutcome.Refused).phase)
        h.timeout(submit)
        assertTrue(h.apply(h.ledger.discard(key)) is DiscardOutcome.Refused)
        assertEquals(1, h.ledger.size)
        val abandoned = h.apply(h.ledger.discard(key, DiscardMode.ABANDON_TRACKING))
        assertEquals(TurnPhase.UNCERTAIN, (abandoned as DiscardOutcome.Discarded).phase)
        assertEquals(0, h.ledger.size)
        assertNull((h.apply(h.ledger.discard(key)) as DiscardOutcome.Refused).phase)
        // A turn that was never sent, or is settled, goes freely.
        val unsent = h.prepare("unsent")
        assertTrue(h.apply(h.ledger.discard(unsent)) is DiscardOutcome.Discarded)
    }

    @Test fun planningOnAWrongBindingKindScopeOrConversationIsRefused() {
        val h = h()
        val key = h.prepare()
        h.attach()
        val watch = EnrollmentScope(StateTestSupport.scope, "watch-key-1", 0)
        val foreign = h.authority.attach(watch, StateTestSupport.attached(0))
        assertEquals(
            PlanRefusal.BINDING_WRONG_SCOPE,
            h.refusal(h.submitUsing(key, foreign.authority, foreign.token))
        )
        val otherConversation = h.authority.attach(h.enrollment, StateTestSupport.attached(1))
        assertEquals(
            PlanRefusal.BINDING_WRONG_SCOPE,
            h.refusal(h.submitUsing(key, otherConversation.authority, otherConversation.token))
        )
        val existing = ExistingBinding.decode(
            RelayVectors.forMethod(RelayMethod.EXISTING_ATTACH).first { it.isSuccess }.result
        )
        val existingStep = h.authority.attachExisting(h.enrollment, existing)
        assertEquals(
            PlanRefusal.BINDING_KIND,
            h.refusal(h.submitUsing(key, existingStep.authority, existingStep.token))
        )
        // A token the authority has since replaced is not current either.
        val stale = h.authority.attach(h.enrollment, StateTestSupport.attached(0)).token
        assertEquals(
            PlanRefusal.BINDING_NOT_CURRENT,
            h.refusal(h.submitUsing(key, h.authority, stale))
        )
        val unknown = key.copy(requestId = foreignRequestId(h))
        assertEquals(
            PlanRefusal.UNKNOWN_TURN,
            h.refusal(h.submitUsing(unknown, h.authority, h.attach()))
        )
    }

    private fun foreignRequestId(h: LedgerHarness) =
        (RequestIds.mint(h.now(), StateTestSupport.entropy(99)) as MintResult.Minted).id

    @Test fun aClockRollbackBetweenDecisionsRefusesPlanning() {
        val h = h()
        val key = h.prepare()
        val token = h.attach()
        h.advance(1_000)
        val earlier = h.instantAtNanos(h.nanos - 500_000_000L)
        h.prepare("later") // the ledger has now seen the later reading
        val step = h.ledger.planSubmit(key, h.authority, token, earlier, h.correlation())
        assertEquals(PlanRefusal.CLOCK_ROLLBACK, (step.result as PlanOutcome.Refused).reason)
        assertSame(h.ledger, step.ledger)
    }
}
