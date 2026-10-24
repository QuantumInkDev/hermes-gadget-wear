package dev.quantumink.hermesgadget.protocol.relay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Five separately reported controls, diagnosing the earlier anonymous loop without changing its original XML. */
class RelayRefusalVariantsFix2Test {
    private fun check(failure: StoreCommit) {
        val f = ControllerFixture()
        f.enable()
        f.connect()
        f.listAnd(Dto.summary())
        f.controller.select(Dto.conversation.value)
        f.run()
        f.attachTo(Dto.binding(1))
        f.store.failAll = failure
        var accepted = true
        f.controller.send("same immutable caller draft") { accepted = it }
        f.run()
        assertFalse(failure.toString(), accepted)
        assertTrue(failure.toString(), f.frames("relay.turn.submit").isEmpty())
        assertTrue(failure.toString(), f.ui.turns.isEmpty())
        assertTrue(f.store.reread().snapshot.ledger.turns.isEmpty())
        f.store.failAll = null
        var recovered = false
        f.controller.send("same immutable caller draft") { recovered = it }
        f.run()
        assertTrue("immediate recovery variant=$failure", recovered)
        assertEquals(1, f.frames("relay.turn.submit").size)
        assertEquals(
            "same immutable caller draft",
            f.store.reread().snapshot.ledger.turns.single().text
        )
    }

    @Test fun failedStorageKeepsLastDurableOwnerAndPermitsValidRecovery() =
        check(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false))

    @Test fun uncertainStorageKeepsLastDurableOwnerAndPermitsValidRecovery() =
        check(StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true))

    @Test fun refusedCountKeepsLastDurableOwnerAndPermitsValidRecovery() =
        check(StoreCommit.Refused(EncodeRefusal.TOO_MANY, "ledger.turns"))

    @Test fun refusedBytesKeepsLastDurableOwnerAndPermitsValidRecovery() =
        check(StoreCommit.Refused(EncodeRefusal.STORAGE_CAP, "document"))

    @Test fun staleCommitKeepsLastDurableOwnerAndPermitsValidRecovery() = check(StoreCommit.Stale)
}
