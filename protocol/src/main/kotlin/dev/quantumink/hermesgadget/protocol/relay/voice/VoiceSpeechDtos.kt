package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.guarded
import dev.quantumink.hermesgadget.protocol.relay.ownedBounded
import dev.quantumink.hermesgadget.protocol.relay.wireArray
import dev.quantumink.hermesgadget.protocol.relay.wireBoolean
import dev.quantumink.hermesgadget.protocol.relay.wireLong
import dev.quantumink.hermesgadget.protocol.relay.wireObject
import dev.quantumink.hermesgadget.protocol.relay.wireString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `SpeechResult.source` (design §6.4). [totalCp] is the PROCESSED PREFIX in code points: the producer sets it to [speakableEndCp] even when
 * [limited] says canonical text continues (ruling R3). It is never the length of the unread tail and nothing here converts it into one.
 */
class SpeechSource(
    val sha256: SourceDigest,
    val totalCp: Int,
    val speakableEndCp: Int,
    val limited: Boolean
) {
    init {
        require(speakableEndCp in 0..totalCp && totalCp <= VoiceLimits.SPEAKABLE_BYTES)
    }

    override fun equals(other: Any?): Boolean = other is SpeechSource && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "SpeechSource(sha=$sha256, end=$speakableEndCp, limited=$limited)"

    fun toWire(): JsonObject = wireObject(
        "sha256" to JsonPrimitive(sha256.value),
        "total_cp" to JsonPrimitive(totalCp),
        "speakable_end_cp" to JsonPrimitive(speakableEndCp),
        "limited" to wireBoolean(limited)
    )

    internal companion object {
        val KEYS = setOf("sha256", "total_cp", "speakable_end_cp", "limited")

        fun decode(reader: FieldReader): SpeechSource = guarded(reader, "source") {
            SpeechSource(
                SourceDigest.of(reader.string("sha256", 64)) ?: reader.fail("sha256"),
                reader.long("total_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.long("speakable_end_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.boolean("limited")
            )
        }
    }
}

/** A half-open code-point interval `[startCp, endCp)` of the exact canonical display text (design §9.4). Never UTF-16 indices. */
class SpeechRange(
    val startCp: Int,
    val endCp: Int,
    val sentences: Int,
    val cutInsideUnit: Boolean,
    val done: Boolean
) {
    init {
        require(startCp >= 0 && endCp > startCp && endCp <= VoiceLimits.SPEAKABLE_BYTES) {
            "an issued range holds at least one code point"
        }
        require(sentences in 0..VoiceLimits.FULL_CODE_POINTS)
    }

    val length: Int get() = endCp - startCp

    override fun equals(other: Any?): Boolean = other is SpeechRange && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "SpeechRange($startCp..$endCp, cut=$cutInsideUnit, done=$done)"

    fun toWire(): JsonObject = wireObject(
        "start_cp" to JsonPrimitive(startCp),
        "end_cp" to JsonPrimitive(endCp),
        "sentences" to JsonPrimitive(sentences),
        "cut_inside_unit" to wireBoolean(cutInsideUnit),
        "done" to wireBoolean(done)
    )

    internal companion object {
        val KEYS = setOf("start_cp", "end_cp", "sentences", "cut_inside_unit", "done")

        fun decode(reader: FieldReader): SpeechRange = guarded(reader, "range") {
            SpeechRange(
                reader.long("start_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.long("end_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.long("sentences", 0, VoiceLimits.FULL_CODE_POINTS.toLong()).toInt(),
                reader.boolean("cut_inside_unit"),
                reader.boolean("done")
            )
        }
    }
}

/** One client-mode chunk: the exact range text for `[startCp, endCp)`. [text] is the reply the user hears; it is never printed. */
class SpeechChunk(val startCp: Int, val endCp: Int, val text: String) {
    init {
        require(
            startCp >= 0 && endCp > startCp && endCp - startCp <= VoiceLimits.CHUNK_CODE_POINTS
        ) {
            "chunk size"
        }
        require(
            RelayJson.utf8Length(text, VoiceLimits.CHUNK_CODE_POINTS * 4) !=
                RelayJson.LONE_SURROGATE
        ) { "chunk text must be encodable" }
        require(text.codePointCount(0, text.length) == endCp - startCp) {
            "chunk text is exactly the code points it names"
        }
    }

    override fun equals(other: Any?): Boolean = other is SpeechChunk && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "SpeechChunk($startCp..$endCp, redacted)"

    fun toWire(): JsonObject = wireObject(
        "start_cp" to JsonPrimitive(startCp),
        "end_cp" to JsonPrimitive(endCp),
        "text" to JsonPrimitive(text)
    )

    internal companion object {
        val KEYS = setOf("start_cp", "end_cp", "text")

        fun decode(reader: FieldReader): SpeechChunk = guarded(reader, "chunk") {
            SpeechChunk(
                reader.long("start_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.long("end_cp", 0, VoiceLimits.SPEAKABLE_BYTES.toLong()).toInt(),
                reader.string("text", VoiceLimits.CHUNK_CODE_POINTS * 4)
            )
        }
    }
}

/** Python `str.isprintable` for the producer's voice hint: no control, format, surrogate, private-use, unassigned or non-space separator. */
internal fun isPrintableHint(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        val type = Character.getType(point).toByte()
        val printable = when (type) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
            Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
            Character.SPACE_SEPARATOR -> point == ' '.code
            else -> true
        }
        if (!printable) return false
        index += Character.charCount(point)
    }
    return true
}

/**
 * The `SpeechResult` of `relay.voice.speak` and `speak.stop` (design §6.4): exactly ten keys, every nullable one present. The cross-field
 * rules are the producer's (`relay_voice_wire.speech_result`, `Speech._dto`): a reason belongs to one state, only a client-mode `text`
 * carries chunks, an unrecorded state carries no stream, range, source or billing, and a range lies inside its source.
 */
class SpeechResult(
    val speechId: SpeechId,
    val messageId: MessageId?,
    val state: SpeechState,
    val reason: SpeechReason?,
    val stream: VoiceStream?,
    val source: SpeechSource?,
    val range: SpeechRange?,
    chunks: List<SpeechChunk>?,
    val voice: String?,
    val provider: VoiceProvider
) {
    val chunks: List<SpeechChunk>? = chunks?.let { ownedBounded(it, VoiceLimits.MAX_CHUNKS) }

    init {
        require((reason != null) == (state in REASON_STATES)) {
            "reason belongs to aborted, failed and unavailable"
        }
        require(reason == null || reason.state == state) { "reason does not belong to the state" }
        require((source == null) == (range == null)) { "source and range come together" }
        if (source != null && range != null) {
            require(range.endCp <= source.speakableEndCp) { "range inside the speakable source" }
            require(range.done == (range.endCp == source.speakableEndCp)) {
                "done is exactly 'reached the speakable end'"
            }
        }
        require((this.chunks != null) == (state == SpeechState.TEXT)) { "chunks belong to text" }
        require(voice == null || state == SpeechState.TEXT) { "the voice hint belongs to text" }
        if (voice != null) {
            require(
                voice.isNotEmpty() &&
                    voice.codePointCount(0, voice.length) <= VoiceLimits.VOICE_HINT_CODE_POINTS &&
                    isPrintableHint(voice)
            )
        }
        if (state.unrecorded) {
            require(
                stream == null && source == null && chunks == null &&
                    provider == VoiceProvider.NOT_STARTED
            ) { "not recorded" }
        }
        when (state) {
            SpeechState.TEXT -> {
                require(
                    source != null && range != null && stream == null &&
                        provider == VoiceProvider.NOT_STARTED
                ) { "text" }
                checkChunks(requireNotNull(this.chunks), range)
            }
            SpeechState.STREAMING -> require(stream != null) { "frames flow on a stream" }
            SpeechState.UNKNOWN -> require(provider == VoiceProvider.UNKNOWN) { "unknown outcome" }
            else -> Unit
        }
        when (state) {
            SpeechState.PREPARING, SpeechState.STOPPED -> Unit
            else -> require(provider != VoiceProvider.IN_FLIGHT) {
                "in_flight only while preparing or stopped"
            }
        }
        if (state == SpeechState.STREAMING || state == SpeechState.COMPLETED) {
            require(provider == VoiceProvider.COMPLETED || provider == VoiceProvider.NOT_STARTED) {
                "audio exists"
            }
        }
        if (reason ==
            SpeechReason.TTS_UNAVAILABLE
        ) {
            require(provider == VoiceProvider.NOT_STARTED) { "unavailable before any send" }
        }
    }

    override fun equals(other: Any?): Boolean = other is SpeechResult && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "SpeechResult(speech=${speechId.value}, state=${state.wire}, reason=${reason?.wire}, provider=${provider.wire}, redacted)"

    fun toWire(): JsonObject = wireObject(
        "speech_id" to JsonPrimitive(speechId.value),
        "message_id" to wireString(messageId?.value),
        "state" to JsonPrimitive(state.wire),
        "reason" to wireString(reason?.wire),
        "stream" to wireLong(stream?.value?.toLong()),
        "source" to (source?.toWire() ?: JsonNull),
        "range" to (range?.toWire() ?: JsonNull),
        "chunks" to (this.chunks?.let { list -> wireArray(list) { it.toWire() } } ?: JsonNull),
        "voice" to wireString(voice),
        "provider" to JsonPrimitive(provider.wire)
    )

    private fun checkChunks(list: List<SpeechChunk>, range: SpeechRange?) {
        require(
            list.isNotEmpty() && range != null && list.first().startCp == range.startCp &&
                list.last().endCp == range.endCp
        ) {
            "chunks cover the range"
        }
        var units = 0
        var bytes = 0
        for ((index, chunk) in list.withIndex()) {
            require(index == 0 || chunk.startCp == list[index - 1].endCp) {
                "chunks are consecutive"
            }
            units += chunk.text.length
            bytes += RelayJson.utf8Length(chunk.text)
        }
        require(units <= VoiceLimits.RANGE_UTF16_UNITS && bytes <= VoiceLimits.FULL_BYTES) {
            "range text bounds"
        }
    }

    internal companion object {
        val KEYS =
            setOf(
                "speech_id", "message_id", "state", "reason", "stream", "source", "range",
                "chunks", "voice", "provider"
            )
        val REASON_STATES = setOf(SpeechState.ABORTED, SpeechState.FAILED, SpeechState.UNAVAILABLE)

        fun decode(result: JsonObject): SpeechResult {
            val reader = FieldReader.of("speech_result", result, KEYS)
            val chunks = if (reader.isNull("chunks")) {
                null
            } else {
                reader.objects(
                    "chunks",
                    VoiceLimits.MAX_CHUNKS,
                    SpeechChunk.KEYS,
                    emptySet(),
                    SpeechChunk::decode
                )
            }
            return guarded(reader, "speech_result") {
                SpeechResult(
                    reader.id("speech_id", SpeechId::of),
                    reader.idOrNull("message_id", MessageId::of),
                    reader.token("state", SpeechState.entries.toTypedArray()),
                    reader.tokenOrNull("reason", SpeechReason.entries.toTypedArray()),
                    if (reader.isNull("stream")) {
                        null
                    } else {
                        VoiceStream.of(reader.long("stream", 1, 255).toInt())
                            ?: reader.fail("stream")
                    },
                    reader.objOrNull("source", SpeechSource.KEYS)?.let(SpeechSource::decode),
                    reader.objOrNull("range", SpeechRange.KEYS)?.let(SpeechRange::decode),
                    chunks,
                    reader.stringOrNull("voice", VoiceLimits.VOICE_HINT_CODE_POINTS * 4),
                    reader.token("provider", VoiceProvider.entries.toTypedArray())
                )
            }
        }
    }
}
