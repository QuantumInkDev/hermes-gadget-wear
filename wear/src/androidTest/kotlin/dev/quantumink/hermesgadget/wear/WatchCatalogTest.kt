package dev.quantumink.hermesgadget.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchCatalogTest {
    @Test fun catalogUpdatesKnownLabelsWithoutEnrollingChangedOrMissingEndpoints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "watch-catalog-$suffix")
        val alias = "hermesgadget.test.catalog.$suffix"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val vault = IdentityVault(context, directory, alias)
            val endpoint = Endpoint.parse("wss://approved.example.invalid/gadget")
            val identity = vault.save(EndpointProfile(endpoint, "Old name", "syntheticToken"))
            val before = directory.listFiles().orEmpty().filter {
                it.name.startsWith("identity-")
            }.associate {
                it.name to
                    it.readBytes().toList()
            }
            val catalog = listOf(
                CatalogPage(
                    "a",
                    "First box",
                    CatalogProfile("p", "New name", endpoint.url, null),
                    CatalogStatus.AVAILABLE,
                    1
                ),
                CatalogPage(
                    "b",
                    "Second box",
                    CatalogProfile("p", "New name", "wss://new.example.invalid/gadget", null),
                    CatalogStatus.AVAILABLE,
                    1
                )
            )
            vault.updateLabels(
                catalog.mapNotNull { page ->
                    page.profile.gadgetUrl?.let {
                        it to
                            page.profile.name
                    }
                }.toMap()
            )
            assertEquals("New name", vault.profile()?.label)
            assertEquals("syntheticToken", vault.profile()?.accessToken)
            assertEquals(identity.deviceId, vault.identity(endpoint).deviceId)
            assertEquals(1, vault.profiles().size)
            assertEquals(
                before,
                directory.listFiles().orEmpty().filter {
                    it.name.startsWith("identity-")
                }.associate {
                    it.name to
                        it.readBytes().toList()
                }
            )
            val pages = ProfilePages.merge(vault.profiles(), catalog)
            assertEquals("First box", pages.first().source)
            assertNull(pages.last().approved)
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
