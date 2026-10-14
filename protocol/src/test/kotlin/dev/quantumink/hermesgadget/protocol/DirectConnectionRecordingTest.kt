package dev.quantumink.hermesgadget.protocol

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectConnectionRecordingTest {
    @Test(timeout = 10000)
    fun acceptedAudioEndProducesOneSuccessButRejectedAudioEndProducesOneCancellation() {
        for (accept in listOf(false, true)) {
            Fixture(rejectType = if (accept) null else "audio.end").use { fixture ->
                val token = fixture.start()
                repeat(13) { fixture.connection.captured(token, ByteArray(640)) }
                fixture.connection.finishRecording(token)
                assertEquals(
                    ConversationEffect.RecordingOutcome(token, accept),
                    fixture.outcome()
                )
                assertTrue(fixture.socket.messages.contains("audio.end"))
                fixture.connection.close()
                assertTrue(fixture.outcomes.isEmpty())
            }
        }
    }

    @Test(timeout = 10000)
    fun autoLimitReportsSuccessAndShortReleaseReportsCancellation() {
        for (automatic in listOf(false, true)) {
            Fixture().use { fixture ->
                val token = fixture.start()
                if (automatic) {
                    repeat(13) { fixture.connection.captured(token, ByteArray(640)) }
                    // text() acts as an event-queue barrier before advancing the injected clock.
                    fixture.connection.text("").get(5, TimeUnit.SECONDS)
                    fixture.clock.set(60000)
                } else {
                    fixture.connection.captured(token, ByteArray(640))
                    fixture.connection.finishRecording(token)
                }
                assertEquals(
                    ConversationEffect.RecordingOutcome(token, automatic),
                    fixture.outcome()
                )
                assertTrue(
                    fixture.socket.messages.contains(if (automatic) "audio.end" else "audio.cancel")
                )
                fixture.connection.close()
                assertTrue(fixture.outcomes.isEmpty())
            }
        }
    }

    @Test(timeout = 10000)
    fun failedUplinkStartCloseAndDisconnectEachResolveTheActiveTokenOnce() {
        val scenarios: List<(Fixture, String) -> Unit> = listOf(
            { fixture, _ -> fixture.connection.close() },
            { fixture, _ -> fixture.connection.disconnect() },
            { fixture, _ -> fixture.connection.cancelRecording() },
            { fixture, token -> fixture.connection.captureFailed(token, "Microphone failed") },
            { fixture, token ->
                fixture.socket.rejectBinary = true
                fixture.connection.captured(token, ByteArray(640))
            }
        )
        for (terminal in scenarios) {
            Fixture().use { fixture ->
                val token = fixture.start()
                terminal(fixture, token)
                assertEquals(ConversationEffect.RecordingOutcome(token, false), fixture.outcome())
                fixture.connection.close()
                assertTrue(fixture.outcomes.isEmpty())
            }
        }
        Fixture(rejectType = "audio.start").use { fixture ->
            fixture.connection.startRecording()
            assertEquals(ConversationEffect.RecordingOutcome("a1", false), fixture.outcome())
            assertTrue(fixture.starts.isEmpty())
            fixture.connection.close()
            assertTrue(fixture.outcomes.isEmpty())
        }
    }

    @Test(timeout = 10000)
    fun closeBeforeCaptureEffectsDispatchStillResolvesTheReducerToken() {
        Fixture().use { fixture ->
            val conversationField = DirectConnection::class.java.getDeclaredField("conversation")
                .apply { isAccessible = true }
            val conversation = conversationField.get(fixture.connection) as Conversation
            val effects = conversation.startRecording(0)
            val token = effects.filterIsInstance<ConversationEffect.CaptureStart>().single().token
            fixture.connection.close()
            DirectConnection::class.java.getDeclaredMethod("effects", List::class.java)
                .apply { isAccessible = true }.invoke(fixture.connection, effects)
            assertEquals(ConversationEffect.RecordingOutcome(token, false), fixture.outcome())
            assertTrue(fixture.starts.isEmpty())
            assertTrue(fixture.outcomes.isEmpty())
        }
    }

    private class Fixture(rejectType: String? = null) : AutoCloseable {
        val starts = LinkedBlockingQueue<String>()
        val outcomes = LinkedBlockingQueue<ConversationEffect.RecordingOutcome>()
        val clock = AtomicLong()
        val socket = ControlledSocket(rejectType)
        val connection = DirectConnection(
            Endpoint.parse("wss://example.invalid/gadget"),
            DeviceIdentity.generate(),
            object : ConnectionObserver {
                override fun stateChanged(state: ConnectionState) = Unit
                override fun effect(effect: ConversationEffect, generation: Long) {
                    when (effect) {
                        is ConversationEffect.CaptureStart -> starts.add(effect.token)
                        is ConversationEffect.RecordingOutcome -> outcomes.add(effect)
                        else -> Unit
                    }
                }
            },
            now = clock::get
        )

        init {
            // Inject only the transport boundary; use the real handshake, reducer and event queue.
            val handshake = field("handshake").get(connection) as Handshake
            handshake.open(0)
            handshake.receive(
                Message.create(
                    "challenge",
                    buildJsonObject {
                        put("nonce", "AAAAAAAAAAAAAAAAAAAAAA==")
                        put("enrolled", false)
                    }
                ),
                0
            )
            handshake.receive(
                Message.create(
                    "welcome",
                    buildJsonObject {
                        put("proto", 1)
                        put("paired", true)
                        put("heartbeat_s", 3600)
                    }
                ),
                0
            )
            check(handshake.canSendConversation)
            (field("conversation").get(connection) as Conversation).setPaired(true)
            field("socket").set(connection, socket)
        }

        fun start(): String {
            connection.startRecording()
            return starts.poll(5, TimeUnit.SECONDS) ?: error("Capture did not start")
        }

        fun outcome(): ConversationEffect.RecordingOutcome = outcomes.poll(5, TimeUnit.SECONDS)
            ?: error("Recording outcome missing")

        private fun field(name: String) = DirectConnection::class.java.getDeclaredField(name)
            .apply { isAccessible = true }

        override fun close() = connection.close()
    }

    private class ControlledSocket(private val rejectType: String?) : WebSocket {
        val messages = LinkedBlockingQueue<String>()

        @Volatile var rejectBinary = false
        override fun request() = Request.Builder().url("https://example.invalid/gadget").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            val type = requireNotNull(Message.parse(text)).type
            messages.add(type)
            return type != rejectType
        }
        override fun send(bytes: ByteString) = !rejectBinary
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }
}
