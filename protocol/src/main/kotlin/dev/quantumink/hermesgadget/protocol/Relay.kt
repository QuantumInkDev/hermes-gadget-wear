package dev.quantumink.hermesgadget.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

object RelayProtocol {
    const val CAPABILITY = "hermes_gadget_relay_v1"
    const val CHANNEL_PATH = "/hermes/relay/v1"
    const val MAX_HEADER = 2048
    const val OK = 0
    const val UNAVAILABLE = 1

    fun writeRequest(output: OutputStream, endpoint: Endpoint) {
        require(endpoint.isTls) { "The secure relay requires wss." }
        val bytes = Message.create(
            "relay.open",
            buildJsonObject { put("url", endpoint.url) }
        ).encode().toByteArray()
        require(bytes.size <= MAX_HEADER)
        DataOutputStream(output).apply {
            writeShort(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun readRequest(input: InputStream): Endpoint {
        val reader = DataInputStream(input)
        val size = reader.readUnsignedShort()
        require(size in 1..MAX_HEADER) { "Invalid relay header." }
        val bytes = ByteArray(size)
        reader.readFully(bytes)
        val message = requireNotNull(Message.parse(bytes.toString(Charsets.UTF_8)))
        require(message.type == "relay.open")
        val endpoint = Endpoint.parse(requireNotNull(message.string("url")))
        require(endpoint.isTls) { "The secure relay requires wss." }
        return endpoint
    }
}

object RelayForwarder {
    fun accept(
        channel: RelayStreams,
        permitted: (Endpoint) -> Boolean = { true },
        completed: () -> Unit = {}
    ): DuplexPump {
        var socket: Socket? = null
        try {
            val endpoint = RelayProtocol.readRequest(channel.input)
            require(permitted(endpoint)) { "Relay is unavailable." }
            val uri = URI(endpoint.url)
            socket = Socket().apply {
                tcpNoDelay = true
                connect(
                    InetSocketAddress(
                        endpoint.host,
                        uri.port.takeIf {
                            it != -1
                        } ?: 443
                    ),
                    10000
                )
            }
            channel.output.write(RelayProtocol.OK)
            channel.output.flush()
            return DuplexPump(channel, RelayStreams.socket(socket), completed = completed).also {
                it.start()
            }
        } catch (failure: Exception) {
            runCatching {
                channel.output.write(RelayProtocol.UNAVAILABLE)
                channel.output.flush()
            }
            socket?.close()
            channel.close()
            throw failure
        }
    }

    /** Call with a bounded setup deadline; the owner closes the channel on cancellation. */
    fun open(channel: RelayStreams, endpoint: Endpoint): RelayStreams {
        try {
            RelayProtocol.writeRequest(channel.output, endpoint)
            check(channel.input.read() == RelayProtocol.OK) { "Phone relay is unavailable." }
            return channel
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }
}

/** Blocking streams provide backpressure; ownership includes cancellation of the channel/socket. */
class RelayStreams(
    val input: InputStream,
    val output: OutputStream,
    private val release: () -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching(release)
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        fun socket(socket: Socket) = RelayStreams(
            socket.getInputStream(),
            socket.getOutputStream(),
            { socket.close() }
        )
    }
}

/** Two fixed 16 KiB buffers; no frame parsing, unbounded queues, or retained content. */
class DuplexPump(
    private val left: RelayStreams,
    private val right: RelayStreams,
    private val idleMillis: Long = 120000,
    private val completed: () -> Unit = {}
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val lifecycle = Any()
    private val started = AtomicBoolean(false)
    private val done = CountDownLatch(1)
    private val activity = AtomicLong(System.nanoTime())
    private val workers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "relay-copy").apply { isDaemon = true }
    }
    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "relay-idle").apply { isDaemon = true }
    }

    fun start() {
        synchronized(lifecycle) {
            check(started.compareAndSet(false, true))
            check(!stopped.get())
            clock.scheduleAtFixedRate(
                {
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - activity.get()) >=
                        idleMillis
                    ) {
                        close()
                    }
                },
                1,
                1,
                TimeUnit.SECONDS
            )
            workers.execute { copy(left, right) }
            workers.execute { copy(right, left) }
        }
    }

    private fun copy(from: RelayStreams, to: RelayStreams) {
        val buffer = ByteArray(16384)
        try {
            while (!stopped.get()) {
                val count = from.input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                to.output.write(buffer, 0, count)
                to.output.flush()
                activity.set(System.nanoTime())
            }
        } catch (_: Exception) {
            // Both directions close together; protocol reconnection owns error presentation.
        } finally {
            buffer.fill(0)
            close()
        }
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

    override fun close() {
        synchronized(lifecycle) {
            if (!stopped.compareAndSet(false, true)) return
        }
        left.close()
        right.close()
        clock.shutdownNow()
        workers.shutdownNow()
        runCatching(completed)
        done.countDown()
    }
}

/** Real loopback socket lets Android's TLS provider validate the original server normally. */
class LoopbackConnectBridge(private val endpoint: Endpoint, private val open: () -> RelayStreams) :
    AutoCloseable {
    private val authority = run {
        require(endpoint.isTls) { "The secure relay requires wss." }
        val uri = URI(endpoint.url)
        val port = if (uri.port == -1) 443 else uri.port
        (if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host) + ":$port"
    }
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val stopped = AtomicBoolean(false)
    private val slot = Semaphore(1)
    private val active = AtomicReference<DuplexPump?>()
    private val opening = AtomicReference<Socket?>()
    private val token = "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) }
    )
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "relay-connect").apply { isDaemon = true }
    }
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(server.inetAddress, server.localPort))

    init {
        worker.execute {
            while (!stopped.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                opening.set(socket)
                if (!runCatching { slot.tryAcquire(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    socket.close()
                    opening.compareAndSet(socket, null)
                    continue
                }
                var remote: RelayStreams? = null
                val ownsSlot = AtomicBoolean(true)
                val releaseSlot = { if (ownsSlot.compareAndSet(true, false)) slot.release() }
                try {
                    socket.soTimeout = 10000
                    validate(socket.getInputStream())
                    remote = open()
                    check(!stopped.get())
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        flush()
                    }
                    socket.soTimeout = 0
                    val pump =
                        DuplexPump(RelayStreams.socket(socket), remote, completed = releaseSlot)
                    active.set(pump)
                    opening.compareAndSet(socket, null)
                    pump.start()
                } catch (_: Exception) {
                    remote?.close()
                    socket.close()
                    releaseSlot()
                } finally {
                    opening.compareAndSet(socket, null)
                }
            }
        }
    }

    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient = builder
        .proxy(proxy)
        .proxyAuthenticator { route, response ->
            if (route?.proxy != proxy || response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder().header("Proxy-Authorization", token).build()
            }
        }.build()

    private fun validate(input: InputStream) {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 4096) {
            val next = input.read()
            require(next >= 0)
            bytes.add(next.toByte())
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        val text = bytes.toByteArray().toString(Charsets.US_ASCII)
        require(text.endsWith("\r\n\r\n")) { "Invalid proxy header." }
        val lines = text.split("\r\n")
        require(
            lines.first() in setOf("CONNECT $authority HTTP/1.1", "CONNECT $authority HTTP/1.0")
        )
        val credentials = lines.drop(1).filter {
            it.substringBefore(':').equals("Proxy-Authorization", ignoreCase = true)
        }.map { it.substringAfter(':').trim() }
        require(
            credentials.size == 1 && MessageDigest.isEqual(
                credentials.single().toByteArray(),
                token.toByteArray()
            )
        ) { "Invalid proxy authorization." }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        server.close()
        opening.getAndSet(null)?.close()
        active.getAndSet(null)?.close()
        worker.shutdownNow()
    }
}
