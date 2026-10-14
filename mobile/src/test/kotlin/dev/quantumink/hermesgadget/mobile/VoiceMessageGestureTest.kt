package dev.quantumink.hermesgadget.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageGestureTest {
    @Test fun releaseSendsOnceAfterTheMinimumHold() {
        val gesture = VoiceMessageGesture(64f)
        assertTrue(gesture.begin(100))
        assertFalse(gesture.begin(120))
        assertEquals(VoiceMessageOutcome.SEND, gesture.end(700))
        assertEquals(null, gesture.end(800))
        assertEquals(null, gesture.cancel())
    }

    @Test fun leftCancellationCannotBeRearmedByMovingRightOrReleasing() {
        val gesture = VoiceMessageGesture(64f)
        gesture.begin(100)
        assertFalse(gesture.move(63f, 0f))
        assertTrue(gesture.move(65f, 2f))
        assertFalse(gesture.move(0f, 0f))
        assertEquals(VoiceMessageOutcome.CANCEL, gesture.end(800))
        assertEquals(null, gesture.end(900))
        assertTrue(gesture.begin(1000))
        assertEquals(VoiceMessageOutcome.SEND, gesture.end(1400))
    }

    @Test fun shortPressAndExternalCancellationNeverSend() {
        val gesture = VoiceMessageGesture(64f)
        gesture.begin(100)
        assertEquals(VoiceMessageOutcome.CANCEL, gesture.end(349))
        gesture.begin(1000)
        assertEquals(VoiceMessageOutcome.CANCEL, gesture.cancel())
        assertEquals(null, gesture.end(2000))
    }

    @Test fun verticalAndRightwardTravelAreNotLeftCancellation() {
        val gesture = VoiceMessageGesture(64f)
        gesture.begin(100)
        assertFalse(gesture.move(70f, 150f))
        assertFalse(gesture.move(-100f, 0f))
        assertEquals(VoiceMessageOutcome.SEND, gesture.end(600))
    }
}
