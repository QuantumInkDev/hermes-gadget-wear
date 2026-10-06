package dev.quantumink.hermesgadget.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileVaultTest {
    @Test fun encryptedBoundedCatalogUpdatesAndRemovesWithoutIdentityOrKeyLeaks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "profiles-test-" + suffix)
        val alias = "hermesgadget.test.profiles." + suffix
        val vault = ProfileVault(context, directory, alias)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val first = Endpoint.parse("wss://first.example/gadget")
            val token = UUID.randomUUID().toString()
            vault.save(EndpointProfile(first, "First", token))
            vault.save(EndpointProfile(Endpoint.parse("wss://second.example/gadget"), "Second", ""))
            assertEquals(2, ProfileVault(context, directory, alias).profiles().size)
            vault.save(EndpointProfile(first, "Renamed", token))
            assertEquals("Renamed", vault.profiles().first().label)
            val file = File(directory, "catalog")
            val encrypted = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(token))
            assertFalse(encrypted.contains(first.url))
            vault.remove(first.url)
            assertEquals(1, vault.profiles().size)
            file.writeBytes(
                file.readBytes().apply {
                    this[lastIndex] =
                        (last().toInt() xor 1).toByte()
                }
            )
            assertThrows(Exception::class.java) { vault.profiles() }
            keys.deleteEntry(alias)
            assertThrows(IllegalStateException::class.java) { vault.profiles() }
            assertFalse(keys.containsAlias(alias))
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
