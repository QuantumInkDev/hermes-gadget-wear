package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.AdmissionId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a receipt may prove: only its exact turn, only forward, and unknown stays unknown. */
class TurnLedgerEvidenceTest {
    /** One harness with one turn that a first receipt admitted. */
    private class Admitted(val h: LedgerHarness, val key: TurnKey) {
        val entry: TurnEntry get() = h.entry(key)

        fun receipt(
            status: ReceiptStatus,
            generation: Long? = null,
            outcome: String? = null
        ): Receipt = h.receipt(key, status, generation, outcome)

        /** A status-refresh lookup of the admitted turn, answered with [receipt]. */
        fun refresh(receipt: Receipt): ReplyOutcome {
            val dispatch = h.planned(h.lookup(key, h.authority.current(h.enrollment)!!))
            return h.reply(dispatch, receipt)
        }
    }

    private fun admitted(
        status: ReceiptStatus = ReceiptStatus.QUEUED,
        generation: Long? = null,
        outcome: String? = null
    ): Admitted {
        val h = LedgerHarness()
        val key = h.prepare()
        val (_, submit) = h.token1(key)
        h.reply(submit, h.receipt(key, status, generation, outcome))
        return Admitted(h, key)
    }

    @Test fun everyCapturedReceiptShapeSettlesItsOwnTurnWithItsRealStatusAndOutcome() {
        val expected = mapOf(
            // status / outcome / generation as captured from the accepted server
            Triple(ReceiptStatus.TERMINAL, "completed", 1L) to TurnStatus.FINISHED_COMPLETED,
            Triple(ReceiptStatus.STARTED, null, 1L) to TurnStatus.RUNNING,
            Triple(ReceiptStatus.QUEUED, null, null) to TurnStatus.QUEUED,
            Triple(ReceiptStatus.STARTED, null, 2L) to TurnStatus.RUNNING,
            Triple(ReceiptStatus.TERMINAL, "cancelled", null) to TurnStatus.FINISHED_OTHER
        )
        val seen = mutableSetOf<Triple<ReceiptStatus, String?, Long?>>()
        assertEquals(8, LedgerHarness.capturedReceipts.size)
        for (captured in LedgerHarness.capturedReceipts) {
            val h = LedgerHarness()
            val key = h.prepare()
            val (_, submit) = h.token1(key)
            // The captured shape is kept; only the identity is the turn's own (a receipt proves only its own turn).
            val receipt = captured.copy(
                requestId = key.requestId,
                conversationId = key.conversation.conversationId
            )
            val triple = Triple(receipt.status, receipt.outcome, receipt.executionGeneration)
            h.reply(submit, receipt)
            assertEquals(triple.toString(), expected.getValue(triple), h.entry(key).status)
            assertEquals(receipt.admissionId, h.entry(key).facts!!.admissionId)
            seen.add(triple)
        }
        assertEquals(expected.keys, seen)
    }

    @Test fun aQueuedTurnHasANullGenerationAndStartsLaterUnderARealOne() {
        val a = admitted(ReceiptStatus.QUEUED, null)
        assertNull(a.entry.facts!!.executionGeneration)
        assertEquals(TurnStatus.QUEUED, a.entry.status)
        a.refresh(a.receipt(ReceiptStatus.STARTED, generation = 1))
        assertEquals(TurnStatus.RUNNING, a.entry.status)
        a.refresh(a.receipt(ReceiptStatus.TERMINAL, 1, "completed"))
        assertEquals(TurnStatus.FINISHED_COMPLETED, a.entry.status)
        assertEquals(TurnPhase.TERMINAL, a.entry.phase)
    }

    @Test fun everyTerminalOutcomeMapsToItsHonestStatusAndOnlyCompletedIsSuccess() {
        val expected = mapOf(
            "completed" to TurnStatus.FINISHED_COMPLETED,
            "cancelled" to TurnStatus.FINISHED_OTHER,
            "interrupted" to TurnStatus.FINISHED_OTHER,
            "steered" to TurnStatus.FINISHED_OTHER,
            "failed" to TurnStatus.FAILED,
            "rejected" to TurnStatus.FAILED,
            "some_future_outcome" to TurnStatus.TERMINAL_UNKNOWN_OUTCOME
        )
        for ((outcome, status) in expected) {
            val a = admitted(ReceiptStatus.TERMINAL, 1, outcome)
            assertEquals(outcome, status, a.entry.status)
            assertEquals(
                outcome,
                outcome == "completed",
                a.entry.status == TurnStatus.FINISHED_COMPLETED
            )
        }
        // A terminal receipt that names no outcome at all is equally not a success.
        assertEquals(
            TurnStatus.TERMINAL_UNKNOWN_OUTCOME,
            admitted(ReceiptStatus.TERMINAL, 1, null).entry.status
        )
    }

    @Test fun unknownStaysUnknownNeverSuccessAndOnlyProofReplacesIt() {
        val a = admitted(ReceiptStatus.UNKNOWN)
        assertEquals(TurnStatus.UNKNOWN_ADMITTED, a.entry.status)
        assertEquals(TurnPhase.ADMITTED_UNKNOWN, a.entry.phase)
        assertNull(a.entry.closure)
        a.refresh(a.receipt(ReceiptStatus.UNKNOWN)) // still unknown
        assertEquals(TurnStatus.UNKNOWN_ADMITTED, a.entry.status)
        a.refresh(a.receipt(ReceiptStatus.STARTED, generation = 1))
        assertEquals(TurnStatus.RUNNING, a.entry.status)
        // An "unknown" answer after proof does not erase the proof.
        a.refresh(a.receipt(ReceiptStatus.UNKNOWN))
        assertEquals(TurnStatus.RUNNING, a.entry.status)
        assertEquals(0, a.entry.anomalies)
    }

    @Test fun anAnswerForAnotherRequestOrConversationSettlesNothingAndIsASurfacedAnomaly() {
        val h = LedgerHarness()
        val key = h.prepare("mine")
        val other = h.prepare("someone else's")
        val (_, submit) = h.token1(key)
        val foreign = h.receipt(other, ReceiptStatus.TERMINAL, 1, "completed")
        val outcome = h.reply(submit, foreign) as ReplyOutcome.Anomalous
        assertEquals(AnomalyKind.SCOPE_MISMATCH, outcome.kind)
        assertEquals(TurnPhase.UNCERTAIN, outcome.phase)
        assertNull(h.entry(key).facts)
        assertEquals(UncertainReason.LINK_FAILURE, h.entry(key).uncertainty)
        assertEquals(1, h.entry(key).anomalies)
        assertTrue(h.effects.contains(TurnEffect.SurfaceAnomaly(key, AnomalyKind.SCOPE_MISMATCH)))
        assertNull(h.entry(other).facts)
        // The same request id under another conversation is equally foreign.
        val h2 = LedgerHarness()
        val k2 = h2.prepare()
        val (_, s2) = h2.token1(k2)
        val wrongConversation = Receipt(
            k2.requestId,
            AdmissionId.of(LedgerHarness.ADMISSION)!!,
            StateTestSupport.conversationId("11111111-2222-4333-8444-555555555555"),
            ReceiptStatus.QUEUED,
            null,
            1,
            null
        )
        h2.reply(s2, wrongConversation)
        assertNull(h2.entry(k2).facts)
        assertEquals(UncertainReason.LINK_FAILURE, h2.entry(k2).uncertainty)
    }

    private fun anomalyCase(
        label: String,
        start: Triple<ReceiptStatus, Long?, String?>,
        next: Receipt.() -> Receipt,
        kind: AnomalyKind
    ) {
        val a = admitted(start.first, start.second, start.third)
        val before = a.entry.facts
        val result = a.refresh(a.receipt(ReceiptStatus.QUEUED).next())
        assertTrue(label, result is ReplyOutcome.Anomalous)
        assertEquals(label, kind, (result as ReplyOutcome.Anomalous).kind)
        assertEquals("$label: the known state is kept", before, a.entry.facts)
        assertEquals(label, 1, a.entry.anomalies)
        assertTrue(label, a.h.effects.contains(TurnEffect.SurfaceAnomaly(a.key, kind)))
        assertEquals(label, 1, a.h.ledger.size)
    }

    @Test fun contradictoryEvidenceNeverReplacesWhatIsKnown() {
        val other = AdmissionId.of(LedgerHarness.OTHER_ADMISSION)!!
        anomalyCase(
            "another admission",
            Triple(ReceiptStatus.STARTED, 1L, null),
            { copy(admissionId = other, status = ReceiptStatus.STARTED, executionGeneration = 1) },
            AnomalyKind.ADMISSION_MISMATCH
        )
        anomalyCase(
            "another authority epoch",
            Triple(ReceiptStatus.STARTED, 1L, null),
            { copy(status = ReceiptStatus.STARTED, executionGeneration = 1, authorityEpoch = 2) },
            AnomalyKind.AUTHORITY_EPOCH_MISMATCH
        )
        anomalyCase(
            "queued after started",
            Triple(ReceiptStatus.STARTED, 2L, null),
            { copy(status = ReceiptStatus.QUEUED, executionGeneration = null) },
            AnomalyKind.STATUS_REGRESSION
        )
        anomalyCase(
            "an older generation",
            Triple(ReceiptStatus.STARTED, 2L, null),
            { copy(status = ReceiptStatus.STARTED, executionGeneration = 1) },
            AnomalyKind.GENERATION_REGRESSION
        )
        anomalyCase(
            "a vanished generation",
            Triple(ReceiptStatus.STARTED, 2L, null),
            {
                copy(
                    status = ReceiptStatus.TERMINAL,
                    executionGeneration = null,
                    outcome = "completed"
                )
            },
            AnomalyKind.GENERATION_REGRESSION
        )
    }

    private fun facts(
        status: ReceiptStatus,
        generation: Long? = null,
        outcome: String? = null,
        admission: String = LedgerHarness.ADMISSION,
        epoch: Long = 1
    ) = AdmissionFacts(AdmissionId.of(admission)!!, epoch, status, generation, outcome)

    private fun conflict(current: AdmissionFacts, incoming: AdmissionFacts): AnomalyKind =
        (TurnLedger.merge(current, incoming) as TurnLedger.Merge.Conflict).kind

    @Test fun aSettledTurnIsFinalSoLaterEvidenceCanOnlyConfirmOrConflict() {
        // A terminal turn is never looked up again, so its conflict rules are exercised on the merge itself.
        val done = facts(ReceiptStatus.TERMINAL, 1, "completed")
        assertEquals(TurnLedger.Merge.Unchanged, TurnLedger.merge(done, done))
        assertEquals(AnomalyKind.STATUS_REGRESSION, conflict(done, facts(ReceiptStatus.STARTED, 1)))
        assertEquals(AnomalyKind.STATUS_REGRESSION, conflict(done, facts(ReceiptStatus.QUEUED)))
        assertEquals(
            AnomalyKind.OUTCOME_CONFLICT,
            conflict(done, facts(ReceiptStatus.TERMINAL, 1, "failed"))
        )
        assertEquals(
            AnomalyKind.OUTCOME_CONFLICT,
            conflict(done, facts(ReceiptStatus.TERMINAL, 1, null))
        )
        assertEquals(
            AnomalyKind.GENERATION_REGRESSION,
            conflict(done, facts(ReceiptStatus.TERMINAL, 2, "completed"))
        )
        assertEquals(
            AnomalyKind.GENERATION_REGRESSION,
            conflict(done, facts(ReceiptStatus.TERMINAL, 0, "completed"))
        )
        assertEquals(
            AnomalyKind.ADMISSION_MISMATCH,
            conflict(
                done,
                facts(ReceiptStatus.TERMINAL, 1, "completed", LedgerHarness.OTHER_ADMISSION)
            )
        )
        assertEquals(
            AnomalyKind.AUTHORITY_EPOCH_MISMATCH,
            conflict(done, facts(ReceiptStatus.TERMINAL, 1, "completed", epoch = 3))
        )
        // Unknown never overrides a final answer; a queued turn cancelled before it ran stays consistent.
        assertEquals(
            TurnLedger.Merge.Unchanged,
            TurnLedger.merge(done, facts(ReceiptStatus.UNKNOWN))
        )
        val cancelled = facts(ReceiptStatus.TERMINAL, null, "cancelled")
        assertEquals(TurnLedger.Merge.Unchanged, TurnLedger.merge(cancelled, cancelled))
        // Review C1: a STARTED projection of a hidden steering transfer, requeued and then cancelled (terminal
        // `cancelled`, generation NULL, same admission and epoch), settles and remembers the start it replaced.
        val started = facts(ReceiptStatus.STARTED, 2)
        val settled = (TurnLedger.merge(started, cancelled) as TurnLedger.Merge.Changed).facts
        assertEquals(ReceiptStatus.TERMINAL, settled.status)
        assertEquals(null, settled.executionGeneration)
        assertEquals(2L, settled.priorStartedGeneration)
        // Anything else that loses or lowers a generation stays a conflict, also after a settlement.
        for (shape in listOf("completed", "failed", "interrupted", "steered", "rejected")) {
            assertEquals(
                shape,
                AnomalyKind.GENERATION_REGRESSION,
                conflict(started, facts(ReceiptStatus.TERMINAL, null, shape))
            )
        }
        assertEquals(
            AnomalyKind.GENERATION_REGRESSION,
            conflict(facts(ReceiptStatus.TERMINAL, 2, "completed"), cancelled)
        )
        assertEquals(TurnLedger.Merge.Unchanged, TurnLedger.merge(settled, cancelled))
        assertEquals(AnomalyKind.STATUS_REGRESSION, conflict(settled, started))
    }

    @Test fun aRestartedRunUnderAHigherGenerationAdvancesAndIdenticalEvidenceIsQuiet() {
        val a = admitted(ReceiptStatus.STARTED, 1)
        a.refresh(a.receipt(ReceiptStatus.STARTED, generation = 1)) // identical: nothing changes
        assertEquals(1L, a.entry.facts!!.executionGeneration)
        a.refresh(a.receipt(ReceiptStatus.STARTED, generation = 2))
        assertEquals(2L, a.entry.facts!!.executionGeneration)
        assertEquals(0, a.entry.anomalies)
        assertTrue(a.h.effects.none { it is TurnEffect.SurfaceAnomaly })
    }

    @Test fun receiptsWhoseStatusOutcomeAndGenerationCannotGoTogetherAreNotAccepted() {
        val bad = listOf(
            ReceiptStatus.QUEUED to (null to 1L), // a queued turn has no generation
            ReceiptStatus.QUEUED to ("completed" to null), // and no outcome
            // a started turn has a generation of at least 1
            ReceiptStatus.STARTED to (null to null),
            ReceiptStatus.STARTED to (null to 0L),
            ReceiptStatus.STARTED to ("completed" to 1L) // and no outcome yet
        )
        for ((status, shape) in bad) {
            val h = LedgerHarness()
            val key = h.prepare()
            val (_, submit) = h.token1(key)
            h.reply(submit, h.receipt(key, status, shape.second, shape.first))
            assertNull("$status $shape", h.entry(key).facts)
            assertEquals("$status $shape", UncertainReason.LINK_FAILURE, h.entry(key).uncertainty)
            assertTrue(
                h.effects.contains(TurnEffect.SurfaceAnomaly(key, AnomalyKind.INCONSISTENT_RECEIPT))
            )
        }
    }

    @Test fun anAbsentAnswerForAnAdmittedTurnIsAnAnomalyNotARegression() {
        for (code in listOf("not_found", "expired")) {
            val a = admitted(ReceiptStatus.STARTED, 1)
            val dispatch = a.h.planned(a.h.lookup(a.key, a.h.authority.current(a.h.enrollment)!!))
            val result = a.h.fail(dispatch, code)
            assertEquals(
                code,
                AnomalyKind.ABSENT_AFTER_ADMISSION,
                (result as ReplyOutcome.Anomalous).kind
            )
            assertEquals(code, TurnStatus.RUNNING, a.entry.status)
            assertNull(code, a.entry.closure)
        }
    }

    @Test fun equalTextualIdsInIndependentScopesStayIndependent() {
        val h = LedgerHarness()
        val phone = h.enrollment
        val watch = EnrollmentScope(StateTestSupport.scope, "watch-key-1", 0)
        val otherEndpoint = EnrollmentScope(
            RelayScope("endpoint-b", StateTestSupport.scope.profileId),
            "phone-key-1",
            0
        )
        val sameEntropy = StateTestSupport.entropy(5)
        val keys = listOf(phone, watch, otherEndpoint).map { scope ->
            val draft =
                TurnDraft(ConversationScope(scope, h.conversation.conversationId), "same words")
            (h.apply(h.ledger.prepare(draft, h.now(), sameEntropy)) as PrepareOutcome.Prepared).key
        }
        // Identical request id, identical conversation id, identical text: three independent turns.
        assertEquals(1, keys.map { it.requestId }.toSet().size)
        assertEquals(1, keys.map { it.conversation.conversationId }.toSet().size)
        assertEquals(3, h.ledger.size)
        assertEquals(3, keys.toSet().size)
        val tokens = keys.map { key ->
            val step = h.authority.attach(key.conversation.enrollment, StateTestSupport.attached(0))
            h.authority = step.authority
            step.token
        }
        val dispatches = keys.zip(tokens).map { (key, token) ->
            h.planned(
                h.apply(h.ledger.planSubmit(key, h.authority, token, h.now(), h.correlation()))
            )
        }
        // Admitting the first scope's turn changes only that turn: the others see nothing of it.
        h.reply(dispatches[0], h.receipt(keys[0], ReceiptStatus.STARTED, generation = 1))
        assertEquals(TurnPhase.STARTED, h.phase(keys[0]))
        assertEquals(TurnPhase.SUBMITTING, h.phase(keys[1]))
        assertEquals(TurnPhase.SUBMITTING, h.phase(keys[2]))
        h.reply(dispatches[1], h.receipt(keys[1], ReceiptStatus.QUEUED))
        assertEquals(TurnPhase.QUEUED, h.phase(keys[1]))
        assertEquals(TurnPhase.STARTED, h.phase(keys[0]))
        assertEquals(TurnPhase.SUBMITTING, h.phase(keys[2]))
        // Permission loss of one scope does not stop the others.
        h.fail(dispatches[2], "permission_denied")
        assertEquals(ScopeStatus.STOPPED, h.ledger.scopeStatus(otherEndpoint))
        assertEquals(ScopeStatus.ACTIVE, h.ledger.scopeStatus(phone))
        assertEquals(ScopeStatus.ACTIVE, h.ledger.scopeStatus(watch))
        assertEquals(1, h.ledger.entriesFor(otherEndpoint).size)
    }

    @Test fun theLedgerOffersNoWayToSendSpeakAnswerCancelOrRespond() {
        val forbidden =
            listOf("send", "speak", "answer", "respond", "cancel", "execute", "play", "auto")
        val names = TurnLedger::class.java.methods.map { it.name.lowercase() } +
            TurnEffect::class.java.classes.map { it.simpleName.lowercase() }
        for (word in forbidden) {
            assertTrue(
                "$word in $names",
                names.none {
                    it.contains(word) &&
                        !it.contains("persistbeforesend")
                }
            )
        }
        // Sending is only ever the product of a plan; the only methods returning a plan are the two named ones.
        val planners = TurnLedger::class.java.methods
            .filter { it.name.startsWith("plan") }.map { it.name.substringBefore('-') }.toSet()
        assertEquals(setOf("planSubmit", "planLookup"), planners)
    }

    @Test fun nothingPrintsTurnTextEndpointOrEnrollmentLabels() {
        val h = LedgerHarness()
        val key = h.prepare("my secret words")
        val (_, submit) = h.token1(key)
        val printed = listOf(
            h.entry(key).toString(),
            key.toString(),
            h.ledger.toString(),
            submit.toString(),
            submit.ref.toString(),
            h.entry(key).fingerprint.toString(),
            TurnDraft(h.conversation, "my secret words").toString(),
            h.enrollment.toString(),
            h.conversation.toString(),
            h.ledger.recordedScopes.toString()
        ).joinToString("\n")
        for (secret in listOf("secret", "endpoint-a", "phone-key-1")) {
            assertFalse(secret, printed.contains(secret))
        }
        assertNotEquals(h.entry(key).fingerprint.hex.take(12), "")
        assertEquals(64, h.entry(key).fingerprint.hex.length)
    }
}
