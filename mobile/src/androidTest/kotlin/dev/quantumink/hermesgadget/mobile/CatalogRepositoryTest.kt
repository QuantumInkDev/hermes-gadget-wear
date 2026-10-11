package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogRepositoryTest {
    @Test fun confirmedSetupRemovalReclaimsFallbackCapacityAcrossRestart() {
        fixture { context, vault, setup, portraits ->
            var repository =
                CatalogRepository(context, vault, setup, portraits, publish = { _, _ -> })
            try {
                repeat(33) { index ->
                    val profile =
                        EndpointProfile(
                            Endpoint.parse("wss://agent$index.example.invalid/gadget"),
                            "Agent",
                            ""
                        )
                    setup.save(profile)
                    repository.selectPhoto(profile, photo(index))
                    val hash = requireNotNull(
                        repository.savedPages().single().profile.portrait
                    ).sha256
                    setup.remove(profile.endpoint.url)
                    repository.setupRemoved()
                    assertTrue(repository.savedPages().isEmpty())
                    assertTrue(
                        "Removed setup must free its fallback record",
                        vault.fallbacks().isEmpty()
                    )
                    assertTrue(
                        "Unreferenced portrait must be retired",
                        portraits.file(hash) == null
                    )
                    repository.close()
                    repository =
                        CatalogRepository(context, vault, setup, portraits, publish = { _, _ -> })
                }
            } finally {
                repository.close()
            }
        }
    }

    @Test fun sourceReferenceKeepsFallbackUntilConfirmedCardRetirement() {
        fixture { context, vault, setup, portraits ->
            val profile =
                EndpointProfile(Endpoint.parse("wss://agent.example.invalid/gadget"), "Agent", "")
            setup.save(profile)
            vault.savePages(
                listOf(
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile("p", "Agent", profile.endpoint.url, null),
                        CatalogStatus.OFFLINE,
                        1
                    )
                )
            )
            val repository =
                CatalogRepository(context, vault, setup, portraits, publish = { _, _ -> })
            try {
                repository.selectPhoto(profile, photo(1))
                val chosen = requireNotNull(repository.savedPages().single().profile.portrait)
                val initial = repository.savedPages().single()
                vault.savePages(
                    listOf(
                        CatalogPage(
                            initial.sourceId,
                            initial.sourceLabel,
                            initial.profile,
                            CatalogStatus.WITHDRAWN,
                            2
                        )
                    )
                )
                repository.retirePage(initial.key)
                assertEquals("manual", repository.savedPages().single().sourceId)
                assertEquals(1, vault.fallbacks().size)
                assertNotNull(portraits.file(chosen.sha256))
                vault.savePages(
                    listOf(
                        CatalogPage(
                            initial.sourceId,
                            initial.sourceLabel,
                            initial.profile,
                            CatalogStatus.OFFLINE,
                            3
                        )
                    )
                )
                setup.remove(profile.endpoint.url)
                repository.setupRemoved()
                assertEquals(1, vault.fallbacks().size)
                assertNotNull(portraits.file(chosen.sha256))
                val page = repository.savedPages().single()
                vault.savePages(
                    listOf(
                        CatalogPage(
                            page.sourceId,
                            page.sourceLabel,
                            page.profile,
                            CatalogStatus.WITHDRAWN,
                            2
                        )
                    )
                )
                repository.retirePage(page.key)
                assertTrue(vault.fallbacks().isEmpty())
                assertTrue(portraits.file(chosen.sha256) == null)
            } finally {
                repository.close()
            }
        }
    }

    private fun photo(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE + color)
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
        }.toByteArray()
    }

    @Test fun ownerFallbackSurvivesPortraitlessRefreshAndRestart() {
        fixture { context, vault, setup, portraits ->
            val endpoint = Endpoint.parse("wss://synthetic.example.invalid/gadget")
            val profile = EndpointProfile(endpoint, "Agent", "")
            setup.save(profile)
            val source =
                CatalogSource(
                    "a",
                    "Box",
                    "https://synthetic.example.invalid/catalog/v1",
                    "synthetic"
                )
            vault.saveSources(listOf(source))
            val fetch: (
                CatalogSource
            ) -> List<CatalogProfile> = { listOf(CatalogProfile("p", "Agent", endpoint.url, null)) }
            var repository =
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    fetchProfiles = fetch,
                    publish = {
                            _,
                            _
                        ->
                    }
                )
            try {
                repository.refresh(true).get(4, TimeUnit.SECONDS)
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.BLUE)
                }
                val raw = ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                bitmap.recycle()
                repository.selectPhoto(profile, raw)
                val chosen = requireNotNull(repository.savedPages().single().profile.portrait)
                repeat(2) {
                    assertEquals(
                        chosen.sha256,
                        repository.refresh(
                            true
                        ).get(4, TimeUnit.SECONDS).single().profile.portrait?.sha256
                    )
                    assertNotNull(portraits.file(chosen.sha256))
                }
                val replacementBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.GREEN)
                }
                val replacement = ByteArrayOutputStream().also {
                    replacementBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                replacementBitmap.recycle()
                repository.selectPhoto(profile, replacement)
                org.junit.Assert.assertNotEquals(
                    chosen.sha256,
                    repository.savedPages().single().profile.portrait?.sha256
                )
                val latest = requireNotNull(repository.savedPages().single().profile.portrait)
                repository.close()
                repository =
                    CatalogRepository(
                        context,
                        vault,
                        setup,
                        portraits,
                        fetchProfiles = fetch,
                        publish = {
                                _,
                                _
                            ->
                        }
                    )
                assertEquals(
                    latest.sha256,
                    repository.refresh(
                        true
                    ).get(4, TimeUnit.SECONDS).single().profile.portrait?.sha256
                )
            } finally {
                repository.close()
            }
        }
    }

    @Test fun healthyPartialRosterReachesPublicationBeforeSlowBoxFinishes() {
        fixture { context, vault, setup, portraits ->
            vault.saveSources(
                listOf("a", "b").map {
                    CatalogSource(
                        it,
                        "Box $it",
                        "https://$it.example.invalid/catalog/v1",
                        "synthetic"
                    )
                }
            )
            val release = CountDownLatch(1)
            val published = CountDownLatch(1)
            val repository =
                CatalogRepository(context, vault, setup, portraits, fetchProfiles = { source ->
                    if (source.id == "b") release.await(3, TimeUnit.SECONDS)
                    listOf(CatalogProfile("p", "Agent", null, null))
                }, publish = { pages, _ ->
                    if (pages.any { it.sourceId == "a" }) published.countDown()
                })
            try {
                val result = repository.refresh(true)
                assertTrue(published.await(1, TimeUnit.SECONDS))
                assertFalse(result.isDone)
                release.countDown()
                assertEquals(2, result.get(4, TimeUnit.SECONDS).size)
            } finally {
                release.countDown()
                repository.close()
            }
        }
    }

    @Test fun corruptSourceImageKeepsLastCommittedPortraitDuringCleanup() {
        fixture { context, vault, setup, portraits ->
            val source =
                CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "synthetic")
            vault.saveSources(listOf(source))
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }
            val raw = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            val previous = portraits.normalize(raw)
            vault.savePages(
                listOf(
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile("p", "Agent", null, previous),
                        CatalogStatus.AVAILABLE,
                        1
                    )
                )
            )
            val invalid = PortraitDescriptor("a".repeat(64), 3, "/assets/${"a".repeat(64)}.png")
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                fetchProfiles = { listOf(CatalogProfile("p", "Agent", null, invalid)) },
                fetchPortrait = { _, _, _ -> byteArrayOf(1, 2, 3) },
                publish = { _, _ -> }
            )
            try {
                val saved = repository.refresh(true).get(4, TimeUnit.SECONDS).single()
                assertEquals(previous.sha256, saved.profile.portrait?.sha256)
                assertNotNull(portraits.file(previous.sha256))
            } finally {
                repository.close()
            }
        }
    }

    @Test fun confirmedRetirementFreesHistoricalCapacityWithoutChangingSetup() {
        fixture { context, vault, setup, portraits ->
            val endpoint = Endpoint.parse("wss://retained.example.invalid/gadget")
            setup.save(EndpointProfile(endpoint, "Agent", "syntheticSavedToken"))
            val source =
                CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "synthetic")
            vault.saveSources(listOf(source))
            val previous = (0..31).map {
                CatalogPage(
                    "a",
                    "Box",
                    CatalogProfile(
                        "p$it",
                        "Agent",
                        if (it ==
                            0
                        ) {
                            endpoint.url
                        } else {
                            null
                        },
                        null
                    ),
                    CatalogStatus.WITHDRAWN,
                    1
                )
            }
            vault.savePages(previous)
            val before = setup.profiles().single().accessToken
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                fetchProfiles = { listOf(CatalogProfile("new", "Replacement", null, null)) },
                publish = { _, _ -> }
            )
            try {
                assertEquals(32, repository.refresh(true).get(4, TimeUnit.SECONDS).size)
                repository.retirePage("a:p31")
                val fresh = repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals(32, fresh.size)
                assertTrue(fresh.any { it.key == "a:new" })
                assertTrue(fresh.none { it.key == "a:p31" })
                assertEquals(before, setup.profiles().single().accessToken)
            } finally {
                repository.close()
            }
        }
    }

    private fun fixture(
        block: (android.content.Context, CatalogVault, ProfileVault, PortraitStore) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "repository-$id")
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
