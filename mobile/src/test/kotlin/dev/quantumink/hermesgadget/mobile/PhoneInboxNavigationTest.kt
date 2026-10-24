package dev.quantumink.hermesgadget.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneInboxNavigationTest {
    @Test fun coldWarmSameProfileAndReplacementUseExactLookupTarget() {
        val calls = ArrayList<Pair<String, (String?) -> Unit>>()
        val nav = PhoneInboxNavigation<String> { hint, done -> calls.add(hint to done) }
        val results = ArrayList<String?>()
        nav.request("cold", { true }, { false }, results::add)
        calls.last().second("profile A / conversation 1")
        nav.request("same-profile", { true }, { false }, results::add)
        calls.last().second("profile A / conversation 2")
        nav.request("old", { true }, { false }, results::add)
        val old = calls.last().second
        nav.request("warm replacement", { true }, { false }, results::add)
        old("must not open")
        calls.last().second("profile B / conversation 1")
        assertEquals(
            listOf(
                "profile A / conversation 1",
                "profile A / conversation 2",
                "profile B / conversation 1"
            ),
            results
        )
    }

    @Test fun busyAndStoppedLifecycleKeepOnlyHintThenRevalidateOnRelease() {
        val calls = ArrayList<Pair<String, (String?) -> Unit>>()
        val nav = PhoneInboxNavigation<String> { hint, done -> calls.add(hint to done) }
        var ready = false
        var busy = true
        val results = ArrayList<String?>()
        nav.request("exact hint", { ready }, { busy }, results::add)
        assertTrue(calls.isEmpty())
        ready = true
        nav.release({ ready }, { busy }, results::add)
        assertTrue(calls.isEmpty())
        busy = false
        nav.release({ ready }, { busy }, results::add)
        assertEquals("exact hint", calls.single().first)
        nav.suspend()
        calls.single().second("stale")
        nav.release({ true }, { false }, results::add)
        calls.last().second(null) // purged/withdrawn at the actual new lookup
        assertEquals(listOf<String?>(null), results)
    }

    @Test fun foregroundPreemptsBeforeReplacementAndOldCompletionCannotReleaseNewLease() {
        val leases = InboxLeases()
        val observations = ArrayList<String>()
        val background = leases.background { observations.add("withdraw") }!!
        val foreground = leases.foreground()
        assertEquals(listOf("withdraw"), observations)
        assertFalse(leases.current(background))
        assertTrue(leases.current(foreground))
        leases.release(background)
        assertTrue(leases.current(foreground))
        assertNull(leases.background { fail("must not start under foreground") })
        leases.release(foreground)
        assertNotNull(leases.background {})
    }

    @Test fun foregroundReplacementWaitsForActualBackgroundWithdrawalAndRefusesNewBackground() {
        val leases = InboxLeases()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val complete = java.util.concurrent.CountDownLatch(1)
        val old = leases.background {
            entered.countDown()
            check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }!!
        var current = 0L
        val thread = Thread {
            current = leases.foreground()
            complete.countDown()
        }
        try {
            thread.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(leases.current(old))
            assertNull(leases.background {})
            assertFalse(complete.await(30, java.util.concurrent.TimeUnit.MILLISECONDS))
            finish.countDown()
            assertTrue(complete.await(5, java.util.concurrent.TimeUnit.SECONDS))
            leases.release(old)
            assertTrue(leases.current(current))
        } finally {
            finish.countDown()
            thread.join(5000)
        }
    }
}
