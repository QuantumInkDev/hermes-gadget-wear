package dev.quantumink.hermesgadget.protocol

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

enum class ConnectionStatus { OFFLINE, CONNECTING, PAIRING, PAIRED, RETRYING, ERROR }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.OFFLINE,
    val conversation: ConversationState = ConversationState(),
    val pairingCode: String = "",
    val pairingCommand: String = "",
    val error: String = ""
) {
    override fun toString(): String = "ConnectionState(status=" + status + ", content=redacted)"
}

interface ConnectionObserver {
    fun stateChanged(state: ConnectionState)
    fun effect(effect: ConversationEffect, generation: Long)
}

class PublicCleartextAddress : UnknownHostException("Public endpoints require TLS.")

class EndpointDns(private val endpoint: Endpoint, private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!endpoint.isTls &&
            (
                hostname != endpoint.host || addresses.isEmpty() ||
                    addresses.any { !PrivateAddresses.contains(it) }
                )
        ) {
            throw PublicCleartextAddress()
        }
        return addresses
    }
}

/** One socket and a bounded event queue. Never logs frames, addresses, or failures. */
class DirectConnection(
    private val endpoint: Endpoint,
    private val identity: DeviceIdentity,
    private val observer: ConnectionObserver,
    private val accessToken: String? = null,
    availableActions: Set<String> = WatchActions.names,
    private val sensors: JsonObject = JsonObject(emptyMap()),
    private val capabilities: JsonObject = WatchActions.capabilities(),
    httpClient: OkHttpClient? = null,
    connectionProxy: Proxy = Proxy.NO_PROXY,
    previousReply: String = "",
    private val now: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val idleMillis: Long = 90000
) : AutoCloseable {
    private class Work(val block: () -> Unit, val rejected: () -> Unit) : Runnable {
        override fun run() = block()
    }
    private val ownsClient = httpClient == null
    private val client = (httpClient ?: OkHttpClient()).newBuilder()
        .dns(EndpointDns(endpoint))
        .proxy(connectionProxy)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val actions = WatchActions.manifest(availableActions)
    private fun offeredFormats(direction: String): Set<String> {
        val formats = (capabilities[direction] as? JsonObject)?.get("formats") as? JsonArray
        return formats?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: setOf("pcm16")
    }
    private val conversation = Conversation(
        availableActions,
        previousReply,
        microphoneFormats = offeredFormats("mic"),
        speakerFormats = offeredFormats("speaker")
    )
    private val stopped = AtomicBoolean(false)
    private val overloaded = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "gadget-events").apply { isDaemon = true } },
        { work, _ ->
            overloaded.set(true)
            socket?.cancel()
            (work as? Work)?.rejected?.invoke()
        }
    )
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "gadget-clock").apply { isDaemon = true }
    }

    @Volatile private var socket: WebSocket? = null
    private var generation = 0L
    private var desired = false
    private var handshake = newHandshake()
    private var state = ConnectionState()

    @Volatile private var published: ConnectionState? = null
    private var reconnectAt: Long? = null
    private var retryCount = 0
    private var healthySince: Long? = null
    private var activityAt = now()

    init {
        require(
            connectionProxy == Proxy.NO_PROXY || (
                endpoint.isTls && connectionProxy.type() == Proxy.Type.HTTP &&
                    (connectionProxy.address() as? InetSocketAddress)?.address?.isLoopbackAddress ==
                    true
                )
        ) { "Only a TLS loopback relay proxy is supported." }
        timer.scheduleAtFixedRate({ dispatch(::tick) }, 100, 100, TimeUnit.MILLISECONDS)
    }

    fun connect() = dispatch {
        desired = true
        activityAt = now()
        retryCount = 0
        if (socket == null) open()
    }

    fun disconnect() = dispatch { disconnectInternal() }
    fun text(value: String): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        submit(
            {
                activityAt = now()
                val values = conversation.sendText(value, now())
                val hasText = values.any {
                    it is ConversationEffect.Send && it.message.type == "text"
                }
                val accepted = effects(values)
                publish()
                result.complete(hasText && accepted)
            },
            { result.complete(false) }
        )
        return result
    }
    fun startRecording() = user { conversation.startRecording(now()) }
    fun finishRecording(token: String? = null) = user { conversation.finishRecording(now(), token) }
    fun cancelRecording() = user { conversation.cancelRecording(now()) }
    fun cancel(newSession: Boolean = false) = user { conversation.cancel(now(), newSession) }
    fun answer(id: String, yes: Boolean) = user { conversation.answer(id, yes, now()) }
    fun dismissDisplay() = dispatch {
        conversation.dismissDisplay()
        publish()
    }
    fun report(message: String) = dispatch {
        conversation.report(message, now())
        publish()
    }

    fun codecHeader(token: String, header: ByteArray) {
        val copy = header.copyOf()
        user { conversation.codecHeader(token, copy, now()) }
    }
    fun capturedOpus(token: String, packet: ByteArray) {
        val copy = packet.copyOf()
        user { conversation.captureOpus(token, copy, now()) }
    }
    fun captureLevel(token: String, pcm: ByteArray) {
        val copy = pcm.copyOf()
        dispatch {
            conversation.captureLevel(token, copy, now())
            publish()
        }
    }

    fun captured(token: String, bytes: ByteArray) {
        val copy = bytes.copyOf()
        dispatch {
            effects(conversation.capture(token, copy, now()))
            publish()
        }
    }

    fun captureFailed(token: String, reason: String) = dispatch {
        effects(conversation.captureFailed(token, now(), reason))
        publish()
    }

    fun actionResult(epoch: Long, id: String, result: JsonObject?, error: String? = null) =
        dispatch {
            if (epoch == generation) effects(conversation.actionResult(id, result, error))
            publish()
        }

    fun updateSensors(value: JsonObject) = dispatch {
        if (handshake.canSendConversation) {
            send(
                Message.create(
                    "state",
                    kotlinx.serialization.json.buildJsonObject {
                        put("sensors", value)
                    }
                )
            )
        }
    }

    private fun user(block: () -> List<ConversationEffect>) = dispatch {
        activityAt = now()
        effects(block())
        publish()
    }

    private fun open() {
        if (!desired || stopped.get()) return
        generation++
        val epoch = generation
        handshake = newHandshake()
        reconnectAt = null
        healthySince = null
        state =
            state.copy(
                status = ConnectionStatus.CONNECTING,
                error = "",
                pairingCode = "",
                pairingCommand = ""
            )
        publish()
        socket = client.newWebSocket(
            Request.Builder().url(
                endpoint.url
            ).header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
                    if (epoch != generation) return@dispatch
                    if (response.header("Sec-WebSocket-Protocol") != Protocol.SUBPROTOCOL) {
                        fail("Server does not speak Gadget v1.", false)
                    } else {
                        send(handshake.open(now()))
                    }
                    publish()
                }
                override fun onMessage(webSocket: WebSocket, text: String) = dispatch {
                    if (epoch != generation) return@dispatch
                    val message = Message.parse(text)
                    if (message == null) {
                        fail("Invalid or oversized server message.", false)
                    } else {
                        incoming(message)
                    }
                    publish()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > BinaryFrame.MAX_FRAME_BYTES) {
                        dispatch {
                            if (epoch ==
                                generation
                            ) {
                                fail("Oversized media frame.", false)
                                publish()
                            }
                        }
                        return
                    }
                    val copy = bytes.toByteArray()
                    dispatch {
                        if (epoch != generation) return@dispatch
                        handshake.recordInbound(now())
                        BinaryFrame.parse(copy)?.let { effects(conversation.binary(it, now())) }
                        activityAt = now()
                        publish()
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                    if (epoch == generation) {
                        fail("Connection closed.", true)
                        publish()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    dispatch {
                        if (epoch != generation) return@dispatch
                        val tls = generateSequence(t) { it.cause }.any { it is SSLException }
                        val public = generateSequence(t) {
                            it.cause
                        }.any { it is PublicCleartextAddress }
                        fail(
                            when {
                                tls -> "TLS certificate or connection validation failed."
                                public -> "Cleartext endpoint resolved outside the private network."
                                else -> "Cannot reach the configured endpoint."
                            },
                            !tls && !public
                        )
                        publish()
                    }
            }
        )
    }

    private fun incoming(message: Message) {
        handshake.receive(message, now()).forEach(::send)
        if (handshake.state == Handshake.State.FAILED) {
            fail(
                when (handshake.failureCode) {
                    "bad_token" -> "Access token missing or rejected."
                    "auth_failed" -> "Device identity rejected. Check the host's enrollment."
                    else -> "Server rejected the protocol connection."
                },
                false
            )
            return
        }
        when (message.type) {
            "welcome", "paired", "unpaired" -> {
                if (message.type == "welcome" &&
                    runCatching { conversation.selectAudio(message) }.isFailure
                ) {
                    fail("Unsupported audio negotiation.", false)
                    return
                }
                val paired = handshake.canSendConversation
                effects(conversation.setPaired(paired))
                state = state.copy(
                    status = if (paired) ConnectionStatus.PAIRED else ConnectionStatus.PAIRING,
                    pairingCode = if (paired) "" else state.pairingCode,
                    pairingCommand = if (paired) "" else state.pairingCommand
                )
                if (healthySince == null &&
                    handshake.state in setOf(Handshake.State.PAIRED, Handshake.State.UNPAIRED)
                ) {
                    healthySince = now()
                    activityAt = now()
                }
            }
            "pairing" -> if (handshake.state == Handshake.State.UNPAIRED) {
                val code = message.string("code").orEmpty()
                if (code.matches(Regex("[A-Z0-9]{4,32}"))) {
                    state =
                        state.copy(
                            pairingCode = code,
                            pairingCommand = message.string("command").orEmpty().take(512)
                        )
                }
            }
            else -> if (handshake.canSendConversation) effects(conversation.receive(message, now()))
        }
        if (message.type !in setOf("ping", "pong", "status")) activityAt = now()
    }

    private fun tick() {
        if (reconnectAt?.let { now() >= it } == true && desired) open()
        if (socket != null) {
            handshake.tick(now())
            if (handshake.state == Handshake.State.FAILED) fail("Connection timed out.", true)
            if (healthySince?.let { now() - it >= 20000 } == true) retryCount = 0
        }
        effects(conversation.tick(now()))
        val inactivityLimit = if (conversation.busy) {
            idleMillis.coerceAtLeast(
                120000
            )
        } else {
            idleMillis
        }
        if (desired && now() - activityAt >= inactivityLimit) {
            disconnectInternal()
        }
        publish()
    }

    private fun fail(error: String, retry: Boolean) {
        generation++
        socket?.cancel()
        socket = null
        handshake.close()
        effects(conversation.disconnected())
        healthySince = null
        if (desired && retry) {
            val base = (1000L shl retryCount.coerceAtMost(5)).coerceAtMost(30000)
            retryCount++
            reconnectAt =
                now() +
                (
                    base * ThreadLocalRandom.current().nextDouble(
                        0.8,
                        1.2
                    )
                    ).toLong().coerceAtMost(30000)
            state =
                state.copy(
                    status = ConnectionStatus.RETRYING,
                    error = error,
                    pairingCode = "",
                    pairingCommand = ""
                )
        } else {
            desired = false
            reconnectAt = null
            state =
                state.copy(
                    status = ConnectionStatus.ERROR,
                    error = error,
                    pairingCode = "",
                    pairingCommand = ""
                )
        }
    }

    private fun disconnectInternal() {
        desired = false
        reconnectAt = null
        generation++
        socket?.cancel()
        socket = null
        handshake.close()
        effects(conversation.disconnected())
        state =
            state.copy(
                status = ConnectionStatus.OFFLINE,
                pairingCode = "",
                pairingCommand = "",
                error = ""
            )
        publish()
    }

    private fun effects(values: List<ConversationEffect>): Boolean {
        val epoch = generation
        var accepted = true
        for (effect in values) {
            if (stopped.get() || epoch != generation) {
                accepted = false
                break
            }
            when (effect) {
                is ConversationEffect.Send -> {
                    if (!handshake.canSendConversation || !send(effect.message)) accepted = false
                }
                is ConversationEffect.SendBinary -> if (handshake.canSendConversation) {
                    val active = socket
                    if (active == null || active.queueSize() > 32000 ||
                        !active.send(effect.frame.encode().toByteString())
                    ) {
                        fail("Audio uplink could not keep up.", true)
                        accepted = false
                        break
                    }
                }
                else -> observer.effect(effect, generation)
            }
        }
        return accepted
    }

    private fun send(message: Message): Boolean {
        if (socket?.send(message.encode()) == true) return true
        fail("Connection cannot send.", true)
        return false
    }

    private fun publish() {
        if (stopped.get()) return
        state = state.copy(conversation = conversation.state)
        if (state != published) {
            published = state
            observer.stateChanged(state)
        }
    }

    private fun dispatch(block: () -> Unit) = submit(block) {}

    private fun submit(block: () -> Unit, rejected: () -> Unit) {
        if (stopped.get()) {
            rejected()
            return
        }
        executor.execute(
            Work(
                {
                    if (stopped.get()) {
                        rejected()
                    } else if (overloaded.getAndSet(false)) {
                        fail("Connection exceeded its work limit.", false)
                        publish()
                        rejected()
                    } else {
                        block()
                    }
                },
                rejected
            )
        )
    }

    private fun newHandshake() = Handshake(
        identity,
        capabilities,
        accessToken = accessToken,
        actions = actions,
        sensors = sensors
    )

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        socket?.cancel()
        timer.shutdownNow()
        executor.shutdownNow().forEach { (it as? Work)?.rejected?.invoke() }
        observer.effect(ConversationEffect.CaptureStop, generation)
        observer.effect(ConversationEffect.PlaybackStop, generation)
        observer.stateChanged(
            ConnectionState(
                conversation =
                published?.conversation ?: ConversationState()
            )
        )
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
