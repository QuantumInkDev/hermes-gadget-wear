package dev.quantumink.hermesgadget.protocol

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MultiEndpointIntegrationTest {
    @Test(timeout = 40000)
    fun independentStockEndpointsPairAndReconnectWithTheirOwnIdentity() {
        val python = System.getenv("HERMES_GADGET_PYTHON").orEmpty()
        assumeTrue("Set stock Python for multi-endpoint test", python.isNotBlank())
        val directories = List(2) { Files.createTempDirectory("gadget-profile-") }
        val processes = directories.map { directory ->
            ProcessBuilder(
                python,
                "-u",
                "tools/integration_devserver.py",
                "--ready-file",
                directory.resolve("ready.json").toString()
            )
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }
        try {
            val endpoints = directories.mapIndexed { i, directory ->
                val file = directory.resolve("ready.json")
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!Files.exists(file)) {
                    check(processes[i].isAlive && System.nanoTime() < until)
                    Thread.sleep(25)
                }
                val port = Json.parseToJsonElement(Files.readString(file))
                    .jsonObject.getValue("port").jsonPrimitive.int
                Endpoint.parse("ws://localhost:$port/gadget", true, true)
            }
            val identities = List(2) { DeviceIdentity.generate() }
            assertNotEquals(identities[0].deviceId, identities[1].deviceId)
            for (round in 0..1) {
                for (index in 0..1) {
                    val states = LinkedBlockingQueue<ConnectionState>()
                    val observer = object : ConnectionObserver {
                        override fun effect(effect: ConversationEffect, generation: Long) = Unit
                        override fun stateChanged(state: ConnectionState) {
                            states.add(state)
                        }
                    }
                    fun await(predicate: (ConnectionState) -> Boolean): ConnectionState {
                        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (System.nanoTime() < until) {
                            val state = states.poll(100, TimeUnit.MILLISECONDS) ?: continue
                            if (predicate(state)) return state
                        }
                        error("Profile state did not arrive")
                    }
                    DirectConnection(
                        endpoints[index],
                        identities[index],
                        observer
                    ).use { connection ->
                        connection.connect()
                        if (round == 0) {
                            val pairing = await { it.pairingCode.isNotEmpty() }
                            processes[index].outputStream.write(
                                ("approve " + pairing.pairingCode + "\n").toByteArray()
                            )
                            processes[index].outputStream.flush()
                        }
                        val paired = await { it.status == ConnectionStatus.PAIRED }
                        assertEquals("", paired.conversation.reply)
                        val text = "Original profile $index round $round"
                        assertTrue(connection.text(text).get(5, TimeUnit.SECONDS))
                        await { it.conversation.reply == "You said: $text" }
                    }
                }
            }
        } finally {
            processes.forEach {
                it.destroyForcibly()
                it.waitFor(3, TimeUnit.SECONDS)
            }
            directories.forEach { directory ->
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}
