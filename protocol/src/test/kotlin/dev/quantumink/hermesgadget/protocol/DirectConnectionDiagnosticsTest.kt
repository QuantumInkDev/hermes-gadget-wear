package dev.quantumink.hermesgadget.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectConnectionDiagnosticsTest {
    @Test(timeout = 15000)
    fun socketChallengeWelcomeAndUnpairedAreDistinctObservedStages() {
        Fixture().use { fixture ->
            fixture.connection.connect()
            val peer = fixture.peer()
            val open = fixture.await {
                it.diagnostics.connectionStage == ConnectionStage.SOCKET_OPEN
            }
            assertEquals(DiagnosticTransport.DIRECT, open.diagnostics.transport)
            assertFalse(open.diagnostics.events.any { it.connectionStage == ConnectionStage.READY })
            fixture.challenge(peer)
            fixture.await { it.diagnostics.connectionStage == ConnectionStage.AUTHENTICATING }
            fixture.welcome(peer, paired = false)
            fixture.await { it.diagnostics.connectionStage == ConnectionStage.PAIRING }
            fixture.send(peer, Message.create("paired"))
            val ready = fixture.await { it.diagnostics.connectionStage == ConnectionStage.READY }
            assertEquals(
                listOf(
                    ConnectionStage.CONNECTING,
                    ConnectionStage.SOCKET_OPEN,
                    ConnectionStage.AUTHENTICATING,
                    ConnectionStage.PAIRING,
                    ConnectionStage.READY
                ),
                ready.diagnostics.events.map { it.connectionStage }
            )
        }
    }

    @Test(timeout = 15000)
    fun staleSocketCallbacksCannotProgressOrFailAReconnectedRequestAndReportsContainNoRawContent() {
        Fixture().use { fixture ->
            fixture.connection.connect()
            val first = fixture.peer()
            fixture.challenge(first)
            fixture.welcome(first)
            fixture.await { it.diagnostics.connectionStage == ConnectionStage.READY }
            assertTrue(
                fixture.connection.text("hostile synthetic request secret").get(5, TimeUnit.SECONDS)
            )
            fixture.send(first, message("turn.start", "old"))
            fixture.send(first, message("reply", "old", "hostile synthetic reply secret"))
            fixture.send(first, message("turn.end", "old"))
            fixture.await { it.diagnostics.requestStage == RequestStage.COMPLETE }
            val oldSocket = fixture.socket()
            val oldListener = fixture.listener(oldSocket)
            fixture.connection.disconnect()
            fixture.await { it.status == ConnectionStatus.OFFLINE }
            fixture.clock.set(100)
            fixture.connection.connect()
            val second = fixture.peer()
            fixture.challenge(second)
            fixture.welcome(second)
            val reset = fixture.await { it.diagnostics.connectionStage == ConnectionStage.READY }
            assertEquals(RequestStage.IDLE, reset.diagnostics.requestStage)
            assertEquals("hostile synthetic reply secret", reset.conversation.reply)
            assertEquals(0L, reset.diagnostics.elapsedMillis)
            assertTrue(fixture.connection.text("next synthetic secret").get(5, TimeUnit.SECONDS))
            fixture.listener(fixture.socket()).onMessage(
                fixture.socket(),
                message("turn.start", "new").encode()
            )
            fixture.connection.text("").get(5, TimeUnit.SECONDS)
            fixture.clock.set(30100)
            fixture.await { it.diagnostics.stalled }
            oldListener.onMessage(
                oldSocket,
                message("reply", "new", "stale synthetic secret").encode()
            )
            oldListener.onFailure(oldSocket, SSLException("hostile synthetic TLS secret"), null)
            // Queue barrier: assertions observe the actual post-callback published state.
            fixture.connection.text("").get(5, TimeUnit.SECONDS)
            fixture.listener(
                fixture.socket()
            ).onMessage(
                fixture.socket(),
                message("status", "wrong", "hostile synthetic status secret").encode()
            )
            fixture.connection.text("").get(5, TimeUnit.SECONDS)
            val waiting = requireNotNull(fixture.latest.get())
            assertTrue(waiting.diagnostics.stalled)
            assertTrue(waiting.diagnostics.elapsedMillis >= 30000)
            assertEquals(ConnectionStage.READY, waiting.diagnostics.connectionStage)
            assertEquals(FailureCategory.NONE, waiting.diagnostics.failureCategory)
            assertEquals(RequestStage.WAITING, waiting.diagnostics.requestStage)
            assertTrue(waiting.conversation.busy)
            val report = ConnectionDiagnosticsReport.encode(waiting.diagnostics)
            assertFalse(report.contains("secret"))
            assertFalse(report.contains("localhost"))
            assertFalse(report.contains("/gadget"))
            fixture.send(second, message("status", "new", "hostile synthetic progress secret"))
            fixture.await { !it.diagnostics.stalled && it.diagnostics.lastProgressAgeMillis == 0L }
            fixture.connection.disconnect()
            val offline = fixture.await { it.status == ConnectionStatus.OFFLINE }
            assertFalse(offline.diagnostics.stalled)
        }
    }

    @Test(timeout = 15000)
    fun typedFailuresAndRelayDoNotExportRawErrorsOrAddresses() {
        Fixture().use { fixture ->
            fixture.connection.connect()
            fixture.peer()
            fixture.await { it.diagnostics.connectionStage == ConnectionStage.SOCKET_OPEN }
            val socket = fixture.socket()
            fixture.listener(
                socket
            ).onFailure(socket, SSLException("hostile synthetic secret"), null)
            val failed = fixture.await { it.diagnostics.connectionStage == ConnectionStage.FAILED }
            assertEquals(FailureCategory.TLS, failed.diagnostics.failureCategory)
            assertFalse(ConnectionDiagnosticsReport.encode(failed.diagnostics).contains("secret"))
        }
        val states = LinkedBlockingQueue<ConnectionState>()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), 9))
        DirectConnection(
            Endpoint.parse("wss://example.invalid/gadget"),
            DeviceIdentity.generate(),
            object : ConnectionObserver {
                override fun stateChanged(state: ConnectionState) {
                    states.add(state)
                }
                override fun effect(effect: ConversationEffect, generation: Long) = Unit
            },
            connectionProxy = proxy
        ).use { connection ->
            connection.connect()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var found = false
            while (System.nanoTime() < deadline && !found) {
                val state = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
                found = state.diagnostics.transport == DiagnosticTransport.PHONE_RELAY
                assertFalse(
                    ConnectionDiagnosticsReport.encode(
                        state.diagnostics
                    ).contains("example.invalid")
                )
            }
            assertTrue(found)
        }
    }

    private fun message(type: String, turn: String, text: String? = null) = Message.create(
        type,
        buildJsonObject {
            put("turn", turn)
            if (text != null) put("text", text)
        }
    )

    /** Minimal loopback WebSocket peer. No Python, provider, installed server or device. */
    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val peers = LinkedBlockingQueue<Socket>()
        private val accepted = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        private val states = LinkedBlockingQueue<ConnectionState>()
        val latest = AtomicReference<ConnectionState>()
        val clock = AtomicLong()
        val connection = DirectConnection(
            Endpoint.parse("ws://localhost:${server.localPort}/gadget", true, true),
            DeviceIdentity.generate(),
            object : ConnectionObserver {
                override fun stateChanged(state: ConnectionState) {
                    latest.set(state)
                    states.add(state)
                }
                override fun effect(effect: ConversationEffect, generation: Long) = Unit
            },
            now = clock::get
        )
        private val thread = Thread({
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    accepted.add(socket)
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    var key = ""
                    while (true) {
                        val line = input.readLine() ?: error("Missing HTTP upgrade")
                        if (line.isEmpty()) break
                        if (line.startsWith("Sec-WebSocket-Key:", true)) {
                            key =
                                line.substringAfter(':').trim()
                        }
                    }
                    val digest = MessageDigest.getInstance("SHA-1")
                        .digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(
                                Charsets.US_ASCII
                            )
                        )
                    val accept = Base64.getEncoder().encodeToString(digest)
                    val response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
                        "Sec-WebSocket-Protocol: ${Protocol.SUBPROTOCOL}\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    peers.add(socket)
                }
            } catch (failure: java.io.IOException) {
                if (!server.isClosed) throw failure
            }
        }, "synthetic-diagnostic-peer").apply {
            isDaemon = true
            start()
        }

        fun peer(): Socket = peers.poll(5, TimeUnit.SECONDS) ?: error("Loopback peer not connected")
        fun challenge(peer: Socket) = send(
            peer,
            Message.create(
                "challenge",
                buildJsonObject {
                    put("nonce", "AAAAAAAAAAAAAAAAAAAAAA==")
                    put("enrolled", false)
                }
            )
        )
        fun welcome(peer: Socket, paired: Boolean = true) = send(
            peer,
            Message.create(
                "welcome",
                buildJsonObject {
                    put("proto", 1)
                    put("paired", paired)
                    put("heartbeat_s", 3600)
                }
            )
        )
        fun send(peer: Socket, message: Message) {
            val bytes = message.encode().toByteArray(Charsets.UTF_8)
            val output = peer.getOutputStream()
            synchronized(output) {
                output.write(0x81)
                if (bytes.size < 126) {
                    output.write(bytes.size)
                } else {
                    output.write(126)
                    output.write(bytes.size ushr 8)
                    output.write(bytes.size and 255)
                }
                output.write(bytes)
                output.flush()
            }
        }
        fun socket(): WebSocket = field("socket").get(connection) as WebSocket
        fun listener(socket: WebSocket): WebSocketListener =
            socket.javaClass.getDeclaredField("listener")
                .apply { isAccessible = true }.get(socket) as WebSocketListener
        fun await(predicate: (ConnectionState) -> Boolean): ConnectionState {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val left = until - System.nanoTime()
                check(left > 0) { "Diagnostic state not observed" }
                val state =
                    states.poll(left, TimeUnit.NANOSECONDS)
                        ?: error("Diagnostic state not observed")
                if (predicate(state)) return state
            }
        }
        private fun field(name: String) =
            DirectConnection::class.java.getDeclaredField(name).apply {
                isAccessible =
                    true
            }
        override fun close() {
            connection.close()
            server.close()
            accepted.forEach { it.close() }
            thread.join(1000)
        }
    }
}
