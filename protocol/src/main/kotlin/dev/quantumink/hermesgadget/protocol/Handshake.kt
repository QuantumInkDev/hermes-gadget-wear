package dev.quantumink.hermesgadget.protocol

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All calls belong to one ordered transport event stream; time is monotonic milliseconds. */
class Handshake(
    private val identity: DeviceIdentity,
    private val capabilities: JsonObject = JsonObject(emptyMap()),
    private val name: String = "Wear Gadget",
    private val board: String = "wear-os",
    private val firmware: String = "0.1.0",
    private val accessToken: String? = null,
    private val actions: JsonArray = JsonArray(emptyList()),
    private val sensors: JsonObject = JsonObject(emptyMap())
) {
    enum class State { CLOSED, HELLO_SENT, AUTH_SENT, UNPAIRED, PAIRED, FAILED }

    var state: State = State.CLOSED
        private set
    var failureCode: String? = null
        private set
    var heartbeatSeconds: Int = 20
        private set
    val canSendConversation: Boolean get() = state == State.PAIRED
    private var openedAt = 0L
    private var lastInboundAt = 0L

    fun open(now: Long): Message {
        require(state == State.CLOSED || state == State.FAILED)
        state = State.HELLO_SENT
        failureCode = null
        heartbeatSeconds = 20
        openedAt = now
        lastInboundAt = now
        return Protocol.hello(
            identity,
            name,
            board,
            firmware,
            capabilities,
            accessToken,
            actions,
            sensors
        )
    }

    fun receive(message: Message, now: Long): List<Message> {
        if (state == State.CLOSED || state == State.FAILED) return emptyList()
        recordInbound(now)
        return when (message.type) {
            "challenge" -> challenge(message)
            "welcome" -> {
                if (state != State.AUTH_SENT || message.int("proto") != Protocol.VERSION) {
                    fail("invalid_welcome")
                } else {
                    val heartbeat = message.int("heartbeat_s")
                    val paired = message.boolean("paired")
                    if (heartbeat == null || heartbeat !in 1..3600 || paired == null) {
                        fail("invalid_welcome")
                    } else {
                        heartbeatSeconds = heartbeat
                        state = if (paired) State.PAIRED else State.UNPAIRED
                    }
                }
                emptyList()
            }
            "paired", "unpaired" -> {
                if (state == State.PAIRED || state == State.UNPAIRED) {
                    state = if (message.type == "paired") State.PAIRED else State.UNPAIRED
                }
                emptyList()
            }
            "ping" -> listOf(
                Message.create(
                    "pong",
                    buildJsonObject {
                        message.field("ts")?.let { put("ts", it) }
                    }
                )
            )
            "error" -> {
                fail(message.string("code") ?: "server_error")
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun challenge(message: Message): List<Message> {
        val nonce = message.string("nonce")
        val enrolled = message.boolean("enrolled")
        if (state != State.HELLO_SENT || nonce == null || !validNonce(nonce) || enrolled == null) {
            fail("invalid_challenge")
            return emptyList()
        }
        state = State.AUTH_SENT
        return listOf(
            Message.create(
                "auth",
                buildJsonObject {
                    if (enrolled) {
                        put(
                            "mac",
                            identity.authMac(nonce)
                        )
                    } else {
                        put("key", identity.enrollmentKey())
                    }
                }
            )
        )
    }

    fun recordInbound(now: Long) {
        lastInboundAt = maxOf(lastInboundAt, now)
    }

    fun tick(now: Long) {
        when (state) {
            State.HELLO_SENT, State.AUTH_SENT -> if (now - openedAt >=
                10000
            ) {
                fail("handshake_timeout")
            }
            State.PAIRED, State.UNPAIRED -> if (now - lastInboundAt >=
                3L * heartbeatSeconds * 1000
            ) {
                fail("heartbeat_timeout")
            }
            else -> Unit
        }
    }

    fun close() {
        state = State.CLOSED
    }

    private fun fail(code: String) {
        failureCode = code
        state = State.FAILED
    }

    private fun validNonce(nonce: String): Boolean = try {
        Base64.getDecoder().decode(nonce).size in 16..64
    } catch (_: IllegalArgumentException) {
        false
    }
}
