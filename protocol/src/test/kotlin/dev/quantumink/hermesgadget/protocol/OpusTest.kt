package dev.quantumink.hermesgadget.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusTest {
    private val header = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val packet = byteArrayOf(0xb8.toByte(), 0, 1)
    private fun negotiated() = Message.create(
        "welcome",
        buildJsonObject {
            put(
                "audio",
                buildJsonObject {
                    put("mic", "opus")
                    put("speaker", "opus")
                }
            )
        }
    )

    @Test fun unwrapsPlatformCsdWithoutSendingContainerAsPacket() {
        val csd =
            "AOPUSHDR".toByteArray() +
                ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(19).array() +
                header
        assertEquals(header.toList(), OpusConfiguration.header(csd).toList())
        assertThrows(IllegalArgumentException::class.java) {
            OpusConfiguration.header(csd.dropLast(1).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { OpusConfiguration.header(header + 0) }
        OpusConfiguration.require20ms(packet)
        assertThrows(IllegalArgumentException::class.java) {
            OpusConfiguration.require20ms(byteArrayOf(0))
        }
    }

    @Test fun stockWelcomeUsesPcmAndUnadvertisedOpusIsRejected() {
        val stock = Conversation(supportsOpus = true)
        stock.selectAudio(Message.create("welcome"))
        stock.setPaired(true)
        assertTrue(
            stock.startRecording(
                0
            ).filterIsInstance<ConversationEffect.CaptureStart>().single().format ==
                "pcm16"
        )
        assertThrows(IllegalArgumentException::class.java) {
            Conversation().selectAudio(negotiated())
        }
    }

    @Test fun headerPrecedesPacketsAndLateCaptureCannotAffectNewRecording() {
        val conversation = Conversation(supportsOpus = true)
        conversation.selectAudio(negotiated())
        conversation.setPaired(true)
        val start = conversation.startRecording(0)
        val capture = start.filterIsInstance<ConversationEffect.CaptureStart>().single()
        assertEquals("opus", capture.format)
        assertEquals(
            20,
            start.filterIsInstance<ConversationEffect.Send>().single {
                it.message.type ==
                    "audio.start"
            }.message.int("frame_ms")
        )
        assertEquals(
            "audio.codec",
            conversation.codecHeader(
                capture.token,
                header,
                1
            ).filterIsInstance<ConversationEffect.Send>().single().message.type
        )
        repeat(15) {
            assertTrue(
                conversation.captureOpus(
                    capture.token,
                    packet,
                    it * 20L
                ).single() is ConversationEffect.SendBinary
            )
        }
        assertTrue(
            conversation.finishRecording(400, capture.token).any {
                it is ConversationEffect.CaptureStop
            }
        )
        conversation.setPaired(false)
        conversation.setPaired(true)
        conversation.startRecording(500)
        assertTrue(conversation.finishRecording(600, capture.token).isEmpty())
    }

    @Test fun negotiatedOddSizedPacketPlaysAndWrongDurationStops() {
        val conversation = Conversation(supportsOpus = true)
        conversation.selectAudio(negotiated())
        conversation.setPaired(true)
        val effects = conversation.receive(
            Message.create(
                "audio.start",
                buildJsonObject {
                    put("stream", 1)
                    put("format", "opus")
                    put("rate", 16000)
                    put("frame_ms", 20)
                    put("opus_header", Base64.getEncoder().encodeToString(header))
                }
            ),
            0
        )
        assertTrue(effects.single() is ConversationEffect.PlaybackStart)
        assertTrue(
            conversation.binary(
                BinaryFrame(1, 1, 0, packet),
                1
            ).single() is ConversationEffect.PlaybackData
        )
        assertFalse(conversation.binary(BinaryFrame(1, 1, 1, byteArrayOf(0)), 2).isEmpty())
    }
}
