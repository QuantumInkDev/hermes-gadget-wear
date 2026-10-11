package dev.quantumink.hermesgadget.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogSource
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogVaultTest {
    @Test fun sourceCredentialsSurviveRestartEncryptedAndFailClosedOnTamper() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "catalog-test-$suffix")
        val alias = "hermesgadget.test.catalog.$suffix"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val vault = CatalogVault(context, directory, alias)
            vault.saveSources(
                listOf(
                    CatalogSource(
                        "a",
                        "Synthetic box",
                        "https://example.invalid/catalog/v1",
                        "synthetic-private-credential"
                    )
                )
            )
            assertEquals("a", CatalogVault(context, directory, alias).sources().single().id)
            val file = File(directory, "sources")
            assertFalse(
                file.readBytes().toString(
                    Charsets.ISO_8859_1
                ).contains("synthetic-private-credential")
            )
            file.writeBytes(
                file.readBytes().apply {
                    this[lastIndex] =
                        (last().toInt() xor 1).toByte()
                }
            )
            assertThrows(Exception::class.java) { vault.sources() }
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
