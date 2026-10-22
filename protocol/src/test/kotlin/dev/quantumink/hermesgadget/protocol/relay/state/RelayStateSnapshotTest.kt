package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapshots and restore: exact continuation of what was committed, and nothing invented, revived or replayed. */
class RelayStateSnapshotTest {
    private val f = HistoryHarness.Companion

    /** A ledger with turns in every interesting state. */
    private class Ledger {
        val h = LedgerHarness()
        val prepared = h.prepare("never sent", ttlMs = 90_000)
        val sending = h.prepare("in flight", ttlMs = 90_000)
        val timedOut = h.prepare("timed out", ttlMs = 90_000)
        val started = h.prepare("running", ttlMs = 90_000)
        val finished = h.prepare("done", ttlMs = 90_000)
        val expired = h.prepare("expired", ttlMs = 90_000)
        val refused = h.prepare("refused", ttlMs = 90_000)
        val held = h.prepare("held", ttlMs = 90_000)

        init {
            val token = h.attach()
            h.planned(h.submit(sending, token))
            h.timeout(h.planned(h.submit(timedOut, token)))
            h.reply(
                h.planned(h.submit(started, token)),
                h.receipt(started, ReceiptStatus.STARTED, generation = 2)
            )
            h.reply(
                h.planned(h.submit(finished, token)),
                h.receipt(
                    finished,
                    ReceiptStatus.TERMINAL,
                    1,
                    "completed",
                    LedgerHarness.OTHER_ADMISSION
                )
            )
            h.fail(h.planned(h.submit(expired, token)), "expired")
            h.fail(h.planned(h.submit(refused, token)), "invalid_request")
            h.fail(h.planned(h.submit(held, token)), "conflict")
        }
    }

    private fun restore(
        snapshot: RelayStateSnapshot,
        nowMs: Long = 0,
        authority: RelayAuthority = RelayAuthority.fresh()
    ): RestoreResult<RestoredState> =
        RelayStateRestore.restore(snapshot, StateTestSupport.at(nowMs), authority)

    private fun restored(result: RestoreResult<RestoredState>): RestoredState =
        (result as RestoreResult.Restored).value

    private fun refusal(result: RestoreResult<RestoredState>): RestoreResult.Refused =
        result as RestoreResult.Refused

    private fun snapshotOf(ledger: TurnLedger) =
        RelayStateSnapshot.of(ledger, emptyList(), emptyList())

    @Test fun aRestoredLedgerKeepsEveryTurnExactlyAndMarksPossiblySentOnesUncertain() {
        val l = Ledger()
        val before = l.h.ledger
        val state = restored(restore(snapshotOf(before)))
        val after = state.ledger
        assertEquals(before.entries.map { it.key }, after.entries.map { it.key })
        for (key in before.entries.map { it.key }) {
            val a = before.entry(key)!!
            val b = after.entry(key)!!
            assertEquals(a.text, b.text)
            assertEquals(a.expiresAtMs, b.expiresAtMs)
            assertEquals(a.preparedAtMs, b.preparedAtMs)
            assertEquals(a.fingerprint, b.fingerprint)
            assertEquals(a.sent, b.sent)
            assertEquals(a.facts, b.facts)
            assertEquals(a.closure, b.closure)
            assertEquals(a.hold, b.hold)
            assertEquals(a.lastErrorCode, b.lastErrorCode)
            assertEquals(a.anomalies, b.anomalies)
        }
        val phases = l.h.ledger.entries.associate { it.key to after.entry(it.key)!!.phase }
        assertEquals(TurnPhase.PREPARED, phases[l.prepared])
        // A call in flight when the process died
        assertEquals(TurnPhase.UNCERTAIN, phases[l.sending])
        assertEquals(UncertainReason.PROCESS_LOSS, after.entry(l.sending)!!.uncertainty)
        assertEquals(TurnPhase.UNCERTAIN, phases[l.timedOut])
        assertEquals(TurnPhase.STARTED, phases[l.started])
        assertEquals(TurnPhase.TERMINAL, phases[l.finished])
        assertEquals(TurnPhase.NOT_ADMITTED, phases[l.expired])
        assertEquals(TurnPhase.REFUSED, phases[l.refused])
        assertEquals(TurnPhase.UNCERTAIN, phases[l.held])
        assertEquals(TurnHold.UNEXPECTED_ERROR, after.entry(l.held)!!.hold)
        // Saving what was restored gives the same durable record back.
        assertEquals(before.snapshot().turns, after.snapshot().turns)
    }

    @Test fun afterARestoreReceiptComesFirstAndOnlyAnAttachMadeAfterwardsCounts() {
        val l = Ledger()
        val state = restored(restore(snapshotOf(l.h.ledger)))
        val fresh = LedgerHarness()
        // Run the restored ledger on a new authority: an attach that happens now.
        var ledger = state.ledger
        var authority = RelayAuthority.fresh()
        val token = authority.attach(l.h.enrollment, StateTestSupport.attached(0)).also {
            authority =
                it.authority
        }.token
        val now = fresh.now()
        val submit = ledger.planSubmit(l.timedOut, authority, token, now, fresh.correlation())
        assertEquals(
            PlanRefusal.LOOKUP_REQUIRED_FIRST,
            (submit.result as PlanOutcome.Refused).reason
        )
        val lookup = ledger.planLookup(l.timedOut, authority, token, now, fresh.correlation())
        assertTrue(lookup.result is PlanOutcome.Planned)
        ledger = lookup.ledger
        // A turn never sent can simply go out; a turn already admitted is never submitted again.
        val sent = ledger.planSubmit(l.prepared, authority, token, now, fresh.correlation())
        assertTrue(sent.result is PlanOutcome.Planned)
        val again = ledger.planSubmit(l.started, authority, token, now, fresh.correlation())
        assertEquals(PlanRefusal.ALREADY_ADMITTED, (again.result as PlanOutcome.Refused).reason)
    }

    @Test fun aRestoreNeedsFreshServerTimeAndFreshAuthorityAndLeavesNoClockOrBinding() {
        val l = Ledger()
        val snapshot = snapshotOf(l.h.ledger)
        // A binding already present is not fresh authority.
        val used = RelayAuthority.fresh().attach(
            l.h.enrollment,
            StateTestSupport.attached(0)
        ).authority
        assertEquals(
            RestoreRefusal.AUTHORITY_NOT_FRESH,
            refusal(restore(snapshot, authority = used)).reason
        )
        // The O5-only info yields no clock, hence no instant to restore with.
        assertTrue(ServerClock.fix(StateTestSupport.o5OnlyInfo, 1) is ClockFix.Refused)
        // Readings before the restore instant are refused afterwards: the monotonic source may not go backwards.
        val state = restored(restore(snapshot, nowMs = 5_000))
        val earlier = StateTestSupport.at(1_000)
        val step = state.ledger.prepare(
            TurnDraft(l.h.conversation, "x"),
            earlier,
            StateTestSupport.entropy(9)
        )
        assertEquals(PrepareRefusal.CLOCK_ROLLBACK, (step.result as PrepareOutcome.Refused).reason)
        val later = state.ledger.prepare(
            TurnDraft(l.h.conversation, "x"),
            StateTestSupport.at(6_000),
            StateTestSupport.entropy(9)
        )
        assertTrue(later.result is PrepareOutcome.Prepared)
    }

    @Test fun noticesTellWhenUnresolvedTurnsAreAlreadyPastTheirDeadlineAndStillUnknown() {
        val l = Ledger()
        val snapshot = snapshotOf(l.h.ledger)
        assertTrue(
            RelayStateRestore.restore(snapshot, StateTestSupport.at(0), RelayAuthority.fresh())
                .let { (it as RestoreResult.Restored).notices.isEmpty() }
        )
        val late = restore(snapshot, nowMs = 10 * 60_000)
        assertEquals(
            listOf(RestoreNotice.UNRESOLVED_TURNS_PAST_DEADLINE),
            (late as RestoreResult.Restored).notices
        )
        val restoredTurn = late.value.ledger.entry(l.timedOut)!!
        assertEquals(TurnStatus.UNCERTAIN, restoredTurn.status) // local expiry changed nothing
        assertTrue(restoredTurn.deadlinePassed(StateTestSupport.at(10 * 60_000)))
        // A server clock behind the saved state is reported, never "corrected".
        val behind = RelayStateRestore.restore(
            snapshot,
            StateTestSupport.raw(1L),
            RelayAuthority.fresh()
        )
        assertTrue(
            (behind as RestoreResult.Restored).notices.contains(
                RestoreNotice.SERVER_CLOCK_BEHIND_SNAPSHOT
            )
        )
    }

    @Test fun aRetainedReceiptIsLookedUpWhateverTheAgeOfItsIdOrItsDeadline() {
        val l = Ledger()
        val eightDays = 8L * 24 * 3600 * 1000
        val state = restored(restore(snapshotOf(l.h.ledger), nowMs = eightDays))
        val now = StateTestSupport.at(eightDays)
        // Far too old for a NEW turn (and long past its own deadline) ...
        assertEquals(IdAge.TOO_OLD, RequestIds.checkNew(l.timedOut.requestId, now.serverMs))
        assertTrue(state.ledger.entry(l.timedOut)!!.deadlinePassed(now))
        // ... yet its receipt is still looked up, and the exact answer settles exactly that turn.
        var authority = RelayAuthority.fresh()
        val step = authority.attach(l.h.enrollment, StateTestSupport.attached(0))
        authority = step.authority
        val planned = state.ledger.planLookup(
            l.timedOut,
            authority,
            step.token,
            now,
            l.h.correlation()
        )
        val dispatch = (planned.result as PlanOutcome.Planned).dispatch
        val receipt = l.h.receipt(l.timedOut, ReceiptStatus.TERMINAL, 1, "completed")
        val settled = planned.ledger.onReply(dispatch.ref, RelayOutcome.Success(receipt))
        assertEquals(TurnStatus.FINISHED_COMPLETED, settled.ledger.entry(l.timedOut)!!.status)
        // The turn that was never sent is past its deadline too, and a late resend is refused, not rewritten.
        val late = state.ledger.planSubmit(
            l.prepared,
            authority,
            step.token,
            now,
            l.h.correlation()
        )
        assertEquals(PlanRefusal.DEADLINE_PASSED, (late.result as PlanOutcome.Refused).reason)
        assertEquals(
            l.h.ledger.entry(l.prepared)!!.expiresAtMs,
            state.ledger.entry(l.prepared)!!.expiresAtMs
        )
    }

    // -- hostile snapshots ---------------------------------------------------------------------------------

    private fun one(): Pair<LedgerHarness, TurnSnapshot> {
        val h = LedgerHarness()
        val key = h.prepare("a valid turn", ttlMs = 60_000)
        h.planned(h.submit(key, h.attach()))
        return h to h.ledger.snapshot().turns.single()
    }

    private fun withTurn(turn: TurnSnapshot, scopes: List<ScopeStateSnapshot> = emptyList()) =
        RelayStateSnapshot(
            RelayStateSnapshot.VERSION,
            TurnLedgerSnapshot(listOf(turn), scopes),
            emptyList(),
            emptyList()
        )

    @Test fun everyTamperingOfASavedTurnIsRefusedWithAPathAndNoValue() {
        val (_, valid) = one()
        assertTrue(restore(withTurn(valid)) is RestoreResult.Restored)
        val facts = AdmissionFactsSnapshot(LedgerHarness.ADMISSION, 1, "started", null, null)
        val bad = linkedMapOf<String, Pair<TurnSnapshot, RestoreRefusal>>(
            "bad profile" to
                (
                    valid.copy(scope = valid.scope.copy(profileId = "nope")) to
                        RestoreRefusal.INVALID_SCOPE
                    ),
            "blank endpoint" to
                (
                    valid.copy(scope = valid.scope.copy(endpointKey = "")) to
                        RestoreRefusal.INVALID_SCOPE
                    ),
            "bad conversation" to (valid.copy(conversationId = "x") to RestoreRefusal.INVALID_ID),
            "uppercase request id" to
                (valid.copy(requestId = valid.requestId.uppercase()) to RestoreRefusal.INVALID_ID),
            "version-4 request id" to (
                valid.copy(
                    requestId =
                    valid.requestId.substring(0, 14) + "4" + valid.requestId.substring(15)
                ) to
                    RestoreRefusal.INVALID_ID
                ),
            "id not stamped with its preparation time" to
                (valid.copy(preparedAtMs = valid.preparedAtMs + 1) to RestoreRefusal.INCONSISTENT),
            "deadline not after preparation" to
                (valid.copy(expiresAtMs = valid.preparedAtMs) to RestoreRefusal.INCONSISTENT),
            "deadline beyond five minutes" to
                (
                    valid.copy(expiresAtMs = valid.preparedAtMs + 300_001) to
                        RestoreRefusal.INCONSISTENT
                    ),
            "fingerprint not hex" to
                (valid.copy(fingerprint = "xyz") to RestoreRefusal.INVALID_FIELD),
            "text changed under the fingerprint" to
                (valid.copy(text = "a different turn") to RestoreRefusal.INCONSISTENT),
            "empty text" to (valid.copy(text = "") to RestoreRefusal.INVALID_FIELD),
            "unknown receipt status" to
                (valid.copy(facts = facts.copy(status = "done")) to RestoreRefusal.INVALID_FIELD),
            "queued with a generation" to
                (
                    valid.copy(facts = facts.copy(status = "queued", executionGeneration = 1)) to
                        RestoreRefusal.INVALID_FIELD
                    ),
            "started without a generation" to
                (valid.copy(facts = facts) to RestoreRefusal.INVALID_FIELD),
            "outcome that is not a token" to
                (
                    valid.copy(
                        facts = facts.copy(
                            status = "terminal",
                            executionGeneration = 1,
                            outcome = "Bad Outcome"
                        )
                    ) to
                        RestoreRefusal.INVALID_FIELD
                    ),
            "unknown closure" to
                (valid.copy(closure = "settled:ok") to RestoreRefusal.INVALID_FIELD),
            "closed and admitted" to
                (
                    valid.copy(
                        closure = "refused:invalid_request",
                        facts = facts.copy(executionGeneration = 1)
                    ) to
                        RestoreRefusal.INCONSISTENT
                    ),
            "held but never sent" to
                (valid.copy(sent = false, held = true) to RestoreRefusal.INCONSISTENT),
            "admitted but never sent" to
                (
                    valid.copy(sent = false, facts = facts.copy(executionGeneration = 1)) to
                        RestoreRefusal.INCONSISTENT
                    ),
            "negative anomalies" to (valid.copy(anomalies = -1) to RestoreRefusal.INVALID_FIELD),
            "unknown error code" to
                (valid.copy(lastErrorCode = "made_up") to RestoreRefusal.INVALID_FIELD)
        )
        val field = mapOf(
            "bad profile" to "scope",
            "blank endpoint" to "scope",
            "bad conversation" to "conversationId",
            "uppercase request id" to "requestId",
            "version-4 request id" to "requestId",
            "id not stamped with its preparation time" to "preparedAtMs",
            "deadline not after preparation" to "expiresAtMs",
            "deadline beyond five minutes" to "expiresAtMs",
            "fingerprint not hex" to "fingerprint",
            "text changed under the fingerprint" to "fingerprint",
            "empty text" to "text",
            "unknown receipt status" to "facts",
            "queued with a generation" to "facts",
            "started without a generation" to "facts",
            "outcome that is not a token" to "facts",
            "unknown closure" to "closure",
            "negative anomalies" to "anomalies",
            "unknown error code" to "lastErrorCode"
        ) // the shape rules (closed and admitted, held but never sent, ...) name the whole turn
        for ((name, case) in bad) {
            val result = refusal(restore(withTurn(case.first)))
            assertEquals(name, case.second, result.reason)
            // The refusal names the very field that was tampered with (not just any later check).
            assertEquals(name, "ledger.turns[0]" + (field[name]?.let { ".$it" } ?: ""), result.path)
            assertFalse(name, result.toString().contains(valid.text!!))
        }
    }

    @Test fun duplicatesVersionsAndLimitsAreRefused() {
        val (_, valid) = one()
        val dup =
            RelayStateSnapshot(
                1,
                TurnLedgerSnapshot(listOf(valid, valid), emptyList()),
                emptyList(),
                emptyList()
            )
        assertEquals(RestoreRefusal.DUPLICATE, refusal(restore(dup)).reason)
        val wrongVersion =
            RelayStateSnapshot(
                2,
                TurnLedgerSnapshot(listOf(valid), emptyList()),
                emptyList(),
                emptyList()
            )
        assertEquals(RestoreRefusal.VERSION, refusal(restore(wrongVersion)).reason)
        // Exactly the policy limit is accepted; one turn more is refused before anything is built.
        val h = LedgerHarness()
        repeat(StateLimits.MAX_RETAINED_TURNS) { h.prepare("turn $it") }
        val atLimit = h.ledger.snapshot().turns
        assertEquals(StateLimits.MAX_RETAINED_TURNS, atLimit.size)
        val ok =
            RelayStateSnapshot(
                1,
                TurnLedgerSnapshot(atLimit, emptyList()),
                emptyList(),
                emptyList()
            )
        assertTrue(restore(ok) is RestoreResult.Restored)
        val past = RelayStateSnapshot(
            1,
            TurnLedgerSnapshot(atLimit + extraTurn(atLimit.first()), emptyList()),
            emptyList(),
            emptyList()
        )
        val refused = refusal(restore(past))
        assertEquals(RestoreRefusal.TOO_MANY, refused.reason)
        assertEquals("ledger.turns", refused.path)
    }

    /** A valid saved turn, different from [template] (fresh id, matching stamp and fingerprint, other text). */
    private fun extraTurn(template: TurnSnapshot, text: String = "x"): TurnSnapshot {
        val id = (
            RequestIds.mint(
                StateTestSupport.raw(template.preparedAtMs),
                StateTestSupport.entropy(77)
            ) as MintResult.Minted
            ).id
        return template.copy(
            requestId = id.value,
            text = text,
            fingerprint = TurnFingerprint.of(
                ConversationId.of(template.conversationId)!!,
                id,
                template.expiresAtMs,
                text
            ).hex
        )
    }

    @Test fun theTextBudgetOfASavedLedgerIsExactToTheByte() {
        val big = "😀".repeat(4_000) // 16,000 bytes
        val turns = ArrayList<TurnSnapshot>()
        val h = LedgerHarness()
        repeat(65) { h.prepare(big) }
        h.prepare("あ".repeat(2_858) + "é") // fills the budget to exactly 1 MiB
        turns.addAll(h.ledger.snapshot().turns)
        val atLimit =
            RelayStateSnapshot(1, TurnLedgerSnapshot(turns, emptyList()), emptyList(), emptyList())
        assertTrue(restore(atLimit) is RestoreResult.Restored)
        val over = turns.toMutableList()
        val extra = extraTurn(turns.first())
        over.add(extra)
        val past =
            RelayStateSnapshot(1, TurnLedgerSnapshot(over, emptyList()), emptyList(), emptyList())
        assertEquals(RestoreRefusal.BUDGET, refusal(restore(past)).reason)
    }

    @Test fun scopeDecisionsSurviveARestoreAndInvalidOnesAreRefused() {
        val l = Ledger()
        l.h.fail(l.h.planned(l.h.submit(l.prepared, l.h.attach())), "permission_denied")
        val state = restored(restore(snapshotOf(l.h.ledger)))
        assertEquals(ScopeStatus.STOPPED, state.ledger.scopeStatus(l.h.enrollment))
        assertTrue(state.ledger.scopeRecord(l.h.enrollment)!!.contentEvictionOwed)
        // A restored stop still refuses new turns and calls.
        val step = state.ledger.prepare(
            TurnDraft(l.h.conversation, "x"),
            StateTestSupport.at(0),
            StateTestSupport.entropy(4)
        )
        assertEquals(PrepareRefusal.SCOPE_STOPPED, (step.result as PrepareOutcome.Refused).reason)
        val scope = l.h.enrollment.toSnapshot()
        val bad = listOf(
            ScopeStateSnapshot(scope, "ACTIVE", false) to RestoreRefusal.INCONSISTENT,
            ScopeStateSnapshot(scope, "BROKEN", true) to RestoreRefusal.INVALID_FIELD,
            ScopeStateSnapshot(scope.copy(profileId = "no"), "STOPPED", true) to
                RestoreRefusal.INVALID_SCOPE
        )
        for ((record, reason) in bad) {
            val snapshot =
                RelayStateSnapshot(
                    1,
                    TurnLedgerSnapshot(emptyList(), listOf(record)),
                    emptyList(),
                    emptyList()
                )
            assertEquals(reason, refusal(restore(snapshot)).reason)
        }
        val twice = RelayStateSnapshot(
            1,
            TurnLedgerSnapshot(
                emptyList(),
                listOf(
                    ScopeStateSnapshot(scope, "STOPPED", true),
                    ScopeStateSnapshot(scope, "NEEDS_REVIEW", false)
                )
            ),
            emptyList(),
            emptyList()
        )
        assertEquals(RestoreRefusal.DUPLICATE, refusal(restore(twice)).reason)
    }

    // -- history -------------------------------------------------------------------------------------------

    private fun committedHistory(): HistoryHarness {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1), f.single(2))))
        h.accepted(h.feed(f.page(f.cursor(2), f.single(3), f.single(4))))
        h.apply(h.assembly.commit(2)) // resume point: the cursor after page one
        return h
    }

    private fun restoreHistory(saved: HistorySnapshot): RestoreResult<HistoryAssembly> =
        HistoryAssembly.restore(saved)

    @Test fun aRestoredListingResumesFromItsSavedCursorAndLosesTheStagedTail() {
        val h = committedHistory()
        val saved = h.assembly.snapshot()
        // Staged (uncommitted) messages 3 and 4 are not part of the saved record.
        assertEquals(2, saved.known.size)
        assertEquals(2L, saved.committedOrdinal)
        assertEquals(f.cursor(1), saved.resumeCursor)
        val restored = (restoreHistory(saved) as RestoreResult.Restored).value
        assertEquals(ListingStatus.ACTIVE, restored.status)
        assertEquals(ListingStart.RESUMED_FROM_SAVED_CURSOR, restored.provenance.start)
        assertEquals(1, restored.provenance.generation)
        assertTrue(restored.stagedMessages.isEmpty())
        assertFalse(restored.hasOpenMessage)
        val g = HistoryHarness(h.scope)
        g.assembly = restored
        val plan = g.plan()
        // Continues exactly where the commit left off
        assertEquals(f.cursor(1), plan.request.cursor)
        // The server's page one is replayed from the saved cursor: committed ones are skipped, the tail is new again.
        val outcome = g.accepted(
            g.accept(plan, f.page(null, f.single(2), f.single(3), f.single(4)))
        )
        assertEquals(1, outcome.duplicateFragments)
        assertEquals(listOf(3L, 4L), outcome.emitted.map { it.ordinal })
    }

    @Test fun aListingThatCommittedWithoutACleanBoundaryIsInvalidatedNotSilentlyReadFromItsTop() {
        val h = HistoryHarness()
        h.accepted(h.feed(f.page(f.cursor(1), f.single(1), f.single(2), f.single(3))))
        h.apply(h.assembly.commit(2)) // mid-page: committed, but no resume cursor exists
        val saved = h.assembly.snapshot()
        assertFalse(saved.hasResume)
        val result = restoreHistory(saved) as RestoreResult.Restored
        assertEquals(listOf(RestoreNotice.HISTORY_NEEDS_REBUILD), result.notices)
        assertEquals(ListingStatus.INVALIDATED, result.value.status)
        assertEquals(
            HistoryInvalidation.RESTORED_WITHOUT_CURSOR,
            result.value.provenance.invalidation
        )
        val g = HistoryHarness(h.scope)
        g.assembly = result.value
        assertEquals(HistoryPlanRefusal.NOT_ACTIVE, g.planRefusal())
        g.assembly = g.assembly.restart()
        assertEquals(ListingStart.REBUILD, g.assembly.provenance.start)
        assertEquals(HistoryInvalidation.RESTORED_WITHOUT_CURSOR, g.assembly.provenance.after)
        assertEquals(2, g.assembly.provenance.generation)
        assertTrue(g.plan().request.isFirstPage)
    }

    @Test fun aListingThatNeverCommittedJustStartsAgainAndACompleteOneStaysComplete() {
        val fresh = HistoryHarness()
        fresh.accepted(fresh.feed(f.page(f.cursor(1), f.single(1)))) // staged only
        val untouched = (restoreHistory(fresh.assembly.snapshot()) as RestoreResult.Restored).value
        assertEquals(ListingStatus.ACTIVE, untouched.status)
        assertEquals(ListingStart.BEGINNING, untouched.provenance.start)
        val g = HistoryHarness(fresh.scope)
        g.assembly = untouched
        assertTrue(g.plan().request.isFirstPage)

        val done = HistoryHarness()
        done.accepted(done.feed(f.page(null, f.single(1), f.single(2))))
        done.apply(done.assembly.commit(2))
        val complete = (restoreHistory(done.assembly.snapshot()) as RestoreResult.Restored).value
        assertEquals(ListingStatus.COMPLETE, complete.status)
        assertEquals(2, complete.knownCount)
    }

    @Test fun aSavedInvalidationAndRefusedMessagesComeBackAsTheyWere() {
        val h = HistoryHarness()
        h.accepted(
            h.feed(
                f.page(
                    f.cursor(1),
                    f.fragment(1, 0, StateLimits.MAX_MESSAGE_SEGMENTS + 1, "x"),
                    f.single(2)
                )
            )
        )
        h.apply(h.assembly.commit(1))
        val restored = (restoreHistory(h.assembly.snapshot()) as RestoreResult.Restored).value
        assertEquals(listOf(HistoryHarness.id(1)), restored.refusedMessages.map { it.messageId })
        val bad = HistoryHarness()
        bad.feed(f.page(f.cursor(1), f.fragment(5, 1, 3, "orphan")))
        val invalid = (restoreHistory(bad.assembly.snapshot()) as RestoreResult.Restored).value
        assertEquals(ListingStatus.INVALIDATED, invalid.status)
        assertEquals(HistoryInvalidation.UNEXPECTED_FRAGMENT, invalid.provenance.invalidation)
    }

    @Test fun tamperedHistoryRecordsAreRefused() {
        val h = committedHistory()
        val valid = h.assembly.snapshot()
        assertTrue(restoreHistory(valid) is RestoreResult.Restored)
        fun copy(
            generation: Long = valid.generation,
            status: String = valid.status,
            invalidation: String? = valid.invalidation,
            committed: Long = valid.committedOrdinal,
            hasResume: Boolean = valid.hasResume,
            cursor: String? = valid.resumeCursor,
            atEnd: Boolean = valid.resumeAtEnd,
            known: List<KnownMessageSnapshot> = valid.known,
            source: String = valid.source,
            conversation: String = valid.conversationId
        ) = HistorySnapshot(
            valid.scope,
            conversation,
            source,
            generation,
            valid.start,
            valid.after,
            status,
            invalidation,
            committed,
            hasResume,
            cursor,
            atEnd,
            known,
            valid.refused
        )
        val dup = valid.known + valid.known.first()
        val cases = linkedMapOf(
            "generation zero" to (copy(generation = 0) to RestoreRefusal.INVALID_FIELD),
            "unknown status" to (copy(status = "DONE") to RestoreRefusal.INVALID_FIELD),
            "invalidation without invalidated" to
                (copy(invalidation = "NO_PROGRESS") to RestoreRefusal.INCONSISTENT),
            "invalidated without a reason" to
                (copy(status = "INVALIDATED") to RestoreRefusal.INCONSISTENT),
            "negative ordinal" to (copy(committed = -1) to RestoreRefusal.INVALID_FIELD),
            "cursor without resume" to (copy(hasResume = false) to RestoreRefusal.INCONSISTENT),
            "end with a cursor" to (copy(atEnd = true) to RestoreRefusal.INCONSISTENT),
            "cursor with forbidden bytes" to
                (copy(cursor = "bad cursor") to RestoreRefusal.INVALID_FIELD),
            "more known than committed" to (copy(committed = 1) to RestoreRefusal.INCONSISTENT),
            "duplicate known" to (copy(known = dup, committed = 3) to RestoreRefusal.DUPLICATE),
            "unknown source" to (copy(source = "ELSEWHERE") to RestoreRefusal.INVALID_FIELD),
            "bad conversation" to (copy(conversation = "x") to RestoreRefusal.INVALID_ID)
        )
        for ((name, case) in cases) {
            val result = restoreHistory(case.first) as RestoreResult.Refused
            assertEquals(name, case.second, result.reason)
        }
    }

    // -- delivery ------------------------------------------------------------------------------------------

    private fun deliveryWithState(): DeliveryHarness {
        val h = DeliveryHarness()
        val read = DeliveryHarness.events("ok-07")
        h.attach(h.bindingOf(read))
        val batch = h.staged(h.stage(h.planRead(50), read))
        h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        val outbox = h.stagedOutbox(
            h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-16"))
        )
        h.apply(h.delivery.commitOutbox(outbox.batchId, h.now()))
        val stock = h.stagedOutbox(h.stageOutbox(h.planOutbox(), DeliveryHarness.outbox("ok-09")))
        h.apply(h.delivery.commitOutbox(stock.batchId, h.now()))
        return h
    }

    @Test fun deliveryStateRestoresIdentitiesObligationsBaselineAndAcksWithoutASession() {
        val h = deliveryWithState()
        val saved = h.delivery.snapshot()
        val restored = (EnrollmentDelivery.restore(saved) as RestoreResult.Restored).value
        assertEquals(h.delivery.seenCount, restored.seenCount)
        assertEquals(h.delivery.acknowledgementsOwed, restored.acknowledgementsOwed)
        assertEquals(h.delivery.committedGapGeneration, restored.committedGapGeneration)
        assertEquals(
            h.delivery.refreshOwed.map { it.kind to it.cause },
            restored.refreshOwed.map {
                it.kind to
                    it.cause
            }
        )
        assertTrue(restored.hasSeen(DedupKey.Opaque("stock-chat", "stock-final")))
        assertFalse(restored.hasSession)
        // The read cursor belonged to a binding that no longer exists
        assertNull(restored.committedSequence)
        assertEquals(saved.seen, restored.snapshot().seen)
        // A replay of what was committed is recognised after the restore.
        val g = DeliveryHarness()
        g.delivery = restored
        val replay = g.stagedOutbox(
            g.stageOutbox(g.planOutbox(50), DeliveryHarness.outbox("ok-16"))
        )
        assertTrue(replay.items.isEmpty())
        assertEquals(3, replay.duplicates.size)
        // Unacknowledged ids are still owed and can be acknowledged.
        assertTrue(g.planAck().call.params["delivery_ids"] != null)
    }

    @Test fun anUnknownBaselineStaysUnknownAndStagedContentIsNeverSaved() {
        val h = DeliveryHarness()
        val read = DeliveryHarness.events("ok-07")
        h.attach(h.bindingOf(read))
        h.staged(h.stage(h.planRead(50), read)) // staged, never committed
        h.stagedOutbox(h.stageOutbox(h.planOutbox(50), DeliveryHarness.outbox("ok-07")))
        val saved = h.delivery.snapshot()
        assertTrue(saved.seen.isEmpty())
        assertTrue(saved.pendingAcks.isEmpty())
        assertNull(saved.gapBaseline)
        val restored = (EnrollmentDelivery.restore(saved) as RestoreResult.Restored).value
        assertNull(restored.committedGapGeneration)
        val g = DeliveryHarness()
        g.delivery = restored
        val batch = g.stagedOutbox(g.stageOutbox(g.planOutbox(50), DeliveryHarness.outbox("ok-07")))
        assertEquals(4, batch.items.size) // nothing was remembered, so it is all new again
        assertTrue(batch.refresh.any { it.cause == RefreshCause.OUTBOX_BASELINE_UNKNOWN })
    }

    @Test fun tamperedDeliveryRecordsAreRefused() {
        val valid = deliveryWithState().delivery.snapshot()
        assertTrue(EnrollmentDelivery.restore(valid) is RestoreResult.Restored)
        fun copy(
            seen: List<SeenKeySnapshot> = valid.seen,
            obligations: List<ObligationSnapshot> = valid.obligations,
            acks: List<String> = valid.pendingAcks,
            baseline: Long? = valid.gapBaseline,
            scope: ScopeRecordSnapshot = valid.scope
        ) = DeliverySnapshot(scope, baseline, seen, obligations, acks)
        val firstSeen = valid.seen.first()
        val cases = linkedMapOf(
            "bad scope" to
                (
                    copy(
                        scope = valid.scope.copy(enrollmentKey = "")
                    ) to RestoreRefusal.INVALID_SCOPE
                    ),
            "negative baseline" to (copy(baseline = -1) to RestoreRefusal.INVALID_FIELD),
            "unknown key kind" to
                (
                    copy(
                        seen = listOf(firstSeen.copy(kind = "other"))
                    ) to RestoreRefusal.INVALID_FIELD
                    ),
            "non-canonical id as canonical" to
                (
                    copy(
                        seen = listOf(firstSeen.copy(kind = "canonical", event = "stock-final"))
                    ) to
                        RestoreRefusal.INVALID_ID
                    ),
            "oversize opaque ref" to
                (
                    copy(seen = listOf(SeenKeySnapshot("opaque", "c", "e".repeat(257), 1))) to
                        RestoreRefusal.INVALID_ID
                    ),
            "negative commit time" to
                (
                    copy(seen = listOf(firstSeen.copy(committedAtMs = -1))) to
                        RestoreRefusal.INVALID_FIELD
                    ),
            "duplicate key" to (copy(seen = valid.seen + firstSeen) to RestoreRefusal.DUPLICATE),
            "bad obligation kind" to
                (
                    copy(obligations = listOf(ObligationSnapshot(null, "OTHER", "ATTACHED"))) to
                        RestoreRefusal.INVALID_FIELD
                    ),
            "bad obligation cause" to
                (
                    copy(obligations = listOf(ObligationSnapshot(null, "HISTORY", "WHY"))) to
                        RestoreRefusal.INVALID_FIELD
                    ),
            "bad obligation conversation" to
                (
                    copy(obligations = listOf(ObligationSnapshot("x", "HISTORY", "ATTACHED"))) to
                        RestoreRefusal.INVALID_ID
                    ),
            "duplicate obligation" to
                (
                    copy(
                        obligations = listOf(
                            ObligationSnapshot(null, "HISTORY", "ATTACHED"),
                            ObligationSnapshot(null, "HISTORY", "OUTBOX_GAP")
                        )
                    ) to
                        RestoreRefusal.DUPLICATE
                    ),
            "bad ack id" to (copy(acks = listOf("nope")) to RestoreRefusal.INVALID_ID),
            "duplicate ack" to
                (
                    copy(acks = valid.pendingAcks + valid.pendingAcks.first()) to
                        RestoreRefusal.DUPLICATE
                    )
        )
        for ((name, case) in cases) {
            val result = EnrollmentDelivery.restore(case.first) as RestoreResult.Refused
            assertEquals(name, case.second, result.reason)
        }
    }

    // -- aggregate -----------------------------------------------------------------------------------------

    @Test fun theAggregateSnapshotRestoresAllPartsAndRefusesDuplicateScopes() {
        val l = Ledger()
        val history = committedHistory()
        val delivery = deliveryWithState()
        val snapshot = RelayStateSnapshot.of(
            l.h.ledger,
            listOf(history.assembly),
            listOf(delivery.delivery)
        )
        val state = restored(restore(snapshot))
        assertEquals(l.h.ledger.size, state.ledger.size)
        assertEquals(1, state.histories.size)
        assertEquals(1, state.deliveries.size)
        assertEquals(history.scope, state.histories.single().scope)
        val dupHistory = RelayStateSnapshot.of(
            l.h.ledger,
            listOf(history.assembly, history.assembly),
            emptyList()
        )
        assertEquals(RestoreRefusal.DUPLICATE, refusal(restore(dupHistory)).reason)
        val dupDelivery = RelayStateSnapshot.of(
            l.h.ledger,
            emptyList(),
            listOf(delivery.delivery, delivery.delivery)
        )
        assertEquals(RestoreRefusal.DUPLICATE, refusal(restore(dupDelivery)).reason)
        val badHistory = RelayStateSnapshot(
            1,
            snapshot.ledger,
            listOf(
                HistorySnapshot(
                    history.assembly.snapshot().scope,
                    "x",
                    "RELAY",
                    1,
                    "BEGINNING",
                    null,
                    "ACTIVE",
                    null,
                    0,
                    false,
                    null,
                    false,
                    emptyList(),
                    emptyList()
                )
            ),
            emptyList()
        )
        val refused = refusal(restore(badHistory))
        assertTrue(refused.path.startsWith("histories[0]"))
    }

    @Test fun snapshotsAreImmutableOwnedBoundedAndHoldNoTransientOrActionableState() {
        val (_, turn) = one()
        val source = mutableListOf(turn)
        val snapshot = TurnLedgerSnapshot(source, emptyList())
        source.add(turn) // the caller keeps mutating its list
        assertEquals(1, snapshot.turns.size)
        try {
            @Suppress("UNCHECKED_CAST")
            (snapshot.turns as MutableList<TurnSnapshot>).add(turn)
            throw AssertionError("the snapshot list is mutable")
        } catch (_: UnsupportedOperationException) {
        }
        val oversize =
            TurnLedgerSnapshot(List(StateLimits.MAX_RETAINED_TURNS + 50) { turn }, emptyList())
        // Kept just past the limit, so restore refuses
        assertEquals(StateLimits.MAX_RETAINED_TURNS + 1, oversize.turns.size)
        // Nothing transient or actionable is part of any saved record.
        val forbidden = listOf(
            "binding", "replay", "clock", "offset", "monotonic", "correlation", "outstanding",
            "prompt", "control", "speak", "playback", "autosend", "autoplay", "staged", "pending",
            "token"
        )
        val classes = listOf(
            TurnSnapshot::class.java,
            ScopeRecordSnapshot::class.java,
            AdmissionFactsSnapshot::class.java,
            ScopeStateSnapshot::class.java,
            TurnLedgerSnapshot::class.java,
            KnownMessageSnapshot::class.java,
            RefusedMessageSnapshot::class.java,
            HistorySnapshot::class.java,
            SeenKeySnapshot::class.java,
            ObligationSnapshot::class.java,
            DeliverySnapshot::class.java,
            RelayStateSnapshot::class.java
        )
        for (type in classes) {
            for (field in type.declaredFields.map { it.name.lowercase() }) {
                // `pendingAcks` are STORED deliveries awaiting acknowledgement, the one allowed "pending".
                val allowed = field == "pendingacks"
                assertTrue("$type.$field", allowed || forbidden.none { field.contains(it) })
            }
        }
    }

    @Test fun nothingPrintsTextCursorsIdsOrLabelsFromASnapshotOrARefusal() {
        val l = Ledger()
        val history = committedHistory()
        val delivery = deliveryWithState()
        val snapshot = RelayStateSnapshot.of(
            l.h.ledger,
            listOf(history.assembly),
            listOf(delivery.delivery)
        )
        val printed = listOf(
            snapshot.toString(),
            snapshot.ledger.toString(),
            snapshot.ledger.turns.first().toString(),
            snapshot.ledger.turns.first().scope.toString(),
            snapshot.histories.first().toString(),
            snapshot.deliveries.first().toString(),
            snapshot.deliveries.first().seen.first().toString(),
            RestoreResult.Refused(RestoreRefusal.INVALID_ID, "turns[0].requestId").toString()
        ).joinToString("\n")
        for (secret in listOf(
            "never sent",
            "running",
            "endpoint-a",
            "phone-key-1",
            f.cursor(1),
            "stock-final"
        )) {
            assertFalse(secret, printed.contains(secret))
        }
        assertEquals(
            RelayMethod.HISTORY_PAGE.wire,
            RelayVectors.forMethod(RelayMethod.HISTORY_PAGE).first().method
        )
    }
}
