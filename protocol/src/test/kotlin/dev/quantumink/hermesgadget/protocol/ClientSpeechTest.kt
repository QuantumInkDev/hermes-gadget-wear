package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSpeechTest {
    @Test fun nativeOggIsDemuxedIntoValidatedMonoPacketsAndFramedSpeech() {
        val output = ByteArrayOutputStream()
        var count = 0
        requireNotNull(javaClass.getResourceAsStream("/client-speech.ogg")).use { input ->
            OggOpus.read(input, { ClientSpeech.start(output, "opus", it) }, {
                ClientSpeech.packet(output, it)
                count++
            })
        }
        ClientSpeech.finish(output)
        assertEquals(31, count)
        var heard = 0
        ClientSpeech.receive(ByteArrayInputStream(output.toByteArray()), "opus", {
            assertEquals(19, it?.size)
        }, { heard++ })
        assertEquals(count, heard)
        assertFalse(output.toByteArray().copyOfRange(4, 8).contentEquals("OggS".toByteArray()))
    }

    @Test fun rejectsCrcCorruptionTruncationOversizedHeadersAndBadRequests() {
        val data = requireNotNull(javaClass.getResourceAsStream("/client-speech.ogg")).readBytes()
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            OggOpus.read(ByteArrayInputStream(data), {}, {})
        }
        assertThrows(java.io.EOFException::class.java) {
            OggOpus.read(ByteArrayInputStream(data.copyOf(30)), {}, {})
        }
        val oversized = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(32769) }
        assertThrows(IllegalArgumentException::class.java) {
            ClientSpeech.readMessage(ByteArrayInputStream(oversized.toByteArray()))
        }
        val endpoint = ClientSpeech.endpointId(Endpoint.parse("wss://example.invalid/gadget"))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRequest(endpoint, "request", "Text", "bad/path", "opus")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRequest(endpoint, "request", "Text", "", "other")
        }
        val receiver = Conversation()
        receiver.setPaired(true)
        receiver.receive(requireNotNull(Message.parse("""{"type":"turn.start","turn":"old"}""")), 0)
        val late = requireNotNull(Message.parse("""{"type":"tts.speak","turn":"old"}"""))
        assertTrue(receiver.acceptsSpeech(late))
        receiver.cancel(1)
        assertFalse(receiver.acceptsSpeech(late))
    }

    @Test fun pcmWireAndRequestRoundTripKeepCredentialsOutOfTheContract() {
        val endpoint = ClientSpeech.endpointId(Endpoint.parse("wss://example.invalid/gadget"))
        val request =
            SpeechRequest(
                endpoint,
                "original-id",
                "Original synthetic reply",
                "syntheticVoice",
                "pcm16"
            )
        val encoded = ByteArrayOutputStream()
        ClientSpeech.request(encoded, request)
        val decoded = ClientSpeech.request(ByteArrayInputStream(encoded.toByteArray()))
        assertEquals(request.text, decoded.text)
        assertEquals(request.endpointId, decoded.endpointId)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("api_key"))
        val audio = ByteArrayOutputStream()
        ClientSpeech.start(audio, "pcm16")
        val pcm = ByteArray(640) { it.toByte() }
        ClientSpeech.packet(audio, pcm)
        ClientSpeech.finish(audio)
        ClientSpeech.receive(ByteArrayInputStream(audio.toByteArray()), "pcm16", {
            assertEquals(null, it)
        }, { assertArrayEquals(pcm, it) })
        assertThrows(java.io.EOFException::class.java) {
            ClientSpeech.receive(
                ByteArrayInputStream(
                    audio.toByteArray().dropLast(1).toByteArray()
                ),
                "pcm16",
                {
                },
                {}
            )
        }
    }
}
