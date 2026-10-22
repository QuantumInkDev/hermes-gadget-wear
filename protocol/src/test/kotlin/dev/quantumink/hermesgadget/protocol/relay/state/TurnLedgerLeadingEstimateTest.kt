package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `not_found` receipt is never proof of non-admission, whatever the LOCAL estimate says about the deadline.
 *
 * The inputs are CONSTRUCTED pure inputs, not recorded server facts. They model the source-derived legal case of the
 * parent ruling: the client's estimate is `S + elapsed` while the server's own wall clock stepped back after the
 * sample, so the estimate LEADS the server; the server then answers a not-yet-admitted intent with `not_found`
 * (receipt answers `expired` only when ITS clock has passed the expiry) and the intent can still pass admission.
 * The estimate is good enough to refuse or propose a request; it is not evidence of what the server holds.
 */
class TurnLedgerLeadingEstimateTest {
    private val ttl = 60_000L

    /** A turn prepared with a 60 s deadline whose submit timed out, observed [afterMs] later by the local estimate. */
    private fun uncertain(afterMs: Long): Pair<LedgerHarness, TurnKey> {
        val h = LedgerHarness()
        val key = h.prepare("do exactly this", ttlMs = ttl)
        h.timeout(h.token1(key).second)
        h.advance(afterMs)
        return h to key
    }

    private fun absent(h: LedgerHarness, key: TurnKey) {
        val outcome = h.fail(h.planned(h.lookup(key, h.attach())), "not_found")
        assertTrue(outcome is ReplyOutcome.Applied)
    }

    @Test fun notFoundAtOrAfterTheEstimatedDeadlineClosesNothingAndKeepsTheImmutableTurn() {
        for (afterMs in listOf(ttl, ttl + 1, 61_000L, 3_600_000L)) {
            val (h, key) = uncertain(afterMs)
            val before = h.entry(key)
            absent(h, key)
            val after = h.entry(key)
            val label = "estimate at +$afterMs"
            assertNull(label, after.closure)
            assertEquals(label, TurnStatus.UNCERTAIN, after.status)
            assertTrue(label, after.mayHaveReachedServer)
            // The local estimate only refuses a resend; it proves nothing about the server.
            assertTrue(label, after.deadlinePassed(h.now()))
            assertEquals(label, before.key, after.key)
            assertEquals(label, before.fingerprint, after.fingerprint)
            assertEquals(label, before.expiresAtMs, after.expiresAtMs)
            assertEquals(label, before.text, after.text)
            // The estimate refuses a resubmit (and never renews the original deadline) but mints nothing.
            val resend = h.submit(after.key, h.attach())
            assertEquals(label, PlanRefusal.DEADLINE_PASSED, h.refusal(resend))
            assertEquals(label, 1, h.entropy.calls)
            assertEquals(label, 1, h.ledger.size)
            assertEquals(label, listOf("relay.turn.submit", "relay.turn.receipt"), h.dispatched)
        }
    }

    @Test fun aLegitimateReceiptAfterAnAbsentObservationStillSettlesExactlyThisTurn() {
        val (h, key) = uncertain(61_000)
        absent(h, key)
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        // The server did admit it after all (its clock was behind): a later lookup finds the receipt.
        val token = h.attach()
        h.reply(
            h.planned(h.lookup(key, token)),
            h.receipt(key, ReceiptStatus.STARTED, generation = 1)
        )
        assertEquals(TurnPhase.STARTED, h.phase(key))
        h.reply(
            h.planned(h.lookup(key, token)),
            h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed")
        )
        assertEquals(TurnStatus.FINISHED_COMPLETED, h.entry(key).status)
        assertEquals(h.entry(key).expiresAtMs, h.entry(key).preparedAtMs + ttl)
        assertEquals(1, h.entropy.calls)
    }

    @Test fun anAbsentObservationSurvivesARestoreAndAFreshAttachStillReconcilesTheReceipt() {
        val (h, key) = uncertain(61_000)
        absent(h, key)
        val saved = StateTestSupport.builtSnapshot(h.ledger, emptyList(), emptyList())
        val state = (
            RelayStateRestore.restore(
                saved,
                h.now(),
                RelayAuthority.fresh()
            ) as RestoreResult.Restored
            ).value
        val restored = state.ledger.entry(key)!!
        assertNull(restored.closure)
        assertEquals(TurnStatus.UNCERTAIN, restored.status)
        assertEquals(h.entry(key).fingerprint, restored.fingerprint)
        assertEquals(h.entry(key).expiresAtMs, restored.expiresAtMs)
        var authority = RelayAuthority.fresh()
        val step = authority.attach(h.enrollment, StateTestSupport.attached(0))
        authority = step.authority
        val now = h.now()
        val planned = state.ledger.planLookup(key, authority, step.token, now, h.correlation())
        val dispatch = (planned.result as PlanOutcome.Planned).dispatch
        val receipt = h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed")
        val settled = planned.ledger.onReply(dispatch.ref, RelayOutcome.Success(receipt))
        assertEquals(TurnStatus.FINISHED_COMPLETED, settled.ledger.entry(key)!!.status)
        // Receipt first still holds after the restore: no resubmit out of uncertainty.
        val resend = state.ledger.planSubmit(key, authority, step.token, now, h.correlation())
        assertTrue(
            (resend.result as PlanOutcome.Refused).reason in
                setOf(PlanRefusal.LOOKUP_REQUIRED_FIRST, PlanRefusal.DEADLINE_PASSED)
        )
    }

    @Test fun aLaterExpiredAnswerAfterEarlierAbsenceIsAlsoOnlyAnObservation() {
        val (h, key) = uncertain(61_000)
        absent(h, key)
        absent(h, key)
        assertNull(h.entry(key).closure)
        h.fail(h.planned(h.lookup(key, h.attach())), "expired")
        // Review C2 supersedes the earlier assumption that `expired` closes: the turn stays unresolved
        assertNull(h.entry(key).closure)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertEquals(TurnStatus.UNCERTAIN, h.entry(key).status)
    }

    @Test fun anAbsentObservationBeforeTheDeadlineKeepsTheExistingSameIdPreExpiryRetry() {
        val (h, key) = uncertain(10_000)
        val original = h.entry(key)
        absent(h, key)
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        val resend = h.planned(h.submit(key, h.attach()))
        assertEquals(key.requestId, resend.key.requestId)
        assertEquals(original.expiresAtMs, h.entry(key).expiresAtMs)
        assertEquals(1, h.entropy.calls)
    }

    @Test fun aKnownAdmissionNeverRegressesToAbsenceAfterTheEstimatedDeadline() {
        val h = LedgerHarness()
        val key = h.prepare(ttlMs = ttl)
        val (_, submit) = h.token1(key)
        h.reply(submit, h.receipt(key, ReceiptStatus.STARTED, generation = 1))
        h.advance(ttl + 5_000)
        val outcome = h.fail(h.planned(h.lookup(key, h.attach())), "not_found")
        assertEquals(AnomalyKind.ABSENT_AFTER_ADMISSION, (outcome as ReplyOutcome.Anomalous).kind)
        assertEquals(TurnStatus.RUNNING, h.entry(key).status)
        assertNull(h.entry(key).closure)
    }

    @Test fun absenceNeverTriggersAnAutomaticLookupResubmitOrNewTurn() {
        val (h, key) = uncertain(120_000)
        val calls = h.dispatched.size
        absent(h, key)
        // One explicit lookup was planned; absence itself plans, sends and mints nothing further.
        assertEquals(calls + 1, h.dispatched.size)
        assertFalse(h.ledger.entry(key)!!.hasOutstandingCall())
        assertEquals(1, h.entropy.calls)
        assertEquals(1, h.ledger.size)
    }

    @Test fun aSnapshotCarryingTheRemovedLocalExpiryProofIsRefusedNotRestoredAsClosed() {
        val (h, key) = uncertain(61_000)
        val turn = h.ledger.snapshot().turns.single()
        val forged = turn.copy(closure = "not_admitted:absent_after_deadline")
        val result = RelayStateRestore.restore(
            RelayStateSnapshot(
                1,
                TurnLedgerSnapshot(listOf(forged), emptyList()),
                emptyList(),
                emptyList()
            ),
            h.now(),
            RelayAuthority.fresh()
        )
        val refused = result as RestoreResult.Refused
        assertEquals(RestoreRefusal.INVALID_FIELD, refused.reason)
        assertEquals("ledger.turns[0].closure", refused.path)
        assertEquals(key.requestId.value, turn.requestId)
    }

    private fun TurnEntry.hasOutstandingCall(): Boolean =
        phase == TurnPhase.SUBMITTING || phase == TurnPhase.LOOKING_UP
}
