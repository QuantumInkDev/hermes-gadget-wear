package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.AnimationCache
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
    @Test fun explicitWatchPictureOverridesChangingBoxImageBeforeSetupAndAcrossRestart() {
        fixture { context, vault, setup, portraits, animations ->
            vault.saveSources(
                listOf(
                    CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "synthetic")
                )
            )
            var box = portraits.normalize(photo(1))
            val fetch: (CatalogSource) -> List<CatalogProfile> = {
                listOf(CatalogProfile("p", "Agent", null, box))
            }
            var repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                animations = animations,
                fetchProfiles = fetch,
                publish = { _, _ -> }
            )
            try {
                repository.refresh(true).get(4, TimeUnit.SECONDS)
                repository.selectWatchPhoto("a:p", photo(2))
                val chosen = requireNotNull(repository.savedPages().single().profile.portrait)
                org.junit.Assert.assertNotEquals(box.sha256, chosen.sha256)
                box = portraits.normalize(photo(3))
                assertEquals(
                    chosen.sha256,
                    repository.refresh(
                        true
                    ).get(4, TimeUnit.SECONDS).single().profile.portrait?.sha256
                )
                assertEquals(box.sha256, vault.pages().single().profile.portrait?.sha256)
                assertTrue(repository.watchPictures.value.contains("a:p"))
                assertNotNull(portraits.file(box.sha256))
                repository.close()
                repository = CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    fetchProfiles = fetch,
                    publish = { _, _ -> }
                )
                assertEquals(
                    chosen.sha256,
                    repository.savedPages().single().profile.portrait?.sha256
                )
                assertEquals(
                    chosen.sha256,
                    repository.refresh(
                        true
                    ).get(4, TimeUnit.SECONDS).single().profile.portrait?.sha256
                )
                repository.useBoxPicture("a:p")
                assertEquals(box.sha256, repository.savedPages().single().profile.portrait?.sha256)
                assertFalse(repository.watchPictures.value.contains("a:p"))
                assertTrue(vault.overrides().isEmpty())
                assertTrue(portraits.file(chosen.sha256) == null)
                assertTrue(setup.profiles().isEmpty())
            } finally {
                repository.close()
            }
        }
    }

    @Test fun duplicateProfileIdsOnDifferentBoxesKeepSeparateWatchPictures() {
        fixture { context, vault, setup, portraits, animations ->
            val box = portraits.normalize(photo(1))
            vault.savePages(
                listOf("a", "b").map { source ->
                    CatalogPage(
                        source,
                        "Box",
                        CatalogProfile("p", "Agent", null, box),
                        CatalogStatus.OFFLINE,
                        1
                    )
                }
            )
            val repository =
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    publish = {
                            _,
                            _
                        ->
                    }
                )
            try {
                repository.selectWatchPhoto("a:p", photo(2))
                val pages = repository.savedPages().associateBy { it.key }
                org.junit.Assert.assertNotEquals(
                    box.sha256,
                    pages.getValue("a:p").profile.portrait?.sha256
                )
                assertEquals(box.sha256, pages.getValue("b:p").profile.portrait?.sha256)
                assertEquals(setOf("a:p"), repository.watchPictures.value)
                val page = vault.pages().first { it.key == "a:p" }
                vault.savePages(
                    vault.pages().map {
                        if (it.key ==
                            page.key
                        ) {
                            CatalogPage(
                                it.sourceId,
                                it.sourceLabel,
                                it.profile,
                                CatalogStatus.WITHDRAWN,
                                2
                            )
                        } else {
                            it
                        }
                    }
                )
                repository.retirePage("a:p")
                assertTrue(vault.overrides().isEmpty())
                assertNotNull(portraits.file(box.sha256))
            } finally {
                repository.close()
            }
        }
    }

    @Test fun phoneSetupPhotoTakesPriorityOverExistingBoxPortraitWithoutChangingEndpoint() {
        fixture { context, vault, setup, portraits, animations ->
            val endpoint = Endpoint.parse("wss://agent.example.invalid/gadget")
            val profile = EndpointProfile(endpoint, "Agent", "syntheticSavedToken")
            setup.save(profile)
            val box = portraits.normalize(photo(1))
            vault.savePages(
                listOf(
                    CatalogPage(
                        "a",
                        "Box",
                        CatalogProfile("p", "Agent", endpoint.url, box),
                        CatalogStatus.AVAILABLE,
                        1
                    )
                )
            )
            val repository =
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    publish = {
                            _,
                            _
                        ->
                    }
                )
            try {
                repository.selectPhoto(profile, photo(2))
                org.junit.Assert.assertNotEquals(
                    box.sha256,
                    repository.savedPages().single().profile.portrait?.sha256
                )
                assertEquals(box.sha256, vault.pages().single().profile.portrait?.sha256)
                assertEquals(
                    dev.quantumink.hermesgadget.protocol.EndpointProfiles.encode(listOf(profile)),
                    dev.quantumink.hermesgadget.protocol.EndpointProfiles.encode(setup.profiles())
                )
                repository.useBoxPicture("a:p")
                assertEquals(box.sha256, repository.savedPages().single().profile.portrait?.sha256)
                assertTrue(vault.fallbacks().isEmpty())
                vault.savePages(
                    listOf(
                        CatalogPage(
                            "a",
                            "Box",
                            CatalogProfile("p", "Agent", endpoint.url, null),
                            CatalogStatus.AVAILABLE,
                            2
                        )
                    )
                )
                assertTrue(repository.savedPages().single().profile.portrait == null)
            } finally {
                repository.close()
            }
        }
    }

    @Test fun confirmedSetupRemovalReclaimsFallbackCapacityAcrossRestart() {
        fixture { context, vault, setup, portraits, animations ->
            var repository =
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    publish = {
                            _,
                            _
                        ->
                    }
                )
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
                        CatalogRepository(
                            context,
                            vault,
                            setup,
                            portraits,
                            animations = animations,
                            publish = {
                                    _,
                                    _
                                ->
                            }
                        )
                }
            } finally {
                repository.close()
            }
        }
    }

    @Test fun sourceReferenceKeepsFallbackUntilConfirmedCardRetirement() {
        fixture { context, vault, setup, portraits, animations ->
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
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    publish = {
                            _,
                            _
                        ->
                    }
                )
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

    @Test fun blockedWatchPublicationDoesNotBlockCurrentPhoneProfileOrCancellation() {
        fixture { context, vault, setup, portraits, animations ->
            val source =
                CatalogSource("a", "Box", "https://a.example.invalid/catalog/v1", "synthetic")
            vault.saveSources(listOf(source))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                animations = animations,
                fetchProfiles = {
                    listOf(CatalogProfile("p", "Agent", "wss://a.example.invalid/gadget", null))
                },
                publish = { _, _ ->
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            )
            try {
                val refresh = repository.refresh(true)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val page = repository.pages.value.single()
                val lookup = worker.submit<EndpointProfile?> { repository.profileFor(page) }
                assertEquals(
                    "wss://a.example.invalid/gadget",
                    lookup.get(500, TimeUnit.MILLISECONDS)?.endpoint?.url
                )
                val cancel = worker.submit { repository.saveSource(source) }
                cancel.get(500, TimeUnit.MILLISECONDS)
                assertTrue(refresh.isCancelled)
            } finally {
                release.countDown()
                worker.shutdownNow()
                repository.close()
            }
        }
    }

    @Test fun newerPictureKeepsInflightPublicationAssetsUntilOrderedReplacement() {
        fixture { context, vault, setup, portraits, animations ->
            val profile =
                EndpointProfile(Endpoint.parse("wss://a.example.invalid/gadget"), "Agent", "")
            setup.save(profile)
            val block = java.util.concurrent.atomic.AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val published = java.util.Collections.synchronizedList(mutableListOf<String>())
            val worker = java.util.concurrent.Executors.newFixedThreadPool(2)
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                animations = animations,
                publish = { pages, store ->
                    val hash = requireNotNull(pages.single().profile.portrait).sha256
                    if (block.compareAndSet(true, false)) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                    assertNotNull(
                        "In-flight immutable publication still owns its asset",
                        store.file(hash)
                    )
                    published.add(hash)
                }
            )
            try {
                repository.selectPhoto(profile, photo(android.graphics.Color.RED))
                val old = requireNotNull(repository.pages.value.single().profile.portrait).sha256
                published.clear()
                block.set(true)
                val first = worker.submit {
                    repository.movePage(repository.pages.value.single().key, 0)
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val second = worker.submit {
                    repository.selectPhoto(profile, photo(android.graphics.Color.BLUE))
                }
                val until = android.os.SystemClock.uptimeMillis() + 2000
                while (repository.pages.value.single().profile.portrait?.sha256 == old &&
                    android.os.SystemClock.uptimeMillis() < until
                ) {
                    android.os.SystemClock.sleep(20)
                }
                val fresh = requireNotNull(repository.pages.value.single().profile.portrait).sha256
                assertTrue(fresh != old)
                // Wait until the mutation has left its monitor.
                repository.profileFor(repository.pages.value.single())
                assertNotNull(
                    "New edit must not retire media still being published",
                    portraits.file(old)
                )
                release.countDown()
                first.get(2, TimeUnit.SECONDS)
                second.get(2, TimeUnit.SECONDS)
                assertEquals(listOf(old, fresh), published.toList())
                assertNotNull(portraits.file(fresh))
                assertTrue(
                    "Old media retires after replacement publication",
                    portraits.file(old) == null
                )
            } finally {
                release.countDown()
                worker.shutdownNow()
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
        fixture { context, vault, setup, portraits, animations ->
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
                    animations = animations,
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
                        animations = animations,
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
        fixture { context, vault, setup, portraits, animations ->
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
                CatalogRepository(
                    context,
                    vault,
                    setup,
                    portraits,
                    animations = animations,
                    fetchProfiles = { source ->
                        if (source.id == "b") release.await(3, TimeUnit.SECONDS)
                        listOf(CatalogProfile("p", "Agent", null, null))
                    },
                    publish = { pages, _ ->
                        if (pages.any { it.sourceId == "a" }) published.countDown()
                    }
                )
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
        fixture { context, vault, setup, portraits, animations ->
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
                animations = animations,
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
        fixture { context, vault, setup, portraits, animations ->
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
                animations = animations,
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
        block: (
            android.content.Context,
            CatalogVault,
            ProfileVault,
            PortraitStore,
            AnimationCache
        ) -> Unit
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
                PortraitStore(File(directory, "portraits")),
                AnimationCache(File(directory, "animations"))
            )
        } finally {
            directory.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
        }
    }
}
