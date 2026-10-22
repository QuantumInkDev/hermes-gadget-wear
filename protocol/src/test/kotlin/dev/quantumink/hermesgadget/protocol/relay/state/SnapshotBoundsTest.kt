package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review I1: the aggregate snapshot must never silently shorten histories, deliveries or ledger scope decisions.
 * The exact limit restores completely; one more is refused with a precise path; a restorable prefix of a longer
 * input never exists; a pathological caller collection is guarded before it is mapped or copied.
 */
class SnapshotBoundsTest {
    private val conversationId = StateTestSupport.attached().conversationId

    private fun enrollment(index: Int) = EnrollmentScope(StateTestSupport.scope, "key-$index", 0)

    private fun history(index: Int) =
        HistoryAssembly.begin(HistoryScope(ConversationScope(enrollment(index), conversationId)))

    private fun delivery(index: Int) = EnrollmentDelivery.begin(enrollment(index))

    private fun stoppedRecord(index: Int) =
        ScopeStateSnapshot(enrollment(index).toSnapshot(), ScopeStatus.STOPPED.name, true)

    private fun restore(snapshot: RelayStateSnapshot): RestoreResult<RestoredState> =
        RelayStateRestore.restore(snapshot, StateTestSupport.at(), RelayAuthority.fresh())

    private fun refusal(result: RestoreResult<RestoredState>) = result as RestoreResult.Refused

    private fun snapshot(
        histories: List<HistorySnapshot> = emptyList(),
        deliveries: List<DeliverySnapshot> = emptyList(),
        scopes: List<ScopeStateSnapshot> = emptyList()
    ) = RelayStateSnapshot(
        RelayStateSnapshot.VERSION,
        TurnLedgerSnapshot(emptyList(), scopes),
        histories,
        deliveries
    )

    /** One enrollment per ledger step, each stopped by `permission_denied`, in a single shared ledger. */
    private fun ledgerWithStoppedScopes(count: Int): TurnLedger {
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

    @Test fun sixteenHistoriesRestoreWholeAndSeventeenAreRefusedAtTheirPath() {
        val limit = StateLimits.MAX_HISTORY_SCOPES
        val whole = restore(snapshot(histories = (1..limit).map { history(it).snapshot() }))
        assertEquals(limit, (whole as RestoreResult.Restored).value.histories.size)
        val over =
            refusal(restore(snapshot(histories = (1..limit + 1).map { history(it).snapshot() })))
        assertEquals(RestoreRefusal.TOO_MANY, over.reason)
        assertEquals("histories", over.path)
    }

    @Test fun sixteenDeliveriesRestoreWholeAndSeventeenAreRefusedAtTheirPath() {
        val limit = StateLimits.MAX_ENROLLMENTS
        val whole = restore(snapshot(deliveries = (1..limit).map { delivery(it).snapshot() }))
        assertEquals(limit, (whole as RestoreResult.Restored).value.deliveries.size)
        val over =
            refusal(restore(snapshot(deliveries = (1..limit + 1).map { delivery(it).snapshot() })))
        assertEquals(RestoreRefusal.TOO_MANY, over.reason)
        assertEquals("deliveries", over.path)
    }

    @Test fun sixtyFourScopeDecisionsRestoreWholeAndSixtyFiveAreRefusedAtTheirPath() {
        val limit = StateLimits.MAX_ENROLLMENTS * 4
        val whole = restore(snapshot(scopes = (1..limit).map { stoppedRecord(it) }))
        val ledger = (whole as RestoreResult.Restored).value.ledger
        for (index in 1..limit) {
            assertEquals(ScopeStatus.STOPPED, ledger.scopeStatus(enrollment(index)))
            assertTrue(ledger.scopeRecord(enrollment(index))!!.contentEvictionOwed)
        }
        val over = refusal(restore(snapshot(scopes = (1..limit + 1).map { stoppedRecord(it) })))
        assertEquals(RestoreRefusal.TOO_MANY, over.reason)
        assertEquals("ledger.scopes", over.path)
    }

    @Test fun aLongerInputNeverLeavesARestorablePrefix() {
        val histories = (1..300).map { history(it % 20 + 1).snapshot() }
        val result = restore(snapshot(histories = histories))
        assertTrue("no prefix may restore", result is RestoreResult.Refused)
        val deliveries = (1..300).map { delivery(it % 20 + 1).snapshot() }
        assertTrue(restore(snapshot(deliveries = deliveries)) is RestoreResult.Refused)
        val scopes = (1..300).map { stoppedRecord(it) }
        assertTrue(restore(snapshot(scopes = scopes)) is RestoreResult.Refused)
    }

    @Test fun aLedgerHoldingSixtyFiveStoppedScopesIsNeverSavedAsALossyRestorablePrefix() {
        val limit = StateLimits.MAX_ENROLLMENTS * 4
        val exact = ledgerWithStoppedScopes(limit)
        assertEquals(limit, exact.recordedScopes.size)
        val back = restore(snapshot(scopes = exact.snapshot().scopes)) as RestoreResult.Restored
        for (index in 1..limit) {
            assertEquals(ScopeStatus.STOPPED, back.value.ledger.scopeStatus(enrollment(index)))
        }
        val over = ledgerWithStoppedScopes(limit + 1)
        // The in-memory decisions are all still there: nothing is dropped from the live ledger.
        assertEquals(limit + 1, over.recordedScopes.size)
        for (index in 1..limit + 1) {
            assertEquals(ScopeStatus.STOPPED, over.scopeStatus(enrollment(index)))
        }
        val refused = refusal(restore(snapshot(scopes = over.snapshot().scopes)))
        assertEquals(RestoreRefusal.TOO_MANY, refused.reason)
        assertEquals("ledger.scopes", refused.path)
    }

    @Test fun aPathologicalHistoryCollectionIsGuardedBeforeItIsMapped() {
        val one = history(1)
        var reads = 0
        val huge = object : AbstractList<HistoryAssembly>() {
            override val size: Int = 500_000

            override fun get(index: Int): HistoryAssembly {
                reads++
                return one
            }
        }
        RelayStateSnapshot.of(TurnLedger.empty(), huge, emptyList())
        assertTrue(
            "read $reads of ${huge.size} before the bound was observed",
            reads <= StateLimits.MAX_HISTORY_SCOPES + 1
        )
    }

    @Test fun aPathologicalDeliveryCollectionIsGuardedBeforeItIsMapped() {
        val one = delivery(1)
        var reads = 0
        val huge = object : AbstractList<EnrollmentDelivery>() {
            override val size: Int = 500_000

            override fun get(index: Int): EnrollmentDelivery {
                reads++
                return one
            }
        }
        RelayStateSnapshot.of(TurnLedger.empty(), emptyList(), huge)
        assertTrue(
            "read $reads of ${huge.size} before the bound was observed",
            reads <= StateLimits.MAX_ENROLLMENTS + 1
        )
    }
}
