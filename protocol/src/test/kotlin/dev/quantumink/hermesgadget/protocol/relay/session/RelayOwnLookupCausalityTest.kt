package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual owner schedules; no ledger replacement or bypass of attempt/binding/clock/persistence gates. */
class RelayOwnLookupCausalityTest {
    private fun selected(own: Boolean = false): ControllerFixture {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect(
            Dto.info(
                methods = if (own) Dto.ALL_METHODS + "relay.turn.receipt.own" else Dto.ALL_METHODS
            )
        )
        fx.listAnd(Dto.summary())
        fx.controller.select(Dto.conversation.value)
        fx.run()
        fx.attachTo(Dto.binding(1))
        fx.controller.send("immutable causal question") {}
        fx.run()
        return fx
    }

    private fun reconnect(fx: ControllerFixture, binding: Int) {
        fx.disconnect()
        fx.connect()
        fx.listAnd(Dto.summary())
        fx.answer("relay.conversation.attach", Dto.binding(binding).toWire())
    }

    /** Reach retained absence by EXHAUSTING the real default three automatic resubmits. */
    private fun retainedAbsence(): ControllerFixture {
        val fx = selected()
        for (binding in 2..5) {
            reconnect(fx, binding)
            assertEquals("relay.turn.receipt", fx.transport.methods().last())
            fx.answerError("relay.turn.receipt", "not_found")
            fx.answer("relay.history.page", Dto.history().toWire())
            if (fx.transport.open("relay.tasks.list").isNotEmpty()) {
                fx.answer(
                    "relay.tasks.list",
                    dev.quantumink.hermesgadget.protocol.relay.TaskPage(emptyList(), null).toWire()
                )
            }
        }
        assertEquals(4, fx.frames("relay.turn.submit").size)
        assertTrue(fx.ui.turns.single().canResend)
        return fx
    }

    private fun failedAfterAbsence(kind: String) {
        val fx = retainedAbsence()
        val original = fx.frames("relay.turn.submit").first().params
        val id = original["request_id"]!!.jsonPrimitive.content
        fx.controller.check(id)
        fx.run()
        val lookup = fx.transport.next("relay.turn.receipt")
        when (kind) {
            "timeout" -> fx.transport.timeout(lookup)
            "refused" -> lookup.deliver(RelayCallResult.Refused(RelayRefusal.NOT_READY))
            "closed" -> lookup.deliver(RelayCallResult.Closed)
            "unavailable" -> fx.transport.fail(lookup, "temporarily_unavailable")
            "invalid" -> fx.transport.reply(lookup, JsonObject(emptyMap()))
            "uncorrelated" -> lookup.frame(
                buildJsonObject {
                    put("id", "another-call")
                    put("error", buildJsonObject { put("code", "not_found") })
                }
            )
            "stale" -> fx.transport.fail(lookup, "stale_epoch")
        }
        fx.run()
        assertEquals(
            "failure must not release automatic work",
            4,
            fx.frames("relay.turn.submit").size
        )
        for (submit in fx.frames("relay.turn.submit")) {
            for (member in listOf(
                "request_id",
                "text",
                "expires_at_ms"
            )) {
                assertEquals(original[member], submit.params[member])
            }
        }
        // The ended call's late negative cannot mint another absence or new automatic SEND.
        lookup.frame(
            buildJsonObject {
                put("id", lookup.correlation.value)
                put("error", buildJsonObject { put("code", "not_found") })
            }
        )
        fx.run()
        assertEquals(4, fx.frames("relay.turn.submit").size)
    }

    @Test fun realPreviousAbsenceThenTimeoutIsAlreadyBlockedByAttemptBudget() =
        failedAfterAbsence("timeout")

    @Test fun realPreviousAbsenceThenRefusalIsAlreadyBlockedByAttemptBudget() =
        failedAfterAbsence("refused")

    @Test fun realPreviousAbsenceThenClosedDoesNotReleaseWork() = failedAfterAbsence("closed")

    @Test fun realPreviousAbsenceThenUnavailableDoesNotReleaseWork() = failedAfterAbsence(
        "unavailable"
    )

    @Test fun realPreviousAbsenceThenInvalidDoesNotReleaseWork() = failedAfterAbsence("invalid")

    @Test fun realPreviousAbsenceThenUncorrelatedDoesNotReleaseWork() = failedAfterAbsence(
        "uncorrelated"
    )

    @Test fun realPreviousAbsenceThenStaleDoesNotReleaseWork() = failedAfterAbsence("stale")

    private fun replaced(kind: String) {
        val fx = selected()
        reconnect(fx, 2)
        val pending = fx.transport.next("relay.turn.receipt")
        when (kind) {
            "binding" -> fx.controller.select(Dto.otherConversation.value)
            "foreground" -> fx.controller.prepare(fx.identity, foregroundSession = 1)
            "endpoint" -> fx.controller.prepare(
                EnrollmentIdentity("wss://replacement.example.invalid/gadget", "phone:replacement")
            )
            "transport" -> fx.controller.attachTransport(null)
        }
        fx.run()
        val before = fx.frames("relay.turn.submit").size
        fx.transport.fail(pending, "not_found")
        fx.run()
        assertEquals(before, fx.frames("relay.turn.submit").size)
    }

    @Test fun replacedBindingCannotReleaseTheOldLookup() = replaced("binding")

    @Test fun replacedForegroundCannotReleaseTheOldLookup() = replaced("foreground")

    @Test fun replacedEndpointCannotReleaseTheOldLookup() = replaced("endpoint")

    @Test fun replacedTransportCannotReleaseTheOldLookup() = replaced("transport")
    private fun negotiatedAgain(firstOwn: Boolean, nextOwn: Boolean) {
        val fx = selected(firstOwn)
        val original = fx.frames("relay.turn.submit").single()
        fx.disconnect()
        fx.connect(
            Dto.info(
                methods = if (nextOwn) {
                    Dto.ALL_METHODS + "relay.turn.receipt.own"
                } else {
                    Dto.ALL_METHODS
                }
            )
        )
        fx.listAnd(Dto.summary())
        fx.answer("relay.conversation.attach", Dto.binding(2).toWire())
        val method = if (nextOwn) "relay.turn.receipt.own" else "relay.turn.receipt"
        val lookup = fx.transport.next(method)
        assertEquals(method, fx.transport.methods().last())
        assertEquals(original.params["request_id"], lookup.params["request_id"])
        fx.transport.fail(lookup, "temporarily_unavailable")
        fx.run()
        assertEquals(1, fx.frames("relay.turn.submit").size)
        assertEquals(1, fx.frames(method).size)
        assertTrue(
            fx.frames(if (nextOwn) "relay.turn.receipt" else "relay.turn.receipt.own").isEmpty()
        )
    }

    @Test fun freshConnectionCanWithdrawOwnWithoutUsingCachedNegotiation() = negotiatedAgain(
        true,
        false
    )

    @Test fun freshConnectionCanAdvertiseOwnAfterALegacyConnection() = negotiatedAgain(false, true)

    @Test fun optionalOwnDoesNotReplaceTheRequiredLegacyBaseline() {
        val fx = ControllerFixture()
        fx.enable()
        fx.connect(
            Dto.info(
                methods =
                Dto.ALL_METHODS - "relay.turn.receipt" + "relay.turn.receipt.own"
            )
        )
        assertTrue(fx.transport.methods().isEmpty())
        assertEquals(false, fx.ui.canSend)
    }
}
