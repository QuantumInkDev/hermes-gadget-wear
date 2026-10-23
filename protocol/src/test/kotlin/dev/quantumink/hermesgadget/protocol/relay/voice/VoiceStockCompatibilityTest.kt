package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.BinaryFrame
import dev.quantumink.hermesgadget.protocol.Protocol
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayNegotiation
import dev.quantumink.hermesgadget.protocol.relay.RelayVectors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stock controls: the voice path is opt-in, unknown voice names are ignored by a build that does not know them, and nothing stock moved. */
class VoiceStockCompatibilityTest {
    private val baseInfo: JsonObject by lazy {
        RelayVectors.forMethod(RelayMethod.INFO).first {
            it.isSuccess &&
                "server_time_ms" in it.result
        }.result
    }

    private fun infoListing(vararg extra: String): Info {
        val methods = baseInfo["methods"] as JsonArray
        return Info.decode(
            JsonObject(
                baseInfo + (
                    "methods" to JsonArray(
                        methods + extra.map {
                            JsonPrimitive(it)
                        }
                    )
                    )
            )
        )
    }

    @Test fun theVoiceMethodsAreCallableOnlyWhenRelayInfoListsThem() {
        val none = infoListing()
        for (method in RelayMethod.entries.filter { it.isVoice }) {
            assertEquals(
                method.toString(),
                RelayNegotiation.Gate.NOT_ADVERTISED,
                RelayNegotiation.gate(none, method)
            )
        }
        val all =
            infoListing(*RelayMethod.entries.filter { it.isVoice }.map { it.wire }.toTypedArray())
        for (method in RelayMethod.entries.filter { it.isVoice }) {
            assertEquals(
                method.toString(),
                RelayNegotiation.Gate.ALLOWED,
                RelayNegotiation.gate(all, method)
            )
        }
        assertTrue(all.unknownMethods.isEmpty())
        // a voice method listed alone does not unlock the others
        val onlyInfo = infoListing(RelayMethod.VOICE_INFO.wire)
        assertEquals(
            RelayNegotiation.Gate.ALLOWED,
            RelayNegotiation.gate(onlyInfo, VoiceCalls.info())
        )
        assertEquals(
            RelayNegotiation.Gate.NOT_ADVERTISED,
            RelayNegotiation.gate(onlyInfo, RelayMethod.VOICE_SPEAK)
        )
    }

    @Test fun anInfoThatListsAFutureVoiceNameKeepsItAndNeverMakesItCallable() {
        val info = infoListing(RelayMethod.VOICE_INFO.wire, "relay.voice.future.thing")
        assertEquals(listOf("relay.voice.future.thing"), info.unknownMethods)
        assertNull(RelayMethod.fromWire("relay.voice.future.thing"))
        assertTrue(info.advertises(RelayMethod.VOICE_INFO))
        // the unchanged Info rules still refuse malformed lists
        assertTrue(
            runCatching {
                infoListing(RelayMethod.VOICE_INFO.wire, RelayMethod.VOICE_INFO.wire)
            }.isFailure
        )
        assertTrue(runCatching { infoListing("Relay.Voice.Info") }.isFailure)
    }

    @Test fun channelFiveIsIgnoredByTheStockBinaryParserUnlessExplicitlyEnabled() {
        val raw = VoiceFrame.create(
            VoiceFrameKind.UPLINK_AUDIO,
            1,
            0,
            ByteArray(640) {
                3
            }
        )!!.encode()
        assertNull(BinaryFrame.parse(raw))
        assertNull(BinaryFrame.parse(raw, setOf(4)))
        val enabled = BinaryFrame.parse(raw, setOf(VoiceLimits.CHANNEL))
        assertNotNull(enabled)
        assertEquals(5, enabled!!.channel)
        // the voice parser, for its part, never claims a stock channel
        for (channel in 1..4) {
            val stock = byteArrayOf(channel.toByte(), 1, 0, 0, 2, 1, 2)
            assertEquals(VoiceParse.NotVoice, VoiceFrame.parse(stock, VoiceDirection.UPLINK))
        }
        assertEquals(
            VoiceParse.NotVoice,
            VoiceFrame.parse(byteArrayOf(5, 1, 0), VoiceDirection.UPLINK)
        )
        assertEquals(VoiceParse.NotVoice, VoiceFrame.parse(ByteArray(0), VoiceDirection.DOWNLINK))
    }

    @Test fun stockConstantsAndNegotiationDidNotMove() {
        assertEquals(1, Protocol.VERSION)
        assertEquals("hermes-gadget.v1", Protocol.SUBPROTOCOL)
        assertEquals(
            setOf("mic", "speaker", "display", "talk_mode"),
            Protocol.pcmCapabilities().keys
        )
        assertEquals(1 shl 20, BinaryFrame.MAX_FRAME_BYTES)
        assertEquals(1, BinaryFrame.AUDIO)
        assertEquals(
            JsonObject(mapOf("version" to JsonPrimitive(1))),
            RelayNegotiation.capability()
        )
        // the voice codec never added a capability: relay negotiation stays exactly {"version":1}
        assertFalse(RelayNegotiation.capability().toString().contains("voice"))
        assertEquals(22, RelayMethod.entries.count { !it.isVoice })
    }
}
