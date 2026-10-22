package dev.quantumink.hermesgadget.protocol.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contract's client rules, as a pure classification (nothing is dispatched or retried here). */
class RelayErrorTest {
    private fun error(code: RelayErrorCode, snapshot: Boolean = false) = RelayError(code, snapshot)

    @Test fun everyStableCodeHasExactlyTheContractAction() {
        val expected = mapOf(
            RelayErrorCode.TEMPORARILY_UNAVAILABLE to RelayErrorAction.RETRY_SAME_REQUEST,
            RelayErrorCode.BUSY to RelayErrorAction.RETRY_SAME_REQUEST,
            RelayErrorCode.RATE_LIMITED to RelayErrorAction.RETRY_SAME_REQUEST,
            RelayErrorCode.STALE_BINDING to RelayErrorAction.REATTACH_AND_REFRESH,
            RelayErrorCode.STALE_EPOCH to RelayErrorAction.REATTACH_AND_REFRESH,
            RelayErrorCode.SNAPSHOT_REQUIRED to RelayErrorAction.REATTACH_AND_REFRESH,
            RelayErrorCode.PERMISSION_DENIED to RelayErrorAction.STOP_AND_EVICT_SCOPE,
            RelayErrorCode.PROFILE_CHANGED to RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
        )
        for (code in RelayErrorCode.entries) {
            assertEquals(code.wire, expected[code] ?: RelayErrorAction.FAILED, error(code).action)
        }
        assertEquals(
            setOf(
                RelayErrorCode.INVALID_REQUEST,
                RelayErrorCode.UNSUPPORTED,
                RelayErrorCode.NOT_FOUND,
                RelayErrorCode.CONFLICT,
                RelayErrorCode.EXPIRED,
                RelayErrorCode.PROJECTION_TOO_LARGE,
                RelayErrorCode.UNKNOWN
            ),
            RelayErrorCode.entries.filter { error(it).action == RelayErrorAction.FAILED }.toSet()
        )
    }

    @Test fun theSnapshotFlagMakesAnyOtherwiseTerminalCodeARefreshButNeverOverridesDenial() {
        for (code in listOf(
            RelayErrorCode.CONFLICT,
            RelayErrorCode.EXPIRED,
            RelayErrorCode.NOT_FOUND,
            RelayErrorCode.STALE_EPOCH
        )) {
            assertEquals(RelayErrorAction.REATTACH_AND_REFRESH, error(code, true).action)
            assertTrue(error(code, true).needsFreshView)
        }
        assertEquals(
            RelayErrorAction.STOP_AND_EVICT_SCOPE,
            error(RelayErrorCode.PERMISSION_DENIED, true).action
        )
        assertEquals(
            RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW,
            error(RelayErrorCode.PROFILE_CHANGED, true).action
        )
        assertFalse(error(RelayErrorCode.CONFLICT).needsFreshView)
        assertTrue(error(RelayErrorCode.SNAPSHOT_REQUIRED).needsFreshView)
    }

    @Test fun wireSpellingsAreTheAcceptedSdkSetAndUnknownTokensNeverMapToAKnownOne() {
        assertEquals(15, RelayErrorCode.entries.size)
        assertEquals(
            RelayErrorCode.PROJECTION_TOO_LARGE,
            RelayErrorCode.fromWire("projection_too_large")
        )
        assertEquals(RelayErrorCode.UNKNOWN, RelayErrorCode.fromWire("Busy"))
        assertEquals(RelayErrorCode.UNKNOWN, RelayErrorCode.fromWire("unknown"))
        assertEquals(RelayErrorCode.UNKNOWN, RelayErrorCode.fromWire(""))
        assertEquals(
            "RelayError(busy, snapshotRequired=false)",
            error(RelayErrorCode.BUSY).toString()
        )
        assertEquals(error(RelayErrorCode.BUSY), error(RelayErrorCode.BUSY))
    }
}
