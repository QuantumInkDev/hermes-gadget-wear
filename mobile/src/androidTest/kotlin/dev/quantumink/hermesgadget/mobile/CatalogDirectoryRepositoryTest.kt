package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDirectoryRepositoryTest {
    @Test fun interleavedMigrationLocalMovesAndManualOrderSurviveRefreshAndRestart() {
        fixture { context, vault, setup, portraits ->
            vault.saveSources(listOf(source("a"), source("b"), source("c")))
            val manual = listOf("one", "two").map {
                EndpointProfile(Endpoint.parse("wss://$it.example.invalid/gadget"), "Duplicate", "")
            }
            manual.forEach(setup::save)
            val ids = manual.map { ClientSpeech.endpointId(it.endpoint) }
            vault.savePages(
                listOf(
                    page("b", "two"),
                    page("a", "same"),
                    page("b", "same"),
                    page("manual", ids[0]),
                    page("a", "one"),
                    page("manual", ids[1])
                )
            )
            var offline = false
            val fetch: (CatalogSource) -> List<CatalogProfile> = {
                if (offline && it.id == "b") throw CatalogFailure(CatalogStatus.OFFLINE)
                if (it.id == "c") {
                    emptyList()
                } else {
                    listOf(
                        CatalogProfile("same", "Duplicate", null, null),
                        CatalogProfile(if (it.id == "a") "one" else "two", "Duplicate", null, null),
                        CatalogProfile("new", "New", null, null)
                    )
                }
            }
            var published = emptyList<String>()
            var repository =
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    fetchProfiles = fetch,
                    publish = {
                            pages,
                            _
                        ->
                        published =
                            pages.map { it.key }
                    }
                )
            try {
                assertEquals(
                    listOf(
                        "b:two",
                        "b:same",
                        "a:same",
                        "a:one",
                        "manual:${ids[0]}",
                        "manual:${ids[1]}"
                    ),
                    repository.savedPages().map {
                        it.key
                    }
                )
                assertEquals(listOf("b", "a", "manual", "c"), repository.sourceOrder())
                repository.movePage("a:one", -1)
                repository.movePage("manual:${ids[1]}", -1)
                repository.moveSource("a", -1)
                offline = true
                val failed = repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals(
                    listOf(
                        "a:one",
                        "a:same",
                        "a:new",
                        "b:two",
                        "b:same",
                        "manual:${ids[1]}",
                        "manual:${ids[0]}"
                    ),
                    failed.map {
                        it.key
                    }
                )
                assertEquals(CatalogStatus.OFFLINE, failed.first { it.sourceId == "b" }.status)
                repository.close()
                repository =
                    CatalogRepository(
                        context,
                        vault,
                        setup,
                        portraits,
                        fetchProfiles = fetch,
                        publish = {
                                pages,
                                _
                            ->
                            published =
                                pages.map { it.key }
                        }
                    )
                assertEquals(failed.map { it.key }, repository.savedPages().map { it.key })
                offline = false
                val returned = repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals(
                    listOf(
                        "a:one",
                        "a:same",
                        "a:new",
                        "b:two",
                        "b:same",
                        "b:new",
                        "manual:${ids[1]}",
                        "manual:${ids[0]}"
                    ),
                    returned.map {
                        it.key
                    }
                )
                assertEquals(returned.map { it.key }, published)
            } finally {
                repository.close()
            }
        }
    }

    @Test fun reorderedEmptyBoxRetainsSlotUntilItsFirstProfilesArrive() {
        fixture { context, vault, setup, portraits ->
            vault.saveSources(listOf(source("a"), source("b"), source("c")))
            vault.savePages(listOf(page("a", "same"), page("b", "same")))
            var repository =
                CatalogRepository(context, vault, setup, portraits, publish = { _, _ -> })
            repository.moveSource("c", -3)
            repository.close()
            repository =
                CatalogRepository(context, vault, setup, portraits, fetchProfiles = {
                    listOf(CatalogProfile("same", "Duplicate", null, null))
                }, publish = { _, _ -> })
            try {
                assertEquals(listOf("c", "a", "b", "manual"), repository.sourceOrder())
                assertEquals(listOf("c", "a", "b"), repository.sources().map { it.id })
                assertEquals(
                    listOf("c:same", "a:same", "b:same"),
                    repository.refresh(true).get(4, TimeUnit.SECONDS).map {
                        it.key
                    }
                )
                assertFalse(vault.sourceOrder().isNullOrEmpty())
            } finally {
                repository.close()
            }
        }
    }

    @Test fun manualFallbackAndExplicitOverrideKeepCorrectPicturesAfterGroupingAndRefresh() {
        fixture { context, vault, setup, portraits ->
            val profile =
                EndpointProfile(Endpoint.parse("wss://a.example.invalid/gadget"), "Agent", "")
            setup.save(profile)
            var boxPortrait: dev.quantumink.hermesgadget.protocol.PortraitDescriptor? = null
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                fetchProfiles = { source ->
                    listOf(
                        CatalogProfile(
                            "p",
                            "Duplicate",
                            if (source.id ==
                                "a"
                            ) {
                                profile.endpoint.url
                            } else {
                                null
                            },
                            boxPortrait
                        )
                    )
                },
                publish = { _, _ -> }
            )
            try {
                repository.selectPhoto(profile, photo(1))
                val fallback = repository.savedPages().single().profile.portrait?.sha256
                repository.saveSource(source("a"))
                repository.saveSource(source("b"))
                val discovered = repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals(
                    fallback,
                    discovered.single {
                        it.key == "a:p"
                    }.profile.portrait?.sha256
                )
                assertTrue(
                    "A phone fallback is an owner picture, not box art",
                    "a:p" in repository.watchPictures.value
                )
                repository.selectWatchPhoto("a:p", photo(2))
                assertEquals(
                    setOf("manual:${ClientSpeech.endpointId(profile.endpoint)}", "a:p"),
                    vault.overrides().map { it.key }.toSet()
                )
                val chosen = repository.savedPages().single {
                    it.key == "a:p"
                }.profile.portrait?.sha256
                repository.moveSource("b", -3)
                boxPortrait = portraits.normalize(photo(3))
                val refreshed = repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals(listOf("b:p", "a:p"), refreshed.map { it.key })
                assertEquals(chosen, refreshed.single { it.key == "a:p" }.profile.portrait?.sha256)
                assertEquals(
                    boxPortrait.sha256,
                    vault.pages().single {
                        it.key == "a:p"
                    }.profile.portrait?.sha256
                )
                repository.useBoxPicture("a:p")
                assertEquals(
                    boxPortrait.sha256,
                    repository.savedPages().single {
                        it.key == "a:p"
                    }.profile.portrait?.sha256
                )
                assertFalse("a:p" in repository.watchPictures.value)
                assertTrue(vault.fallbacks().isEmpty())
            } finally {
                repository.close()
            }
        }
    }

    private fun photo(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE + color)
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
        }.toByteArray()
    }

    @Test fun conversationProjectionUsesOnlyExactSavedGadgetCredentialAndLegacyManualId() {
        fixture { context, vault, setup, portraits ->
            vault.saveSources(listOf(source("a")))
            setup.save(
                EndpointProfile(
                    Endpoint.parse("WSS://AGENT.EXAMPLE.INVALID:443/gadget"),
                    "Saved",
                    "syntheticGadgetToken"
                )
            )
            val legacy =
                EndpointProfile(
                    Endpoint.parse(
                        "ws://legacy.example.invalid/gadget",
                        privateNetwork = true,
                        cleartextAccepted = true
                    ),
                    "Legacy",
                    "legacyGadgetToken"
                )
            setup.save(legacy)
            val repository =
                CatalogRepository(context, vault, setup, portraits, publish = { _, _ -> })
            try {
                val matching =
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile(
                            "match",
                            "Catalog label",
                            "wss://agent.example.invalid/gadget",
                            null
                        ),
                        CatalogStatus.OFFLINE,
                        1
                    )
                assertEquals("syntheticGadgetToken", repository.profileFor(matching)?.accessToken)
                assertEquals("Catalog label", repository.profileFor(matching)?.label)
                val unrelated =
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile("other", "Other", "wss://agent.example.invalid/other", null),
                        CatalogStatus.AVAILABLE,
                        1
                    )
                assertEquals("", repository.profileFor(unrelated)?.accessToken)
                assertNull(repository.profileFor(page("a", "unconfigured")))
                val saved = repository.savedPages().single {
                    it.profile.id ==
                        ClientSpeech.endpointId(legacy.endpoint)
                }
                assertEquals(legacy.endpoint.url, repository.profileFor(saved)?.endpoint?.url)
                assertFalse(requireNotNull(repository.profileFor(saved)).endpoint.isTls)
                assertEquals("legacyGadgetToken", repository.profileFor(saved)?.accessToken)
            } finally {
                repository.close()
            }
        }
    }

    private fun source(id: String) = CatalogSource(
        id,
        "Box $id",
        "https://$id.example.invalid/catalog/v1",
        "syntheticCatalogOnly"
    )

    private fun page(source: String, id: String) = CatalogPage(
        source,
        "Box $source",
        CatalogProfile(id, "Duplicate", null, null),
        CatalogStatus.AVAILABLE,
        1
    )

    private fun fixture(block: (Context, CatalogVault, ProfileVault, PortraitStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "directory-$id")
        val aliases = listOf("hermes.test.catalog.$id", "hermes.test.setup.$id")
        try {
            block(
                context,
                CatalogVault(context, File(directory, "vault"), aliases[0]),
                ProfileVault(context, File(directory, "setup"), aliases[1]),
                PortraitStore(File(directory, "portraits"))
            )
        } finally {
            directory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
        }
    }
}
