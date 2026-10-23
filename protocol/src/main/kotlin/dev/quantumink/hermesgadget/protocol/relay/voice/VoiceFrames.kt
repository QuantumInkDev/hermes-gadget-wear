package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.BinaryFrame

/** Which end writes a channel-5 frame. The two kind ranges are disjoint (`0x0_` uplink, `0x1_` downlink): a frame of the wrong direction is invalid. */
enum class VoiceDirection { UPLINK, DOWNLINK }

/** The payload kinds of channel 5 (design §6.3/§6.4; `relay_voice_wire.KIND_*`, `OUT_KIND_*`). */
enum class VoiceFrameKind(val code: Int, val direction: VoiceDirection) {
    UPLINK_OPUS_HEADER(0x01, VoiceDirection.UPLINK),
    UPLINK_AUDIO(0x02, VoiceDirection.UPLINK),
    DOWNLINK_OPUS_HEADER(0x11, VoiceDirection.DOWNLINK),
    DOWNLINK_AUDIO(0x12, VoiceDirection.DOWNLINK),
    DOWNLINK_END(0x13, VoiceDirection.DOWNLINK),
    DOWNLINK_ABORT(0x14, VoiceDirection.DOWNLINK);

    internal val isHeader: Boolean get() = this == UPLINK_OPUS_HEADER ||
        this == DOWNLINK_OPUS_HEADER
    internal val isAudio: Boolean get() = this == UPLINK_AUDIO || this == DOWNLINK_AUDIO

    companion object {
        fun of(code: Int): VoiceFrameKind? = entries.firstOrNull { it.code == code }
    }
}

enum class FrameRefusal {
    /** Not even a kind byte after the four header bytes. */
    NO_KIND,
    UNKNOWN_KIND,
    WRONG_DIRECTION,
    OVERSIZE,
    BAD_OPUS_HEADER,
    BAD_AUDIO_SIZE,
    BAD_END_BODY,
    BAD_ABORT_BODY,
    SEQUENCE_OUT_OF_RANGE
}

/** What a channel-5 frame parser made of one raw binary message. Stock behaviour for anything that is not channel 5: ignored. */
sealed interface VoiceParse {
    /** Not a voice frame: fewer than four bytes or another channel. The stock parser decides what that is. */
    data object NotVoice : VoiceParse

    class Parsed internal constructor(val frame: VoiceFrame) : VoiceParse {
        override fun toString(): String = "VoiceParse.Parsed($frame)"
    }

    class Refused internal constructor(val reason: FrameRefusal) : VoiceParse {
        override fun toString(): String = "VoiceParse.Refused($reason)"
    }
}

/**
 * One channel-5 frame: `[0x05, stream, seq_lo, seq_hi]` + kind byte + body. The body is copied in and copied out (a caller can never alias it) and is
 * never printed. A frame only exists with a body that fits its kind; format-dependent audio rules belong to the stream state machines below, which know
 * whether PCM or Opus was negotiated.
 */
class VoiceFrame private constructor(
    val kind: VoiceFrameKind,
    val stream: Int,
    val sequence: Int,
    body: ByteArray
) {
    private val bytes: ByteArray = body.copyOf()

    val body: ByteArray get() = bytes.copyOf()

    val bodySize: Int get() = bytes.size

    /** The AUDIO-frame count an END frame carries (unsigned big-endian 32 bit), or null for any other kind. */
    val endCount: Long?
        get() = if (kind == VoiceFrameKind.DOWNLINK_END) {
            bytes.fold(0L) { total, b -> (total shl 8) or (b.toLong() and 0xff) }
        } else {
            null
        }

    /** The ABORT reason of an ABORT frame, or null for any other kind. */
    val abortCode: AbortCode? get() = if (kind ==
        VoiceFrameKind.DOWNLINK_ABORT
    ) {
        AbortCode.of(bytes[0].toInt() and 0xff)
    } else {
        null
    }

    /** The raw binary message exactly as it goes on the wire. */
    fun encode(): ByteArray {
        val out = ByteArray(HEADER + 1 + bytes.size)
        out[0] = VoiceLimits.CHANNEL.toByte()
        out[1] = stream.toByte()
        out[2] = sequence.toByte()
        out[3] = (sequence ushr 8).toByte()
        out[HEADER] = kind.code.toByte()
        bytes.copyInto(out, HEADER + 1)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceFrame && other.kind == kind && other.stream == stream &&
            other.sequence == sequence && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int =
        ((kind.hashCode() * 31 + stream) * 31 + sequence) * 31 + bytes.contentHashCode()

    override fun toString(): String =
        "VoiceFrame(${kind.name}, stream=$stream, seq=$sequence, ${bytes.size} bytes)"

    companion object {
        private const val HEADER = 4

        /** The larger of the downlink PCM frame (1,280) and an Opus packet (1,275); the stream machines apply the per-format bound. */
        private const val MAX_AUDIO_BODY = VoiceLimits.OUT_PCM_FRAME_BYTES

        /** Builds one frame, or null for what no peer would accept (stream outside 0..255, a 16-bit sequence overflow, a body that does not fit its kind). */
        fun create(kind: VoiceFrameKind, stream: Int, sequence: Int, body: ByteArray): VoiceFrame? =
            build(kind, stream, sequence, body)

        internal fun build(
            kind: VoiceFrameKind,
            stream: Int,
            sequence: Int,
            body: ByteArray
        ): VoiceFrame? {
            if (stream !in 0..255 || sequence !in 0..VoiceLimits.MAX_SEQUENCE) return null
            return if (refusalOf(kind, body) ==
                null
            ) {
                VoiceFrame(kind, stream, sequence, body)
            } else {
                null
            }
        }

        internal fun refusalOf(kind: VoiceFrameKind, body: ByteArray): FrameRefusal? = when {
            kind.isHeader -> if (OpusRules.isHeader(body)) null else FrameRefusal.BAD_OPUS_HEADER
            kind.isAudio -> if (body.size in
                1..MAX_AUDIO_BODY
            ) {
                null
            } else {
                FrameRefusal.BAD_AUDIO_SIZE
            }
            kind == VoiceFrameKind.DOWNLINK_END ->
                if (body.size == VoiceLimits.END_BODY_BYTES) null else FrameRefusal.BAD_END_BODY
            else ->
                if (body.size == VoiceLimits.ABORT_BODY_BYTES &&
                    AbortCode.of(body[0].toInt() and 0xff) != null
                ) {
                    null
                } else {
                    FrameRefusal.BAD_ABORT_BODY
                }
        }

        /**
         * Parses [raw] as a frame that the peer [expected] to be writing. Channel 5 only: anything shorter than a header or on another channel is
         * [VoiceParse.NotVoice] (the stock parser ignores it). A stream byte of 0 is not a refusal here: the stream machines simply never match it.
         */
        fun parse(raw: ByteArray, expected: VoiceDirection): VoiceParse {
            if (raw.size < HEADER ||
                (raw[0].toInt() and 0xff) != VoiceLimits.CHANNEL
            ) {
                return VoiceParse.NotVoice
            }
            if (raw.size >
                BinaryFrame.MAX_FRAME_BYTES
            ) {
                return VoiceParse.Refused(FrameRefusal.OVERSIZE)
            }
            if (raw.size == HEADER) return VoiceParse.Refused(FrameRefusal.NO_KIND)
            val kind =
                VoiceFrameKind.of(raw[HEADER].toInt() and 0xff)
                    ?: return VoiceParse.Refused(FrameRefusal.UNKNOWN_KIND)
            if (kind.direction != expected) return VoiceParse.Refused(FrameRefusal.WRONG_DIRECTION)
            val body = raw.copyOfRange(HEADER + 1, raw.size)
            refusalOf(kind, body)?.let { return VoiceParse.Refused(it) }
            val sequence = (raw[2].toInt() and 0xff) or ((raw[3].toInt() and 0xff) shl 8)
            return VoiceParse.Parsed(VoiceFrame(kind, raw[1].toInt() and 0xff, sequence, body))
        }
    }
}

/** The Opus rules of the admitted validators (`opus.header`, `opus.packet`): header shape and the single-20-ms-packet rule. */
internal object OpusRules {
    private val MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    /** A 19-byte mono OpusHead: `OpusHead`, version 1, one channel, channel mapping family 0. */
    fun isHeader(value: ByteArray): Boolean = value.size == VoiceLimits.OPUS_HEADER_BYTES &&
        MAGIC.indices.all { value[it] == MAGIC[it] } && value[8].toInt() == 1 &&
        value[9].toInt() == 1 &&
        value[18].toInt() == 0

    /** 1..1,275 bytes whose TOC describes exactly 960 samples at 48 kHz (20 ms): `samples-per-frame * frames == 960`. */
    fun isTwentyMsPacket(value: ByteArray): Boolean {
        if (value.size !in 1..VoiceLimits.OPUS_PACKET_MAX) return false
        val toc = value[0].toInt() and 0xff
        val samples = when {
            toc and 128 != 0 -> 120 shl ((toc shr 3) and 3)
            toc and 96 == 96 -> if (toc and 8 != 0) 960 else 480
            else -> {
                val config = (toc shr 3) and 3
                if (config == 3) 2880 else 480 shl config
            }
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (value.size > 1) value[1].toInt() and 63 else 0
        }
        return samples * frames == 960
    }
}

/** Why an uplink frame was not built. Nothing is ever repaired, trimmed or re-sequenced. */
enum class UplinkRefusal {
    /** PCM on an Opus capture, a header on a PCM capture, or a second header. */
    WRONG_KIND_FOR_FORMAT,
    HEADER_MISSING,
    HEADER_INVALID,
    PCM_SIZE,
    OPUS_PACKET,
    TOO_MANY_FRAMES,
    TOO_MANY_BYTES,
    TOO_LONG
}

/** The `capture.finish` counts of one upload: AUDIO frames only and the stock duration (PCM `bytes / 32` floored; Opus `frames * 20`). */
class FinishCounts internal constructor(val frames: Int, val durationMs: Int) {
    /** The server refuses a recording below 250 ms (`too_short`); the client does not even finish one. */
    val tooShort: Boolean get() = durationMs < VoiceLimits.MIN_MS

    override fun equals(other: Any?): Boolean =
        other is FinishCounts && other.frames == frames && other.durationMs == durationMs

    override fun hashCode(): Int = frames * 31 + durationMs

    override fun toString(): String = "FinishCounts(frames=$frames, durationMs=$durationMs)"
}

/** One step of an upload: the next state and exactly one of a frame to write or the refusal. */
class UplinkStep internal constructor(
    val capture: UplinkCapture,
    val frame: VoiceFrame?,
    val refusal: UplinkRefusal?
)

/**
 * The client side of ONE upload as a persistent value: it numbers frames strictly consecutively (PCM from 0; Opus header 0 then packets from 1),
 * enforces the producer's per-frame, total-byte, frame-count and duration caps BEFORE a frame exists, and yields the finish counts. It never reads a
 * clock: the server's wall-time `too_fast` rule is a property of real-time pacing, which the recorder owns.
 */
class UplinkCapture private constructor(
    val stream: VoiceStream,
    val format: CaptureFormat,
    private val headerSent: Boolean,
    val frames: Int,
    val bytes: Int
) {
    private val nextSequence: Int get() = if (format == CaptureFormat.OPUS) frames + 1 else frames

    /** The duration the server computes from what it received. */
    val durationMs: Int
        get() = if (format ==
            CaptureFormat.OPUS
        ) {
            frames * VoiceLimits.FRAME_MS
        } else {
            bytes /
                VoiceLimits.PCM_BYTES_PER_MS
        }

    fun counts(): FinishCounts? = if (frames >= 1 &&
        (format == CaptureFormat.PCM16 || headerSent)
    ) {
        FinishCounts(frames, durationMs)
    } else {
        null
    }

    fun header(head: ByteArray): UplinkStep {
        if (format != CaptureFormat.OPUS || headerSent ||
            frames != 0
        ) {
            return refused(UplinkRefusal.WRONG_KIND_FOR_FORMAT)
        }
        val frame =
            VoiceFrame.build(VoiceFrameKind.UPLINK_OPUS_HEADER, stream.value, 0, head)
                ?: return refused(UplinkRefusal.HEADER_INVALID)
        return UplinkStep(UplinkCapture(stream, format, true, frames, bytes), frame, null)
    }

    /** One PCM16 LE frame: even, 2..640 bytes. */
    fun pcm(body: ByteArray): UplinkStep {
        if (format != CaptureFormat.PCM16) return refused(UplinkRefusal.WRONG_KIND_FOR_FORMAT)
        if (body.size % 2 != 0 ||
            body.size !in VoiceLimits.PCM_FRAME_MIN..VoiceLimits.PCM_FRAME_MAX
        ) {
            return refused(UplinkRefusal.PCM_SIZE)
        }
        return append(body, VoiceLimits.MAX_PCM_BYTES)
    }

    /** One 20 ms Opus packet (after the header). */
    fun opus(packet: ByteArray): UplinkStep {
        if (format != CaptureFormat.OPUS) return refused(UplinkRefusal.WRONG_KIND_FOR_FORMAT)
        if (!headerSent) return refused(UplinkRefusal.HEADER_MISSING)
        if (!OpusRules.isTwentyMsPacket(packet)) return refused(UplinkRefusal.OPUS_PACKET)
        return append(packet, VoiceLimits.MAX_OPUS_BYTES)
    }

    private fun append(body: ByteArray, byteCap: Int): UplinkStep {
        if (frames >= VoiceLimits.MAX_FRAMES) return refused(UplinkRefusal.TOO_MANY_FRAMES)
        if (bytes + body.size > byteCap) return refused(UplinkRefusal.TOO_MANY_BYTES)
        val next = UplinkCapture(stream, format, headerSent, frames + 1, bytes + body.size)
        if (next.durationMs > VoiceLimits.MAX_MS) return refused(UplinkRefusal.TOO_LONG)
        val frame =
            VoiceFrame.build(VoiceFrameKind.UPLINK_AUDIO, stream.value, nextSequence, body)
                ?: return refused(UplinkRefusal.PCM_SIZE)
        return UplinkStep(next, frame, null)
    }

    private fun refused(reason: UplinkRefusal) = UplinkStep(this, null, reason)

    override fun toString(): String =
        "UplinkCapture(stream=${stream.value}, ${format.wire}, frames=$frames, bytes=$bytes)"

    companion object {
        fun start(stream: VoiceStream, format: CaptureFormat): UplinkCapture =
            UplinkCapture(stream, format, false, 0, 0)
    }
}

/** Why a downlink stream was closed locally. Any of these is "Speech interrupted" plus a best-effort `speak.stop` (design §7.2). */
enum class StreamViolation {
    SEQUENCE,
    HEADER_REQUIRED,
    HEADER_REPEATED,
    HEADER_ON_PCM,
    HEADER_WITHOUT_AUDIO,
    AUDIO_SIZE,
    OPUS_PACKET,
    TOO_MANY_FRAMES,
    TOO_MANY_BYTES,
    TOO_LONG,
    END_COUNT,
    WRONG_KIND
}

/** Why a frame did not touch the stream's state. */
enum class FrameIgnored {
    /** Another stream, or stream 0: "frames for other or retired streams are ignored". */
    OTHER_STREAM,

    /** The stream already ended, aborted or was closed by a violation. */
    CLOSED
}

sealed interface DownlinkEvent {
    class Header internal constructor() : DownlinkEvent {
        override fun toString(): String = "DownlinkEvent.Header"
    }

    class Audio internal constructor(val audioFrames: Int, val bytes: Int) : DownlinkEvent {
        override fun toString(): String = "DownlinkEvent.Audio(frames=$audioFrames)"
    }

    /** END with the exact count. [audioFrames] 0 is a silent clip (the producer sends END alone at sequence 0). */
    class Completed internal constructor(val audioFrames: Int, val durationMs: Long) :
        DownlinkEvent {
        val silent: Boolean get() = audioFrames == 0

        override fun toString(): String =
            "DownlinkEvent.Completed(frames=$audioFrames, ms=$durationMs)"
    }

    class Aborted internal constructor(val code: AbortCode) : DownlinkEvent {
        override fun toString(): String = "DownlinkEvent.Aborted($code)"
    }

    class Ignored internal constructor(val why: FrameIgnored) : DownlinkEvent {
        override fun toString(): String = "DownlinkEvent.Ignored($why)"
    }

    class Violation internal constructor(val reason: StreamViolation) : DownlinkEvent {
        override fun toString(): String = "DownlinkEvent.Violation($reason)"
    }
}

class DownlinkStep internal constructor(val stream: DownlinkStream, val event: DownlinkEvent)

/**
 * The receiving side of ONE speech stream as a persistent value (design §7.2, `relay_voice_stream.Clip`). Frames are accepted in strict sequence from 0
 * (an Opus clip: header 0, packets from 1), header first and once, AUDIO bounded (PCM even 2..1,280 bytes; Opus one 20 ms packet 1..1,275), at most
 * 15,000 AUDIO frames INCLUDING the Opus priming (header and END are not AUDIO, so an Opus END may sit at 15,001), END's count equal to the AUDIO
 * frames received, ABORT at the next sequence. A gap, duplicate, reorder, wrong-direction or wrong-format frame closes the stream with a typed violation;
 * nothing is repaired or skipped. Generation fences (`speechGeneration`, `playbackVersion`, socket generation) belong to the caller that owns the sink.
 */
class DownlinkStream private constructor(
    val stream: VoiceStream,
    val format: CaptureFormat,
    private val nextSequence: Int,
    private val headerSeen: Boolean,
    val audioFrames: Int,
    val audioBytes: Int,
    val closed: Boolean
) {
    /** Decoded play time of what was accepted so far, from the producer's formulas (PCM `bytes / 32`; Opus `frames * 20`). */
    val durationMs: Long
        get() = if (format == CaptureFormat.OPUS) {
            audioFrames.toLong() * VoiceLimits.OUT_OPUS_FRAME_MS
        } else {
            audioBytes.toLong() / VoiceLimits.PCM_BYTES_PER_MS
        }

    fun accept(frame: VoiceFrame): DownlinkStep {
        if (frame.stream !=
            stream.value
        ) {
            return step(this, DownlinkEvent.Ignored(FrameIgnored.OTHER_STREAM))
        }
        if (closed) return step(this, DownlinkEvent.Ignored(FrameIgnored.CLOSED))
        if (frame.kind.direction !=
            VoiceDirection.DOWNLINK
        ) {
            return violation(StreamViolation.WRONG_KIND)
        }
        return when (frame.kind) {
            VoiceFrameKind.DOWNLINK_OPUS_HEADER -> acceptHeader(frame)
            VoiceFrameKind.DOWNLINK_AUDIO -> acceptAudio(frame)
            VoiceFrameKind.DOWNLINK_END -> acceptEnd(frame)
            else -> acceptAbort(frame)
        }
    }

    private fun acceptHeader(frame: VoiceFrame): DownlinkStep {
        if (format != CaptureFormat.OPUS) return violation(StreamViolation.HEADER_ON_PCM)
        if (headerSeen) return violation(StreamViolation.HEADER_REPEATED)
        if (frame.sequence != 0 || nextSequence != 0) return violation(StreamViolation.SEQUENCE)
        return step(
            DownlinkStream(stream, format, 1, true, audioFrames, audioBytes, false),
            DownlinkEvent.Header()
        )
    }

    private fun acceptAudio(frame: VoiceFrame): DownlinkStep {
        if (format == CaptureFormat.OPUS &&
            !headerSeen
        ) {
            return violation(StreamViolation.HEADER_REQUIRED)
        }
        if (frame.sequence != nextSequence) return violation(StreamViolation.SEQUENCE)
        val body = frame.body
        if (format == CaptureFormat.PCM16) {
            if (body.size % 2 != 0 ||
                body.size !in VoiceLimits.PCM_FRAME_MIN..VoiceLimits.OUT_PCM_FRAME_BYTES
            ) {
                return violation(StreamViolation.AUDIO_SIZE)
            }
        } else if (!OpusRules.isTwentyMsPacket(body)) {
            return violation(StreamViolation.OPUS_PACKET)
        }
        if (audioFrames >=
            VoiceLimits.OUT_MAX_FRAMES
        ) {
            return violation(StreamViolation.TOO_MANY_FRAMES)
        }
        if (audioBytes.toLong() + body.size >
            VoiceLimits.OUT_MAX_PCM_BYTES
        ) {
            return violation(StreamViolation.TOO_MANY_BYTES)
        }
        val next = DownlinkStream(
            stream,
            format,
            nextSequence + 1,
            headerSeen,
            audioFrames + 1,
            audioBytes + body.size,
            false
        )
        if (next.durationMs > VoiceLimits.OUT_MAX_MS) return violation(StreamViolation.TOO_LONG)
        return step(next, DownlinkEvent.Audio(next.audioFrames, next.audioBytes))
    }

    private fun acceptEnd(frame: VoiceFrame): DownlinkStep {
        if (frame.sequence != nextSequence) return violation(StreamViolation.SEQUENCE)
        if (frame.endCount != audioFrames.toLong()) return violation(StreamViolation.END_COUNT)
        if (headerSeen && audioFrames == 0) return violation(StreamViolation.HEADER_WITHOUT_AUDIO)
        val ended =
            DownlinkStream(
                stream,
                format,
                nextSequence + 1,
                headerSeen,
                audioFrames,
                audioBytes,
                true
            )
        return step(ended, DownlinkEvent.Completed(audioFrames, durationMs))
    }

    private fun acceptAbort(frame: VoiceFrame): DownlinkStep {
        if (frame.sequence != nextSequence) return violation(StreamViolation.SEQUENCE)
        val code = frame.abortCode ?: return violation(StreamViolation.WRONG_KIND)
        val ended =
            DownlinkStream(
                stream,
                format,
                nextSequence + 1,
                headerSeen,
                audioFrames,
                audioBytes,
                true
            )
        return step(ended, DownlinkEvent.Aborted(code))
    }

    private fun violation(reason: StreamViolation): DownlinkStep = step(
        DownlinkStream(stream, format, nextSequence, headerSeen, audioFrames, audioBytes, true),
        DownlinkEvent.Violation(reason)
    )

    private fun step(next: DownlinkStream, event: DownlinkEvent) = DownlinkStep(next, event)

    override fun toString(): String =
        "DownlinkStream(stream=${stream.value}, ${format.wire}, frames=$audioFrames, closed=$closed)"

    companion object {
        /** The reader of the stream named by the current `SpeechResult.stream` for the negotiated [format]. */
        fun open(stream: VoiceStream, format: CaptureFormat): DownlinkStream =
            DownlinkStream(stream, format, 0, false, 0, 0, false)
    }
}
