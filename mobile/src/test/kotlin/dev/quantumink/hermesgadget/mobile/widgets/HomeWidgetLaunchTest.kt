package dev.quantumink.hermesgadget.mobile.widgets

import dev.quantumink.hermesgadget.mobile.PhoneConversationState
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetLaunchTest {
    @Test fun sameProfileIsIdempotentButAnotherProfileWaitsForTheTurn() {
        assertEquals(LaunchDecision.IGNORE, decideLaunch("a:one", "a:one", true, true))
        assertEquals(LaunchDecision.DEFER, decideLaunch("b:one", "a:one", true, true))
        assertEquals(LaunchDecision.OPEN, decideLaunch("b:one", "a:one", false, true))
        assertEquals(LaunchDecision.UNAVAILABLE, decideLaunch("a:one", "a:one", true, false))
    }

    @Test fun staleSlotRevisionOrProviderCannotLaunchAChangedBot() {
        val config = WidgetConfig(HomeWidgetPreset.FOCUS, listOf("a:one"), revision = 2)
        assertTrue(WidgetLaunchRequest(1, 0, "a:one", 2).matches(config, HomeWidgetPreset.FOCUS))
        assertFalse(WidgetLaunchRequest(1, 0, "a:one", 1).matches(config, HomeWidgetPreset.FOCUS))
        assertFalse(WidgetLaunchRequest(1, 0, "b:one", 2).matches(config, HomeWidgetPreset.FOCUS))
        assertFalse(WidgetLaunchRequest(1, 0, "a:one", 2).matches(config, HomeWidgetPreset.FOUR))
        assertFalse(WidgetLaunchRequest(1, 1, "a:one", 2).matches(config, HomeWidgetPreset.FOCUS))
    }

    @Test fun malformedRequestsNeverCarryUrlsOrUnboundedSlots() {
        assertThrows(IllegalArgumentException::class.java) { WidgetLaunchRequest(0, 0, "a:one", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetLaunchRequest(1, 16, "a:one", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetLaunchRequest(1, 0, "wss://host.invalid", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WidgetLaunchRequest(1, 0, "a:one", -1)
        }
    }

    @Test fun recordingPromptSendingAndSpeakingAllProtectTheForegroundTurn() {
        val ready = PhoneConversationState(visible = true)
        assertFalse(phoneSelectionLocked(ready))
        val conversation =
            listOf(
                ConversationState(mode = ConversationMode.LISTENING),
                ConversationState(busy = true),
                ConversationState(prompt = Prompt("p", "Allow", "Review", 1, null))
            )
        conversation.forEach {
            assertTrue(
                phoneSelectionLocked(ready.copy(connection = ConnectionState(conversation = it)))
            )
        }
        assertTrue(phoneSelectionLocked(ready.copy(sending = true)))
        assertTrue(phoneSelectionLocked(ready.copy(speaking = true)))
        assertFalse(phoneSelectionLocked(ready.copy(visible = false, speaking = true)))
    }
}
