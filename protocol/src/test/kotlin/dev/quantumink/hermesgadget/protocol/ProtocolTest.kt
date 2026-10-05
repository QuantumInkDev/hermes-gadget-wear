package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val nonce = "bm9uY2Utbm9uY2Utbm9uY2U="

    @Test
    fun upstreamIdentityAndAuthenticationVectors() {
        assertEquals("hg-630dcd2966c43366", identity.deviceId)
        assertEquals("AMUEF53Phk8+1vHw7R8PgiDwlPd8rXUx+cgyYv1YJX0=", identity.authMac(nonce))
        assertEquals(
            "ieymn+y1CMEJ5uX8yGz8VrXxT2KKjSOnzJC5qagTTwk=",
            identity.otaMac(
                "AAECAwQFBgcICQoLDA0ODw==",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                1234
            )
        )
    }

    @Test
    fun authenticationChangesWithKeyAndNonce() {
        assertNotEquals(identity.authMac(nonce), DeviceIdentity(ByteArray(32)).authMac(nonce))
        assertNotEquals(identity.authMac(nonce), identity.authMac("other"))
    }

    @Test
    fun keyDecodingRequiresExactly32BytesAndStrictBase64() {
        assertEquals(
            identity.deviceId,
            DeviceIdentity.fromBase64(identity.enrollmentKey())?.deviceId
        )
        assertNull(DeviceIdentity.fromBase64("not base64!"))
        assertNull(DeviceIdentity.fromBase64("AA=="))
        assertNull(DeviceIdentity.fromBase64(identity.enrollmentKey().trimEnd('=')))
    }

    @Test
    fun identityCopiesSecretAndRedactsItsString() {
        val key = ByteArray(32) { it.toByte() }
        val copy = DeviceIdentity(key)
        key.fill(0)
        assertEquals(identity.deviceId, copy.deviceId)
        assertFalse(copy.toString().contains(copy.enrollmentKey()))
        assertNotEquals(DeviceIdentity.generate().deviceId, DeviceIdentity.generate().deviceId)
    }

    @Test
    fun upstreamBinaryHeaderIsLittleEndian() {
        val frame = BinaryFrame(BinaryFrame.AUDIO, 7, 0x1234, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 7, 0x34, 0x12, 1, 2), frame.encode())
        val parsed = requireNotNull(BinaryFrame.parse(frame.encode()))
        assertEquals(7, parsed.stream)
        assertEquals(0x1234, parsed.sequence)
        assertArrayEquals(byteArrayOf(1, 2), parsed.payload)
    }

    @Test
    fun framingWrapsAndIgnoresUnknownChannels() {
        assertEquals(
            0,
            BinaryFrame.parse(BinaryFrame(3, 256, 65536, byteArrayOf()).encode())?.sequence
        )
        assertNull(BinaryFrame.parse(byteArrayOf(9, 0, 0, 0)))
        assertNull(BinaryFrame.parse(byteArrayOf(1)))
        assertNull(BinaryFrame.parse(ByteArray(BinaryFrame.MAX_FRAME_BYTES + 1)))
        assertEquals(4, BinaryFrame.parse(byteArrayOf(4, 0, 0, 0), setOf(4))?.channel)
    }

    @Test
    fun envelopesIgnoreUnknownFieldsAndRejectMalformedTypes() {
        val message =
            requireNotNull(Message.parse("""{"type":"future.feature","new":true,"text":"café"}"""))
        assertEquals("café", message.string("text"))
        assertTrue(message.boolean("new") == true)
        assertNull(Message.parse("[]"))
        assertNull(Message.parse("{"))
        assertNull(Message.parse("""{"type":1}"""))
        assertNull(Message.parse("""{"type":""}"""))
        assertNull(Message.parse("""{"type":"test","number":"7"}""")?.int("number"))
        assertFalse(message.toString().contains("café"))
    }

    @Test
    fun absentNegotiationFallsBackToPcmAndUnsolicitedCodecIsRejected() {
        assertEquals("pcm16", Protocol.selectedAudioFormat(null, setOf("pcm16", "opus")))
        assertEquals("opus", Protocol.selectedAudioFormat("opus", setOf("pcm16", "opus")))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Protocol.selectedAudioFormat("opus")
        }
    }

    @Test
    fun nestedAndOversizedInputsAreBoundedWithoutRejectingQuotedBrackets() {
        assertNull(
            Message.parse(
                "{\"type\":\"test\",\"nested\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
            )
        )
        assertNull(Message.parse("x".repeat(BinaryFrame.MAX_FRAME_BYTES + 1)))
        assertEquals("reply", Message.parse("""{"type":"reply","text":"[{\"x\"}]"}""")?.type)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            BinaryFrame(1, 0, 0, ByteArray(BinaryFrame.MAX_FRAME_BYTES)).encode()
        }
    }

    @Test
    fun enrollmentAndReconnectChooseDifferentAuthFields() {
        for (enrolled in listOf(false, true)) {
            val handshake = Handshake(identity)
            assertEquals("hello", handshake.open(0).type)
            val auth = handshake.receive(challenge(enrolled), 1).single()
            assertEquals("auth", auth.type)
            assertEquals(enrolled, auth.string("mac") != null)
            assertEquals(!enrolled, auth.string("key") != null)
            handshake.receive(welcome(false), 2)
            assertFalse(handshake.canSendConversation)
            handshake.receive(Message.create("paired"), 3)
            assertTrue(handshake.canSendConversation)
            handshake.receive(Message.create("unpaired"), 4)
            assertFalse(handshake.canSendConversation)
        }
    }

    @Test
    fun malformedOrOutOfOrderHandshakeFailsClosed() {
        val handshake = Handshake(identity)
        handshake.open(0)
        handshake.receive(welcome(true), 1)
        assertEquals(Handshake.State.FAILED, handshake.state)
        assertFalse(handshake.canSendConversation)
        handshake.open(2)
        handshake.receive(
            Message.create(
                "challenge",
                buildJsonObject {
                    put("enrolled", false)
                }
            ),
            3
        )
        assertEquals("invalid_challenge", handshake.failureCode)
    }

    @Test
    fun heartbeatEchoesTimestampAndCountsBinaryTraffic() {
        val handshake = online()
        val pong = handshake.receive(
            Message.create(
                "ping",
                buildJsonObject {
                    put("ts", 123)
                }
            ),
            10
        ).single()
        assertEquals("pong", pong.type)
        assertEquals(123, pong.int("ts"))
        handshake.recordInbound(59000)
        handshake.tick(60000)
        assertTrue(handshake.canSendConversation)
        handshake.tick(119000)
        assertEquals("heartbeat_timeout", handshake.failureCode)
    }

    @Test
    fun unknownMessagesDoNotBreakHandshakeAndTimeoutDoesNotAuthorize() {
        val handshake = Handshake(identity)
        handshake.open(0)
        assertTrue(handshake.receive(Message.create("extension"), 5).isEmpty())
        handshake.tick(10000)
        assertEquals("handshake_timeout", handshake.failureCode)
        handshake.receive(Message.create("paired"), 10001)
        assertFalse(handshake.canSendConversation)
    }

    private fun challenge(enrolled: Boolean): Message = Message.create(
        "challenge",
        buildJsonObject {
            put("nonce", nonce)
            put("enrolled", enrolled)
        }
    )

    private fun welcome(paired: Boolean): Message = Message.create(
        "welcome",
        buildJsonObject {
            put("proto", 1)
            put("paired", paired)
            put("heartbeat_s", 20)
        }
    )

    private fun online(): Handshake = Handshake(identity).also {
        it.open(0)
        it.receive(challenge(true), 1)
        it.receive(welcome(true), 2)
    }
}
