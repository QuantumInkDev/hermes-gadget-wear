package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C1 (confirmed from the first review's cannot-verify item): a started claim that receives a hidden steering
 * transfer is projected publicly as STARTED(generation); an undelivered transfer is requeued (`queued`, generation
 * NULL) and the queued admission can then be cancelled: terminal `cancelled` with a NULL generation, same admission,
 * same authority epoch.
 *
 * Source (core runtime `7c17e6d`, read-only): `hermes_state_runtime.py` 194-223 (claim), 304-324 (steer a queued
 * admission to terminal/steering under the claim's generation), 297-301 and 327-341 (requeue an undelivered transfer to
 * queued/generation NULL), 254-280 (cancelling a queued admission adds no generation); `gateway/session_authority.py`
 * 489-494 (`steering` is projected as STARTED, hiding the transfer); SDK `relay_core.py` 730-733 and
 * `relay_store.py` 1128-1183 (the generation is passed through and this shape is not filtered).
 *
 * Every receipt below is a CONSTRUCTED pure input derived from that source, not captured traffic.
 */
class TurnLedgerRequeueTerminalTest {
    private fun started(generation: Long = 2): Triple<LedgerHarness, TurnKey, BindingToken> {
        val h = LedgerHarness()
        val key = h.prepare("steered and cancelled")
        val (token, dispatch) = h.token1(key)
        h.reply(dispatch, h.receipt(key, ReceiptStatus.STARTED, generation))
        assertEquals(TurnPhase.STARTED, h.phase(key))
        return Triple(h, key, token)
    }

    private fun cancelledNull(h: LedgerHarness, key: TurnKey, epoch: Long = 1) =
        h.receipt(key, ReceiptStatus.TERMINAL, null, "cancelled", LedgerHarness.ADMISSION, epoch)

    private fun lookupThen(h: LedgerHarness, key: TurnKey, token: BindingToken, receipt: Receipt) =
        h.reply(h.planned(h.lookup(key, token)), receipt)

    @Test fun aTerminalNullGenerationCancelAfterAHiddenSteeringStartSettlesTheExactAdmission() {
        val (h, key, token) = started()
        val outcome = lookupThen(h, key, token, cancelledNull(h, key))
        assertTrue("$outcome", outcome is ReplyOutcome.Applied)
        assertEquals(TurnPhase.TERMINAL, h.phase(key))
        assertEquals(TurnStatus.FINISHED_OTHER, h.entry(key).status)
        val facts = h.entry(key).facts!!
        assertEquals(ReceiptStatus.TERMINAL, facts.status)
        assertEquals("cancelled", facts.outcome)
        assertEquals(LedgerHarness.ADMISSION, facts.admissionId.value)
        assertEquals(0, h.entry(key).anomalies)
    }

    @Test fun aQueuedObservationBetweenIsAnAnomalyThatKeepsTheStartAndTheLaterCancelStillSettles() {
        val (h, key, token) = started()
        val queued = lookupThen(h, key, token, h.receipt(key, ReceiptStatus.QUEUED))
        assertTrue("$queued", queued is ReplyOutcome.Anomalous)
        assertEquals(AnomalyKind.STATUS_REGRESSION, (queued as ReplyOutcome.Anomalous).kind)
        assertEquals(TurnPhase.STARTED, h.phase(key))
        val outcome = lookupThen(h, key, token, cancelledNull(h, key))
        assertTrue("$outcome", outcome is ReplyOutcome.Applied)
        assertEquals(TurnPhase.TERMINAL, h.phase(key))
    }

    @Test fun theSettlementSurvivesARestoreAndAFreshAttach() {
        val (h, key, _) = started()
        val snapshot = RelayStateSnapshot(
            RelayStateSnapshot.VERSION,
            h.ledger.snapshot(),
            emptyList(),
            emptyList()
        )
        val restored = (
            RelayStateRestore.restore(snapshot, h.now(), RelayAuthority.fresh())
                as RestoreResult.Restored
            ).value.ledger
        val fresh = LedgerHarness()
        fresh.ledger = restored
        val token = fresh.attach()
        val outcome = lookupThen(fresh, key, token, cancelledNull(fresh, key))
        assertTrue("$outcome", outcome is ReplyOutcome.Applied)
        assertEquals(TurnPhase.TERMINAL, fresh.phase(key))
    }

    @Test fun nullablePreStartTerminalsAndOrdinaryStartedTerminalsStillSettleAsBefore() {
        val first = LedgerHarness()
        val a = first.prepare("never started")
        val (tokenA, dispatchA) = first.token1(a)
        first.timeout(dispatchA)
        val freshA = first.attach()
        assertTrue(
            first.reply(
                first.planned(first.lookup(a, freshA)),
                cancelledNull(first, a)
            ) is ReplyOutcome.Applied
        )
        assertEquals(TurnPhase.TERMINAL, first.phase(a))
        assertTrue(tokenA.serial < freshA.serial)

        val queued = LedgerHarness()
        val b = queued.prepare("queued then cancelled")
        val (tokenB, dispatchB) = queued.token1(b)
        queued.reply(dispatchB, queued.receipt(b, ReceiptStatus.QUEUED))
        assertTrue(lookupThen(queued, b, tokenB, cancelledNull(queued, b)) is ReplyOutcome.Applied)
        assertEquals(TurnPhase.TERMINAL, queued.phase(b))

        val (h, key, token) = started(2)
        val done = lookupThen(h, key, token, h.receipt(key, ReceiptStatus.TERMINAL, 2, "completed"))
        assertTrue(done is ReplyOutcome.Applied)
        assertEquals(TurnStatus.FINISHED_COMPLETED, h.entry(key).status)
    }

    @Test fun everyOtherNullOrRegressedGenerationAfterAStartIsStillAConflictThatKeepsTheStart() {
        val cases = listOf(
            "completed, null" to ("completed" to null),
            "failed, null" to ("failed" to null),
            "interrupted, null" to ("interrupted" to null),
            "steered, null" to ("steered" to null),
            "rejected, null" to ("rejected" to null),
            "completed, generation 1" to ("completed" to 1L),
            "cancelled, generation 1" to ("cancelled" to 1L)
        )
        for ((label, shape) in cases) {
            val (h, key, token) = started(2)
            val receipt = h.receipt(key, ReceiptStatus.TERMINAL, shape.second, shape.first)
            val outcome = lookupThen(h, key, token, receipt)
            assertTrue("$label: $outcome", outcome is ReplyOutcome.Anomalous)
            assertEquals(
                label,
                AnomalyKind.GENERATION_REGRESSION,
                (outcome as ReplyOutcome.Anomalous).kind
            )
            assertEquals(label, TurnPhase.STARTED, h.phase(key))
            assertEquals(label, 2L, h.entry(key).facts!!.executionGeneration)
        }
    }

    @Test fun theExactAdmissionAuthorityAndRequestFencesStillApply() {
        val (h1, key1, token1) = started()
        val otherReceipt = h1.receipt(
            key1,
            ReceiptStatus.TERMINAL,
            null,
            "cancelled",
            LedgerHarness.OTHER_ADMISSION
        )
        val admission = lookupThen(h1, key1, token1, otherReceipt)
        assertEquals(AnomalyKind.ADMISSION_MISMATCH, (admission as ReplyOutcome.Anomalous).kind)
        assertEquals(TurnPhase.STARTED, h1.phase(key1))

        val (h2, key2, token2) = started()
        val epoch = lookupThen(h2, key2, token2, cancelledNull(h2, key2, epoch = 2))
        assertEquals(AnomalyKind.AUTHORITY_EPOCH_MISMATCH, (epoch as ReplyOutcome.Anomalous).kind)
        assertEquals(TurnPhase.STARTED, h2.phase(key2))

        val (h3, own, token3) = started()
        val foreign = h3.prepare("another turn")
        val mismatch = lookupThen(h3, own, token3, cancelledNull(h3, foreign))
        assertEquals(AnomalyKind.SCOPE_MISMATCH, (mismatch as ReplyOutcome.Anomalous).kind)
        assertEquals(TurnPhase.STARTED, h3.phase(own))
    }

    @Test fun unknownNeverCompletesAndALateReplyToAnOldCallIsIgnored() {
        val (h, key, token) = started()
        val unknown = lookupThen(h, key, token, h.receipt(key, ReceiptStatus.UNKNOWN))
        assertTrue(unknown is ReplyOutcome.Applied)
        assertEquals(TurnPhase.STARTED, h.phase(key))
        val dispatch = h.planned(h.lookup(key, token))
        h.reply(dispatch, cancelledNull(h, key))
        val late = h.reply(dispatch, h.receipt(key, ReceiptStatus.STARTED, 2))
        assertTrue("$late", late is ReplyOutcome.Ignored)
        assertEquals(TurnPhase.TERMINAL, h.phase(key))
    }
}
