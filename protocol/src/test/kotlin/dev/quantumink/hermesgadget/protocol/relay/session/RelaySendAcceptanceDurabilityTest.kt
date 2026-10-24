package dev.quantumink.hermesgadget.protocol.relay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual DirectConnection Peer.send acceptance, separate new runs from frozen Minor1 evidence. */
class RelaySendAcceptanceDurabilityTest {
    private fun setup(h: OwnReplayHarness) {
        h.prepareAndEnable()
        h.connect()
        h.controller.create("Planning")
        h.settle()
        assertEquals(RelayPhase.SELECTED, h.ui.phase)
    }

    private fun refused(failure: StoreCommit) {
        OwnReplayHarness("absent").use { h ->
            setup(h)
            h.store.failAll = failure
            var accepted = true
            h.controller.send("first question") { accepted = it }
            h.settle()
            assertFalse(accepted)
            assertEquals(0, h.entropyExhaustions)
            assertEquals(0, h.durableSubmits)
            assertTrue(h.requests("relay.turn.submit").isEmpty())
        }
    }

    @Test fun failedCommitNeverReachesSocketAcceptance() =
        refused(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun uncertainCommitNeverReachesSocketAcceptance() =
        refused(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true))

    @Test fun refusedCommitNeverReachesSocketAcceptance() =
        refused(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun staleCommitNeverReachesSocketAcceptance() = refused(StoreCommit.Stale)

    @Test fun freshCommittedTurnMatchesRequestIdTextAndExpiryAtSocketAcceptance() {
        OwnReplayHarness("absent").use { h ->
            setup(h)
            h.server.swallowNext("relay.turn.submit")
            var accepted = false
            h.controller.send("first question") { accepted = it }
            h.settle()
            assertTrue(accepted)
            assertEquals(0, h.entropyExhaustions)
            assertEquals(1, h.durableSubmits)
        }
    }

    @Test fun reusingTheFiniteRecordingAfterFailedCommitExhaustsEntropyWithoutSending() {
        OwnReplayHarness("absent").use { h ->
            setup(h)
            assertEquals(2, h.session.mintPlan.size)
            h.store.failAll = StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
            var accepted = true
            h.controller.send("first question") { accepted = it }
            h.settle()
            assertFalse(accepted)
            assertEquals(0, h.entropyExhaustions)
            h.store.failAll = null
            h.controller.send("first question") { accepted = it }
            h.settle()
            assertFalse(accepted)
            assertEquals(1, h.entropyExhaustions)
            assertEquals(RelayNotice.TURN_REFUSED, h.ui.notice)
            assertEquals(0, h.durableSubmits)
            assertTrue(h.requests("relay.turn.submit").isEmpty())
        }
    }
}
