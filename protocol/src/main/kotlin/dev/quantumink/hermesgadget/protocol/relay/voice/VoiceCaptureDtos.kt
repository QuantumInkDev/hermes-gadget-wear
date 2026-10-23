package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.guarded
import dev.quantumink.hermesgadget.protocol.relay.wireLong
import dev.quantumink.hermesgadget.protocol.relay.wireObject
import dev.quantumink.hermesgadget.protocol.relay.wireString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `relay.voice.capture.start` answer: `{capture_id, stream, deadline_ms}` where `deadline_ms` is the server time by which `finish` must arrive. */
class CaptureStarted(val captureId: CaptureId, val stream: VoiceStream, val deadlineMs: Long) {
    init {
        require(deadlineMs >= 0)
    }

    override fun equals(other: Any?): Boolean =
        other is CaptureStarted && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "CaptureStarted(capture=${captureId.value}, stream=${stream.value})"

    fun toWire(): JsonObject = wireObject(
        "capture_id" to JsonPrimitive(captureId.value),
        "stream" to JsonPrimitive(stream.value),
        "deadline_ms" to JsonPrimitive(deadlineMs)
    )

    internal companion object {
        fun decode(result: JsonObject): CaptureStarted {
            val reader = FieldReader.of(
                "capture_started",
                result,
                setOf("capture_id", "stream", "deadline_ms")
            )
            return guarded(reader, "capture_started") {
                CaptureStarted(
                    reader.id("capture_id", CaptureId::of),
                    VoiceStream.of(reader.long("stream", 1, 255).toInt()) ?: reader.fail("stream"),
                    reader.long("deadline_ms")
                )
            }
        }
    }
}

/**
 * The `CaptureResult` of `finish`, `result` and `discard` (design §6.3): exactly nine keys, every nullable one PRESENT. The closed
 * `(state, reason, provider)` combinations follow the producer (`relay_voice._dto`, `_pre_seal`, `wire.map_stt`): a refusal here means the answer
 * cannot be a truthful server answer, never that the capture is retried.
 *
 * [transcript] is the user's speech; it is held only for the TTL, is never part of a snapshot and is never printed.
 */
class CaptureResult(
    val captureId: CaptureId,
    val conversationId: ConversationId,
    val state: CaptureState,
    val reason: CaptureFailure?,
    val transcript: String?,
    val transcriptExpiresAtMs: Long?,
    val source: TranscriptSource?,
    val provider: VoiceProvider,
    val sealedAtMs: Long?
) {
    init {
        require((reason != null) == (state == CaptureState.FAILED)) { "reason belongs to failed" }
        val held = state == CaptureState.TRANSCRIBED
        require((transcript != null) == held && (transcriptExpiresAtMs != null) == held) {
            "transcript belongs to transcribed"
        }
        require(!held || source != null) { "a transcript names its source" }
        require(transcriptExpiresAtMs == null || transcriptExpiresAtMs >= 0)
        if (transcript != null) {
            val bytes = RelayJson.utf8Length(transcript, VoiceLimits.TRANSCRIPT_BYTES)
            require(bytes in 1..VoiceLimits.TRANSCRIPT_BYTES) { "transcript bytes" }
            require(
                transcript.codePointCount(0, transcript.length) <=
                    VoiceLimits.TRANSCRIPT_CODE_POINTS
            ) { "transcript code points" }
        }
        // A pre-seal answer (finish verdict, pre-seal discard) names no row: nothing was sealed, sent or billed.
        val preSeal = sealedAtMs == null
        if (preSeal) {
            require(state == CaptureState.FAILED || state == CaptureState.DISCARDED) {
                "only a failed or discarded capture can be pre-seal"
            }
            require(provider == VoiceProvider.NOT_STARTED && source == null) {
                "pre-seal is not started"
            }
            require(reason == null || reason.preSeal) { "pre-seal reason" }
        } else {
            require(sealedAtMs >= 0) { "sealed_at_ms" }
            require(reason == null || !reason.preSeal) {
                "a sealed row never carries a pre-seal reason"
            }
        }
        when (state) {
            CaptureState.QUEUED, CaptureState.PREPARING -> require(
                provider == VoiceProvider.NOT_STARTED
            ) {
                "nothing sent yet"
            }
            CaptureState.TRANSCRIBING -> require(provider == VoiceProvider.IN_FLIGHT) {
                "in flight"
            }
            CaptureState.UNKNOWN -> require(provider == VoiceProvider.UNKNOWN) { "unknown outcome" }
            CaptureState.DISCARDED -> Unit
            else -> require(provider != VoiceProvider.IN_FLIGHT) {
                "in_flight only while transcribing or discard_pending"
            }
        }
        if (reason == CaptureFailure.AUDIO_DECODE || reason == CaptureFailure.INTERRUPTED) {
            require(provider == VoiceProvider.NOT_STARTED) { "decided before any send" }
        }
    }

    override fun equals(other: Any?): Boolean = other is CaptureResult && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "CaptureResult(capture=${captureId.value}, state=${state.wire}, reason=${reason?.wire}, provider=${provider.wire}, redacted)"

    fun toWire(): JsonObject = wireObject(
        "capture_id" to JsonPrimitive(captureId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "state" to JsonPrimitive(state.wire),
        "reason" to wireString(reason?.wire),
        "transcript" to wireString(transcript),
        "transcript_expires_at_ms" to wireLong(transcriptExpiresAtMs),
        "source" to wireString(source?.wire),
        "provider" to JsonPrimitive(provider.wire),
        "sealed_at_ms" to wireLong(sealedAtMs)
    )

    internal companion object {
        val KEYS = setOf(
            "capture_id", "conversation_id", "state", "reason", "transcript",
            "transcript_expires_at_ms", "source", "provider",
            "sealed_at_ms"
        )

        fun decode(result: JsonObject): CaptureResult {
            val reader = FieldReader.of("capture_result", result, KEYS)
            return guarded(reader, "capture_result") {
                CaptureResult(
                    reader.id("capture_id", CaptureId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.token("state", CaptureState.entries.toTypedArray()),
                    reader.tokenOrNull("reason", CaptureFailure.entries.toTypedArray()),
                    reader.stringOrNull("transcript", VoiceLimits.TRANSCRIPT_BYTES),
                    reader.longOrNull("transcript_expires_at_ms"),
                    reader.tokenOrNull("source", TranscriptSource.entries.toTypedArray()),
                    reader.token("provider", VoiceProvider.entries.toTypedArray()),
                    reader.longOrNull("sealed_at_ms")
                )
            }
        }
    }
}
