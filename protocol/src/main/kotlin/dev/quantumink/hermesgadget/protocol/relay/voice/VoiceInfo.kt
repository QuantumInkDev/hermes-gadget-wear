package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.FieldReader
import dev.quantumink.hermesgadget.protocol.relay.WireToken
import dev.quantumink.hermesgadget.protocol.relay.guarded
import dev.quantumink.hermesgadget.protocol.relay.ownedBounded
import dev.quantumink.hermesgadget.protocol.relay.wireArray
import dev.quantumink.hermesgadget.protocol.relay.wireBoolean
import dev.quantumink.hermesgadget.protocol.relay.wireObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A list of distinct enum tokens in their wire spelling: unknown, duplicate or non-string members refuse the whole answer. */
internal fun <E> FieldReader.tokens(
    name: String,
    values: Array<E>,
    maxItems: Int
): List<E> where E : Enum<E>, E : WireToken {
    val out = ArrayList<E>(maxItems)
    for ((index, element) in array(name, maxItems).withIndex()) {
        val text =
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$name[$index]")
        val token = values.firstOrNull { it.wire == text } ?: fail("$name[$index]")
        if (token in out) fail("$name[$index]")
        out.add(token)
    }
    if (out.isEmpty()) fail(name)
    return out
}

/**
 * `relay.voice.info` `input` (design §6.2): exact keys, the producer's constants as the ceiling of each number. `rate`, `frame_ms` and
 * `channel` are exact (the native codec is 16 kHz mono, 20 ms Opus, channel 5); the others are bounded by the SOURCE maxima, so a server
 * can only advertise less than the codec can carry, never more.
 */
class VoiceInputInfo(
    formats: List<CaptureFormat>,
    val minMs: Int,
    val maxMs: Int,
    val maxFrames: Int,
    val maxPcmBytes: Int,
    val maxOpusBytes: Int,
    val localFallback: Boolean
) {
    val formats: List<CaptureFormat> = ownedBounded(formats, CaptureFormat.entries.size)

    init {
        require(this.formats.isNotEmpty() && this.formats.toSet().size == this.formats.size)
        require(minMs in 1..VoiceLimits.MAX_MS && maxMs in minMs..VoiceLimits.MAX_MS)
        require(maxFrames in 1..VoiceLimits.MAX_FRAMES)
        require(
            maxPcmBytes in 1..VoiceLimits.MAX_PCM_BYTES &&
                maxOpusBytes in 1..VoiceLimits.MAX_OPUS_BYTES
        )
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceInputInfo && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "VoiceInputInfo(formats=${formats.map {
        it.wire
    }}, maxMs=$maxMs)"

    fun toWire(): JsonObject = wireObject(
        "formats" to wireArray(formats) { JsonPrimitive(it.wire) },
        "rate" to JsonPrimitive(VoiceLimits.RATE),
        "frame_ms" to JsonPrimitive(VoiceLimits.FRAME_MS),
        "min_ms" to JsonPrimitive(minMs),
        "max_ms" to JsonPrimitive(maxMs),
        "max_frames" to JsonPrimitive(maxFrames),
        "max_pcm_bytes" to JsonPrimitive(maxPcmBytes),
        "max_opus_bytes" to JsonPrimitive(maxOpusBytes),
        "channel" to JsonPrimitive(VoiceLimits.CHANNEL),
        "local_fallback" to wireBoolean(localFallback)
    )

    internal companion object {
        val KEYS = setOf(
            "formats", "rate", "frame_ms", "min_ms", "max_ms", "max_frames", "max_pcm_bytes",
            "max_opus_bytes", "channel",
            "local_fallback"
        )

        fun decode(reader: FieldReader): VoiceInputInfo {
            val formats = reader.tokens(
                "formats",
                CaptureFormat.entries.toTypedArray(),
                CaptureFormat.entries.size
            )
            reader.long("rate", VoiceLimits.RATE.toLong(), VoiceLimits.RATE.toLong())
            reader.long("frame_ms", VoiceLimits.FRAME_MS.toLong(), VoiceLimits.FRAME_MS.toLong())
            reader.long("channel", VoiceLimits.CHANNEL.toLong(), VoiceLimits.CHANNEL.toLong())
            return guarded(reader, "input") {
                VoiceInputInfo(
                    formats,
                    reader.long("min_ms", 1, VoiceLimits.MAX_MS.toLong()).toInt(),
                    reader.long("max_ms", 1, VoiceLimits.MAX_MS.toLong()).toInt(),
                    reader.long("max_frames", 1, VoiceLimits.MAX_FRAMES.toLong()).toInt(),
                    reader.long("max_pcm_bytes", 1, VoiceLimits.MAX_PCM_BYTES.toLong()).toInt(),
                    reader.long("max_opus_bytes", 1, VoiceLimits.MAX_OPUS_BYTES.toLong()).toInt(),
                    reader.boolean("local_fallback")
                )
            }
        }
    }
}

/** `relay.voice.info` `output`: null on the wire when this server can speak nothing to this socket. */
class VoiceOutputInfo(formats: List<CaptureFormat>, modes: List<OutputMode>, val maxMs: Int) {
    val formats: List<CaptureFormat> = ownedBounded(formats, CaptureFormat.entries.size)
    val modes: List<OutputMode> = ownedBounded(modes, OutputMode.entries.size)

    init {
        require(this.formats.isNotEmpty() && this.formats.toSet().size == this.formats.size)
        require(this.modes.isNotEmpty() && this.modes.toSet().size == this.modes.size)
        require(maxMs in 1..VoiceLimits.OUT_MAX_MS)
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceOutputInfo && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "VoiceOutputInfo(formats=${formats.map { it.wire }}, modes=${modes.map { it.wire }})"

    fun toWire(): JsonObject = wireObject(
        "formats" to wireArray(formats) { JsonPrimitive(it.wire) },
        "rate" to JsonPrimitive(VoiceLimits.OUT_RATE),
        "modes" to wireArray(modes) { JsonPrimitive(it.wire) },
        "channel" to JsonPrimitive(VoiceLimits.CHANNEL),
        "max_ms" to JsonPrimitive(maxMs)
    )

    internal companion object {
        val KEYS = setOf("formats", "rate", "modes", "channel", "max_ms")

        fun decode(reader: FieldReader): VoiceOutputInfo {
            val formats = reader.tokens(
                "formats",
                CaptureFormat.entries.toTypedArray(),
                CaptureFormat.entries.size
            )
            val modes = reader.tokens(
                "modes",
                OutputMode.entries.toTypedArray(),
                OutputMode.entries.size
            )
            reader.long("rate", VoiceLimits.OUT_RATE.toLong(), VoiceLimits.OUT_RATE.toLong())
            reader.long("channel", VoiceLimits.CHANNEL.toLong(), VoiceLimits.CHANNEL.toLong())
            return guarded(reader, "output") {
                VoiceOutputInfo(
                    formats,
                    modes,
                    reader.long("max_ms", 1, VoiceLimits.OUT_MAX_MS.toLong()).toInt()
                )
            }
        }
    }
}

/**
 * `relay.voice.info` `limits`: the exact fifteen keys. Each value is bounded by its SOURCE constant so the client can only be told less
 * than the codec supports. The speech numbers are served as constants by the producer today; they are decoded, not assumed.
 */
class VoiceLimitsInfo(
    val idPastMs: Long,
    val idFutureMs: Long,
    val resultWaitMs: Int,
    val transcriptTtlMs: Long,
    val transcriptCodePoints: Int,
    val transcriptBytes: Int,
    val liveWindowMs: Long,
    val conciseSentences: Int,
    val conciseCodePoints: Int,
    val conciseBytes: Int,
    val fullCodePoints: Int,
    val fullBytes: Int,
    val rangeUtf16Units: Int,
    val clientChunkCodePoints: Int,
    val speakableBytes: Int
) {
    init {
        require(idPastMs in 1..VoiceLimits.ID_PAST_MS && idFutureMs in 1..VoiceLimits.ID_FUTURE_MS)
        require(resultWaitMs in 0..VoiceLimits.RESULT_WAIT_MS)
        require(transcriptTtlMs in 1..VoiceLimits.TRANSCRIPT_TTL_MS)
        require(transcriptCodePoints in 1..VoiceLimits.TRANSCRIPT_CODE_POINTS)
        require(transcriptBytes in 1..VoiceLimits.TRANSCRIPT_BYTES)
        require(liveWindowMs in 1..VoiceLimits.LIVE_WINDOW_MS)
        require(conciseSentences in 1..VoiceLimits.CONCISE_SENTENCES)
        require(
            conciseCodePoints in 1..VoiceLimits.CONCISE_CODE_POINTS &&
                conciseBytes in 1..VoiceLimits.CONCISE_BYTES
        )
        require(
            fullCodePoints in 1..VoiceLimits.FULL_CODE_POINTS &&
                fullBytes in 1..VoiceLimits.FULL_BYTES
        )
        require(rangeUtf16Units in 1..VoiceLimits.RANGE_UTF16_UNITS)
        require(clientChunkCodePoints in 1..VoiceLimits.CHUNK_CODE_POINTS)
        require(speakableBytes in 1..VoiceLimits.SPEAKABLE_BYTES)
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceLimitsInfo && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "VoiceLimitsInfo(transcriptCodePoints=$transcriptCodePoints, fullCodePoints=$fullCodePoints)"

    fun toWire(): JsonObject = wireObject(
        "id_past_ms" to JsonPrimitive(idPastMs),
        "id_future_ms" to JsonPrimitive(idFutureMs),
        "result_wait_ms" to JsonPrimitive(resultWaitMs),
        "transcript_ttl_ms" to JsonPrimitive(transcriptTtlMs),
        "transcript_code_points" to JsonPrimitive(transcriptCodePoints),
        "transcript_bytes" to JsonPrimitive(transcriptBytes),
        "live_window_ms" to JsonPrimitive(liveWindowMs),
        "concise_sentences" to JsonPrimitive(conciseSentences),
        "concise_code_points" to JsonPrimitive(conciseCodePoints),
        "concise_bytes" to JsonPrimitive(conciseBytes),
        "full_code_points" to JsonPrimitive(fullCodePoints),
        "full_bytes" to JsonPrimitive(fullBytes),
        "range_utf16_units" to JsonPrimitive(rangeUtf16Units),
        "client_chunk_code_points" to JsonPrimitive(clientChunkCodePoints),
        "speakable_bytes" to JsonPrimitive(speakableBytes)
    )

    internal companion object {
        val KEYS = setOf(
            "id_past_ms", "id_future_ms", "result_wait_ms", "transcript_ttl_ms",
            "transcript_code_points", "transcript_bytes",
            "live_window_ms", "concise_sentences", "concise_code_points", "concise_bytes",
            "full_code_points", "full_bytes",
            "range_utf16_units", "client_chunk_code_points", "speakable_bytes"
        )

        fun decode(reader: FieldReader): VoiceLimitsInfo {
            fun int(name: String, max: Int, min: Long = 1): Int =
                reader.long(name, min, max.toLong()).toInt()
            return guarded(reader, "limits") {
                VoiceLimitsInfo(
                    reader.long("id_past_ms", 1, VoiceLimits.ID_PAST_MS),
                    reader.long("id_future_ms", 1, VoiceLimits.ID_FUTURE_MS),
                    int("result_wait_ms", VoiceLimits.RESULT_WAIT_MS, 0),
                    reader.long("transcript_ttl_ms", 1, VoiceLimits.TRANSCRIPT_TTL_MS),
                    int("transcript_code_points", VoiceLimits.TRANSCRIPT_CODE_POINTS),
                    int("transcript_bytes", VoiceLimits.TRANSCRIPT_BYTES),
                    reader.long("live_window_ms", 1, VoiceLimits.LIVE_WINDOW_MS),
                    int("concise_sentences", VoiceLimits.CONCISE_SENTENCES),
                    int("concise_code_points", VoiceLimits.CONCISE_CODE_POINTS),
                    int("concise_bytes", VoiceLimits.CONCISE_BYTES),
                    int("full_code_points", VoiceLimits.FULL_CODE_POINTS),
                    int("full_bytes", VoiceLimits.FULL_BYTES),
                    int("range_utf16_units", VoiceLimits.RANGE_UTF16_UNITS),
                    int("client_chunk_code_points", VoiceLimits.CHUNK_CODE_POINTS),
                    int("speakable_bytes", VoiceLimits.SPEAKABLE_BYTES)
                )
            }
        }
    }
}

/** What this client may do with one `relay.voice.info` answer and its own probed formats. */
class VoiceAvailability internal constructor(
    val captureFormats: List<CaptureFormat>,
    val serverSpeechFormats: List<CaptureFormat>,
    val clientSpeech: Boolean
) {
    /** Hold-to-talk inside a Relay conversation needs at least one common input format (design §4.1 layer 3). */
    val canCapture: Boolean get() = captureFormats.isNotEmpty()

    val canSpeak: Boolean get() = serverSpeechFormats.isNotEmpty() || clientSpeech

    override fun toString(): String = "VoiceAvailability(capture=${captureFormats.map {
        it.wire
    }}, server=${serverSpeechFormats.map { it.wire }}, client=$clientSpeech)"
}

/**
 * `relay.voice.info` (design §6.2). Acceptance is exactly `version == 1` with every member strict; usability additionally needs a format
 * both ends can process ([negotiate]). A malformed answer is a refusal for the whole socket generation, never a partial success.
 */
class VoiceInfo(
    val input: VoiceInputInfo?,
    val output: VoiceOutputInfo?,
    val limits: VoiceLimitsInfo
) {
    /** The formats and modes usable between this server and a client that can process [inputFormats] / [outputFormats]. */
    fun negotiate(
        inputFormats: Set<CaptureFormat>,
        outputFormats: Set<CaptureFormat>
    ): VoiceAvailability {
        val capture = input?.formats?.filter { it in inputFormats }.orEmpty()
        val server = if (output != null &&
            OutputMode.SERVER in output.modes
        ) {
            output.formats.filter { it in outputFormats }
        } else {
            emptyList()
        }
        val client = output != null && OutputMode.CLIENT in output.modes
        return VoiceAvailability(capture, server, client)
    }

    override fun equals(other: Any?): Boolean = other is VoiceInfo && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "VoiceInfo(input=$input, output=$output)"

    fun toWire(): JsonObject = wireObject(
        "version" to JsonPrimitive(VoiceLimits.VERSION),
        "input" to (input?.toWire() ?: JsonNull),
        "output" to (output?.toWire() ?: JsonNull),
        "limits" to limits.toWire()
    )

    internal companion object {
        fun decode(result: JsonObject): VoiceInfo {
            val reader = FieldReader.of(
                "voice_info",
                result,
                setOf("version", "input", "output", "limits")
            )
            reader.long("version", VoiceLimits.VERSION, VoiceLimits.VERSION)
            return VoiceInfo(
                reader.objOrNull("input", VoiceInputInfo.KEYS)?.let(VoiceInputInfo::decode),
                reader.objOrNull("output", VoiceOutputInfo.KEYS)?.let(VoiceOutputInfo::decode),
                VoiceLimitsInfo.decode(reader.obj("limits", VoiceLimitsInfo.KEYS))
            )
        }
    }
}
