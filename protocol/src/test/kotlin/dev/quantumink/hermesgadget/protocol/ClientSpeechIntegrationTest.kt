package dev.quantumink.hermesgadget.protocol

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ClientSpeechIntegrationTest {
    @Test(timeout = 30000)
    fun optionalServerEmitsSpeechAndStockServerKeepsReadableReply() {
        val stock = System.getenv("HERMES_GADGET_PYTHON").orEmpty()
        val extension = System.getenv("HERMES_GADGET_EXTENSION_PYTHON").orEmpty()
        assumeTrue(
            "Set stock and extension Python for live speech tests",
            stock.isNotBlank() && extension.isNotBlank()
        )
        for (enabled in listOf(false, true)) {
            val directory = Files.createTempDirectory("gadget-speech-")
            val ready = directory.resolve("ready.json")
            val args =
                listOf(
                    if (enabled) extension else stock,
                    "-u",
                    "tools/integration_devserver.py",
                    "--ready-file",
                    ready.toString()
                ) +
                    if (enabled) listOf("--client-tts") else emptyList()
            val process = ProcessBuilder(
                args
            ).redirectError(
                ProcessBuilder.Redirect.DISCARD
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!Files.exists(ready)) {
                    check(process.isAlive && System.nanoTime() < deadline)
                    Thread.sleep(25)
                }
                val port = Json.parseToJsonElement(
                    Files.readString(ready)
                ).jsonObject.getValue("port").jsonPrimitive.int
                val states = LinkedBlockingQueue<ConnectionState>()
                val spoken = LinkedBlockingQueue<ConversationEffect.ClientSpeak>()
                val observer = object : ConnectionObserver {
                    override fun stateChanged(state: ConnectionState) {
                        states.add(state)
                    }
                    override fun effect(effect: ConversationEffect, generation: Long) {
                        if (effect is ConversationEffect.ClientSpeak) spoken.add(effect)
                    }
                }
                fun await(predicate: (ConnectionState) -> Boolean): ConnectionState {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < until) {
                        val state = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        if (predicate(state)) return state
                    }
                    error("Speech SDK state did not arrive")
                }
                val caps = buildJsonObject { put("tts", "client") }
                DirectConnection(
                    Endpoint.parse("ws://localhost:$port/gadget", true, true),
                    DeviceIdentity.generate(),
                    observer,
                    capabilities = caps
                ).use { connection ->
                    connection.connect()
                    val pairing = await { it.pairingCode.isNotEmpty() }
                    process.outputStream.write(
                        ("approve " + pairing.pairingCode + "\n").toByteArray()
                    )
                    process.outputStream.flush()
                    assertEquals(
                        enabled,
                        await {
                            it.status == ConnectionStatus.PAIRED
                        }.clientSpeech
                    )
                    assertTrue(connection.text("Original speech fixture").get(5, TimeUnit.SECONDS))
                    val reply = "You said: Original speech fixture"
                    await { it.conversation.reply == reply }
                    val speech = spoken.poll(if (enabled) 5 else 1, TimeUnit.SECONDS)
                    assertEquals(if (enabled) reply else null, speech?.text)
                    if (enabled) assertEquals("syntheticVoice", speech?.voice)
                }
            } finally {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}
