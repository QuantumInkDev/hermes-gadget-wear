package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C2, which SUPERSEDES the earlier assumption that any server `expired` is irrevocable non-admission:
 * the shared `expired` code also answers a NEW intent whose UUIDv7 timestamp or deadline lies too far in the FUTURE of
 * the server clock (`relay_store.py` 760-779 `_intent`, 780-810 `reserve_turn`), which may become admissible later, and
 * the receipt path reads a raw wall-clock sample (`relay_conversations.py` 90, 667-690) rather than the persistent
 * high-water clock of new admissions (`relay_store.py` 698-708, 983-1004). The code alone never proves that THIS
 * immutable request can never be admitted. An `expired` answer is therefore an observation; the turn stays
 * unresolved and reconcilable with its exact id, text, deadline and fingerprint, and the receipt-first order holds.
 *
 * Every scenario is a CONSTRUCTED pure input derived from that source, not captured traffic.
 */
class TurnLedgerExpiredTest {
    private fun ReplyOutcome.isApplied() = this is ReplyOutcome.Applied

    private fun sentThenExpired(): Triple<LedgerHarness, TurnKey, BindingToken> {
        val h = LedgerHarness()
        val key = h.prepare("a possibly sent intent")
        val (token, dispatch) = h.token1(key)
        assertTrue(h.fail(dispatch, "expired").isApplied())
        return Triple(h, key, token)
    }

    @Test fun aSubmitAnsweredExpiredIsAnObservationThatClosesNothingAndKeepsTheImmutableTurn() {
        val (h, key, _) = sentThenExpired()
        val before = h.entry(key)
        assertNull(before.closure)
        assertNotEquals("NOT_ADMITTED", before.phase.name)
        assertNotEquals("NOT_ADMITTED", before.status.name)
        assertEquals(TurnPhase.UNCERTAIN, before.phase)
        assertTrue(before.mayHaveReachedServer)
        assertEquals("a possibly sent intent", before.text)
        assertEquals(
            h.ledger.reconcileIntent(key, "a possibly sent intent", before.expiresAtMs),
            IntentMatch.SAME
        )
        assertEquals(1, h.entropy.calls)
        assertEquals(1, h.ledger.size)
        assertEquals(listOf("relay.turn.submit"), h.dispatched)
    }

    @Test fun anExpiredSubmitKeepsReceiptFirstAndNeverResubmitsOrLooksUpByItself() {
        val (h, key, token) = sentThenExpired()
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, token)))
        // Planning a lookup on the SAME binding is not allowed either: a fresh attach comes first.
        assertEquals(PlanRefusal.FRESH_ATTACH_REQUIRED, h.refusal(h.lookup(key, token)))
        assertEquals(listOf("relay.turn.submit"), h.dispatched)
        assertEquals(1, h.entropy.calls)
    }

    @Test fun aTooFutureIntentThatTheServerLaterAcceptsIsNotLockedOutByTheEarlierExpired() {
        // Constructed: the server clock was behind this turn's stamp/deadline, so the submit was answered `expired`;
        // later (fresh attach) the lookup finds nothing and the SAME id may be submitted again before its deadline.
        val (h, key, _) = sentThenExpired()
        val fresh = h.attach()
        val absent = h.fail(h.planned(h.lookup(key, fresh)), "not_found")
        assertTrue(absent.isApplied())
        assertEquals(TurnPhase.ABSENT_SO_FAR, h.phase(key))
        val again = h.planned(h.submit(key, fresh))
        assertEquals(key, again.key)
        assertEquals(1, h.entropy.calls)
        val admitted = h.reply(again, h.receipt(key, ReceiptStatus.QUEUED))
        assertTrue(admitted.isApplied())
        assertEquals(TurnPhase.QUEUED, h.phase(key))
    }

    @Test fun aLookupAnsweredExpiredIsAlsoAnObservationAndALaterReceiptStillSettlesTheTurn() {
        val h = LedgerHarness()
        val key = h.prepare("lookup says expired")
        val (_, dispatch) = h.token1(key)
        h.timeout(dispatch)
        val first = h.attach()
        assertTrue(h.fail(h.planned(h.lookup(key, first)), "expired").isApplied())
        assertNull(h.entry(key).closure)
        assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, h.refusal(h.submit(key, first)))
        val second = h.attach()
        val started = h.reply(
            h.planned(h.lookup(key, second)),
            h.receipt(key, ReceiptStatus.STARTED, 1)
        )
        assertTrue(started.isApplied())
        assertEquals(TurnPhase.STARTED, h.phase(key))
        val third = h.attach()
        h.reply(
            h.planned(h.lookup(key, third)),
            h.receipt(key, ReceiptStatus.TERMINAL, 1, "completed")
        )
        assertEquals(TurnStatus.FINISHED_COMPLETED, h.entry(key).status)
    }

    @Test fun anExpiredObservationSurvivesARestoreAndAFreshAttachStillReconcilesTheReceipt() {
        val (h, key, _) = sentThenExpired()
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
        val saved = restored.entry(key)!!
        assertNull(saved.closure)
        assertEquals(TurnPhase.UNCERTAIN, saved.phase)
        assertEquals("a possibly sent intent", saved.text)
        assertEquals(h.entry(key).fingerprint, saved.fingerprint)
        val fresh = LedgerHarness()
        fresh.ledger = restored
        val token = fresh.attach()
        assertEquals(PlanRefusal.LOOKUP_REQUIRED_FIRST, fresh.refusal(fresh.submit(key, token)))
        val settled = fresh.reply(
            fresh.planned(fresh.lookup(key, token)),
            fresh.receipt(key, ReceiptStatus.TERMINAL, 1, "completed")
        )
        assertTrue(settled.isApplied())
        assertEquals(TurnStatus.FINISHED_COMPLETED, fresh.entry(key).status)
    }

    @Test fun anExpiredAnswerNeverRemovesWhatAnEarlierReceiptProved() {
        val h = LedgerHarness()
        val key = h.prepare("already admitted")
        val (token, dispatch) = h.token1(key)
        h.reply(dispatch, h.receipt(key, ReceiptStatus.STARTED, 3))
        val outcome = h.fail(h.planned(h.lookup(key, token)), "expired")
        assertTrue("$outcome", outcome is ReplyOutcome.Anomalous)
        assertEquals(TurnPhase.STARTED, h.phase(key))
        assertEquals(3L, h.entry(key).facts!!.executionGeneration)
        assertNull(h.entry(key).closure)
    }

    @Test fun noRepeatedExpiredAnswerEverClosesTheTurn() {
        val (h, key, _) = sentThenExpired()
        repeat(5) {
            val token = h.attach()
            assertTrue(h.fail(h.planned(h.lookup(key, token)), "expired").isApplied())
            assertNull(h.entry(key).closure)
            assertEquals(TurnPhase.UNCERTAIN, h.phase(key))
        }
        assertEquals(1, h.entropy.calls)
        assertEquals(1, h.ledger.size)
    }

    @Test fun aSnapshotCarryingAnObsoleteNotAdmittedProofIsRefusedNotRestoredAsClosed() {
        val (h, key, _) = sentThenExpired()
        val turn = h.ledger.snapshot().turns.single()
        for (proof in listOf("server_expired", "absent_after_deadline", "anything")) {
            val forged = turn.copy(closure = "not_admitted:$proof")
            val snapshot = RelayStateSnapshot(
                RelayStateSnapshot.VERSION,
                TurnLedgerSnapshot(listOf(forged), emptyList()),
                emptyList(),
                emptyList()
            )
            val result = RelayStateRestore.restore(snapshot, h.now(), RelayAuthority.fresh())
            val refused = result as? RestoreResult.Refused
            assertTrue("$proof: $result", refused != null)
            assertEquals(proof, RestoreRefusal.INVALID_FIELD, refused!!.reason)
            assertEquals(proof, "ledger.turns[0].closure", refused.path)
        }
        assertEquals(key, h.ledger.entries.single().key)
    }

    @Test fun aSavedExpiredErrorCodeRestoresAnUnresolvedTurnNotAClosedOne() {
        val (h, key, _) = sentThenExpired()
        val turn = h.ledger.snapshot().turns.single().copy(lastErrorCode = "expired")
        val snapshot = RelayStateSnapshot(
            RelayStateSnapshot.VERSION,
            TurnLedgerSnapshot(listOf(turn), emptyList()),
            emptyList(),
            emptyList()
        )
        val restored = (
            RelayStateRestore.restore(snapshot, h.now(), RelayAuthority.fresh())
                as RestoreResult.Restored
            ).value.ledger
        assertNull(restored.entry(key)!!.closure)
        assertEquals(TurnPhase.UNCERTAIN, restored.entry(key)!!.phase)
    }
}
