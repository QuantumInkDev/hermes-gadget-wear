package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class ConversationMode { READY, LISTENING, THINKING, REPLY }

class Prompt(
    val id: String,
    val title: String,
    val text: String,
    val availableAt: Long,
    val expiresAt: Long?
) {
    override fun toString(): String = "Prompt(content=redacted)"
}

class Card(val title: String, val body: String, val expiresAt: Long?) {
    override fun toString(): String = "Card(content=redacted)"
}

class Picture(val width: Int, val height: Int, pixels: ByteArray, val expiresAt: Long?) {
    private val pixels = pixels.copyOf()
    init {
        require(width in 1..320 && height in 1..320 && pixels.size == width * height * 2)
    }

    fun argb(): IntArray = IntArray(width * height) { index ->
        val value = (pixels[index * 2].toInt() and 255) or
            ((pixels[index * 2 + 1].toInt() and 255) shl 8)
        val red = (value ushr 11) and 31
        val green = (value ushr 5) and 63
        val blue = value and 31
        -0x1000000 or ((red * 255 / 31) shl 16) or ((green * 255 / 63) shl 8) or (blue * 255 / 31)
    }

    override fun toString(): String = "Picture(pixels=redacted)"
}

data class ConversationState(
    val mode: ConversationMode = ConversationMode.READY,
    val reply: String = "",
    val transcript: String = "",
    val status: String = "",
    val notice: String = "",
    val prompt: Prompt? = null,
    val canAnswer: Boolean = false,
    val card: Card? = null,
    val picture: Picture? = null,
    val level: Float = 0f
) {
    override fun toString(): String = "ConversationState(mode=" + mode + ", content=redacted)"
}

sealed interface ConversationEffect {
    data class Send(val message: Message) : ConversationEffect
    data class SendBinary(val frame: BinaryFrame) : ConversationEffect
    data class CaptureStart(val token: String) : ConversationEffect
    data object CaptureStop : ConversationEffect
    data class PlaybackStart(val rate: Int) : ConversationEffect
    class PlaybackData(bytes: ByteArray) : ConversationEffect {
        val bytes = bytes.copyOf()
        override fun toString(): String = "PlaybackData(redacted)"
    }
    data object PlaybackFinish : ConversationEffect
    data object PlaybackStop : ConversationEffect
    data class Action(val id: String, val action: WatchAction) : ConversationEffect
    enum class Haptic : ConversationEffect { LISTEN_START, LISTEN_END, REPLY, PROMPT }
}

/** One ordered event stream. All deadlines are monotonic milliseconds. No Android or disk state. */
class Conversation(private val availableActions: Set<String> = WatchActions.names) {
    val busy: Boolean
        get() = upload != null || turn != null || state.mode == ConversationMode.THINKING ||
            state.prompt != null
    var state = ConversationState()
        private set
    private var paired = false
    private var turn: String? = null
    private var inputCounter = 0L
    private var upload: Upload? = null
    private var playback: Stream? = null
    private var image: ImageTransfer? = null
    private var noticeExpiresAt: Long? = null
    private val answered = linkedSetOf<String>()
    private val actionsSeen = linkedSetOf<String>()
    private val actionsPending = linkedMapOf<String, Long>()

    private class Upload(val token: String, val stream: Int, val started: Long) {
        var sequence = 0
        var bytes = 0
        var meterAt = 0L
    }
    private class Stream(val id: Int, var sequence: Int = 0)
    private class ImageTransfer(
        val stream: Stream,
        val width: Int,
        val height: Int,
        val expiresAt: Long?
    ) {
        val bytes = ByteArrayOutputStream(width * height * 2)
        val length: Int get() = width * height * 2
    }

    fun setPaired(value: Boolean): List<ConversationEffect> {
        paired = value
        return if (value) emptyList() else disconnected()
    }

    fun disconnected(): List<ConversationEffect> {
        paired = false
        turn = null
        upload = null
        playback = null
        image = null
        actionsPending.clear()
        state = state.copy(
            mode = if (state.reply.isEmpty()) ConversationMode.READY else ConversationMode.REPLY,
            status = "",
            prompt = null,
            canAnswer = false,
            card = null,
            picture = null,
            level = 0f
        )
        return listOf(ConversationEffect.CaptureStop, ConversationEffect.PlaybackStop)
    }

    fun receive(message: Message, now: Long): List<ConversationEffect> {
        if (!paired) return emptyList()
        return try {
            when (message.type) {
                "turn.start" -> {
                    turn = text(message, "turn", 128)
                    if (upload == null) {
                        state =
                            state.copy(
                                mode = ConversationMode.THINKING,
                                status = "",
                                transcript = ""
                            )
                    }
                    emptyList()
                }
                "reply.delta", "reply" -> {
                    if (!matchesTurn(message) || upload != null) return emptyList()
                    val reply = text(message, "text", 32768)
                    if (message.boolean("interim") == true) {
                        state = state.copy(status = reply)
                        emptyList()
                    } else {
                        state = state.copy(reply = reply, mode = ConversationMode.REPLY)
                        if (message.type ==
                            "reply"
                        ) {
                            listOf(ConversationEffect.Haptic.REPLY)
                        } else {
                            emptyList()
                        }
                    }
                }
                "turn.end" -> {
                    if (message.string("turn") == null || !matchesTurn(message)) return emptyList()
                    turn = null
                    if (upload == null) {
                        state = state.copy(
                            mode = if (state.reply.isEmpty()) {
                                ConversationMode.READY
                            } else {
                                ConversationMode.REPLY
                            },
                            status = ""
                        )
                    }
                    if (message.string("outcome") == "failure") notice("The turn failed.", now)
                    emptyList()
                }
                "transcript", "status" -> {
                    if (upload == null) {
                        val value = text(message, "text", 8192)
                        state =
                            if (message.type ==
                                "transcript"
                            ) {
                                state.copy(transcript = value)
                            } else {
                                state.copy(status = value)
                            }
                    }
                    emptyList()
                }
                "notice" -> {
                    notice(text(message, "text", 4096), now, deadline(message, now, 8.0, false))
                    emptyList()
                }
                "prompt" -> showPrompt(message, now)
                "prompt.close" -> {
                    message.string("id")?.let { remember(answered, it) }
                    if (state.prompt?.id ==
                        message.string("id")
                    ) {
                        state = state.copy(prompt = null, canAnswer = false)
                    }
                    emptyList()
                }
                "display" -> {
                    state = state.copy(
                        card = Card(
                            text(message, "title", 256),
                            text(message, "body", 8192),
                            deadline(message, now, 15.0, true)
                        )
                    )
                    emptyList()
                }
                "image.start" -> {
                    image = null
                    val width = message.int("width")
                    val height = message.int("height")
                    require(width != null && width in 1..320 && height != null && height in 1..320)
                    require(message.string("format") == "rgb565")
                    image =
                        ImageTransfer(
                            Stream(stream(message)),
                            width,
                            height,
                            deadline(message, now, 30.0, true)
                        )
                    emptyList()
                }
                "image.end" -> {
                    val transfer = image ?: return emptyList()
                    if (message.int("stream") != transfer.stream.id) return emptyList()
                    image = null
                    if (transfer.bytes.size() == transfer.length) {
                        state = state.copy(
                            picture = Picture(
                                transfer.width,
                                transfer.height,
                                transfer.bytes.toByteArray(),
                                transfer.expiresAt
                            ),
                            card = null
                        )
                    } else {
                        notice("Image interrupted.", now)
                    }
                    emptyList()
                }
                "audio.start" -> {
                    playback = null
                    require(
                        message.int("rate") == 16000 &&
                            (message.field("format") == null || message.string("format") == "pcm16")
                    )
                    if (!matchesTurn(message) || upload != null || state.prompt != null) {
                        listOf(ConversationEffect.PlaybackStop)
                    } else {
                        playback = Stream(stream(message))
                        listOf(ConversationEffect.PlaybackStart(16000))
                    }
                }
                "audio.end", "audio.abort" -> {
                    if (playback?.id != message.int("stream")) return emptyList()
                    playback = null
                    if (message.type ==
                        "audio.end"
                    ) {
                        listOf(ConversationEffect.PlaybackFinish)
                    } else {
                        listOf(ConversationEffect.PlaybackStop)
                    }
                }
                "action" -> action(message, now)
                else -> emptyList()
            }
        } catch (_: IllegalArgumentException) {
            notice("Unsupported or malformed server message.", now)
            if (message.type ==
                "audio.start"
            ) {
                listOf(ConversationEffect.PlaybackStop)
            } else {
                emptyList()
            }
        }
    }

    fun binary(frame: BinaryFrame, now: Long): List<ConversationEffect> {
        if (!paired) return emptyList()
        val payload = frame.payload
        if (frame.channel == BinaryFrame.AUDIO) {
            val output = playback ?: return emptyList()
            if (frame.stream != output.id) return emptyList()
            if (frame.sequence != output.sequence || payload.isEmpty() || payload.size > 32000 ||
                payload.size % 2 != 0
            ) {
                playback = null
                notice("Audio interrupted.", now)
                return listOf(ConversationEffect.PlaybackStop)
            }
            output.sequence = (output.sequence + 1) and 65535
            return listOf(ConversationEffect.PlaybackData(payload))
        }
        if (frame.channel == BinaryFrame.IMAGE) {
            val transfer = image ?: return emptyList()
            if (frame.stream != transfer.stream.id) return emptyList()
            if (frame.sequence != transfer.stream.sequence ||
                transfer.bytes.size() + payload.size > transfer.length
            ) {
                image = null
                notice("Image interrupted.", now)
            } else {
                transfer.bytes.write(payload)
                transfer.stream.sequence = (transfer.stream.sequence + 1) and 65535
            }
        }
        return emptyList()
    }

    fun sendText(value: String, now: Long): List<ConversationEffect> {
        if (!canInput()) return emptyList()
        val text = value.trim()
        if (text.isEmpty() || text.toByteArray().size > 8192) {
            notice("Enter a shorter message.", now)
            return emptyList()
        }
        val effects = cancelUpload("replaced") + listOf(ConversationEffect.PlaybackStop)
        turn = null
        playback = null
        state = state.copy(mode = ConversationMode.THINKING, status = "", transcript = "")
        return effects +
            listOf(
                send("cancel"),
                send("text") {
                    put("id", "t" + ++inputCounter)
                    put("text", text)
                }
            )
    }

    fun startRecording(now: Long): List<ConversationEffect> {
        if (!canInput() || upload != null) return emptyList()
        val token = "a" + ++inputCounter
        val stream = (inputCounter % 250 + 1).toInt()
        upload = Upload(token, stream, now)
        playback = null
        turn = null
        state =
            state.copy(mode = ConversationMode.LISTENING, level = 0f, status = "", transcript = "")
        return listOf(
            ConversationEffect.PlaybackStop,
            send("cancel"),
            send("audio.start") {
                put("id", token)
                put("stream", stream)
                put("rate", 16000)
                put("format", "pcm16")
                put("mode", "hold")
            },
            ConversationEffect.CaptureStart(token),
            ConversationEffect.Haptic.LISTEN_START
        )
    }

    fun capture(token: String, bytes: ByteArray, now: Long): List<ConversationEffect> {
        val input = upload ?: return emptyList()
        if (input.token != token) return emptyList()
        if (bytes.size !in 2..640 ||
            bytes.size % 2 != 0
        ) {
            return cancelRecording(now, "Microphone interrupted.")
        }
        if (input.bytes + bytes.size > 16000 * 2 * 60) return finishRecording(now)
        input.bytes += bytes.size
        if (now - input.meterAt >= 100) {
            input.meterAt = now
            state = state.copy(level = pcmLevel(bytes))
        }
        val frame = BinaryFrame(BinaryFrame.AUDIO, input.stream, input.sequence++, bytes)
        return listOf(ConversationEffect.SendBinary(frame))
    }

    fun finishRecording(now: Long): List<ConversationEffect> {
        val input = upload ?: return emptyList()
        if (input.bytes < 8000) return cancelRecording(now, "Recording too short.")
        upload = null
        state = state.copy(mode = ConversationMode.THINKING, level = 0f)
        return listOf(
            ConversationEffect.CaptureStop,
            send("audio.end") {
                put("id", input.token)
                put("stream", input.stream)
                put(
                    "duration_ms",
                    input.bytes / 32
                )
            },
            ConversationEffect.Haptic.LISTEN_END
        )
    }

    fun cancelRecording(
        now: Long,
        reason: String = "Recording discarded."
    ): List<ConversationEffect> {
        if (upload == null) return emptyList()
        val effects = cancelUpload("cancelled")
        state = state.copy(mode = ConversationMode.READY, level = 0f)
        notice(reason, now)
        return effects + ConversationEffect.Haptic.LISTEN_END
    }

    fun captureFailed(token: String, now: Long, reason: String): List<ConversationEffect> =
        if (upload?.token == token) cancelRecording(now, reason) else emptyList()

    fun cancel(now: Long, newSession: Boolean = false): List<ConversationEffect> {
        if (!canInput()) return emptyList()
        val effects = cancelRecording(now)
        turn = null
        playback = null
        state = state.copy(mode = ConversationMode.READY, status = "")
        return effects +
            listOf(
                ConversationEffect.PlaybackStop,
                send(if (newSession) "session.new" else "cancel")
            )
    }

    fun answer(id: String, yes: Boolean, now: Long): List<ConversationEffect> {
        val prompt = state.prompt ?: return emptyList()
        if (!paired || prompt.id != id || id in answered || now < prompt.availableAt ||
            (prompt.expiresAt != null && now >= prompt.expiresAt)
        ) {
            return emptyList()
        }
        remember(answered, id)
        state = state.copy(prompt = null, canAnswer = false)
        return listOf(
            send("prompt.reply") {
                put("id", id)
                put("answer", if (yes) "yes" else "no")
            }
        )
    }

    fun actionResult(
        id: String,
        result: JsonObject?,
        error: String? = null
    ): List<ConversationEffect> {
        if (actionsPending.remove(id) == null || !paired) return emptyList()
        return listOf(actionReply(id, result, error))
    }

    fun dismissDisplay() {
        state = state.copy(card = null, picture = null)
    }

    fun report(text: String, now: Long) {
        notice(text, now)
    }

    fun tick(now: Long): List<ConversationEffect> {
        val effects = mutableListOf<ConversationEffect>()
        val prompt = state.prompt
        if (prompt != null) {
            state = if (prompt.expiresAt != null && now >= prompt.expiresAt) {
                remember(answered, prompt.id)
                state.copy(prompt = null, canAnswer = false)
            } else {
                state.copy(canAnswer = now >= prompt.availableAt)
            }
        }
        if (noticeExpiresAt?.let { now >= it } == true) {
            state = state.copy(notice = "")
            noticeExpiresAt = null
        }
        if (state.card?.expiresAt?.let { now >= it } == true) state = state.copy(card = null)
        if (state.picture?.expiresAt?.let { now >= it } == true) state = state.copy(picture = null)
        if (image?.expiresAt?.let { now >= it } == true) image = null
        if (upload?.let { now - it.started >= 60000 } == true) effects += finishRecording(now)
        for (id in actionsPending.filterValues { now >= it }.keys.toList()) {
            actionsPending.remove(id)
            effects += actionReply(id, null, "Device action timed out.")
        }
        return effects
    }

    private fun showPrompt(message: Message, now: Long): List<ConversationEffect> {
        val id = text(message, "id", 128)
        if (id in answered || state.prompt?.id == id) return emptyList()
        val title = text(message, "title", 256)
        val body = text(message, "text", 8192)
        val expiry = deadline(message, now, null, false)
        val effects =
            cancelUpload("prompt") +
                listOf(ConversationEffect.PlaybackStop, ConversationEffect.Haptic.PROMPT)
        playback = null
        state = state.copy(
            prompt = Prompt(id, title, body, now + 600, expiry),
            canAnswer = false,
            level = 0f,
            mode = if (state.mode ==
                ConversationMode.LISTENING
            ) {
                ConversationMode.READY
            } else {
                state.mode
            }
        )
        return effects
    }

    private fun action(message: Message, now: Long): List<ConversationEffect> {
        val id = text(message, "id", 128)
        if (id in actionsSeen) return emptyList()
        remember(actionsSeen, id)
        return try {
            val name = text(message, "name", 64)
            require(name in availableActions) { "Device action is unavailable." }
            val args =
                message.field("args") as? JsonObject
                    ?: throw IllegalArgumentException("Expected action parameters.")
            val action = WatchActions.validate(name, args)
            require(actionsPending.size < 8) { "Too many pending device actions." }
            actionsPending[id] = now + 5000
            listOf(ConversationEffect.Action(id, action))
        } catch (failure: IllegalArgumentException) {
            listOf(actionReply(id, null, failure.message ?: "Invalid action."))
        }
    }

    private fun actionReply(id: String, result: JsonObject?, error: String?) =
        send("action.result") {
            put("id", id)
            put("ok", result != null)
            if (result !=
                null
            ) {
                put("result", result)
            } else {
                put("error", error ?: "Device action failed.")
            }
        }

    private fun cancelUpload(reason: String): List<ConversationEffect> {
        val input = upload ?: return emptyList()
        upload = null
        return listOf(
            ConversationEffect.CaptureStop,
            send("audio.cancel") {
                put("id", input.token)
                put("stream", input.stream)
                put("reason", reason)
            }
        )
    }

    private fun canInput(): Boolean = paired && state.prompt == null
    private fun matchesTurn(message: Message): Boolean =
        message.string("turn")?.let { it == turn } ?: true
    private fun send(
        type: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}
    ) = ConversationEffect.Send(Message.create(type, buildJsonObject(fields)))
    private fun text(message: Message, key: String, max: Int): String {
        val value = message.string(key)
        require(
            value != null && value.length <= max &&
                (key !in setOf("id", "turn") || value.isNotBlank())
        )
        return value
    }
    private fun stream(message: Message): Int =
        requireNotNull(message.int("stream")).also { require(it in 0..255) }
    private fun notice(text: String, now: Long, expiry: Long? = now + 8000) {
        state = state.copy(notice = text)
        noticeExpiresAt = expiry
    }
    private fun remember(set: MutableSet<String>, id: String) {
        set += id
        if (set.size > 128) set.remove(set.first())
    }
    private fun deadline(
        message: Message,
        now: Long,
        default: Double?,
        persistent: Boolean
    ): Long? {
        val field = message.field("ttl_s")
        val seconds = if (field ==
            null
        ) {
            default
        } else {
            (field as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        }
        if (field == null && seconds == null) return null
        require(seconds != null && seconds.isFinite() && seconds in 0.0..3600.0)
        return if (persistent && seconds == 0.0) null else now + (seconds * 1000).toLong()
    }
}

fun pcmLevel(bytes: ByteArray): Float {
    if (bytes.isEmpty() || bytes.size % 2 != 0) return 0f
    var squares = 0.0
    for (index in bytes.indices step 2) {
        val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8))
            .toShort().toDouble()
        squares += sample * sample
    }
    return (sqrt(squares / (bytes.size / 2)) / 8192).toFloat().coerceIn(0f, 1f)
}
