package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DevserverIntegrationTest {
    @Test(timeout = 40000)
    fun enrollApproveEchoLoopbackAndReconnectAgainstStockSdk() {
        val python = System.getenv("HERMES_GADGET_PYTHON").orEmpty()
        assumeTrue(
            "Set HERMES_GADGET_PYTHON to run the stock SDK integration test",
            python.isNotBlank()
        )
        val ready = Files.createTempFile("gadget-ready-", ".json")
        Files.delete(ready)
        val process = ProcessBuilder(
            python,
            "-u",
            "tools/integration_devserver.py",
            "--ready-file",
            ready.toString()
        )
            .redirectOutput(
                ProcessBuilder.Redirect.DISCARD
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val client = OkHttpClient.Builder().build()
        try {
            val port = awaitPort(process, ready)
            val url = URI(
                "ws",
                null,
                InetAddress.getLoopbackAddress().hostAddress,
                port,
                "/gadget",
                null,
                null
            ).toString()
            val identity = DeviceIdentity.generate()
            Peer(client, url, identity).use { peer ->
                peer.await("welcome")
                assertFalse(peer.handshake.canSendConversation)
                assertFalse(peer.usedMac)
                val code = requireNotNull(peer.await("pairing").string("code"))
                process.outputStream.write("approve $code\n".toByteArray())
                process.outputStream.flush()
                peer.await("paired")
                assertTrue(peer.handshake.canSendConversation)

                peer.send(
                    Message.create(
                        "text",
                        buildJsonObject {
                            put("id", "t1")
                            put("text", "hello café")
                        }
                    )
                )
                peer.await("turn.start")
                val delta = peer.await("reply.delta")
                assertEquals("You", delta.string("text"))
                assertEquals("You said: hello café", peer.await("reply").string("text"))
                assertEquals("success", peer.await("turn.end").string("outcome"))

                val audio = syntheticPcm()
                peer.binary.clear()
                peer.send(
                    Message.create(
                        "audio.start",
                        buildJsonObject {
                            put("id", "a2")
                            put("stream", 4)
                            put("rate", 16000)
                            put("format", "pcm16")
                            put("mode", "hold")
                        }
                    )
                )
                audio.asList().chunked(640).forEachIndexed { sequence, chunk ->
                    assertTrue(
                        peer.socket.send(
                            BinaryFrame(
                                BinaryFrame.AUDIO,
                                4,
                                sequence,
                                chunk.toByteArray()
                            ).encode().toByteString()
                        )
                    )
                }
                peer.send(
                    Message.create(
                        "audio.end",
                        buildJsonObject {
                            put("id", "a2")
                            put("stream", 4)
                            put("duration_ms", 400)
                        }
                    )
                )
                peer.await("turn.start")
                val playback = peer.await("audio.start")
                assertEquals("pcm16", playback.string("format"))
                assertEquals(16000, playback.int("rate"))
                peer.await("audio.end")
                val frames = peer.binary.toList().filter {
                    it.channel == BinaryFrame.AUDIO &&
                        it.stream == playback.int("stream")
                }
                assertTrue(frames.isNotEmpty())
                frames.forEachIndexed { sequence, frame -> assertEquals(sequence, frame.sequence) }
                val received = ByteArrayOutputStream().also { out ->
                    frames.forEach { out.write(it.payload) }
                }.toByteArray()
                assertArrayEquals(audio, received)
                assertTrue(
                    requireNotNull(
                        peer.await("reply").string("text")
                    ).startsWith("I heard 0.4 seconds")
                )
                peer.await("turn.end")
            }
            Peer(client, url, identity).use { peer ->
                peer.await("welcome")
                assertTrue(peer.usedMac)
                assertTrue(peer.handshake.canSendConversation)
                peer.send(
                    Message.create(
                        "text",
                        buildJsonObject {
                            put("id", "t3")
                            put("text", "reconnected")
                        }
                    )
                )
                assertEquals("You said: reconnected", peer.await("reply").string("text"))
                peer.await("turn.end")
            }
        } finally {
            runCatching {
                process.outputStream.write("quit\n".toByteArray())
                process.outputStream.flush()
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            Files.deleteIfExists(ready)
        }
    }

    private fun awaitPort(process: Process, ready: Path): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "SDK development server exited before readiness" }
            if (Files.exists(ready) && Files.size(ready) > 0) {
                return Json.parseToJsonElement(
                    Files.readString(ready)
                ).jsonObject.getValue("port").jsonPrimitive.int
            }
            Thread.sleep(50)
        }
        error("SDK development server did not become ready")
    }

    private fun syntheticPcm(): ByteArray = ByteArray(12800).also { pcm ->
        for (sample in 0 until pcm.size / 2) {
            val value = (sin(sample * 2 * Math.PI * 440 / 16000) * 4000).toInt()
            pcm[sample * 2] = value.toByte()
            pcm[sample * 2 + 1] = (value shr 8).toByte()
        }
    }

    private class Peer(client: OkHttpClient, url: String, identity: DeviceIdentity) :
        AutoCloseable {
        val handshake = Handshake(identity, Protocol.pcmCapabilities())
        val binary = LinkedBlockingQueue<BinaryFrame>()
        private val messages = LinkedBlockingQueue<Message>()
        private val closed = CountDownLatch(1)

        @Volatile
        var usedMac = false
            private set
        val socket: WebSocket = client.newWebSocket(
            Request.Builder().url(
                url
            ).header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    check(response.header("Sec-WebSocket-Protocol") == Protocol.SUBPROTOCOL)
                    webSocket.send(handshake.open(now()).encode())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = Message.parse(text) ?: return
                    handshake.receive(message, now()).forEach { outgoing ->
                        if (outgoing.type == "auth") usedMac = outgoing.string("mac") != null
                        webSocket.send(outgoing.encode())
                    }
                    messages.add(message)
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    handshake.recordInbound(now())
                    BinaryFrame.parse(bytes.toByteArray())?.let(binary::add)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    messages.add(Message.create("transport.failure"))
                    closed.countDown()
                }
            }
        )

        fun send(message: Message) {
            check(handshake.canSendConversation)
            check(socket.send(message.encode()))
        }

        fun await(type: String): Message {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (true) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "Timed out waiting for message type $type" }
                val message =
                    messages.poll(left, TimeUnit.NANOSECONDS)
                        ?: error("Timed out waiting for message type $type")
                check(message.type != "transport.failure" && message.type != "error") {
                    "Transport or protocol failure"
                }
                if (message.type == type) return message
            }
        }

        override fun close() {
            socket.close(1000, null)
            if (!closed.await(2, TimeUnit.SECONDS)) socket.cancel()
        }

        private fun now(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
}
