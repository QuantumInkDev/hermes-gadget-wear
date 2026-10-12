package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOutcomeTest {
    @Test
    fun shortReleaseReportsCancellationAndNeverFinishes() {
        val c = paired()
        val token = start(c)
        c.capture(token, ByteArray(640), 20)
        val effects = c.finishRecording(100, token)
        assertEquals(listOf("audio.cancel"), sends(effects))
        assertOutcome(effects, token, false)
        assertTrue(c.finishRecording(101, token).isEmpty())
        assertTrue(c.captureFailed(token, 102, "Stale failure").isEmpty())
        assertNoOutcome(c.disconnected())
    }

    @Test
    fun manualAndTimerLimitFinishesReportAcceptedAudioEndExactlyOnce() {
        for (automatic in listOf(false, true)) {
            val c = paired()
            val token = start(c)
            repeat(13) { c.capture(token, ByteArray(640), it * 20L) }
            val effects = if (automatic) c.tick(60000) else c.finishRecording(300, token)
            assertEquals(listOf("audio.end"), sends(effects))
            assertOutcome(effects, token, true)
            assertTrue(effects.contains(ConversationEffect.CaptureStop))
            assertTrue(c.tick(60001).isEmpty())
            assertTrue(c.finishRecording(60002, token).isEmpty())
            assertNoOutcome(c.disconnected())
        }
    }

    @Test
    fun shortTimerLimitStillReportsCancellation() {
        val c = paired()
        val token = start(c)
        val effects = c.tick(60000)
        assertEquals(listOf("audio.cancel"), sends(effects))
        assertOutcome(effects, token, false)
    }

    @Test
    fun byteLimitFinishesPcmAndOpusWithTheSameAcceptedOutcome() {
        for (opus in listOf(false, true)) {
            val c = Conversation(supportsOpus = opus).also { it.setPaired(true) }
            if (opus) {
                c.selectAudio(
                    Message.create(
                        "welcome",
                        buildJsonObject {
                            put("audio", buildJsonObject { put("mic", "opus") })
                        }
                    )
                )
            }
            val token = start(c)
            if (opus) {
                c.codecHeader(
                    token,
                    "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, -128, 62, 0, 0, 0, 0, 0),
                    0
                )
            }
            fun capture() = if (opus) {
                c.captureOpus(token, byteArrayOf(0xF8.toByte(), 0), 1)
            } else {
                c.capture(token, ByteArray(640), 1)
            }
            repeat(3000) { assertNoOutcome(capture()) }
            val effects = capture()
            assertEquals(listOf("audio.end"), sends(effects))
            assertOutcome(effects, token, true)
            assertTrue(capture().isEmpty())
        }
    }

    @Test
    fun cancelFailureDisconnectReplacementAndPromptProduceOneNegativeOutcome() {
        val terminals: List<(Conversation, String) -> List<ConversationEffect>> = listOf(
            { c, _ -> c.cancelRecording(10) },
            { c, _ -> c.cancel(10) },
            { c, token -> c.captureFailed(token, 10, "Microphone failed") },
            { c, token -> c.capture(token, byteArrayOf(1), 10) },
            { c, _ -> c.disconnected() },
            { c, _ -> c.setPaired(false) },
            { c, _ -> c.sendText("Replacement", 10) },
            { c, _ ->
                c.receive(
                    Message.create(
                        "prompt",
                        buildJsonObject {
                            put("id", "confirm")
                            put("title", "Confirm")
                            put("text", "Continue?")
                        }
                    ),
                    10
                )
            }
        )
        for (terminal in terminals) {
            val c = paired()
            val token = start(c)
            assertOutcome(terminal(c, token), token, false)
            assertTrue(c.finishRecording(11, token).isEmpty())
            assertTrue(c.captureFailed(token, 12, "Stale failure").isEmpty())
            assertNoOutcome(c.disconnected())
        }
    }

    @Test
    fun staleTokensAndWrongFormatsCannotResolveTheCurrentRecording() {
        val c = paired()
        val old = start(c)
        c.cancelRecording(1)
        val token = start(c)
        assertTrue(c.finishRecording(2, old).isEmpty())
        assertTrue(c.captureFailed(old, 3, "Stale failure").isEmpty())
        assertTrue(c.capture(old, ByteArray(640), 4).isEmpty())
        assertTrue(c.captureOpus(token, byteArrayOf(0xF8.toByte(), 0), 5).isEmpty())
        assertOutcome(c.cancelRecording(6), token, false)
    }

    private fun assertOutcome(effects: List<ConversationEffect>, token: String, finished: Boolean) {
        assertEquals(
            listOf(ConversationEffect.RecordingOutcome(token, finished)),
            effects.filterIsInstance<ConversationEffect.RecordingOutcome>()
        )
    }

    private fun assertNoOutcome(effects: List<ConversationEffect>) {
        assertTrue(effects.none { it is ConversationEffect.RecordingOutcome })
    }

    private fun paired() = Conversation().also { it.setPaired(true) }
    private fun start(c: Conversation) = c.startRecording(0)
        .filterIsInstance<ConversationEffect.CaptureStart>().single().token
    private fun sends(effects: List<ConversationEffect>) = effects
        .filterIsInstance<ConversationEffect.Send>().map { it.message.type }
}
