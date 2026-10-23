package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLedgerOwnLookupTest {
    @Test fun typedChoiceBuildsTheActualCallAndPreservesOutstandingIdentity() {
        for (choice in ReceiptLookup.entries) {
            val h = LedgerHarness()
            val key = h.prepare("immutable plan")
            val (_, submit) = h.token1(key)
            h.timeout(submit)
            val token = h.attach()
            val id = h.correlation()
            val step = h.ledger.planLookup(key, h.authority, token, h.now(), id, choice)
            val dispatch = (step.result as PlanOutcome.Planned).dispatch
            assertEquals(
                if (choice ==
                    ReceiptLookup.OWN
                ) {
                    RelayMethod.TURN_RECEIPT_OWN
                } else {
                    RelayMethod.TURN_RECEIPT
                },
                dispatch.call.method
            )
            assertEquals(CallKind.LOOKUP, dispatch.ref.kind)
            assertEquals(token.serial, dispatch.ref.bindingSerial)
            assertEquals(key.conversation.enrollment, dispatch.ref.scope)
            assertEquals(id, dispatch.ref.correlation)
            assertEquals(dispatch.ref, step.ledger.entry(key)!!.outstanding)
            assertEquals(h.entry(key).fingerprint, step.ledger.entry(key)!!.fingerprint)
            assertEquals(h.entry(key).expiresAtMs, step.ledger.entry(key)!!.expiresAtMs)
            assertTrue(step.effects.isEmpty())
            val legacy = h.ledger.planLookup(key, h.authority, token, h.now(), id)
            assertEquals(
                RelayMethod.TURN_RECEIPT,
                (legacy.result as PlanOutcome.Planned).dispatch.call.method
            )
        }
    }
}
