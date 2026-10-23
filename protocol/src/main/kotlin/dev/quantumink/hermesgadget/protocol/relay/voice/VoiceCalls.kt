package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.BindingId
import dev.quantumink.hermesgadget.protocol.relay.BindingRef
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.DecodeFailure
import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a `relay.voice.speak` answer is voiced: by the server (the audio streams back on channel 5) or by the client (exact chunks come back as text). */
sealed interface SpeechDelivery {
    /** Server synthesis; [format] must be one the server advertised in `output.formats` and this client can decode. */
    class Server(val format: CaptureFormat) : SpeechDelivery {
        override fun toString(): String = "Server(${format.wire})"
    }

    data object Client : SpeechDelivery
}

/**
 * One `relay.voice.speak` request (design §6.4 combination table). Each subtype fixes the members the table forces, so an illegal mix cannot be
 * built: `live` is automatic, author-only and offset 0; `replay` is deliberate and offset 0; `more` is deliberate and continues an issued boundary
 * of one exact source.
 */
sealed class SpeechRequest(
    val speechId: SpeechId,
    val style: SpeechStyle,
    val delivery: SpeechDelivery
) {
    abstract val mode: SpeechMode

    internal abstract val requestId: RequestId?
    internal abstract val messageId: MessageId?
    internal abstract val digest: SourceDigest?
    internal abstract val startCp: Int

    class Live(
        speechId: SpeechId,
        /** The author device's own durable turn id. */
        val request: RequestId,
        /** Optional expected canonical id of that turn's final. */
        val expectedMessage: MessageId?,
        style: SpeechStyle,
        delivery: SpeechDelivery
    ) : SpeechRequest(speechId, style, delivery) {
        override val mode: SpeechMode get() = SpeechMode.LIVE
        override val requestId: RequestId get() = request
        override val messageId: MessageId? get() = expectedMessage
        override val digest: SourceDigest? get() = null
        override val startCp: Int get() = 0
    }

    class Replay(
        speechId: SpeechId,
        val message: MessageId,
        style: SpeechStyle,
        delivery: SpeechDelivery
    ) : SpeechRequest(speechId, style, delivery) {
        override val mode: SpeechMode get() = SpeechMode.REPLAY
        override val requestId: RequestId? get() = null
        override val messageId: MessageId get() = message
        override val digest: SourceDigest? get() = null
        override val startCp: Int get() = 0
    }

    class More(
        speechId: SpeechId,
        val message: MessageId,
        val source: SourceDigest,
        /** An issued boundary of this exact source (the `start_cp` or `end_cp` of a previously issued range). */
        val start: Int,
        style: SpeechStyle,
        delivery: SpeechDelivery
    ) : SpeechRequest(speechId, style, delivery) {
        init {
            require(start in 0..Int.MAX_VALUE) { "start_cp" }
        }

        override val mode: SpeechMode get() = SpeechMode.MORE
        override val requestId: RequestId? get() = null
        override val messageId: MessageId get() = message
        override val digest: SourceDigest get() = source
        override val startCp: Int get() = start
    }

    override fun toString(): String = "SpeechRequest(${mode.wire}, speech=${speechId.value})"
}

/**
 * Typed requests for the seven canonical-voice methods with the exact parameter sets of the admitted SDK (`relay_voice_wire.*_params`).
 *
 * Unlike the older Relay builders, a REQUIRED nullable member is sent as an explicit `null`, never omitted: the producer's `exact()` check refuses
 * a missing member (`speak` has eleven keys, always). Nothing here sends, retries, persists, mints an id or decides whether the method is listed
 * ([dev.quantumink.hermesgadget.protocol.relay.RelayNegotiation.gate] does).
 */
object VoiceCalls {
    fun info(): RelayCall<VoiceInfo> =
        RelayCall(RelayMethod.VOICE_INFO, JsonObject(emptyMap()), VoiceInfo::decode)

    fun captureStart(
        captureId: CaptureId,
        binding: BindingRef,
        conversationId: ConversationId,
        stream: VoiceStream,
        format: CaptureFormat
    ): RelayCall<CaptureStarted> = RelayCall(
        RelayMethod.VOICE_CAPTURE_START,
        JsonObject(
            linkedMapOf(
                "capture_id" to JsonPrimitive(captureId.value),
                "binding_id" to JsonPrimitive(binding.bindingId.value),
                "authority_epoch" to JsonPrimitive(binding.authorityEpoch),
                "conversation_id" to JsonPrimitive(conversationId.value),
                "stream" to JsonPrimitive(stream.value),
                "format" to JsonPrimitive(format.wire)
            )
        ),
        CaptureStarted::decode
    )

    /** [frames] counts AUDIO frames only; [durationMs] is the stock formula (PCM `bytes / 32` floored, Opus `frames * 20`). */
    fun captureFinish(
        captureId: CaptureId,
        stream: VoiceStream,
        frames: Int,
        durationMs: Int
    ): RelayCall<CaptureResult> {
        require(frames >= 1 && durationMs >= 0) { "Invalid counts." }
        return RelayCall(
            RelayMethod.VOICE_CAPTURE_FINISH,
            JsonObject(
                linkedMapOf(
                    "capture_id" to JsonPrimitive(captureId.value),
                    "stream" to JsonPrimitive(stream.value),
                    "frames" to JsonPrimitive(frames),
                    "duration_ms" to JsonPrimitive(durationMs)
                )
            ),
            CaptureResult::decode
        )
    }

    /** A receipt read / long-poll: [waitMs] in `0..20,000`. */
    fun captureResult(captureId: CaptureId, waitMs: Int): RelayCall<CaptureResult> {
        require(waitMs in 0..VoiceLimits.RESULT_WAIT_MS) { "Invalid wait." }
        return RelayCall(
            RelayMethod.VOICE_CAPTURE_RESULT,
            JsonObject(
                linkedMapOf(
                    "capture_id" to JsonPrimitive(captureId.value),
                    "wait_ms" to JsonPrimitive(waitMs)
                )
            ),
            CaptureResult::decode
        )
    }

    fun captureDiscard(captureId: CaptureId): RelayCall<CaptureResult> = RelayCall(
        RelayMethod.VOICE_CAPTURE_DISCARD,
        JsonObject(linkedMapOf("capture_id" to JsonPrimitive(captureId.value))),
        CaptureResult::decode
    )

    fun speak(binding: BindingRef, request: SpeechRequest): RelayCall<SpeechResult> {
        val format = (request.delivery as? SpeechDelivery.Server)?.format
        return RelayCall(
            RelayMethod.VOICE_SPEAK,
            JsonObject(
                linkedMapOf(
                    "speech_id" to JsonPrimitive(request.speechId.value),
                    "binding_id" to JsonPrimitive(binding.bindingId.value),
                    "authority_epoch" to JsonPrimitive(binding.authorityEpoch),
                    "mode" to JsonPrimitive(request.mode.wire),
                    "request_id" to (
                        request.requestId?.let {
                            JsonPrimitive(it.value)
                        } ?: JsonNull
                        ),
                    "message_id" to (
                        request.messageId?.let {
                            JsonPrimitive(it.value)
                        } ?: JsonNull
                        ),
                    "source_sha256" to (
                        request.digest?.let {
                            JsonPrimitive(it.value)
                        } ?: JsonNull
                        ),
                    "start_cp" to JsonPrimitive(request.startCp),
                    "style" to JsonPrimitive(request.style.wire),
                    "output" to
                        JsonPrimitive(
                            if (format !=
                                null
                            ) {
                                SpeechOutput.SERVER.wire
                            } else {
                                SpeechOutput.CLIENT.wire
                            }
                        ),
                    "format" to (format?.let { JsonPrimitive(it.wire) } ?: JsonNull)
                )
            ),
            SpeechResult::decode
        )
    }

    fun stop(speechId: SpeechId): RelayCall<SpeechResult> = RelayCall(
        RelayMethod.VOICE_SPEAK_STOP,
        JsonObject(linkedMapOf("speech_id" to JsonPrimitive(speechId.value))),
        SpeechResult::decode
    )

    internal fun resultDecoder(method: RelayMethod): (JsonObject) -> Any = when (method) {
        RelayMethod.VOICE_INFO -> VoiceInfo::decode
        RelayMethod.VOICE_CAPTURE_START -> CaptureStarted::decode
        RelayMethod.VOICE_CAPTURE_FINISH,
        RelayMethod.VOICE_CAPTURE_RESULT,
        RelayMethod.VOICE_CAPTURE_DISCARD -> CaptureResult::decode
        RelayMethod.VOICE_SPEAK, RelayMethod.VOICE_SPEAK_STOP -> SpeechResult::decode
        else -> throw IllegalArgumentException("not a voice method")
    }

    /** The inverse of the builders (test support): the typed call a wire `params` object encodes, or null if the server would refuse it. */
    internal fun fromWire(method: RelayMethod, params: JsonObject): RelayCall<*>? = try {
        parse(method, params)
    } catch (_: DecodeFailure) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parse(method: RelayMethod, params: JsonObject): RelayCall<*> {
        fun reader(required: Set<String>) = FieldReader.of("params", params, required)
        return when (method) {
            RelayMethod.VOICE_INFO -> {
                reader(emptySet())
                info()
            }
            RelayMethod.VOICE_CAPTURE_START -> reader(
                setOf(
                    "capture_id",
                    "binding_id",
                    "authority_epoch",
                    "conversation_id",
                    "stream",
                    "format"
                )
            ).let {
                captureStart(
                    it.id("capture_id", CaptureId::of),
                    BindingRef(it.id("binding_id", BindingId::of), it.long("authority_epoch")),
                    it.id("conversation_id", ConversationId::of),
                    VoiceStream.of(it.long("stream", 1, 255).toInt()) ?: it.fail("stream"),
                    it.token("format", CaptureFormat.entries.toTypedArray())
                )
            }
            RelayMethod.VOICE_CAPTURE_FINISH -> reader(
                setOf("capture_id", "stream", "frames", "duration_ms")
            ).let {
                captureFinish(
                    it.id("capture_id", CaptureId::of),
                    VoiceStream.of(it.long("stream", 1, 255).toInt()) ?: it.fail("stream"),
                    it.long("frames", 1, Int.MAX_VALUE.toLong()).toInt(),
                    it.long("duration_ms", 0, Int.MAX_VALUE.toLong()).toInt()
                )
            }
            RelayMethod.VOICE_CAPTURE_RESULT -> reader(setOf("capture_id", "wait_ms")).let {
                captureResult(
                    it.id("capture_id", CaptureId::of),
                    it.long("wait_ms", 0, VoiceLimits.RESULT_WAIT_MS.toLong()).toInt()
                )
            }
            RelayMethod.VOICE_CAPTURE_DISCARD ->
                captureDiscard(reader(setOf("capture_id")).id("capture_id", CaptureId::of))
            RelayMethod.VOICE_SPEAK -> parseSpeak(
                reader(
                    setOf(
                        "speech_id", "binding_id", "authority_epoch", "mode", "request_id",
                        "message_id", "source_sha256", "start_cp",
                        "style", "output", "format"
                    )
                )
            )
            RelayMethod.VOICE_SPEAK_STOP -> stop(
                reader(setOf("speech_id")).id("speech_id", SpeechId::of)
            )
            else -> throw IllegalArgumentException("not a voice method")
        }
    }

    private fun parseSpeak(r: FieldReader): RelayCall<SpeechResult> {
        val binding = BindingRef(r.id("binding_id", BindingId::of), r.long("authority_epoch"))
        val speech = r.id("speech_id", SpeechId::of)
        val style = r.token("style", SpeechStyle.entries.toTypedArray())
        val output = r.token("output", SpeechOutput.entries.toTypedArray())
        val format = r.tokenOrNull("format", CaptureFormat.entries.toTypedArray())
        val delivery = when (output) {
            SpeechOutput.SERVER -> SpeechDelivery.Server(format ?: r.fail("format"))
            SpeechOutput.CLIENT -> if (format == null) SpeechDelivery.Client else r.fail("format")
        }
        val startCp = r.long("start_cp", 0, Int.MAX_VALUE.toLong()).toInt()
        val request = r.idOrNull("request_id", RequestId::of)
        val message = r.idOrNull("message_id", MessageId::of)
        val digest = r.stringOrNull("source_sha256", 64)?.let {
            SourceDigest.of(it)
                ?: r.fail("source_sha256")
        }
        val built = when (r.token("mode", SpeechMode.entries.toTypedArray())) {
            SpeechMode.LIVE -> {
                if (request == null || digest != null || startCp != 0) r.fail("mode")
                SpeechRequest.Live(speech, request, message, style, delivery)
            }
            SpeechMode.REPLAY -> {
                if (request != null || message == null || digest != null ||
                    startCp != 0
                ) {
                    r.fail("mode")
                }
                SpeechRequest.Replay(speech, message, style, delivery)
            }
            SpeechMode.MORE -> {
                if (request != null || message == null || digest == null) r.fail("mode")
                SpeechRequest.More(speech, message, digest, startCp, style, delivery)
            }
        }
        return speak(binding, built)
    }
}
