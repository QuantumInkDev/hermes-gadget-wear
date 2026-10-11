package dev.quantumink.hermesgadget.wear

import dev.quantumink.hermesgadget.protocol.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchNavigationTest {
    @Test fun recreatingTheInteractiveSurfaceRetainsReviewFieldsAndRenewedConsent() {
        val navigation = WatchNavigation()
        val profile =
            EndpointProfile(
                Endpoint.parse("ws://localhost/gadget", true, true),
                "Agent",
                "synthetic"
            )
        val first = navigation.setupFor(profile, true)
        assertFalse(first.accepted)
        first.label = "Unfinished review"
        first.url = "wss://edited.example.invalid/gadget"
        first.token = "editedSynthetic"
        first.accepted = true
        val restored = navigation.setupFor(
            EndpointProfile(profile.endpoint, "Updated metadata", "synthetic"),
            true
        )
        assertEquals("Unfinished review", restored.label)
        assertEquals("wss://edited.example.invalid/gadget", restored.url)
        assertEquals("editedSynthetic", restored.token)
        assertEquals(true, restored.accepted)
        navigation.clearSetup()
        assertEquals("Agent", navigation.setupFor(profile, true).label)
    }
}
