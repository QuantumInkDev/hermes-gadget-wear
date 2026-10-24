package dev.quantumink.hermesgadget.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxNetworkOwnerTest {
    private class Owner {
        var active: String? = "vpn"
        var dns = 0
        var sockets = 0
        var transports = 0
        var completions = 0
        var used: String? = null
        fun refresh(background: Boolean, assigned: String?): Boolean = try {
            InboxNetworkOwner.run(background, assigned, { active }) {
                used = it
                dns++
                sockets++
                transports++
            }
        } finally {
            completions++
        }
    }

    @Test fun absentBackgroundNetworkFinishesBeforeAnyTransportWork() {
        val h = Owner()
        assertFalse("Null assigned job network must refuse", h.refresh(true, null))
        assertEquals(0, h.dns)
        assertEquals(0, h.sockets)
        assertEquals(0, h.transports)
        assertNull(h.used)
        assertEquals(1, h.completions)
    }

    @Test fun mismatchedAssignedNetworkRefusesEffectiveVpn() {
        val h = Owner()
        assertFalse(h.refresh(true, "underlying"))
        assertEquals(0, h.transports)
        assertEquals(0, h.dns)
        assertEquals(1, h.completions)
    }

    @Test fun validAssignedNetworkOwnsAllDownstreamWork() {
        val h = Owner()
        assertTrue(h.refresh(true, "vpn"))
        assertEquals("vpn", h.used)
        assertEquals(1, h.dns)
        assertEquals(1, h.sockets)
        assertEquals(1, h.transports)
        assertEquals(1, h.completions)
    }

    @Test fun explicitForegroundMayChooseActiveNetwork() {
        val h = Owner()
        assertTrue(h.refresh(false, null))
        assertEquals("vpn", h.used)
        assertEquals(1, h.transports)
        assertEquals(1, h.completions)
    }

    @Test fun absentActiveNetworkRefusesBothOwners() {
        val h = Owner()
        h.active = null
        assertFalse(h.refresh(true, "vpn"))
        assertFalse(h.refresh(false, null))
        assertEquals(0, h.transports)
        assertEquals(2, h.completions)
    }
}
