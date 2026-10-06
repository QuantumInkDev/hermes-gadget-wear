package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test
    fun pairingGatesConversationAndCumulativeDeltasFilterOldTurns() {
        val conversation = Conversation()
        assertTrue(conversation.sendText("hello", 0).isEmpty())
        conversation.receive(message("reply") { put("text", "unpaired") }, 0)
        assertEquals("", conversation.state.reply)
        conversation.setPaired(true)
        conversation.receive(message("turn.start") { put("turn", "current") }, 0)
        conversation.receive(reply("reply.delta", "Hello", "current"), 1)
        conversation.receive(reply("reply.delta", "Hello world", "current"), 2)
        assertEquals("Hello world", conversation.state.reply)
        conversation.receive(reply("reply", "stale", "old"), 3)
        assertEquals("Hello world", conversation.state.reply)
        conversation.receive(message("turn.end") { put("turn", "old") }, 4)
        assertTrue(conversation.busy)
        conversation.receive(message("turn.end") { put("turn", "current") }, 5)
        assertFalse(conversation.busy)
        conversation.setPaired(false)
        assertEquals("Hello world", conversation.state.reply)
        assertTrue(conversation.startRecording(6).isEmpty())
    }

    @Test
    fun promptGuardIsMonotonicExactlyOnceAndRepetitionDoesNotResetIt() {
        val c = paired()
        val prompt = message("prompt") {
            put("id", "q1")
            put("title", "Confirm")
            put("text", "Proceed?")
            put("ttl_s", 2)
        }
        c.receive(prompt, 100)
        assertTrue(c.answer("q1", true, 699).isEmpty())
        c.receive(prompt, 500)
        c.tick(700)
        assertTrue(c.state.canAnswer)
        val reply = sends(c.answer("q1", true, 700)).single()
        assertEquals("prompt.reply", reply.type)
        assertEquals("yes", reply.string("answer"))
        assertTrue(c.answer("q1", false, 701).isEmpty())
        c.receive(prompt, 702)
        assertNull(c.state.prompt)
    }

    @Test
    fun expiryWithdrawalAndImmediateExpiryCannotProduceAnAnswer() {
        val c = paired()
        fun prompt(id: String, ttl: Int) = message("prompt") {
            put("id", id)
            put("title", "Confirm")
            put("text", "Proceed?")
            put("ttl_s", ttl)
        }
        c.receive(prompt("expired", 1), 0)
        assertTrue(c.answer("expired", true, 1000).isEmpty())
        c.tick(1000)
        assertNull(c.state.prompt)
        c.receive(prompt("withdrawn", 10), 1000)
        c.receive(message("prompt.close") { put("id", "withdrawn") }, 1100)
        c.receive(prompt("withdrawn", 10), 1200)
        assertNull(c.state.prompt)
        c.receive(prompt("zero", 0), 1300)
        c.tick(1300)
        assertNull(c.state.prompt)
    }

    @Test
    fun shortCaptureIsDiscardedAndOldCallbacksCannotEnterTheNextRecording() {
        val c = paired()
        val first = c.startRecording(0).filterIsInstance<ConversationEffect.CaptureStart>().single()
        c.capture(first.token, ByteArray(640), 20)
        assertEquals("audio.cancel", sends(c.finishRecording(100)).single().type)
        val second = c.startRecording(200)
            .filterIsInstance<ConversationEffect.CaptureStart>().single()
        assertTrue(c.capture(first.token, ByteArray(640), 220).isEmpty())
        assertTrue(c.captureFailed(first.token, 220, "Stale capture failure").isEmpty())
        assertEquals(ConversationMode.LISTENING, c.state.mode)
        repeat(20) { c.capture(second.token, ByteArray(640), 220L + it * 20) }
        val end = sends(c.finishRecording(650)).single()
        assertEquals("audio.end", end.type)
        assertEquals(400, end.int("duration_ms"))
        assertEquals(ConversationMode.THINKING, c.state.mode)
        assertTrue(c.capture(second.token, ByteArray(640), 660).isEmpty())
    }

    @Test
    fun captureMaxDurationAndPromptInterruptionsStopTheMicrophone() {
        val c = paired()
        val token = c.startRecording(0)
            .filterIsInstance<ConversationEffect.CaptureStart>().single().token
        repeat(13) { c.capture(token, ByteArray(640), it * 20L) }
        assertTrue(c.tick(60000).contains(ConversationEffect.CaptureStop))
        c.startRecording(61000)
        val effects = c.receive(
            message("prompt") {
                put("id", "q2")
                put("title", "Approval")
                put("text", "Continue?")
            },
            61100
        )
        assertTrue(effects.contains(ConversationEffect.CaptureStop))
        assertTrue(sends(effects).any { it.type == "audio.cancel" })
        assertTrue(c.sendText("blocked", 62000).isEmpty())
        assertTrue(c.startRecording(62000).isEmpty())
    }

    @Test
    fun imageRequiresExactOrderedBytesAndDecodesLittleEndianRgb565() {
        val c = paired()
        val start = message("image.start") {
            put("stream", 7)
            put("width", 3)
            put("height", 1)
            put("format", "rgb565")
            put("ttl_s", 1)
        }
        val pixels = byteArrayOf(0, -8, -32, 7, 31, 0)
        c.receive(start, 0)
        c.binary(BinaryFrame(BinaryFrame.IMAGE, 8, 0, pixels), 1)
        c.binary(BinaryFrame(BinaryFrame.IMAGE, 7, 0, pixels), 2)
        c.receive(message("image.end") { put("stream", 7) }, 3)
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            requireNotNull(c.state.picture).argb()
        )
        c.tick(1000)
        assertNull(c.state.picture)
        c.receive(start, 2000)
        c.binary(BinaryFrame(BinaryFrame.IMAGE, 7, 1, pixels), 2001)
        c.receive(message("image.end") { put("stream", 7) }, 2002)
        assertNull(c.state.picture)
        c.receive(start, 3000)
        c.binary(BinaryFrame(BinaryFrame.IMAGE, 7, 0, pixels + byteArrayOf(0)), 3001)
        c.receive(message("image.end") { put("stream", 7) }, 3002)
        assertNull(c.state.picture)
    }

    @Test
    fun pcmPlaybackRejectsGapsUnsupportedRatesAndOddLengths() {
        val c = paired()
        fun start(rate: Int) = message("audio.start") {
            put("stream", 4)
            put("format", "pcm16")
            put("rate", rate)
        }
        assertEquals(
            listOf(ConversationEffect.PlaybackStart(16000)),
            c.receive(start(16000), 0)
        )
        val bytes = byteArrayOf(0, 1, 0, 2)
        val data = c.binary(BinaryFrame(BinaryFrame.AUDIO, 4, 0, bytes), 1)
            .filterIsInstance<ConversationEffect.PlaybackData>().single()
        assertArrayEquals(bytes, data.bytes)
        assertEquals(
            listOf(ConversationEffect.PlaybackStop),
            c.binary(BinaryFrame(BinaryFrame.AUDIO, 4, 2, bytes), 2)
        )
        assertTrue(c.binary(BinaryFrame(BinaryFrame.AUDIO, 4, 1, bytes), 3).isEmpty())
        assertEquals(listOf(ConversationEffect.PlaybackStop), c.receive(start(48000), 4))
        c.receive(start(16000), 5)
        assertEquals(
            listOf(ConversationEffect.PlaybackStop),
            c.binary(BinaryFrame(BinaryFrame.AUDIO, 4, 0, byteArrayOf(1)), 6)
        )
        assertEquals(
            listOf(ConversationEffect.PlaybackStart(16000)),
            c.receive(
                message("audio.start") {
                    put("stream", 5)
                    put("rate", 16000)
                },
                7
            )
        )
    }

    @Test
    fun cardsPersistentTtlAndDismissalAreBounded() {
        val c = paired()
        c.receive(
            message("display") {
                put("title", "Persistent")
                put("body", "Synthetic card")
                put("ttl_s", 0)
            },
            0
        )
        c.tick(100000)
        assertEquals("Persistent", c.state.card?.title)
        c.dismissDisplay()
        assertNull(c.state.card)
        c.receive(
            message("display") {
                put("title", "Temporary")
                put("body", "Synthetic card")
                put("ttl_s", 1)
            },
            100000
        )
        c.tick(101000)
        assertNull(c.state.card)
        c.receive(
            message("display") {
                put("title", "Invalid")
                put("body", "Synthetic card")
                put("ttl_s", -1)
            },
            102000
        )
        assertNull(c.state.card)
    }

    @Test
    fun actionsDeduplicateTimeoutOnceAndRejectUnadvertisedOrMalformedCalls() {
        val c = Conversation(setOf("watch.vibrate")).also { it.setPaired(true) }
        fun action(id: String, name: String, args: JsonObject = buildJsonObject {}) =
            message("action") {
                put("id", id)
                put("name", name)
                put("args", args)
            }
        assertTrue(
            c.receive(action("a1", "watch.vibrate"), 0).single() is ConversationEffect.Action
        )
        assertTrue(c.receive(action("a1", "watch.vibrate"), 1).isEmpty())
        val timeout = sends(c.tick(5000)).single()
        assertEquals("action.result", timeout.type)
        assertEquals(false, timeout.boolean("ok"))
        assertTrue(c.actionResult("a1", buildJsonObject { put("vibrated", true) }).isEmpty())
        assertTrue(c.tick(5001).isEmpty())
        assertEquals(
            false,
            sends(c.receive(action("a2", "timer.start"), 5002))
                .single().boolean("ok")
        )
        assertEquals(
            false,
            sends(
                c.receive(
                    action(
                        "a3",
                        "watch.vibrate",
                        buildJsonObject { put("duration_ms", 1001) }
                    ),
                    5003
                )
            ).single().boolean("ok")
        )
    }

    @Test
    fun validatorsBoundPlatformWorkAndManifestReflectsAvailability() {
        listOf(
            "timer.start" to buildJsonObject { put("seconds", 3601) },
            "screen.brightness" to buildJsonObject { put("level", 0.0) },
            "watch.vibrate" to buildJsonObject { put("duration_ms", "100") },
            "watch.vibrate" to buildJsonObject { put("unknown", true) },
            "notification.show" to buildJsonObject {
                put("title", "")
                put("text", "x")
            }
        ).forEach { (name, args) ->
            assertThrows(IllegalArgumentException::class.java) { WatchActions.validate(name, args) }
        }
        assertEquals(1, WatchActions.manifest(setOf("watch.vibrate")).size)
        assertEquals(0f, pcmLevel(ByteArray(640)), 0f)
        assertEquals(1f, pcmLevel(byteArrayOf(-1, 127)), 0f)
        assertEquals(0f, pcmLevel(byteArrayOf(1)), 0f)
    }

    private fun paired() = Conversation().also { it.setPaired(true) }
    private fun sends(effects: List<ConversationEffect>) =
        effects.filterIsInstance<ConversationEffect.Send>().map { it.message }
    private fun message(type: String, fields: JsonObjectBuilder.() -> Unit = {}) =
        Message.create(type, buildJsonObject(fields))
    private fun reply(type: String, text: String, turn: String) = message(type) {
        put("text", text)
        put("turn", turn)
    }
}
