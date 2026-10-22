package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gaps, snapshots, errors and restores never create a turn, answer a prompt, cancel a run or speak content. */
class NoSpontaneousActionTest {
    @Test fun onlyPrepareAndMintTakeEntropySoNothingElseCanMintAnId() {
        val holders = listOf(
            TurnLedger::class.java,
            HistoryAssembly::class.java,
            EnrollmentDelivery::class.java,
            RelayAuthority::class.java,
            ServerClock::class.java,
            RelayStateRestore::class.java,
            RequestIds::class.java
        )
        val takers = holders.flatMap { type ->
            type.methods
                .filter { method ->
                    method.parameterTypes.any { it == RequestIdEntropy::class.java }
                }
                .map { method -> "${type.simpleName}.${method.name}" }
        }.toSet()
        assertEquals(setOf("TurnLedger.prepare", "RequestIds.mint"), takers)
    }

    @Test fun aGapAnErrorAndARestoreLeaveTheTurnLedgerUntouchedAndPlanNoCall() {
        val turns = LedgerHarness()
        val key = turns.prepare("pending words")
        val (_, submit) = turns.token1(key)
        turns.timeout(submit)
        val ledgerBefore = turns.ledger
        val dispatched = turns.dispatched.toList()

        // A recorded gap arrives for the same conversation, history is invalidated, errors come back.
        val delivery = DeliveryHarness()
        val read = DeliveryHarness.events("ok-10")
        delivery.attach(delivery.bindingOf(read))
        val batch = delivery.staged(delivery.stage(delivery.planRead(50), read))
        delivery.apply(delivery.delivery.commitEvents(batch.batchId, delivery.now()))
        val history = HistoryHarness()
        history.plan()
        history.apply(history.assembly.invalidate(HistoryInvalidation.GAP_EVENT))

        // Nothing above had any handle on the ledger, and what it asked for is only a refresh or a re-attach.
        assertSame(ledgerBefore, turns.ledger)
        assertEquals(dispatched, turns.dispatched)
        assertEquals(TurnPhase.UNCERTAIN, turns.phase(key))
        assertTrue(
            delivery.effects.all {
                it is DeliveryEffect.PersistDelivery || it is DeliveryEffect.RefreshRequired ||
                    it is DeliveryEffect.ReattachRequired
            }
        )
        assertTrue(history.effects.all { it is HistoryEffect.RebuildRequired })

        // A restore likewise only re-creates the uncertainty; the same id, no call, no new turn.
        val snapshot = StateTestSupport.builtSnapshot(
            turns.ledger,
            listOf(history.assembly),
            listOf(delivery.delivery)
        )
        val state = (
            RelayStateRestore.restore(snapshot, StateTestSupport.at(0), RelayAuthority.fresh())
                as RestoreResult.Restored
            ).value
        assertEquals(1, state.ledger.size)
        assertEquals(key, state.ledger.entries.single().key)
        assertEquals(TurnPhase.UNCERTAIN, state.ledger.entries.single().phase)
        assertNull(state.ledger.entries.single().facts)
        assertEquals(1, turns.entropy.calls) // the one prepare, ever
    }

    @Test fun anAdmissionOrGapNeverTurnsAnEventIntoAnActionablePromptOrAnOutcome() {
        val needsInput = DeliveryHarness.events("ok-14")
        val h = DeliveryHarness()
        h.attach(h.bindingOf(needsInput))
        val batch = h.staged(h.stage(h.planRead(50), needsInput))
        assertTrue(batch.events.isNotEmpty())
        // The staged batch exposes events and refresh obligations only: no reply to give, no call to make.
        assertFalse(
            batch.javaClass.methods.any {
                it.name.lowercase().let { n ->
                    n.contains("answer") ||
                        n.contains("respond")
                }
            }
        )
        val ledger = LedgerHarness()
        val key = ledger.prepare()
        val (_, submit) = ledger.token1(key)
        ledger.reply(submit, ledger.receipt(key, ReceiptStatus.STARTED, generation = 1))
        // A task event for that very conversation does not settle or change the turn: only a receipt can.
        assertEquals(TurnPhase.STARTED, ledger.phase(key))
        h.apply(h.delivery.commitEvents(batch.batchId, h.now()))
        assertEquals(TurnPhase.STARTED, ledger.phase(key))
    }

    @Test fun permissionLossForgetsDeliveryStateWithoutAcknowledgingAnything() {
        val h = DeliveryHarness()
        val stock = h.stagedOutbox(h.stageOutbox(h.planOutbox(), DeliveryHarness.outbox("ok-09")))
        h.apply(h.delivery.commitOutbox(stock.batchId, h.now()))
        assertEquals(1, h.delivery.acknowledgementsOwed.size)
        val forgotten = h.apply(h.delivery.evictScopeContent())
        // One identity, one unacknowledged id and the two refreshes the unknown baseline owed.
        assertEquals(4, forgotten)
        assertEquals(0, h.delivery.seenCount)
        assertTrue(h.delivery.acknowledgementsOwed.isEmpty())
        assertNull(h.delivery.committedGapGeneration)
        // Nothing was acknowledged to the server, and a later grant starts from an unknown baseline.
        val again = h.stagedOutbox(h.stageOutbox(h.planOutbox(), DeliveryHarness.outbox("ok-09")))
        assertEquals(1, again.items.size)
        assertTrue(again.refresh.any { it.cause == RefreshCause.OUTBOX_BASELINE_UNKNOWN })
    }
}
