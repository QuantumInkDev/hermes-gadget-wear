package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.WireToken
import dev.quantumink.hermesgadget.protocol.relay.isCanonicalUuid

/**
 * Bounds of the canonical-voice codec and its pure state.
 *
 * SOURCE values mirror the admitted SDK (`relay_voice_wire.py`, `relay_voice_stream.py`, `relay_voice_capture.py`, `opus.py`, core
 * `session_native_voice`) and name their origin: they are protocol facts. POLICY values are a LOCAL client choice of this layer, never a
 * server mandate; reaching one is always a typed refusal, never a silent truncation, eviction or conversion to success.
 */
object VoiceLimits {
    // -- SOURCE: wire --------------------------------------------------------------------------------------------------

    /** `relay_voice_wire.VERSION`: the only `relay.voice.info` version. */
    const val VERSION = 1L

    /** `relay_voice_wire.CHANNEL`: the binary channel both directions share. */
    const val CHANNEL = 5

    /** `relay_voice_wire.BOUNDS`: info/start/discard/stop. */
    const val SMALL_RESPONSE_BYTES = 8_192

    /** `relay_voice_wire.BOUNDS`: finish/result/speak. */
    const val LARGE_RESPONSE_BYTES = 32_768

    // -- SOURCE: uplink (relay_voice_wire, relay_voice_capture) -----------------------------------------------------------

    const val RATE = 16_000
    const val FRAME_MS = 20
    const val MIN_MS = 250
    const val MAX_MS = 60_000
    const val MAX_FRAMES = 3_000
    const val MAX_PCM_BYTES = 1_920_000
    const val MAX_OPUS_BYTES = 1_000_000

    /** `relay_voice_wire.PCM_FRAME_MAX`; a PCM frame is even and at least 2 bytes (`Capture._accept`). */
    const val PCM_FRAME_MAX = 640
    const val PCM_FRAME_MIN = 2

    /** `opus.MAX_PACKET`. */
    const val OPUS_PACKET_MAX = 1_275

    /** `relay_voice_wire.OPUS_HEADER_BYTES`. */
    const val OPUS_HEADER_BYTES = 19

    /** Stock PCM duration: `bytes // 32` (16 kHz mono 16-bit); Opus is exactly `frames * 20`. */
    const val PCM_BYTES_PER_MS = 32

    // -- SOURCE: identities, results, transcript ------------------------------------------------------------------------

    const val ID_PAST_MS = 600_000L
    const val ID_FUTURE_MS = 300_000L
    const val RESULT_WAIT_MS = 20_000
    const val TRANSCRIPT_TTL_MS = 600_000L
    const val TRANSCRIPT_CODE_POINTS = 4_000
    const val TRANSCRIPT_BYTES = 16_384

    /** `relay_voice_wire.RETENTION_MS`: the server keeps a terminal row this long. */
    const val SERVER_RETENTION_MS = 86_400_000L

    // -- SOURCE: downlink (relay_voice_wire, relay_voice_stream) ------------------------------------------------------------

    const val OUT_RATE = 16_000
    const val OUT_MAX_MS = 300_000

    /** HARD: includes the Opus priming; header and END are not AUDIO. */
    const val OUT_MAX_FRAMES = 15_000
    const val OUT_MAX_PCM_BYTES = 9_600_000
    const val OUT_PCM_FRAME_BYTES = 1_280
    const val OUT_OPUS_FRAME_MS = 20
    const val END_BODY_BYTES = 4
    const val ABORT_BODY_BYTES = 1

    /** The 16-bit header sequence is never wrapped: an Opus clip's END is at most 15,001. */
    const val MAX_SEQUENCE = 65_535

    // -- SOURCE: speech (relay_voice_wire.SPEECH_LIMITS, design §9.4) ----------------------------------------------------------

    const val LIVE_WINDOW_MS = 120_000L
    const val CONCISE_SENTENCES = 3
    const val CONCISE_CODE_POINTS = 600
    const val CONCISE_BYTES = 2_400
    const val FULL_CODE_POINTS = 3_000
    const val FULL_BYTES = 12_288
    const val RANGE_UTF16_UNITS = 4_096
    const val CHUNK_CODE_POINTS = 450
    const val SPEAKABLE_BYTES = 262_144
    const val VOICE_HINT_CODE_POINTS = 128

    /** Chunks tile one range of at most [FULL_CODE_POINTS] code points and each holds at least one. */
    const val MAX_CHUNKS = FULL_CODE_POINTS

    // -- POLICY: capture ledger (design §7.4 "Client voice records") ---------------------------------------------------------

    /** Records per enrollment scope, every state including retained terminal ones. */
    const val CAPTURES_PER_ENROLLMENT = 16

    /** Records in the whole ledger. */
    const val CAPTURES_TOTAL = 64

    /** From release to the transcript: a spoken intent older than this becomes a draft (design §7.4). */
    const val INTENT_WINDOW_MS = 120_000L

    /**
     * Terminal records keep their billing truth this long (design §8.4, "retained up to 24 h"). It equals the server's own retention so the
     * client never claims a terminal fact the server has already dropped. Cost: sixteen terminal records block a new press for up to 24 h
     * unless the user dismisses them.
     */
    const val TERMINAL_RETENTION_MS = SERVER_RETENTION_MS

    const val SNAPSHOT_SECTION_VERSION = 1

    // -- POLICY: range coverage (memory only) -------------------------------------------------------------------------------

    /** Messages whose coverage is tracked at once; a new one beyond this is refused (typed), nothing is evicted. */
    const val COVERAGE_MESSAGES = 32

    /**
     * Disjoint played intervals kept per message, and (separately) interrupted ranges remembered per message. Sequential play merges adjacent intervals, so
     * this is only reached by scattered replays.
     */
    const val COVERAGE_INTERVALS = 64

    /** Issued boundaries (valid `start_cp` values) kept per message: a 262,144-byte source walks in a few hundred ranges. */
    const val COVERAGE_BOUNDARIES = 1_024

    /** Speech attempts in flight per message (issued, not yet played, interrupted or forgotten). */
    const val COVERAGE_ATTEMPTS = 8
}

/** A capture identity: a UUIDv7 minted from the server-time estimate. A distinct type from [RequestId] and [SpeechId] (design §6.1). */
@JvmInline
value class CaptureId private constructor(val value: String) {
    companion object {
        fun of(value: String): CaptureId? = RequestId.of(value)?.let { CaptureId(it.value) }

        /** The very text a mint produced; the caller checks it is distinct from every turn id before using it. */
        internal fun fromMinted(id: RequestId): CaptureId = CaptureId(id.value)
    }

    override fun toString(): String = "CaptureId($value)"
}

/** A speech identity: a UUIDv7 minted from the server-time estimate. */
@JvmInline
value class SpeechId private constructor(val value: String) {
    companion object {
        fun of(value: String): SpeechId? = RequestId.of(value)?.let { SpeechId(it.value) }

        internal fun fromMinted(id: RequestId): SpeechId = SpeechId(id.value)
    }

    override fun toString(): String = "SpeechId($value)"
}

/** The channel-5 stream byte: chosen by the client for an upload (`capture.start`) or by the server for a speech (`SpeechResult.stream`). */
@JvmInline
value class VoiceStream private constructor(val value: Int) {
    companion object {
        fun of(value: Int): VoiceStream? = if (value in 1..255) VoiceStream(value) else null
    }

    override fun toString(): String = "VoiceStream($value)"
}

/** `source.sha256`: lowercase hex SHA-256 of the exact speakable source text. */
@JvmInline
value class SourceDigest private constructor(val value: String) {
    companion object {
        fun of(value: String): SourceDigest? = if (value.length == 64 &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
        ) {
            SourceDigest(value)
        } else {
            null
        }
    }

    override fun toString(): String = "SourceDigest(${value.take(12)}...)"
}

internal fun isUuidV7(value: String): Boolean = RequestId.of(value) != null

internal fun isUuid(value: String): Boolean = isCanonicalUuid(value)

enum class CaptureFormat(override val wire: String) : WireToken {
    PCM16("pcm16"),
    OPUS("opus")
}

/** `relay.voice.info` `output.modes`. */
enum class OutputMode(override val wire: String) : WireToken {
    SERVER("server"),
    CLIENT("client")
}

enum class CaptureState(override val wire: String) : WireToken {
    QUEUED("queued"),
    PREPARING("preparing"),
    TRANSCRIBING("transcribing"),
    TRANSCRIBED("transcribed"),
    EMPTY("empty"),
    TOO_LONG("too_long"),
    FAILED("failed"),
    DISCARDED("discarded"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    UNKNOWN("unknown");

    /** The server may still change this state (the row is queued, preparing or in flight). */
    val inProgress: Boolean get() = this == QUEUED || this == PREPARING || this == TRANSCRIBING
}

enum class CaptureFailure(override val wire: String, val preSeal: Boolean) : WireToken {
    AUDIO_INCOMPLETE("audio_incomplete", true),
    SEQUENCE("sequence", true),
    TOO_SHORT("too_short", true),
    TOO_LONG_AUDIO("too_long_audio", true),
    TOO_FAST("too_fast", true),
    AUDIO_FORMAT("audio_format", true),
    AUDIO_DECODE("audio_decode", false),
    STT_UNAVAILABLE("stt_unavailable", false),
    PROVIDER_FAILED("provider_failed", false),
    INTERRUPTED("interrupted", false)
}

/** Truthful billing evidence (`provider`): anything but [NOT_STARTED] may have been billed. Shared by capture and speech. */
enum class VoiceProvider(override val wire: String) : WireToken {
    NOT_STARTED("not_started"),
    IN_FLIGHT("in_flight"),
    COMPLETED("completed"),
    FAILED_AFTER_SEND("failed_after_send"),
    UNKNOWN("unknown");

    val mayHaveBeenBilled: Boolean get() = this != NOT_STARTED
}

enum class TranscriptSource(override val wire: String) : WireToken {
    CONFIGURED("configured"),
    LOCAL_FALLBACK("local_fallback")
}

enum class SpeechMode(override val wire: String) : WireToken {
    LIVE("live"),
    REPLAY("replay"),
    MORE("more")
}

enum class SpeechStyle(override val wire: String) : WireToken {
    FULL("full"),
    CONCISE("concise")
}

enum class SpeechOutput(override val wire: String) : WireToken {
    SERVER("server"),
    CLIENT("client")
}

enum class SpeechState(override val wire: String) : WireToken {
    PREPARING("preparing"),
    STREAMING("streaming"),
    COMPLETED("completed"),
    STOPPED("stopped"),
    ABORTED("aborted"),
    FAILED("failed"),
    TEXT("text"),
    OFF("off"),
    NO_FINAL("no_final"),
    NOT_SPEAKABLE("not_speakable"),
    EXHAUSTED("exhausted"),
    UNAVAILABLE("unavailable"),
    UNKNOWN("unknown");

    /** States the server does not record: no row, no stream, no range, no billing. */
    val unrecorded: Boolean
        get() = this == OFF || this == NO_FINAL || this == NOT_SPEAKABLE || this == EXHAUSTED ||
            this == UNAVAILABLE
}

/** The closed `reason` vocabulary of a speech result; each reason belongs to exactly one state. */
enum class SpeechReason(override val wire: String, val state: SpeechState) : WireToken {
    REVOKED("revoked", SpeechState.ABORTED),
    STALE("stale", SpeechState.ABORTED),
    DISCONNECTED("disconnected", SpeechState.ABORTED),
    TOO_LONG("too_long", SpeechState.ABORTED),
    ENCODE_FAILED("encode_failed", SpeechState.ABORTED),
    TTS_UNAVAILABLE("tts_unavailable", SpeechState.FAILED),
    PROVIDER_FAILED("provider_failed", SpeechState.FAILED),
    BEYOND_INDEX("beyond_index", SpeechState.UNAVAILABLE),
    SOURCE_REFUSED("source_refused", SpeechState.UNAVAILABLE)
}

/** The one-byte ABORT body (`relay_voice_wire.ABORT_CODES`). */
enum class AbortCode(val code: Int) {
    STOPPED(1),
    REVOKED(2),
    STALE(3),
    TOO_LONG(4),
    ENCODE_FAILED(5),
    PROVIDER_FAILED(6);

    companion object {
        fun of(code: Int): AbortCode? = entries.firstOrNull { it.code == code }
    }
}
