package dev.quantumink.hermesgadget.protocol.relay.state

import java.io.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server clock: `relay.info.server_time_ms` + a local monotonic offset, never the device wall clock. */
class ServerClockTest {
    private val info = StateTestSupport.clockInfo
    private val anchor = StateTestSupport.serverTime

    private fun fix(serverMs: Long?, nanos: Long): ClockFix = ServerClock.fix(serverMs, nanos)

    private fun clock(serverMs: Long = anchor, nanos: Long = 5_000_000_000L): ServerClock =
        (fix(serverMs, nanos) as ClockFix.Fixed).clock

    private fun read(clock: ServerClock, nanos: Long): InstantRead = clock.read(nanos)

    @Test fun theCapturedConversationInfoCarriesTheServerTimeThatAnchorsTheClock() {
        assertEquals(1_792_300_000_000L, anchor)
        val fixed = ServerClock.fix(info, 5_000_000_000L)
        assertTrue(fixed is ClockFix.Fixed)
        val at = read((fixed as ClockFix.Fixed).clock, 5_000_000_000L) as InstantRead.At
        assertEquals(anchor, at.instant.serverMs)
        assertEquals(5_000_000_000L, at.instant.monotonicNanos)
    }

    @Test fun theO5OnlyInfoHasNoClockAndRefusesEveryTimeDecisionWithoutAWallClockFallback() {
        assertNull(StateTestSupport.o5OnlyInfo.serverTimeMs)
        val refused = ServerClock.fix(StateTestSupport.o5OnlyInfo, 5_000_000_000L)
        assertEquals(TimeRefusal.NO_SERVER_TIME, (refused as ClockFix.Refused).reason)
        // The only way to a ServerClock is a fix; there is no member that consults System time.
        val names = ServerClock::class.java.methods.map { it.name }.toSet()
        assertFalse(
            names.any {
                it.contains("wall", ignoreCase = true) ||
                    it.contains("system", ignoreCase = true)
            }
        )
        assertEquals(setOf("read"), names.filter { it == "read" }.toSet())
    }

    @Test fun elapsedTimeIsFloorMillisecondsOfTheMonotonicDelta() {
        val c = clock()
        fun ms(delta: Long) =
            (read(c, 5_000_000_000L + delta) as InstantRead.At).instant.serverMs - anchor
        assertEquals(0, ms(0))
        assertEquals(0, ms(999_999))
        assertEquals(1, ms(1_000_000))
        assertEquals(1, ms(1_999_999))
        assertEquals(2, ms(2_000_000))
        assertEquals(86_400_000, ms(86_400_000L * 1_000_000L))
    }

    @Test fun negativeOutOfRangeAndRolledBackInputsAreRefusedNotRepaired() {
        assertEquals(TimeRefusal.NEGATIVE_INPUT, (fix(-1, 1) as ClockFix.Refused).reason)
        assertEquals(TimeRefusal.NEGATIVE_INPUT, (fix(anchor, -1) as ClockFix.Refused).reason)
        assertEquals(
            TimeRefusal.SERVER_TIME_OUT_OF_RANGE,
            (fix(StateLimits.SERVER_TIME_MAX_MS + 1, 1) as ClockFix.Refused).reason
        )
        // The exact boundary is the server's own limit and is accepted.
        assertTrue(fix(StateLimits.SERVER_TIME_MAX_MS, 1) is ClockFix.Fixed)
        val c = clock()
        assertEquals(TimeRefusal.NEGATIVE_INPUT, (read(c, -1) as InstantRead.Refused).reason)
        // A reading before the fix cannot be a later point of the same monotonic source.
        assertEquals(
            TimeRefusal.MONOTONIC_ROLLBACK,
            (read(c, 4_999_999_999L) as InstantRead.Refused).reason
        )
        assertTrue(read(c, 5_000_000_000L) is InstantRead.At)
    }

    @Test fun arithmeticNeverWrapsAndNeverExceedsTheServersOwnRange() {
        val near = clock(StateLimits.SERVER_TIME_MAX_MS - 5, 0)
        assertEquals(
            StateLimits.SERVER_TIME_MAX_MS,
            (read(near, 5_000_000L) as InstantRead.At).instant.serverMs
        )
        assertEquals(
            TimeRefusal.ARITHMETIC_OVERFLOW,
            (read(near, 6_000_000L) as InstantRead.Refused).reason
        )
        // A huge but valid monotonic reading simply produces a large estimate; it cannot wrap.
        val big = (read(clock(anchor, 0), Long.MAX_VALUE) as InstantRead.At).instant.serverMs
        assertTrue(big > anchor)
    }

    @Test fun aRefixAfterARestartAdoptsTheNewAnchorAndOldInstantsStayWhatTheyWere() {
        val first = clock(anchor, 5_000_000_000L)
        val before = (read(first, 6_000_000_000L) as InstantRead.At).instant
        val second = clock(anchor + 90_000, 100_000_000L)
        val after = (read(second, 100_000_000L) as InstantRead.At).instant
        assertEquals(anchor + 1_000, before.serverMs)
        assertEquals(anchor + 90_000, after.serverMs)
        // The monotonic ordering of decisions is enforced where a decision is recorded.
        assertEquals(TimeRefusal.MONOTONIC_ROLLBACK, monotonicRefusal(before.monotonicNanos, after))
        assertNull(monotonicRefusal(after.monotonicNanos, before))
    }

    @Test fun aClockAndAnInstantAreNotPersistableAndPrintOnlyNumbers() {
        assertFalse(Serializable::class.java.isAssignableFrom(ServerClock::class.java))
        assertFalse(Serializable::class.java.isAssignableFrom(ServerInstant::class.java))
        assertEquals("ServerClock", clock().toString())
        assertNotNull(StateTestSupport.at(0).toString())
    }
}
