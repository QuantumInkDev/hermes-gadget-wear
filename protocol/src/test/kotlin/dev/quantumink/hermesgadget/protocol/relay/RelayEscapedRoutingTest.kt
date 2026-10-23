package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.relay.session.Dto
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEscapedRoutingTest {
    private val headers = listOf(
        "\"\\u0074ype\":\"relay.response\"",
        "\"type\":\"relay.\\u0072esponse\"",
        "\"type\":\"status\",\"type\":\"relay.response\"",
        "\"type\":\"relay.response\",\"type\":\"status\"",
        "\"type\":null,\"\\u0074ype\":\"relay.response\""
    )

    @Test fun escapedAndAmbiguousTypesCannotFallThroughTheClassifier() {
        for (header in headers) {
            assertEquals(
                header,
                RelayFrameSniffer.Verdict.RELAY_RESPONSE,
                RelayFrameSniffer.classify("{$header,\"v\":1,\"id\":\"x\",\"result\":{}}")
            )
            assertEquals(
                RelayFrameSniffer.Verdict.RELAY_RESPONSE,
                RelayFrameSniffer.classify(
                    "{$header,\"result\":{\"blob\":\"${"x".repeat(70000)}\"}}"
                )
            )
        }
        assertEquals(
            RelayFrameSniffer.Verdict.OTHER,
            RelayFrameSniffer.classify("""{"\u0074ype":"sta\u0074us","text":"ok"}""")
        )
    }

    @Test(timeout = 60000)
    fun rawEntryCorrelatesEscapesAndRejectsAmbiguityDepthAndBytes() {
        for ((index, header) in headers.withIndex()) {
            RelayConnectionHarness(RelaySocketFixture()).use { h ->
                h.connection.connect()
                h.awaitState<RelayChannelState.LoadingInfo>()
                val peer = h.fixture.latest
                val info = peer.nextRequest()
                peer.reply(info["id"]!!.jsonPrimitive.content, Dto.info().toWire())
                h.awaitState<RelayChannelState.Ready>()
                peer.deliver("""{"\u0074ype":"sta\u0074us","text":"stock compatible"}""")
                h.connection.text("").get(5, TimeUnit.SECONDS)
                assertEquals("stock compatible", h.connectionStates.last().conversation.status)
                val latch = CountDownLatch(1)
                var response: RelayCallResult<Receipt>? = null
                val id = h.transport.correlation()
                h.transport.call(
                    id,
                    RelayCalls.receipt(
                        Dto.conversation,
                        RequestId.of("01939c8e-7a00-7000-8000-000000000001")!!
                    ),
                    10000
                ) {
                    response = it
                    latch.countDown()
                }
                peer.nextRequest()
                peer.deliver(
                    "{$header,\"v\":1,\"id\":\"${id.value}\",\"error\":{\"code\":\"not_found\"}}"
                )
                assertTrue(
                    "raw header $index must complete without timeout",
                    latch.await(2, TimeUnit.SECONDS)
                )
                val outcome = (response as RelayCallResult.Outcome).outcome
                assertTrue(
                    if (index <
                        2
                    ) {
                        outcome is RelayOutcome.Failed
                    } else {
                        outcome is RelayOutcome.Invalid
                    }
                )
                // The same escaped header must reach Relay's byte gate, never stock's 1 MiB parser.
                peer.deliver("{$header,\"result\":{\"blob\":\"${"x".repeat(70000)}\"}}")
                assertEquals(
                    RelayFailure.OVERSIZE_RESPONSE,
                    h.awaitState<RelayChannelState.Failed>().reason
                )
                h.connection.text("").get(5, TimeUnit.SECONDS)
                assertEquals(ConnectionStatus.PAIRED, h.connectionStates.last().status)
            }
        }
        RelayConnectionHarness(RelaySocketFixture()).use { h ->
            h.connection.connect()
            h.awaitState<RelayChannelState.LoadingInfo>()
            val peer = h.fixture.latest
            val info = peer.nextRequest()
            peer.reply(info["id"]!!.jsonPrimitive.content, Dto.info().toWire())
            h.awaitState<RelayChannelState.Ready>()
            val latch = CountDownLatch(1)
            h.transport.call(h.transport.correlation(), RelayCalls.info(), 10000) {
                assertTrue((it as RelayCallResult.Outcome).outcome is RelayOutcome.Invalid)
                latch.countDown()
            }
            val call = peer.nextRequest()
            peer.deliver(
                "{${headers[0]},\"v\":1,\"id\":${call["id"]},\"result\":" +
                    """{"a":{"b":{"c":{"d":{"e":{"f":{"g":1}}}}}}}}"""
            )
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        }
    }
}
