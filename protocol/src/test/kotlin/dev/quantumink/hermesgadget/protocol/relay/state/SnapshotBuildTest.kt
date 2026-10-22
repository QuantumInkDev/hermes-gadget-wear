package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review I1 and C1 on the typed API: building the aggregate snapshot refuses an over-limit component instead of
 * shortening it, and the evidence of a requeued-then-cancelled admission survives a save and a restore.
 */
class SnapshotBuildTest {
    private val conversationId = StateTestSupport.attached().conversationId

    private fun enrollment(index: Int) = EnrollmentScope(StateTestSupport.scope, "key-$index", 0)

    private fun history(index: Int) =
        HistoryAssembly.begin(HistoryScope(ConversationScope(enrollment(index), conversationId)))

    private fun delivery(index: Int) = EnrollmentDelivery.begin(enrollment(index))

    private fun tooMany(build: SnapshotBuild): SnapshotBuild.TooMany =
        build as SnapshotBuild.TooMany

    private fun stopped(count: Int): TurnLedger {
        var shared = TurnLedger.empty()
        for (index in 1..count) {
            val h = LedgerHarness(enrollment(index))
            h.ledger = shared
            val key = h.prepare("turn $index")
            val token = h.attach()
            val correlation = CorrelationId.of("s-%05d".format(index))!!
            h.fail(h.planned(h.submit(key, token, correlation)), "permission_denied")
            shared = h.ledger
        }
        return shared
    }

    @Test fun theExactLimitBuildsAndRestoresWhileOneMoreIsATypedRefusalOfNothingBuilt() {
        val histories = (1..StateLimits.MAX_HISTORY_SCOPES).map { history(it) }
        val deliveries = (1..StateLimits.MAX_ENROLLMENTS).map { delivery(it) }
        val built = (
            RelayStateSnapshot.of(TurnLedger.empty(), histories, deliveries) as SnapshotBuild.Built
            ).snapshot
        assertEquals(histories.size, built.histories.size)
        assertEquals(deliveries.size, built.deliveries.size)
        val restored = RelayStateRestore.restore(
            built,
            StateTestSupport.at(),
            RelayAuthority.fresh()
        )
        assertTrue(restored is RestoreResult.Restored)

        val moreHistories = tooMany(
            RelayStateSnapshot.of(TurnLedger.empty(), histories + history(99), deliveries)
        )
        assertEquals("histories", moreHistories.path)
        assertEquals(StateLimits.MAX_HISTORY_SCOPES, moreHistories.limit)
        val moreDeliveries = tooMany(
            RelayStateSnapshot.of(TurnLedger.empty(), histories, deliveries + delivery(99))
        )
        assertEquals("deliveries", moreDeliveries.path)
        assertEquals(StateLimits.MAX_ENROLLMENTS, moreDeliveries.limit)
    }

    @Test fun aLedgerWithMoreScopeDecisionsThanTheLimitIsRefusedUntilTheConsumerClearsOne() {
        val limit = StateLimits.MAX_SCOPE_DECISIONS
        val exact = stopped(limit)
        assertTrue(RelayStateSnapshot.of(exact, emptyList(), emptyList()) is SnapshotBuild.Built)
        val over = stopped(limit + 1)
        assertEquals(limit + 1, over.scopeDecisionCount)
        val refusal = tooMany(RelayStateSnapshot.of(over, emptyList(), emptyList()))
        assertEquals("ledger.scopes", refusal.path)
        assertEquals(limit, refusal.limit)
        // The consumer re-authorises one scope (evicting its content first): the decisions fit again, none was lost.
        val evicted = over.evictContent(enrollment(1)).ledger
        val reactivated = evicted.reactivate(enrollment(1), Reactivation.USER_REAUTHORIZED).ledger
        assertEquals(limit, reactivated.scopeDecisionCount)
        val built = RelayStateSnapshot.of(reactivated, emptyList(), emptyList())
        assertTrue(built is SnapshotBuild.Built)
        val back = (
            RelayStateRestore.restore(
                (built as SnapshotBuild.Built).snapshot,
                StateTestSupport.at(),
                RelayAuthority.fresh()
            ) as RestoreResult.Restored
            ).value.ledger
        for (index in 2..limit + 1) {
            assertEquals(ScopeStatus.STOPPED, back.scopeStatus(enrollment(index)))
            assertTrue(back.scopeRecord(enrollment(index))!!.contentEvictionOwed)
        }
    }

    private fun requeuedCancelled(): Triple<LedgerHarness, TurnKey, BindingToken> {
        val h = LedgerHarness()
        val key = h.prepare("steered and cancelled")
        val (token, dispatch) = h.token1(key)
        h.reply(dispatch, h.receipt(key, ReceiptStatus.STARTED, 3))
        h.reply(
            h.planned(h.lookup(key, token)),
            h.receipt(key, ReceiptStatus.TERMINAL, null, "cancelled")
        )
        return Triple(h, key, token)
    }

    @Test fun theStartThatACancelReplacedIsRememberedAndSurvivesASaveAndARestore() {
        val (h, key, _) = requeuedCancelled()
        val facts = h.entry(key).facts!!
        assertEquals(3L, facts.priorStartedGeneration)
        assertNull(facts.executionGeneration)
        val saved = h.ledger.snapshot().turns.single().facts!!
        assertEquals(3L, saved.priorStartedGeneration)
        val snapshot = RelayStateSnapshot(
            RelayStateSnapshot.VERSION,
            h.ledger.snapshot(),
            emptyList(),
            emptyList()
        )
        val restored = (
            RelayStateRestore.restore(snapshot, h.now(), RelayAuthority.fresh())
                as RestoreResult.Restored
            ).value.ledger.entry(key)!!
        assertEquals(facts, restored.facts)
        assertEquals(TurnPhase.TERMINAL, restored.phase)
    }

    @Test fun aRememberedStartOnAnyOtherShapeOfEvidenceIsRefusedOnRestore() {
        val (h, _, _) = requeuedCancelled()
        val turn = h.ledger.snapshot().turns.single()
        val good = turn.facts!!
        val cases = mapOf(
            "generation present" to good.copy(executionGeneration = 3),
            "not cancelled" to good.copy(outcome = "completed"),
            "not terminal" to good.copy(status = "queued", outcome = null),
            "below one" to good.copy(priorStartedGeneration = 0)
        )
        for ((label, facts) in cases) {
            val snapshot = RelayStateSnapshot(
                RelayStateSnapshot.VERSION,
                TurnLedgerSnapshot(listOf(turn.copy(facts = facts)), emptyList()),
                emptyList(),
                emptyList()
            )
            val result = RelayStateRestore.restore(snapshot, h.now(), RelayAuthority.fresh())
            val refused = result as? RestoreResult.Refused
            assertTrue("$label: $result", refused != null)
            assertEquals(label, RestoreRefusal.INVALID_FIELD, refused!!.reason)
            assertEquals(label, "ledger.turns[0].facts", refused.path)
        }
    }

    @Test fun anExpiredAnswerIsRecordedDurablyAsTheLastErrorWithoutClosingAnything() {
        val h = LedgerHarness()
        val key = h.prepare("observed expired")
        val (_, dispatch) = h.token1(key)
        h.effects.clear()
        h.fail(dispatch, "expired")
        assertEquals(
            dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode.EXPIRED,
            h.entry(key).lastErrorCode
        )
        assertEquals(UncertainReason.EXPIRED_REPORTED, h.entry(key).uncertainty)
        assertTrue(h.effects.contains(TurnEffect.PersistLedger))
        assertNull(h.entry(key).closure)
    }
}
