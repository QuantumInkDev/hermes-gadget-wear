package dev.quantumink.hermesgadget.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationVaultTest {
    @Test fun independentIdentitiesSurviveRestartAndCorruptionFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "phone-identities-" + UUID.randomUUID())
        val alias = "test.phone." + UUID.randomUUID()
        val first = Endpoint.parse("wss://first.example.invalid/gadget")
        val second = Endpoint.parse("wss://second.example.invalid/gadget")
        try {
            val vault = ConversationVault(context, directory, alias)
            assertTrue(runCatching { vault.identity(first) }.isFailure)
            val one = vault.identity(first, create = true).deviceId
            val two = vault.identity(second, create = true).deviceId
            assertNotEquals(one, two)
            val restarted = ConversationVault(context, directory, alias)
            assertEquals(one, restarted.identity(first).deviceId)
            assertEquals(two, restarted.identity(second).deviceId)
            val record = directory.listFiles()!!.first { it.name.startsWith("identity-") }
            val bytes = record.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            record.writeBytes(bytes)
            assertTrue(
                runCatching { restarted.identity(first, true) }.isFailure ||
                    runCatching { restarted.identity(second, true) }.isFailure
            )
            directory.listFiles()!!.filter {
                it.name.startsWith("identity-")
            }.forEach { it.delete() }
            assertTrue(runCatching { restarted.identity(first, true) }.isFailure)
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }
}
