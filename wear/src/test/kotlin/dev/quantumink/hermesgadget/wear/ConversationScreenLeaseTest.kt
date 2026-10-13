package dev.quantumink.hermesgadget.wear

import dev.quantumink.hermesgadget.client.WatchRecordingHaptic
import dev.quantumink.hermesgadget.client.WatchTick
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationScreenLeaseTest {
    @Test fun deadlineIsMonotonicAndNeverRenewedByRepeatedEvaluation() {
        assertEquals(0L, ConversationScreenLease.remaining(null, 0))
        assertEquals(120000L, ConversationScreenLease.remaining(500, 500))
        assertEquals(1L, ConversationScreenLease.remaining(500, 120499))
        repeat(20) { assertEquals(0L, ConversationScreenLease.remaining(500, 120500L + it)) }
        assertEquals(120000L, ConversationScreenLease.remaining(120501, 120501))
    }

    @Test fun recordingUsesDeviceTunedTickBeforeAttenuatedFallbacks() {
        assertEquals(WatchTick.EFFECT, WatchRecordingHaptic.select(true, true, true))
        assertEquals(WatchTick.EFFECT, WatchRecordingHaptic.select(false, true, true))
        assertEquals(WatchTick.EFFECT, WatchRecordingHaptic.select(false, false, true))
        assertEquals(WatchTick.PRIMITIVE, WatchRecordingHaptic.select(true, true, false))
        assertEquals(WatchTick.AMPLITUDE, WatchRecordingHaptic.select(false, true, false))
        assertEquals(WatchTick.DEFAULT, WatchRecordingHaptic.select(false, false, false))
    }
}
