package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Captured on the controller thread from the Info/immutable clock that admitted this read.
 * Final SEND reads only final anchor/expiry data and atomics, never actor-owned controller state.
 * Local time is an estimate, not proof of server acceptance. A fresh Info creates a new lease.
 */
internal class InboxAuthorityLease private constructor(
    private val clock: ServerClock,
    private val expiresAtMs: Long,
    admitted: ServerInstant
) {
    private val highWater = AtomicLong(admitted.monotonicNanos)
    private val withdrawn = AtomicBoolean()

    fun allows(nanos: Long, promptDeadlineMs: Long? = null): Boolean {
        if (withdrawn.get()) return false
        if (promptDeadlineMs != null && promptDeadlineMs !in 1..StateLimits.SERVER_TIME_MAX_MS) {
            return refuse()
        }
        while (true) {
            val previous = highWater.get()
            if (nanos < previous) return refuse()
            if (highWater.compareAndSet(previous, nanos)) break
        }
        val instant = (clock.read(nanos) as? InstantRead.At)?.instant ?: return refuse()
        // ServerClock floors sub-ms elapsed time; use the conservative upper millisecond.
        val upper = try {
            Math.addExact(instant.serverMs, 1L)
        } catch (_: ArithmeticException) {
            return refuse()
        }
        if (upper > StateLimits.SERVER_TIME_MAX_MS || upper >= expiresAtMs ||
            (promptDeadlineMs != null && upper >= promptDeadlineMs)
        ) {
            return refuse()
        }
        return !withdrawn.get()
    }

    /** Actor-side request identity uses this SAME immutable clock, never a later Info sample. */
    fun instant(nanos: Long, promptDeadlineMs: Long?): ServerInstant? {
        if (!allows(nanos, promptDeadlineMs)) return null
        return (clock.read(nanos) as? InstantRead.At)?.instant
    }

    private fun refuse(): Boolean {
        withdrawn.set(true)
        return false
    }

    companion object {
        fun captureInput(info: Info?, clock: ServerClock?, nanos: Long): InboxAuthorityLease? {
            if (info == null || info.expiresAtMs !in 1..StateLimits.SERVER_TIME_MAX_MS) return null
            return capture(info, clock, nanos)
        }

        fun capture(info: Info?, clock: ServerClock?, nanos: Long): InboxAuthorityLease? {
            if (info == null || clock == null) return null
            val admitted = (clock.read(nanos) as? InstantRead.At)?.instant ?: return null
            return InboxAuthorityLease(clock, info.expiresAtMs, admitted).takeIf {
                it.allows(nanos)
            }
        }
    }
}
