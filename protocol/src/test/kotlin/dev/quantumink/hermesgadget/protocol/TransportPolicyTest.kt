package dev.quantumink.hermesgadget.protocol

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyTest {
    private val tls = Endpoint.parse("wss://example.invalid/gadget")
    private val lan = Endpoint.parse("ws://127.0.0.1/gadget", true, true)

    @Test fun choosesReachableTlsRelayAndRespectsOverride() {
        assertEquals(
            TransportPath.RELAY,
            TransportPolicy.select(tls, TransportPreference.AUTO, true)
        )
        assertEquals(
            TransportPath.DIRECT,
            TransportPolicy.select(tls, TransportPreference.AUTO, false)
        )
        assertEquals(
            TransportPath.DIRECT,
            TransportPolicy.select(lan, TransportPreference.AUTO, true)
        )
        assertEquals(
            TransportPath.DIRECT,
            TransportPolicy.select(tls, TransportPreference.DIRECT, true)
        )
        assertThrows(IllegalArgumentException::class.java) {
            TransportPolicy.select(lan, TransportPreference.RELAY, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransportPolicy.select(tls, TransportPreference.RELAY, false)
        }
    }

    @Test fun fallbackRequiresAutomaticRelayNetworkFailure() {
        val network = ConnectionState(ConnectionStatus.RETRYING, error = "Connection closed.")
        assertTrue(
            TransportPolicy.canFallBack(TransportPreference.AUTO, TransportPath.RELAY, network)
        )
        assertFalse(
            TransportPolicy.canFallBack(TransportPreference.RELAY, TransportPath.RELAY, network)
        )
        assertFalse(
            TransportPolicy.canFallBack(TransportPreference.AUTO, TransportPath.DIRECT, network)
        )
        for (error in listOf(
            "TLS validation failed.",
            "Authentication failed.",
            "Enrollment failed."
        )) {
            assertFalse(
                TransportPolicy.canFallBack(
                    TransportPreference.AUTO,
                    TransportPath.RELAY,
                    ConnectionState(ConnectionStatus.ERROR, error = error)
                )
            )
        }
    }

    @Test fun rejectsExternalProxyAndPreservesOnlyReplyAcrossNewTransport() {
        val observer = object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) = Unit
            override fun effect(effect: ConversationEffect, generation: Long) = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectConnection(
                tls,
                DeviceIdentity.generate(),
                observer,
                connectionProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 8888))
            )
        }
        val conversation = Conversation(previousReply = "Last reply")
        assertEquals("Last reply", conversation.state.reply)
        assertFalse(conversation.busy)
        assertEquals("", conversation.state.transcript)
    }
}
