package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayTest {
    @Test
    fun secureHeaderRoundTripAndStrictBounds() {
        val endpoint = Endpoint.parse("wss://example.invalid:8443/gadget")
        val output = ByteArrayOutputStream()
        RelayProtocol.writeRequest(output, endpoint)
        assertEquals(
            endpoint.url,
            RelayProtocol.readRequest(ByteArrayInputStream(output.toByteArray())).url
        )
        val oversized = ByteArrayOutputStream()
        DataOutputStream(oversized).writeShort(RelayProtocol.MAX_HEADER + 1)
        assertThrows(IllegalArgumentException::class.java) {
            RelayProtocol.readRequest(ByteArrayInputStream(oversized.toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayProtocol.writeRequest(
                output,
                Endpoint.parse("ws://localhost/gadget", true, true)
            )
        }
    }

    @Test
    fun proxyRequiresTlsAndCannotTargetAnExternalProxy() {
        val observer = object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) = Unit
            override fun effect(effect: ConversationEffect, generation: Long) = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectConnection(
                Endpoint.parse("ws://localhost/gadget", true, true),
                DeviceIdentity.generate(),
                observer,
                connectionProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1234))
            )
        }
    }

    @Test(timeout = 10000)
    fun loopbackProxyRejectsUnauthenticatedAndWrongAuthorityRequestsBeforeOpeningRelay() {
        val opened = AtomicBoolean(false)
        LoopbackConnectBridge(Endpoint.parse("wss://example.invalid/gadget")) {
            opened.set(true)
            error("Unexpected relay open")
        }.use { bridge ->
            listOf("example.invalid:443", "other.invalid:443").forEach { authority ->
                Socket().use { socket ->
                    socket.connect(bridge.proxy.address())
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(
                        "CONNECT $authority HTTP/1.1\r\n\r\n".toByteArray()
                    )
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertFalse(opened.get())
        }
    }
}
