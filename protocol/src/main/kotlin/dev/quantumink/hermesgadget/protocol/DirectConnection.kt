package dev.quantumink.hermesgadget.protocol

import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayChannel
import dev.quantumink.hermesgadget.protocol.relay.RelayFrameSniffer
import dev.quantumink.hermesgadget.protocol.relay.RelayNegotiation
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
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
import java.util.concurrent.atomic.AtomicReference
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
    val clientSpeech: Boolean = false,
    val error: String = "",
    val diagnostics: ConnectionDiagnosticsSnapshot = ConnectionDiagnosticsSnapshot()
) {
    override fun toString(): String = "ConnectionState(status=" + status + ", content=redacted)"
}

interface ConnectionObserver {
    fun stateChanged(state: ConnectionState)
    fun pet(effect: PetEffect) {}
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

/**
 * One socket and a bounded event queue. Never logs frames, addresses, or failures.
 *
 * [relay] is the explicit opt-in to Relay v1 for a deliberate phone mode: without it nothing about the hello, the
 * routing or the stock behaviour changes. With it this connection also owns the ONE raw `relay.response` router
 * ([RelayChannel], exposed as [relayTransport]) on the same serial event queue and socket generation.
 * [webSockets] replaces the OkHttp client as the socket factory (tests and in-process fixtures; no network).
 */
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
    private val idleMillis: Long = 90000,
    relay: RelayOptIn? = null,
    private val webSockets: WebSocket.Factory? = null
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
    private val diagnosticTransport = if (connectionProxy ==
        Proxy.NO_PROXY
    ) {
        DiagnosticTransport.DIRECT
    } else {
        DiagnosticTransport.PHONE_RELAY
    }
    private val diagnostics = ConnectionDiagnostics(diagnosticTransport)
    private val actions = WatchActions.manifest(availableActions)
    private val relayScopeLock = Any()
    private val relayScope = java.util.concurrent.atomic.AtomicLong()
    private val relayChannel = relay?.let { RelayChannel(it.listener, it.monotonicNanos) }
    private val helloCapabilities =
        if (relayChannel != null) RelayNegotiation.withRelay(capabilities) else capabilities

    /** Non-null only for a connection built with [RelayOptIn]. Calls run on this connection's event queue. */
    val relayTransport: RelayTransport? = relayChannel?.let { channel ->
        object : RelayTransport {
            override val generation: Long get() = channel.currentGeneration

            override fun correlation(): CorrelationId = channel.correlation()

            override fun <R : Any> call(
                correlation: CorrelationId,
                call: RelayCall<R>,
                timeoutMs: Long,
                handler: (RelayCallResult<R>) -> Unit
            ) {
                val scope = relayScope.get()
                submit(
                    {
                        channel.callScoped(
                            correlation,
                            call,
                            timeoutMs,
                            now(),
                            { text ->
                                synchronized(relayScopeLock) {
                                    scope == relayScope.get() &&
                                        relaySend(text)
                                }
                            },
                            { scope == relayScope.get() },
                            handler
                        )
                    },
                    { handler(RelayCallResult.Refused(RelayRefusal.QUEUE_FULL)) }
                )
            }

            override fun invalidatePending() {
                synchronized(relayScopeLock) {
                    relayScope.incrementAndGet()
                } // synchronous fence before queue drain
                submit({ channel.refuseObsolete() }, {})
            }

            override fun refreshInfo(handler: (RelayCallResult<Info>) -> Unit) {
                submit(
                    { channel.refreshInfo(now(), ::relaySend, handler) },
                    { handler(RelayCallResult.Refused(RelayRefusal.QUEUE_FULL)) }
                )
            }
        }
    }
    private fun offeredFormats(direction: String): Set<String> {
        val formats = (capabilities[direction] as? JsonObject)?.get("formats") as? JsonArray
        return formats?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: setOf("pcm16")
    }
    private val conversation = Conversation(
        availableActions,
        previousReply,
        microphoneFormats = offeredFormats("mic"),
        speakerFormats = offeredFormats("speaker"),
        diagnostics = diagnostics
    )
    private val spoken = LinkedHashSet<String>()
    private var clientSpeech = false
    private val clientSpeechOffered = capabilities["tts"] == JsonPrimitive("client")
    private val pets = PetTransfers()
    private val petEnabled =
        (capabilities["pet"] as? JsonObject)?.get("asset_channel") == JsonPrimitive(4)
    private var petRequested = false
    private val stopped = AtomicBoolean(false)
    private data class Recording(val token: String, val generation: Long)
    private val recording = AtomicReference<Recording?>()
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
        relayChannel?.open(generation)
        diagnostics.reset(diagnosticTransport, now())
        val epoch = generation
        handshake = newHandshake()
        pets.clear()
        petRequested = false
        clientSpeech = false
        spoken.clear()
        reconnectAt = null
        healthySince = null
        state =
            state.copy(
                status = ConnectionStatus.CONNECTING,
                clientSpeech = false,
                error = "",
                pairingCode = "",
                pairingCommand = ""
            )
        publish()
        socket = (webSockets ?: client).newWebSocket(
            Request.Builder().url(
                endpoint.url
            ).header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
                    if (epoch != generation) return@dispatch
                    diagnostics.connection(ConnectionStage.SOCKET_OPEN, now())
                    if (response.header("Sec-WebSocket-Protocol") != Protocol.SUBPROTOCOL) {
                        fail("Server does not speak Gadget v1.", false)
                    } else {
                        send(handshake.open(now()))
                    }
                    publish()
                }
                override fun onMessage(webSocket: WebSocket, text: String) = dispatch {
                    if (epoch != generation) return@dispatch
                    if (relayChannel != null &&
                        RelayFrameSniffer.classify(text) ==
                        RelayFrameSniffer.Verdict.RELAY_RESPONSE
                    ) {
                        // Judged before the permissive stock parse; never stock conversation traffic.
                        if (handshake.canSendConversation) {
                            handshake.recordInbound(now())
                            relayChannel.frame(text)
                            activityAt = now()
                        }
                        publish()
                        return@dispatch
                    }
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
                        BinaryFrame.parse(copy, if (petEnabled) setOf(4) else emptySet())?.let {
                            if (it.channel == 4) {
                                if (handshake.canSendConversation) {
                                    pets.binary(
                                        it,
                                        now()
                                    ).forEach(observer::pet)
                                }
                            } else {
                                effects(conversation.binary(it, now()))
                            }
                        }
                        activityAt = now()
                        publish()
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                    if (epoch == generation) {
                        fail("Connection closed.", true, FailureCategory.DISCONNECTED)
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
                            !tls && !public,
                            when {
                                tls -> FailureCategory.TLS
                                public -> FailureCategory.ADDRESS_POLICY
                                else -> FailureCategory.REACHABILITY
                            }
                        )
                        publish()
                    }
            }
        )
    }

    private fun incoming(message: Message) {
        val handshakeReplies = handshake.receive(message, now())
        if (message.type == "challenge" && handshake.state == Handshake.State.AUTH_SENT) {
            diagnostics.connection(ConnectionStage.AUTHENTICATING, now())
        }
        handshakeReplies.forEach(::send)
        if (handshake.state == Handshake.State.FAILED) {
            fail(
                when (handshake.failureCode) {
                    "bad_token" -> "Access token missing or rejected."
                    "auth_failed" -> "Device identity rejected. Check the host's enrollment."
                    else -> "Server rejected the protocol connection."
                },
                false,
                if (handshake.failureCode in
                    setOf("bad_token", "auth_failed")
                ) {
                    FailureCategory.AUTH
                } else {
                    FailureCategory.PROTOCOL
                }
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
                if (message.type ==
                    "welcome"
                ) {
                    clientSpeech =
                        clientSpeechOffered && message.string("tts") == "client"
                }
                val paired = handshake.canSendConversation
                if (message.type == "welcome") {
                    relayChannel?.welcome(message, paired, now(), ::relaySend)
                }
                effects(conversation.setPaired(paired, now()))
                if (handshake.state in setOf(Handshake.State.PAIRED, Handshake.State.UNPAIRED)) {
                    diagnostics.connection(
                        if (paired) ConnectionStage.READY else ConnectionStage.PAIRING,
                        now()
                    )
                }
                state = state.copy(
                    status = if (paired) ConnectionStatus.PAIRED else ConnectionStatus.PAIRING,
                    clientSpeech = clientSpeech,
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
            else -> if (handshake.canSendConversation) {
                if (message.type == "tts.speak") {
                    val id = message.string("id").orEmpty()
                    val text = message.string("text").orEmpty()
                    val voice = message.string("voice").orEmpty()
                    if (clientSpeech && conversation.acceptsSpeech(message) &&
                        runCatching {
                            SpeechRequest(
                                ClientSpeech.endpointId(endpoint),
                                id,
                                text,
                                voice,
                                "pcm16"
                            )
                        }.isSuccess && spoken.add(id)
                    ) {
                        while (spoken.size > 64) spoken.remove(spoken.first())
                        effects(listOf(ConversationEffect.ClientSpeak(id, text, voice)))
                    }
                } else if (petEnabled &&
                    message.type in setOf("pet.manifest", "asset.start", "asset.end", "asset.abort")
                ) {
                    pets.message(message, now()).forEach(observer::pet)
                } else {
                    effects(conversation.receive(message, now()))
                }
            }
        }
        if (petEnabled && handshake.canSendConversation && !petRequested) {
            petRequested = true
            send(Message.create("pet.request"))
        }
        if (message.type !in setOf("ping", "pong", "status")) activityAt = now()
    }

    private fun tick() {
        if (reconnectAt?.let { now() >= it } == true && desired) open()
        if (socket != null) {
            handshake.tick(now())
            if (handshake.state ==
                Handshake.State.FAILED
            ) {
                fail("Connection timed out.", true, FailureCategory.TIMEOUT)
            }
            if (healthySince?.let { now() - it >= 20000 } == true) retryCount = 0
        }
        pets.tick(now())
        relayChannel?.tick(now(), ::relaySend)
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

    private fun fail(
        error: String,
        retry: Boolean,
        category: FailureCategory = FailureCategory.PROTOCOL
    ) {
        generation++
        relayChannel?.close(generation)
        socket?.cancel()
        socket = null
        handshake.close()
        pets.clear()
        effects(conversation.disconnected(now()))
        healthySince = null
        diagnostics.connection(
            if (desired &&
                retry
            ) {
                ConnectionStage.RETRYING
            } else {
                ConnectionStage.FAILED
            },
            now(),
            category
        )
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
        relayChannel?.close(generation)
        socket?.cancel()
        socket = null
        handshake.close()
        pets.clear()
        effects(conversation.disconnected(now()))
        diagnostics.connection(ConnectionStage.OFFLINE, now())
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
        values.filterIsInstance<ConversationEffect.CaptureStart>().forEach {
            recording.set(Recording(it.token, epoch))
        }
        var accepted = true
        for (effect in values) {
            if (stopped.get() || epoch != generation) {
                accepted = false
                break
            }
            when (effect) {
                is ConversationEffect.RecordingOutcome -> Unit
                is ConversationEffect.Send -> {
                    if (!handshake.canSendConversation || !send(effect.message)) accepted = false
                }
                is ConversationEffect.SendBinary -> if (handshake.canSendConversation) {
                    val active = socket
                    if (active == null || active.queueSize() > 32000 ||
                        !active.send(effect.frame.encode().toByteString())
                    ) {
                        fail("Audio uplink could not keep up.", true, FailureCategory.OVERLOAD)
                        accepted = false
                        break
                    }
                }
                else -> observer.effect(effect, generation)
            }
        }
        for (outcome in values.filterIsInstance<ConversationEffect.RecordingOutcome>()) {
            recordingOutcome(outcome.copy(finished = outcome.finished && accepted))
        }
        if (!accepted) {
            for (capture in values.filterIsInstance<ConversationEffect.CaptureStart>()) {
                recordingOutcome(ConversationEffect.RecordingOutcome(capture.token, false))
            }
        }
        return accepted
    }

    private fun recordingOutcome(outcome: ConversationEffect.RecordingOutcome) {
        val active = recording.get() ?: return
        if (active.token == outcome.token && recording.compareAndSet(active, null)) {
            observer.effect(outcome, active.generation)
        }
    }

    private fun send(message: Message): Boolean {
        if (socket?.send(message.encode()) == true) return true
        fail("Connection cannot send.", true, FailureCategory.DISCONNECTED)
        return false
    }

    /** One Relay request frame on the shared socket queue: bounded, and only for a paired connection. */
    private fun relaySend(text: String): Boolean {
        val active = socket
        if (active == null || !handshake.canSendConversation ||
            active.queueSize() > RELAY_QUEUE_BYTES
        ) {
            return false
        }
        if (active.send(text)) {
            activityAt = now()
            return true
        }
        fail("Connection cannot send.", true, FailureCategory.DISCONNECTED)
        return false
    }

    private fun publish() {
        if (stopped.get()) return
        state = state.copy(
            conversation = conversation.state.copy(busy = conversation.busy),
            diagnostics = diagnostics.snapshot(now())
        )
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
                        fail("Connection exceeded its work limit.", false, FailureCategory.OVERLOAD)
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
        helloCapabilities,
        accessToken = accessToken,
        actions = actions,
        sensors = sensors
    )

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        relayChannel?.close(generation + 1)
        socket?.cancel()
        timer.shutdownNow()
        executor.shutdownNow().forEach { (it as? Work)?.rejected?.invoke() }
        observer.effect(ConversationEffect.CaptureStop, generation)
        recording.getAndSet(null)?.let {
            observer.effect(ConversationEffect.RecordingOutcome(it.token, false), it.generation)
        }
        observer.effect(ConversationEffect.PlaybackStop, generation)
        observer.stateChanged(
            ConnectionState(
                diagnostics = ConnectionDiagnosticsSnapshot(transport = diagnosticTransport),
                conversation =
                published?.conversation ?: ConversationState()
            )
        )
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        /** A relay frame is at most 64 KiB: refuse new ones once this many bytes wait on the socket. */
        const val RELAY_QUEUE_BYTES = 262_144L
    }
}
