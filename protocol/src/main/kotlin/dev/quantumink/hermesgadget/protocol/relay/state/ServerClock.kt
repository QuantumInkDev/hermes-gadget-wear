package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.Info

/** Why a clock fix or reading was refused. A refusal never falls back to the device wall clock. */
enum class TimeRefusal {
    /** The `relay.info` answer was the O5-only form: it carries no `server_time_ms`, so there is no clock. */
    NO_SERVER_TIME,

    /** A negative server time or monotonic reading. */
    NEGATIVE_INPUT,

    /** A server time beyond what the server itself accepts (`relay_store._clock`). */
    SERVER_TIME_OUT_OF_RANGE,

    /** The local monotonic reading went backwards (before the fix, or before an earlier reading). */
    MONOTONIC_ROLLBACK,

    /** The checked arithmetic would have wrapped. */
    ARITHMETIC_OVERFLOW
}

sealed interface ClockFix {
    class Fixed internal constructor(val clock: ServerClock) : ClockFix {
        override fun toString(): String = "ClockFix.Fixed"
    }

    class Refused internal constructor(val reason: TimeRefusal) : ClockFix {
        override fun toString(): String = "ClockFix.Refused($reason)"
    }
}

sealed interface InstantRead {
    class At internal constructor(val instant: ServerInstant) : InstantRead {
        override fun toString(): String = "InstantRead.At($instant)"
    }

    class Refused internal constructor(val reason: TimeRefusal) : InstantRead {
        override fun toString(): String = "InstantRead.Refused($reason)"
    }
}

/** One estimate of the server's clock at one local monotonic reading. Only a [ServerClock] makes one. */
class ServerInstant internal constructor(
    /**
     * The estimated server time in milliseconds: the sampled `server_time_ms` plus local monotonic elapsed time. It
     * is an ESTIMATE only; it may lead or lag the server (see [ServerClock]). */
    val serverMs: Long,
    val monotonicNanos: Long
) {
    override fun toString(): String =
        "ServerInstant(serverMs=$serverMs, monotonicNanos=$monotonicNanos)"

    override fun equals(other: Any?): Boolean = other is ServerInstant &&
        other.serverMs == serverMs && other.monotonicNanos == monotonicNanos

    override fun hashCode(): Int = 31 * serverMs.hashCode() + monotonicNanos.hashCode()
}

/**
 * The server clock as the contract defines it: `relay.info.server_time_ms` plus a LOCAL MONOTONIC offset, never the
 * device wall clock. It is immutable and deliberately not serializable: a reading is only meaningful inside the
 * process/boot whose monotonic source made it, so an offset is never a durable cross-boot clock and a saved
 * timestamp can never revive clock authority. A restart needs a fresh `relay.info`.
 *
 * The monotonic reading is a NON-NEGATIVE count of nanoseconds from one fixed origin that keeps counting while the
 * device sleeps (on Android `SystemClock.elapsedRealtimeNanos()`); `System.nanoTime()` may be negative and is refused.
 * Guarantee: a reading is the sampled `server_time_ms` plus the local monotonic time elapsed since the sample. Nothing
 * else. There is no device wall-clock fallback. There is NO guarantee that an estimate never leads (or never lags) the
 * server: the server's own clock can step back or drift after the sample (its wall clock can fall behind while its
 * retained high-water mark stays put), while local elapsed time keeps advancing. An estimate is therefore usable to
 * propose a request or to refuse one conservatively, and never as proof of what the server has admitted or can still
 * admit.
 */
class ServerClock private constructor(
    private val anchorServerMs: Long,
    private val anchorNanos: Long
) {
    /** The estimate at [monotonicNanos], or the reason there is none. */
    fun read(monotonicNanos: Long): InstantRead {
        if (monotonicNanos < 0) return InstantRead.Refused(TimeRefusal.NEGATIVE_INPUT)
        if (monotonicNanos < anchorNanos) return InstantRead.Refused(TimeRefusal.MONOTONIC_ROLLBACK)
        val elapsedMs = (monotonicNanos - anchorNanos) / NANOS_PER_MS
        val server = try {
            Math.addExact(anchorServerMs, elapsedMs)
        } catch (_: ArithmeticException) {
            return InstantRead.Refused(TimeRefusal.ARITHMETIC_OVERFLOW)
        }
        if (server > StateLimits.SERVER_TIME_MAX_MS) {
            return InstantRead.Refused(TimeRefusal.ARITHMETIC_OVERFLOW)
        }
        return InstantRead.At(ServerInstant(server, monotonicNanos))
    }

    override fun toString(): String = "ServerClock"

    companion object {
        private const val NANOS_PER_MS = 1_000_000L

        /** Anchors a clock at the moment [info] was received; refused for the O5-only form (no `server_time_ms`). */
        fun fix(info: Info, monotonicNanos: Long): ClockFix = fix(info.serverTimeMs, monotonicNanos)

        internal fun fix(serverTimeMs: Long?, monotonicNanos: Long): ClockFix = when {
            serverTimeMs == null -> ClockFix.Refused(TimeRefusal.NO_SERVER_TIME)
            serverTimeMs < 0 || monotonicNanos < 0 -> ClockFix.Refused(TimeRefusal.NEGATIVE_INPUT)
            serverTimeMs > StateLimits.SERVER_TIME_MAX_MS ->
                ClockFix.Refused(TimeRefusal.SERVER_TIME_OUT_OF_RANGE)
            else -> ClockFix.Fixed(ServerClock(serverTimeMs, monotonicNanos))
        }
    }
}

/**
 * The ordering guard a stateful component applies to every [ServerInstant] it is handed: the local monotonic source
 * may not go backwards between decisions, and that is enforced where a decision is recorded, not in the clock.
 */
internal fun monotonicRefusal(lastNanos: Long, instant: ServerInstant): TimeRefusal? =
    if (instant.monotonicNanos < lastNanos) TimeRefusal.MONOTONIC_ROLLBACK else null
