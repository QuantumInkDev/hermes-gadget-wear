package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RelayIntegrationTest {
    @Test(timeout = 45000)
    fun opaqueRelayPreservesWatchTlsPairingTextAndSavedIdentityReconnect() {
        val python = System.getenv("HERMES_GADGET_PYTHON").orEmpty()
        assumeTrue("Set HERMES_GADGET_PYTHON for the live SDK test", python.isNotBlank())
        val directory = Files.createTempDirectory("gadget-relay-")
        val ready = directory.resolve("ready.json")
        val certificate = HeldCertificate.Builder().commonName("Synthetic relay test")
            .addSubjectAlternativeName("127.0.0.1").build()
        val pem = directory.resolve("cert.pem")
        val key = directory.resolve("key.pem")
        Files.writeString(pem, certificate.certificatePem())
        Files.writeString(key, certificate.privateKeyPkcs8Pem())
        val process = ProcessBuilder(
            python, "-u", "tools/integration_devserver.py", "--ready-file", ready.toString(),
            "--tls-cert", pem.toString(), "--tls-key", key.toString()
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        Thread({ process.inputStream.bufferedReader().useLines { lines -> lines.forEach { } } })
            .apply { isDaemon = true }.start()
        val phone = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val phoneBytes = ByteArrayOutputStream()
        val stopped = AtomicBoolean(false)
        val pumps = LinkedBlockingQueue<DuplexPump>()
        var client: OkHttpClient? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(ready)) {
                check(process.isAlive && System.nanoTime() < deadline) { "SDK readiness failed" }
                Thread.sleep(25)
            }
            val port = Json.parseToJsonElement(Files.readString(ready))
                .jsonObject.getValue("port").jsonPrimitive.int
            val endpoint = Endpoint.parse("wss://127.0.0.1:$port/gadget")
            Thread({
                while (!stopped.get()) {
                    val socket = try {
                        phone.accept()
                    } catch (_: Exception) {
                        break
                    }
                    val raw = socket.getInputStream()
                    val snoop = object : FilterInputStream(raw) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            val count = super.read(bytes, offset, length)
                            if (count > 0) {
                                synchronized(phoneBytes) {
                                    val keep = count.coerceAtMost(65536 - phoneBytes.size())
                                    if (keep > 0) phoneBytes.write(bytes, offset, keep)
                                }
                            }
                            return count
                        }
                    }
                    val streams = RelayStreams(snoop, socket.getOutputStream(), { socket.close() })
                    runCatching {
                        RelayForwarder.accept(streams, { it.url == endpoint.url }).also(pumps::add)
                    }
                }
            }).apply { isDaemon = true }.start()
            LoopbackConnectBridge(endpoint) {
                RelayForwarder.open(
                    RelayStreams.socket(Socket("127.0.0.1", phone.localPort)),
                    endpoint
                )
            }.use { bridge ->
                val rejected = Probe()
                val untrusted = bridge.client()
                try {
                    DirectConnection(
                        endpoint,
                        DeviceIdentity.generate(),
                        rejected,
                        httpClient = untrusted,
                        connectionProxy = bridge.proxy
                    ).use { connection ->
                        connection.connect()
                        assertTrue(
                            rejected.await {
                                it.status == ConnectionStatus.ERROR
                            }.error.startsWith("TLS")
                        )
                    }
                } finally {
                    untrusted.dispatcher.executorService.shutdown()
                    untrusted.connectionPool.evictAll()
                }
                val trust = HandshakeCertificates.Builder()
                    .addTrustedCertificate(certificate.certificate).build()
                client = bridge.client(
                    OkHttpClient.Builder().sslSocketFactory(
                        trust.sslSocketFactory(),
                        trust.trustManager
                    )
                )
                val identity = DeviceIdentity.generate()
                val probe = Probe()
                DirectConnection(
                    endpoint,
                    identity,
                    probe,
                    httpClient = client,
                    connectionProxy = bridge.proxy
                ).use { connection ->
                    connection.connect()
                    val pairing = probe.await { it.pairingCode.isNotBlank() }
                    process.outputStream.write(
                        ("approve " + pairing.pairingCode + "\n").toByteArray()
                    )
                    process.outputStream.flush()
                    probe.await { it.status == ConnectionStatus.PAIRED }
                    assertTrue(connection.text("Synthetic relay hello").get(5, TimeUnit.SECONDS))
                    probe.await { it.conversation.reply == "You said: Synthetic relay hello" }
                    connection.disconnect()
                    probe.await { it.status == ConnectionStatus.OFFLINE }
                    connection.connect()
                    probe.await { it.status == ConnectionStatus.PAIRED }
                    assertTrue(
                        connection.text("Synthetic relay reconnect").get(5, TimeUnit.SECONDS)
                    )
                    probe.await { it.conversation.reply == "You said: Synthetic relay reconnect" }
                    val captured = synchronized(phoneBytes) {
                        phoneBytes.toByteArray().toString(Charsets.ISO_8859_1)
                    }
                    assertFalse(captured.contains(identity.enrollmentKey()))
                    assertFalse(captured.contains("Synthetic relay hello"))
                }
            }
        } finally {
            stopped.set(true)
            phone.close()
            pumps.forEach { it.close() }
            runCatching {
                process.outputStream.write("quit\n".toByteArray())
                process.outputStream.flush()
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll()
            directory.toFile().deleteRecursively()
        }
    }

    private class Probe : ConnectionObserver {
        private val states = LinkedBlockingQueue<ConnectionState>()
        override fun stateChanged(state: ConnectionState) {
            states.add(state)
        }
        override fun effect(effect: ConversationEffect, generation: Long) = Unit
        fun await(predicate: (ConnectionState) -> Boolean): ConnectionState {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val value = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(value)) return value
            }
            error("Relay did not reach the expected state")
        }
    }
}
