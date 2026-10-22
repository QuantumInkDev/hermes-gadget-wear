package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.Handshake
import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.Protocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in negotiation helpers, and the proof that the stock handshake/parser/capabilities did not move. */
class RelayNegotiationAndStockTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })

    private fun welcome(relay: String?): Message = Message.parse(
        "{\"type\":\"welcome\",\"proto\":1,\"heartbeat_s\":20,\"paired\":true" +
            (relay?.let { ",\"relay\":$it" } ?: "") +
            "}"
    )!!

    @Test fun theStockHelloAdvertisesNoRelayByDefault() {
        val hello = Protocol.hello(identity, "Pixel", "android", "0.1.0")
        assertEquals(JsonObject(emptyMap()), hello.field("caps"))
        assertFalse(hello.encode().contains("relay"))
        val handshake = Handshake(identity, name = "Pixel")
        val opened = handshake.open(1_000)
        assertEquals(JsonObject(emptyMap()), opened.field("caps"))
        assertEquals(
            setOf("mic", "speaker", "display", "talk_mode"),
            Protocol.pcmCapabilities().keys
        )
        assertEquals(1, Protocol.VERSION)
        assertEquals("hermes-gadget.v1", Protocol.SUBPROTOCOL)
        assertEquals("pcm16", Protocol.selectedAudioFormat(null))
    }

    @Test fun relayIsAdvertisedOnlyByTheExplicitOptInHelperAndKeepsOtherCapabilities() {
        val base = Protocol.pcmCapabilities()
        val with = RelayNegotiation.withRelay(base)
        assertEquals(buildJsonObject { put("version", 1) }, with["relay"])
        assertEquals(base.keys + "relay", with.keys)
        assertFalse("input is never mutated", "relay" in base)
        val hello = Protocol.hello(identity, "Pixel", "android", "0.1.0", with)
        assertEquals(
            JsonObject(mapOf("version" to JsonPrimitive(1))),
            (hello.field("caps") as JsonObject)["relay"]
        )
        try {
            RelayNegotiation.withRelay(with)
            throw AssertionError("a second advertisement was accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(RelayNegotiation.capability(), RelayNegotiation.capability())
    }

    @Test fun onlyAnExactVersionOneAnswerNegotiatesAndNothingElseIsDowngradedSilently() {
        assertEquals(RelayWelcome.NOT_OFFERED, RelayNegotiation.welcome(welcome(null)))
        assertEquals(RelayWelcome.NEGOTIATED, RelayNegotiation.welcome(welcome("{\"version\":1}")))
        val invalids = listOf(
            "{\"version\":2}", "{\"version\":true}", "{\"version\":\"1\"}", "{\"version\":1.0}",
            "{\"version\":1,\"x\":1}", "{}", "null", "\"yes\"", "[1]", "1"
        )
        for (invalid in invalids) {
            assertEquals(invalid, RelayWelcome.INVALID, RelayNegotiation.welcome(welcome(invalid)))
        }
        assertEquals(RelayWelcome.INVALID, RelayNegotiation.welcome(Message.create("paired")))
    }

    @Test fun onlyMethodsInfoListsMayBeCalled() {
        val full = Info.decode(
            RelayVectors.forMethod(RelayMethod.INFO).first {
                it.isSuccess &&
                    "outbox_payloads" in it.result["limits"].toString()
            }.result
        )
        val o5Only = Info.decode(
            RelayVectors.forMethod(RelayMethod.INFO).first {
                it.isSuccess &&
                    "server_time_ms" !in it.result
            }.result
        )
        assertEquals(RelayNegotiation.Gate.ALLOWED, RelayNegotiation.gate(full, RelayMethod.INFO))
        assertNotNull(full.limits.outboxPayloads)
        assertTrue(full.limits.hasConversationBounds && full.serverTimeMs != null)
        assertFalse(o5Only.limits.hasConversationBounds)
        // The O5-only form carries no server clock: deadlines need the full form.
        assertNull(o5Only.serverTimeMs)
        assertEquals(
            RelayNegotiation.Gate.NOT_ADVERTISED,
            RelayNegotiation.gate(o5Only, RelayMethod.CONVERSATION_ATTACH)
        )
        assertEquals(
            RelayNegotiation.Gate.ALLOWED,
            RelayNegotiation.gate(o5Only, RelayCalls.outboxRead())
        )
        for (method in RelayMethod.entries) {
            assertEquals(
                method in full.supported,
                RelayNegotiation.gate(full, method) == RelayNegotiation.Gate.ALLOWED
            )
        }
        assertTrue(full.unknownMethods.isEmpty())
    }

    @Test fun infoWithAnUnknownMethodKeepsItButNeverMakesItCallable() {
        val vector = RelayVectors.forMethod(RelayMethod.INFO).first {
            it.isSuccess &&
                "server_time_ms" in it.result
        }.result
        val methods = vector["methods"] as kotlinx.serialization.json.JsonArray
        val mutated = JsonObject(
            vector +
                (
                    "methods" to
                        kotlinx.serialization.json.JsonArray(
                            methods + JsonPrimitive("relay.future.thing")
                        )
                    )
        )
        val info = Info.decode(mutated)
        assertEquals(listOf("relay.future.thing"), info.unknownMethods)
        assertTrue(RelayMethod.entries.none { it.wire == "relay.future.thing" })
        for (bad in listOf(
            listOf("relay.conversation.attach"),
            listOf("relay.info", "relay.info"),
            emptyList(),
            listOf("Relay.Info")
        )) {
            val broken = JsonObject(
                vector +
                    ("methods" to kotlinx.serialization.json.JsonArray(bad.map(::JsonPrimitive)))
            )
            try {
                Info.decode(broken)
                throw AssertionError("accepted $bad")
            } catch (_: DecodeFailure) {
            }
        }
    }

    @Test fun theStockGenericParserKeepsItsDepthSixtyFourAndTheRelayParserIsASeparateObject() {
        // `containers` nested containers in total (the root object plus `containers - 1` arrays).
        fun nested(containers: Int) =
            "{\"type\":\"x\",\"a\":" + "[".repeat(containers - 1) + "]".repeat(containers - 1) + "}"
        assertNotNull(Message.parse(nested(64)))
        assertNull(Message.parse(nested(65)))
        // Relay allows a scalar at depth 8 only; the stock parser has never refused deeper and still does not.
        val nine = "{\"type\":\"x\",\"a\":" + "{\"k\":".repeat(7) + "1" + "}".repeat(7) + "}"
        assertNotNull(Message.parse(nine))
        assertTrue(RelayJson.parse(nine) is RelayParse.Rejected)
        // Stock accepts a duplicate key; Relay does not.
        assertNotNull(Message.parse("{\"type\":\"x\",\"a\":1,\"a\":2}"))
        assertTrue(RelayJson.parse("{\"type\":\"x\",\"a\":1,\"a\":2}") is RelayParse.Rejected)
        assertEquals(1, Message.parse("{\"type\":\"x\",\"n\":1}")!!.int("n"))
        // The stock frame cap is 1 MiB; Relay's is 65,536 UTF-8 bytes. 80,000 bytes pass stock and fail Relay.
        val big = "{\"type\":\"x\",\"s\":\"" + "é".repeat(40_000) + "\"}"
        assertNotNull(Message.parse(big))
        assertEquals(RelayRejection.OVERSIZE, (RelayJson.parse(big) as RelayParse.Rejected).reason)
    }

    @Test fun relayDtoTypesDoNotLeakIntoTheStockProtocolPackage() {
        val stockPackage = Protocol::class.java.`package`.name
        for (type in listOf(
            Info::class.java,
            RelayCalls::class.java,
            RelayEnvelope::class.java,
            RelayJson::class.java
        )) {
            assertEquals("$stockPackage.relay", type.`package`.name)
        }
        assertTrue(
            Class.forName(
                "dev.quantumink.hermesgadget.protocol.RelayProtocol"
            ).name.endsWith("RelayProtocol")
        )
    }
}
