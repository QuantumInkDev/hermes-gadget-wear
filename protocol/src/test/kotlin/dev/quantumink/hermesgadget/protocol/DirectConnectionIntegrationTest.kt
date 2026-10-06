package dev.quantumink.hermesgadget.protocol

import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DirectConnectionIntegrationTest {
    @Test(timeout = 45000)
    fun nativeTlsPairingTextPcmPromptCardImageActionCancelAndReconnect() {
        val python = System.getenv("HERMES_GADGET_PYTHON").orEmpty()
        assumeTrue("Set HERMES_GADGET_PYTHON for the live SDK test", python.isNotBlank())
        val directory = Files.createTempDirectory("gadget-direct-")
        val ready = directory.resolve("ready.json")
        val host = InetAddress.getLoopbackAddress().hostAddress
        val certificate = HeldCertificate.Builder().commonName("Synthetic SDK test")
            .addSubjectAlternativeName(host).build()
        val pem = directory.resolve("cert.pem")
        val key = directory.resolve("key.pem")
        Files.writeString(pem, certificate.certificatePem())
        Files.writeString(key, certificate.privateKeyPkcs8Pem())
        val process = ProcessBuilder(
            python, "-u", "tools/integration_devserver.py", "--ready-file", ready.toString(),
            "--tls-cert", pem.toString(), "--tls-key", key.toString()
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val acceptedActions = LinkedBlockingQueue<Boolean>()
        // Retain only a boolean proof that the SDK received an action result, never console data.
        Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.endsWith("{'vibrated': True}")) {
                        acceptedActions.add(true)
                    }
                }
            }
        }, "sdk-output-filter").apply { isDaemon = true }.start()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(ready)) {
                check(process.isAlive && System.nanoTime() < deadline) { "SDK readiness failed" }
                Thread.sleep(25)
            }
            val port = Json.parseToJsonElement(Files.readString(ready))
                .jsonObject.getValue("port").jsonPrimitive.int
            val endpoint = Endpoint.parse(
                URI("wss", null, host, port, "/gadget", null, null)
                    .toString()
            )
            val identity = DeviceIdentity.generate()
            val rejected = Probe()
            DirectConnection(endpoint, identity, rejected).use { connection ->
                connection.connect()
                assertTrue(
                    rejected.await { it.status == ConnectionStatus.ERROR }
                        .error.startsWith("TLS")
                )
            }
            val probe = Probe()
            val clockOffset = AtomicLong()
            DirectConnection(
                endpoint,
                identity,
                probe,
                availableActions = setOf("watch.vibrate"),
                httpClient = client,
                now = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + clockOffset.get() }
            ).use { connection ->
                connection.connect()
                val pairing = probe.await { it.pairingCode.isNotBlank() }
                command(process, "approve " + pairing.pairingCode)
                probe.await { it.status == ConnectionStatus.PAIRED }
                assertTrue(connection.text("synthetic café").get(5, TimeUnit.SECONDS))
                probe.await { it.conversation.reply == "You said: synthetic café" }
                probe.playback.clear()
                connection.startRecording()
                val token = probe.capture.poll(5, TimeUnit.SECONDS)
                    ?: error("Capture did not start")
                val pcm = ByteArray(12800) { (it % 100).toByte() }
                pcm.asList().chunked(640).forEach { connection.captured(token, it.toByteArray()) }
                connection.finishRecording()
                probe.await { it.conversation.reply.startsWith("I heard 0.4 seconds") }
                assertArrayEquals(
                    pcm,
                    probe.playback.toList().flatMap {
                        it.toList()
                    }.toByteArray()
                )
                command(process, "card Test card|Synthetic content")
                probe.await { it.conversation.card?.title == "Test card" }
                command(process, "ask Confirm|Continue synthetic demo?")
                val prompt = requireNotNull(
                    probe.await { it.conversation.prompt != null }
                        .conversation.prompt
                )
                probe.await { it.conversation.canAnswer }
                connection.answer(prompt.id, true)
                probe.await { it.conversation.notice == "You answered yes" }
                val image = directory.resolve("synthetic.png")
                val bitmap = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
                bitmap.setRGB(0, 0, 0xFF0000)
                bitmap.setRGB(1, 0, 0x0000FF)
                ImageIO.write(bitmap, "png", image.toFile())
                command(process, "image " + image)
                probe.await { it.conversation.picture != null }
                command(process, "action watch.vibrate " + "{}")
                val call = probe.actions.poll(5, TimeUnit.SECONDS)
                    ?: error("Advertised action did not arrive")
                connection.actionResult(
                    call.first,
                    call.second.id,
                    buildJsonObject { put("vibrated", true) }
                )
                assertEquals(true, acceptedActions.poll(5, TimeUnit.SECONDS))
                connection.startRecording()
                val cancelled = probe.capture.poll(5, TimeUnit.SECONDS)
                    ?: error("Cancel capture did not start")
                connection.captured(cancelled, ByteArray(640))
                connection.cancelRecording()
                probe.await { it.conversation.notice == "Recording discarded." }
                connection.disconnect()
                probe.await { it.status == ConnectionStatus.OFFLINE }
                assertFalse(connection.text("unsent draft").get(5, TimeUnit.SECONDS))
                connection.connect()
                probe.await { it.status == ConnectionStatus.PAIRED }
                assertTrue(connection.text("reconnected").get(5, TimeUnit.SECONDS))
                probe.await {
                    it.conversation.reply == "You said: reconnected" &&
                        it.conversation.status.isEmpty()
                }
                clockOffset.addAndGet(200000)
                val idle = probe.await { it.status == ConnectionStatus.OFFLINE }
                assertEquals("You said: reconnected", idle.conversation.reply)
                assertFalse(connection.text("idle draft").get(5, TimeUnit.SECONDS))
                connection.connect()
                probe.await { it.status == ConnectionStatus.PAIRED }
                assertTrue(connection.text("fresh send").get(5, TimeUnit.SECONDS))
                probe.await { it.conversation.reply == "You said: fresh send" }
                connection.close()
                assertFalse(connection.text("closed draft").get(5, TimeUnit.SECONDS))
            }
        } finally {
            runCatching { command(process, "quit") }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.toFile().deleteRecursively()
        }
    }

    private fun command(process: Process, text: String) {
        process.outputStream.write((text + "\n").toByteArray())
        process.outputStream.flush()
    }

    private class Probe : ConnectionObserver {
        private val states = LinkedBlockingQueue<ConnectionState>()
        val capture = LinkedBlockingQueue<String>()
        val playback = LinkedBlockingQueue<ByteArray>()
        val actions = LinkedBlockingQueue<Pair<Long, ConversationEffect.Action>>()
        override fun stateChanged(state: ConnectionState) {
            states.add(state)
        }
        override fun effect(effect: ConversationEffect, generation: Long) {
            when (effect) {
                is ConversationEffect.CaptureStart -> capture.add(effect.token)
                is ConversationEffect.PlaybackData -> playback.add(effect.bytes)
                is ConversationEffect.Action -> actions.add(generation to effect)
                else -> Unit
            }
        }
        fun await(predicate: (ConnectionState) -> Boolean): ConnectionState {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (true) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "Timed out waiting for direct connection state" }
                val state = states.poll(left, TimeUnit.NANOSECONDS)
                    ?: error("Timed out waiting for direct connection state")
                if (predicate(state)) return state
                check(state.status != ConnectionStatus.ERROR) { "Direct connection failed" }
            }
        }
    }
}
