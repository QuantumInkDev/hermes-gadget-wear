package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.relay.session.Dto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDelayedAuthorityTest {
    private fun waitingChange(kind: String, reentrant: Boolean = false) {
        val sent = ArrayList<String>()
        val channel = RelayChannel({ _, _ -> }, { 5_000_000_000L })
        val send: (String) -> Boolean = {
            sent.add(it)
            true
        }
        fun id(): String =
            Json.parseToJsonElement(sent.last()).jsonObject["id"]!!.jsonPrimitive.content
        fun reply(body: String) =
            channel.frame("""{"type":"relay.response","v":1,"id":"${id()}",$body}""")
        channel.open(1)
        channel.welcome(
            Message.parse("""{"type":"welcome","relay":{"version":1}}""")!!,
            true,
            1000,
            send
        )
        reply("\"result\":" + Dto.info().toWire())
        repeat(14) {
            channel.call(
                channel.correlation(),
                RelayCalls.receipt(
                    Dto.conversation,
                    RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
                ),
                10000,
                1000,
                send
            ) {}
            reply("\"error\":{\"code\":\"not_found\"}")
        }
        channel.refreshInfo(1000, send) {}
        val completions = ArrayList<RelayCallResult<Receipt>>()
        val mutation = RelayCalls.submit(
            Dto.binding(1).ref,
            RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!,
            Dto.SERVER_TIME + 1000,
            "queued"
        )
        channel.call(channel.correlation(), mutation, 10000, 1000, send) {
            completions.add(it)
            if (reentrant) channel.tick(3000, send)
        }
        assertEquals(15, sent.size) // both info and mutation really await pacing tokens
        channel.tick(1112, send)
        assertEquals(16, sent.size) // only the refresh left
        val changed = when (kind) {
            "denied" -> "\"error\":{\"code\":\"permission_denied\"}"
            "profile" -> "\"result\":" + Dto.info(profile = Dto.otherProfile).toWire()
            else ->
                "\"result\":" +
                    Dto.info(methods = Dto.ALL_METHODS - "relay.turn.submit").toWire()
        }
        reply(changed)
        channel.tick(1224, send)
        channel.tick(3000, send)
        assertEquals("obsolete mutation must never SEND", 16, sent.size)
        assertEquals(1, completions.size)
        assertTrue(completions.single() is RelayCallResult.Refused)
        assertEquals(0, channel.outstanding)
    }

    @Test fun denialRetractsAnActuallyTokenWaitingSubmit() = waitingChange("denied")

    @Test fun profileReplacementRetractsAnActuallyTokenWaitingSubmit() = waitingChange("profile")

    @Test fun removedMethodRetractsAnActuallyTokenWaitingSubmit() = waitingChange("method")

    @Test fun refusalCompletionCanReenterTickWithoutReleasingObsoleteWork() =
        waitingChange("denied", true)
}
