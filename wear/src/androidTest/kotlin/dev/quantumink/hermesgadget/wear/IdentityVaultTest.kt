package dev.quantumink.hermesgadget.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val directory = File(context.filesDir, "test-vault-" + suffix)
    private val alias = "hermesgadget.test." + suffix
    private lateinit var vault: IdentityVault
    private val first = Endpoint.parse("wss://first.example/gadget")
    private val second = Endpoint.parse("wss://second.example/gadget")

    @Before
    fun prepare() {
        vault = IdentityVault(context, directory, alias)
    }

    @After
    fun cleanUpOwnedTestRecordsOnly() {
        vault.reset()
    }

    @Test
    fun ciphertextPersistsSeparateIdentitiesAndNonExportableKey() {
        val token = "synthetic-token-for-test"
        val profile = EndpointProfile(first, "Synthetic test", token)
        val identity = vault.save(profile)
        val restored = IdentityVault(context, directory, alias)
        assertEquals(first.url, restored.profile()?.endpoint?.url)
        assertEquals(token, restored.profile()?.accessToken)
        assertEquals(identity.deviceId, restored.identity(first).deviceId)
        val other = restored.save(EndpointProfile(second, "Second", ""))
        assertNotEquals(identity.deviceId, other.deviceId)
        assertEquals(identity.deviceId, restored.save(profile).deviceId)
        requireNotNull(directory.listFiles()).forEach { file ->
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains(token))
            assertFalse(raw.contains(first.url))
            assertFalse(raw.contains(identity.enrollmentKey()))
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(keys.getKey(alias, null).encoded)
    }

    @Test
    fun tamperedIdentityFailsWithoutRotatingOrOverwritingIt() {
        val profile = EndpointProfile(first, "Synthetic test", "")
        vault.save(profile)
        val file = requireNotNull(directory.listFiles()).single {
            it.name.startsWith("identity-")
        }
        val tampered = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(tampered)
        assertThrows(Exception::class.java) { vault.identity(first) }
        assertThrows(Exception::class.java) { vault.save(profile) }
        assertEquals(tampered.toList(), file.readBytes().toList())
    }

    @Test
    fun missingIdentityOrKeystoreKeyRequiresExplicitReset() {
        val profile = EndpointProfile(first, "Synthetic test", "")
        vault.save(profile)
        val file = requireNotNull(directory.listFiles()).single {
            it.name.startsWith("identity-")
        }
        check(file.delete())
        assertThrows(IllegalStateException::class.java) { vault.save(profile) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        assertThrows(IllegalStateException::class.java) { vault.profile() }
        vault.reset()
        assertNull(vault.profile())
        vault.save(profile)
        assertEquals(first.url, vault.profile()?.endpoint?.url)
    }
}
