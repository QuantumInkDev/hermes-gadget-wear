package dev.quantumink.hermesgadget.protocol

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    @Test
    fun cleartextRequiresBothChoicesAndIsScopedToPrivateAddresses() {
        val url = "ws://localhost/gadget"
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(url) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(url, true, false) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(url, false, true) }
        assertFalse(Endpoint.parse(url, true, true).isTls)
        val public = address(8, 8, 4, 4)
        assertThrows(IllegalArgumentException::class.java) {
            Endpoint.parse("ws://" + public.hostAddress + "/gadget", true, true)
        }
    }

    @Test
    fun tlsCanonicalizesWithoutCredentialsAndDiagnosticsRedact() {
        val endpoint = Endpoint.parse("WSS://EXAMPLE.COM:443/a/../gadget")
        assertEquals("wss://example.com/gadget", endpoint.url)
        assertTrue(endpoint.isTls)
        assertFalse(endpoint.toString().contains("example.com"))
        listOf(
            "https://example.com",
            "wss://u:p@example.com/gadget",
            "wss://example.com/?token=example",
            "wss://example.com/#fragment",
            "ws://[::%1]/",
            "wss://example.com:0"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(it, true, true) }
        }
    }

    @Test
    fun dnsChecksEveryAnswerOnEveryLookupAndTlsUsesNormalDns() {
        var answers = listOf(address(10, 1, 2, 3))
        val resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = answers
        }
        val gate = EndpointDns(Endpoint.parse("ws://host.test/gadget", true, true), resolver)
        assertEquals(answers, gate.lookup("host.test"))
        answers = answers + address(8, 8, 4, 4)
        assertThrows(PublicCleartextAddress::class.java) { gate.lookup("host.test") }
        answers = emptyList()
        assertThrows(PublicCleartextAddress::class.java) { gate.lookup("host.test") }
        answers = listOf(address(8, 8, 4, 4))
        val tls = EndpointDns(Endpoint.parse("wss://host.test/gadget"), resolver)
        assertEquals(answers, tls.lookup("host.test"))
    }

    @Test
    fun privateRangesRejectUnspecifiedMulticastAndPublicEdges() {
        listOf(
            address(10, 0, 0, 1), address(172, 16, 0, 1), address(172, 31, 255, 254),
            address(192, 168, 0, 1), address(100, 64, 0, 1), address(100, 127, 255, 254),
            address(169, 254, 1, 1), InetAddress.getByName("::1"),
            InetAddress.getByName("fd00::1"), InetAddress.getByName("fe80::1")
        ).forEach { assertTrue(PrivateAddresses.contains(it)) }
        listOf(
            address(0, 0, 0, 0), address(172, 15, 0, 1), address(172, 32, 0, 1),
            address(100, 63, 0, 1), address(100, 128, 0, 1), address(224, 0, 0, 1),
            InetAddress.getByName("::"), InetAddress.getByName("ff02::1"),
            InetAddress.getByName("2001:db8::1")
        ).forEach { assertFalse(PrivateAddresses.contains(it)) }
    }

    private fun address(vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
}
