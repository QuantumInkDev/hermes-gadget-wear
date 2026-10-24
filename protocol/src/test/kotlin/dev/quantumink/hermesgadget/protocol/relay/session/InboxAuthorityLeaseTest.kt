package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.state.ClockFix
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxAuthorityLeaseTest {
    private val anchor = 5_000_000_000L
    private fun clock(server: Long = Dto.SERVER_TIME) =
        (ServerClock.fix(server, anchor) as ClockFix.Fixed).clock

    @Test fun nullAuthorityAndInvalidAdmissionTimeRefuse() {
        val info = Dto.info()
        assertNull(InboxAuthorityLease.capture(null, clock(), anchor))
        assertNull(InboxAuthorityLease.capture(info, null, anchor))
        assertNull(InboxAuthorityLease.capture(info, clock(), -1))
        assertNull(InboxAuthorityLease.capture(info, clock(), anchor - 1))
    }

    @Test fun immutableLeaseExpiresConservativelyAndCannotRevive() {
        val info = Dto.info(expiresAtMs = Dto.SERVER_TIME + 10)
        val lease = InboxAuthorityLease.capture(info, clock(), anchor)!!
        assertTrue(lease.allows(anchor + 8_000_000))
        assertFalse(lease.allows(anchor + 9_000_000))
        assertFalse(lease.allows(anchor + 1_000_000))
        assertNull(InboxAuthorityLease.capture(info, clock(), anchor + 10_000_000))
    }

    @Test fun monotonicRollbackSinceAnEarlierDecisionIsSticky() {
        val lease = InboxAuthorityLease.capture(Dto.info(), clock(), anchor)!!
        assertTrue(lease.allows(anchor + 20))
        assertFalse(lease.allows(anchor + 19))
        assertFalse(lease.allows(anchor + 21))
    }

    @Test fun serverEstimateOverflowRefusesInsteadOfWrapping() {
        val server = StateLimits.SERVER_TIME_MAX_MS - 2
        val info = Dto.info(serverTimeMs = server, expiresAtMs = Long.MAX_VALUE)
        val lease = InboxAuthorityLease.capture(info, clock(server), anchor)!!
        assertFalse(lease.allows(anchor + 3_000_000))
        assertFalse(lease.allows(anchor))
        assertNull(
            InboxAuthorityLease.capture(
                Dto.info(
                    serverTimeMs = StateLimits.SERVER_TIME_MAX_MS,
                    expiresAtMs = Long.MAX_VALUE
                ),
                clock(StateLimits.SERVER_TIME_MAX_MS),
                anchor
            )
        )
    }
}
