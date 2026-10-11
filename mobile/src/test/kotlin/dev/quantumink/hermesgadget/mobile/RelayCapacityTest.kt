package dev.quantumink.hermesgadget.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCapacityTest {
    @Test fun slowCatalogDoesNotPreventRelayAndSpeechAdmission() {
        val capacity = RelayCapacity()
        val relay = requireNotNull(capacity.acquire(false))
        val catalog = requireNotNull(capacity.acquire(true))
        val speech = capacity.acquire(false)
        assertNotNull("Speech must remain available alongside relay and catalog", speech)
        assertNull(capacity.acquire(true))
        assertNull(capacity.acquire(false))
        relay.close()
        catalog.close()
        assertFalse(capacity.idle)
        requireNotNull(speech).close()
        assertTrue(capacity.idle)
    }

    @Test fun cancellationReleasesEachKindOnceAndKeepsForegroundForOtherWork() {
        val capacity = RelayCapacity()
        val catalog = requireNotNull(capacity.acquire(true))
        val relay = requireNotNull(capacity.acquire(false))
        catalog.close()
        catalog.close()
        assertFalse(capacity.idle)
        val next = requireNotNull(capacity.acquire(true))
        assertNull(capacity.acquire(true))
        relay.close()
        assertFalse(capacity.idle)
        next.close()
        next.close()
        assertTrue(capacity.idle)
    }
}
