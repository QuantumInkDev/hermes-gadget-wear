package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.Protocol
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * An in-memory stand-in for the WebSocket factory of the REAL [DirectConnection]: no socket, no listener, no network.
 * It speaks only the stock handshake (challenge/welcome, synthetic) and hands every `relay.request` frame the client
 * wrote to the test, which answers with [Peer.deliver]. Everything the client does with those frames (negotiation,
 * gating, routing, bounds, correlation) is the production code under test.
 */
internal class RelaySocketFixture(
    /** The welcome's `relay` member; null leaves it out (a stock server). */
    var relayMember: JsonElement? = RelayNegotiation.capability(),
    var paired: Boolean = true
) : WebSocket.Factory {
    @Volatile var onRelayAcceptance: ((JsonObject) -> Unit)? = null
    inner class Peer(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val relayRequests = LinkedBlockingQueue<JsonObject>()
        val relayTexts = CopyOnWriteArrayList<String>()
        val others = CopyOnWriteArrayList<String>()
        var hello: JsonObject? = null

        @Volatile var refuseSends = false

        @Volatile var cancelled = false

        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (refuseSends) return false
            val parsed = Json.parseToJsonElement(text).jsonObject
            when (parsed["type"]?.jsonPrimitive?.content) {
                "hello" -> {
                    hello = parsed
                    deliver(
                        challenge()
                    )
                }
                "auth" -> deliver(welcome())
                "relay.request" -> {
                    onRelayAcceptance?.invoke(parsed)
                    relayTexts.add(text)
                    relayRequests.add(parsed)
                }
                else -> others.add(text)
            }
            return true
        }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            cancelled = true
        }

        fun deliver(text: String) = listener.onMessage(this, text)

        fun deliver(json: JsonObject) = deliver(RelayVectors.asciiJson(json))

        fun serverClose() = listener.onClosed(this, 1000, "")

        /** The next relay request the client wrote, or fails after five seconds. */
        fun nextRequest(): JsonObject =
            relayRequests.poll(5, TimeUnit.SECONDS) ?: error("No relay request arrived")

        /** Answers [id] with a `relay.response` carrying [result]. */
        fun reply(id: String, result: JsonObject) = deliver(
            buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                put("id", id)
                put("result", result)
            }
        )

        fun fail(id: String?, code: String) = deliver(
            buildJsonObject {
                put("type", "relay.response")
                put("v", 1)
                if (id != null) put("id", id)
                put("error", buildJsonObject { put("code", code) })
            }
        )
    }

    val peers = CopyOnWriteArrayList<Peer>()

    val latest: Peer get() = peers.last()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val peer = Peer(request, listener)
        peers.add(peer)
        val response = Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols")
            .header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL).build()
        listener.onOpen(peer, response)
        return peer
    }

    private fun challenge(): JsonObject = buildJsonObject {
        put("type", "challenge")
        put("nonce", "AAAAAAAAAAAAAAAAAAAAAA==")
        put("enrolled", true)
    }

    private fun welcome(): JsonObject = buildJsonObject {
        put("type", "welcome")
        put("proto", 1)
        put("heartbeat_s", 3600)
        put("paired", paired)
        relayMember?.let { put("relay", it) }
    }
}

/** Records everything a test needs from a real connection built over a [RelaySocketFixture]. */
internal class RelayConnectionHarness(
    val fixture: RelaySocketFixture,
    optIn: Boolean = true,
    capabilities: JsonObject = Protocol.pcmCapabilities(),
    val clock: AtomicLong = AtomicLong(1_000_000L),
    private val nanos: AtomicLong = AtomicLong(5_000_000_000L)
) : AutoCloseable {
    val states = LinkedBlockingQueue<RelayChannelState>()
    val generations = CopyOnWriteArrayList<Long>()
    val connectionStates = CopyOnWriteArrayList<ConnectionState>()

    val connection = DirectConnection(
        Endpoint.parse("wss://example.invalid/gadget"),
        DeviceIdentity.generate(),
        object : ConnectionObserver {
            override fun stateChanged(state: ConnectionState) {
                connectionStates.add(state)
            }
            override fun effect(effect: ConversationEffect, generation: Long) = Unit
        },
        capabilities = capabilities,
        now = clock::get,
        relay = if (optIn) {
            RelayOptIn({ state, generation ->
                generations.add(generation)
                states.add(state)
            }) { nanos.get() }
        } else {
            null
        },
        webSockets = fixture
    )

    val transport: RelayTransport get() = requireNotNull(connection.relayTransport)

    fun nanosNow(): Long = nanos.get()

    fun advance(ms: Long) {
        clock.addAndGet(ms)
        nanos.addAndGet(ms * 1_000_000L)
    }

    /** Waits for a state of type [T] that the listener already saw (earlier ones are skipped). */
    inline fun <reified T : RelayChannelState> awaitState(): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val next = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (next is T) return next
        }
        error("State not reached")
    }

    fun awaitConnected() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (connectionStates.lastOrNull()?.status ==
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED
            ) {
                return
            }
            Thread.sleep(10)
        }
        error("Connection did not pair")
    }

    override fun close() = connection.close()
}

/** A captured [RelayVectors.Case] re-addressed to [id]: only the correlation id of the verbatim frame changes. */
internal fun RelayVectors.Case.readdressed(id: String): JsonObject =
    JsonObject(response + ("id" to JsonPrimitive(id)))
