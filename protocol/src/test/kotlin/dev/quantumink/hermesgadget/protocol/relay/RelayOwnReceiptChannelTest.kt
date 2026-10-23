package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.relay.session.Dto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The actual channel pacing queue and final method/Ready/transport-scope fences. */
class RelayOwnReceiptChannelTest {
    private fun queued(change: String) {
        val sent = ArrayList<String>()
        val channel = RelayChannel({ _, _ -> }, { 5_000_000_000L })
        val send: (String) -> Boolean = {
            sent.add(it)
            true
        }
        val methods = Dto.ALL_METHODS + RelayMethod.TURN_RECEIPT_OWN.wire
        fun info(own: Boolean = true) = Dto.info(methods = if (own) methods else Dto.ALL_METHODS)
        fun reply(body: String) {
            val id = Json.parseToJsonElement(sent.last()).jsonObject["id"]!!.jsonPrimitive.content
            channel.frame("""{"type":"relay.response","v":1,"id":"$id",$body}""")
        }
        channel.open(1)
        channel.welcome(
            Message.parse("""{"type":"welcome","relay":{"version":1}}""")!!,
            true,
            1000,
            send
        )
        reply("\"result\":" + info().toWire())
        val call = RelayCalls.ownReceipt(
            Dto.conversation,
            RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
        )
        repeat(14) {
            channel.call(channel.correlation(), call, 10000, 1000, send) {}
            reply("\"error\":{\"code\":\"not_found\"}")
        }
        val refresh = change in setOf("method", "ready")
        if (refresh) channel.refreshInfo(1000, send) {}
        val results = ArrayList<RelayCallResult<Receipt>>()
        var scopeCurrent = true
        val correlation = channel.correlation()
        channel.callScoped(correlation, call, 10000, 1000, send, {
            scopeCurrent
        }) { results.add(it) }
        assertEquals("lookup is genuinely token-waiting", 15, sent.size)
        when (change) {
            "method", "ready" -> {
                channel.tick(1112, send)
                assertEquals(16, sent.size)
                reply("\"result\":" + info(own = change != "method").toWire())
            }
            "scope" -> scopeCurrent = false
            "socket" -> channel.close(2)
        }
        channel.tick(3000, send)
        if (change == "current") {
            assertEquals(16, sent.size)
            reply("\"error\":{\"code\":\"not_found\"}")
            assertTrue(results.single() is RelayCallResult.Outcome)
        } else {
            assertEquals(if (refresh) 16 else 15, sent.size)
            assertTrue(
                results.single() is RelayCallResult.Refused ||
                    results.single() is RelayCallResult.Closed
            )
        }
        assertEquals(1, results.size)
        assertEquals(0, channel.outstanding)
        channel.frame(
            """{"type":"relay.response","v":1,"id":"${correlation.value}","error":{"code":"not_found"}}"""
        )
        channel.tick(4000, send)
        assertEquals("late callback cannot complete or send again", 1, results.size)
        assertEquals(if (refresh || change == "current") 16 else 15, sent.size)
    }

    @Test fun withdrawnOwnMethodInvalidatesAQueuedLookup() = queued("method")

    @Test fun freshReadySerialInvalidatesAQueuedLookupEvenWhenOwnIsStillAdvertised() = queued(
        "ready"
    )

    @Test fun replacedTransportScopeInvalidatesAQueuedLookup() = queued("scope")

    @Test fun replacedSocketInvalidatesAQueuedLookup() = queued("socket")

    @Test fun aCurrentQueuedOwnLookupSendsAndCompletesOnce() = queued("current")
}
