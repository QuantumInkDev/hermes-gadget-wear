package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointProfilesTest {
    @Test fun canonicalUrlsAndPoliciesRoundTripWithoutKeys() {
        val first =
            EndpointProfile(
                Endpoint.parse("wss://EXAMPLE.invalid:443/gadget"),
                "First",
                "",
                TransportPreference.RELAY
            )
        val second =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8765/gadget", true, true),
                "Second",
                "syntheticToken",
                TransportPreference.DIRECT
            )
        val data = EndpointProfiles.encode(listOf(first, second))
        val decoded = EndpointProfiles.decode(data)
        assertEquals("wss://example.invalid/gadget", decoded[0].endpoint.url)
        assertEquals(TransportPreference.DIRECT, decoded[1].transport)
        assertEquals("syntheticToken", decoded[1].accessToken)
        assertFalse(data.contains("device_key"))
        assertFalse(first.toString().contains(first.endpoint.url))
    }

    @Test fun boundedCatalogRejectsDuplicateMalformedOrInsecureProfiles() {
        val first = EndpointProfile(Endpoint.parse("wss://example.invalid/gadget"), "First", "")
        assertThrows(IllegalArgumentException::class.java) {
            EndpointProfiles.encode(List(9) { first })
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointProfiles.encode(listOf(first, first))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointProfiles.decode(" ".repeat(65537))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointProfile(first.endpoint, "First", "bad\ntoken")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndpointProfile(
                Endpoint.parse("ws://localhost/gadget", true, true),
                "LAN",
                "",
                TransportPreference.RELAY
            )
        }
    }
}
