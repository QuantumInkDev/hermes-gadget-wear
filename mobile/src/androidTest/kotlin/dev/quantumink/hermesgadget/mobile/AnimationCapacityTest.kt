package dev.quantumink.hermesgadget.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.AnimationCache
import dev.quantumink.hermesgadget.client.GifDecoder
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationCapacityTest {
    @Test fun thirtyTwoProtectedAnimationsCanBeReplacedWithoutRemovingPublishedDataEarly() {
        fixture { context, root, vault, setup, portraits, original ->
            val cache = AnimationCache(File(root, "animations"))
            val records = seed(vault, portraits, cache, original)
            val previous = records.getValue("box:p0")
            var publications = 0
            val repository = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                animations = cache,
                publish = { _, _ ->
                    publications++
                    assertNotNull(
                        "Old published asset survives until publication completes",
                        cache.file(previous)
                    )
                    assertEquals(32, vault.animations().size)
                    vault.animations().values.forEach { assertNotNull(cache.file(it)) }
                }
            )
            try {
                repository.selectWatchPhoto("box:p0", changed(original, 100))
                val chosen = vault.animations().getValue("box:p0")
                assertEquals(1, publications)
                assertTrue(chosen.sha256 != previous.sha256)
                assertNotNull(cache.file(chosen))
                assertEquals("gif", cache.file(chosen)?.extension)
                assertNull(cache.file(previous))
                assertEquals(32, settled(root).size)
                assertTrue(settled(root).sumOf { it.length() } <= AnimationCache.MAX_BYTES)
                assertTrue(staged(root).isEmpty())
            } finally {
                repository.close()
            }
        }
    }

    @Test fun failedPublicationKeepsOneDurableNewChoiceAndPublishedAssetsAcrossRestart() {
        fixture { context, root, vault, setup, portraits, original ->
            var cache = AnimationCache(File(root, "animations"))
            val records = seed(vault, portraits, cache, original)
            var fail = true
            var publications = 0
            fun repository() = CatalogRepository(
                context,
                vault,
                setup,
                portraits,
                animations = cache,
                publish = { _, _ ->
                    publications++
                    vault.animations().values.forEach { assertNotNull(cache.file(it)) }
                    if (fail) error("Synthetic publication failure")
                }
            )
            var repository = repository()
            try {
                assertThrows(Exception::class.java) {
                    repository.selectWatchPhoto("box:p0", changed(original, 100))
                }
                val pending = vault.animations().getValue("box:p0")
                assertEquals(1, publications)
                assertTrue(pending.sha256 != records.getValue("box:p0").sha256)
                records.values.forEach { assertNotNull("Published bytes retained", cache.file(it)) }
                assertEquals(32, settled(root).size)
                assertEquals(1, staged(root).size)
                assertTrue(staged(root).single().length() <= GifPortrait.MAX_BYTES)
                repository.close()
                cache = AnimationCache(File(root, "animations"))
                repository = repository()
                assertEquals(pending, vault.animations().getValue("box:p0"))
                assertEquals(
                    pending.fallbackSha256,
                    repository.savedPages().first {
                        it.key ==
                            "box:p0"
                    }.profile.portrait?.sha256
                )
                assertNotNull(cache.file(pending))
                assertThrows(Exception::class.java) {
                    repository.selectWatchPhoto("box:p1", changed(original, 101))
                }
                assertEquals(pending, vault.animations().getValue("box:p0"))
                assertEquals(records.getValue("box:p1"), vault.animations().getValue("box:p1"))
                assertEquals(1, staged(root).size)
                records.values.forEach { assertNotNull(cache.file(it)) }
                fail = false
                repository.refresh(true).get(4, TimeUnit.SECONDS)
                assertEquals("gif", cache.file(pending)?.extension)
                assertNull(cache.file(records.getValue("box:p0")))
                assertTrue(staged(root).isEmpty())
                assertEquals(32, settled(root).size)
                repository.selectWatchPhoto("box:p1", changed(original, 101))
                assertEquals(32, settled(root).size)
                assertTrue(staged(root).isEmpty())
            } finally {
                repository.close()
            }
        }
    }

    private fun settled(root: File) = File(root, "animations").listFiles().orEmpty().filter {
        it.extension ==
            "gif"
    }
    private fun staged(root: File) = File(root, "animations").listFiles().orEmpty().filter {
        it.extension ==
            "staged"
    }
    private fun changed(original: ByteArray, red: Int) = original.copyOf().also {
        it[13] =
            red.toByte()
    }

    private fun seed(
        vault: CatalogVault,
        portraits: PortraitStore,
        cache: AnimationCache,
        original: ByteArray
    ): Map<String, AnimationDescriptor> {
        val pages = mutableListOf<CatalogPage>()
        val records = linkedMapOf<String, AnimationDescriptor>()
        repeat(32) { index ->
            val gif = changed(original, index)
            val png = portraits.normalize(GifDecoder.firstFrame(gif))
            val descriptor = AnimationDescriptor(AnimationCache.sha256(gif), gif.size, png.sha256)
            cache.store(descriptor, gif)
            pages +=
                CatalogPage(
                    "box",
                    "Synthetic",
                    CatalogProfile("p$index", "Agent", null, png),
                    CatalogStatus.AVAILABLE,
                    0
                )
            records["box:p$index"] = descriptor
        }
        vault.savePages(pages)
        vault.saveAnimations(records)
        cache.protect(records.values.map { it.sha256 }.toSet())
        return records
    }

    private fun fixture(
        block: (
            android.content.Context,
            File,
            CatalogVault,
            ProfileVault,
            PortraitStore,
            ByteArray
        ) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "capacity-" + UUID.randomUUID())
        val alias = "test.animation.capacity." + UUID.randomUUID()
        val setupAlias = alias + ".setup"
        try {
            val original = GifPortrait.normalize(
                instrumentation.context.assets.open("original-two-frame.gif").use {
                    it.readBytes()
                }
            ).bytes
            block(
                context,
                root,
                CatalogVault(context, File(root, "vault"), alias),
                ProfileVault(context, File(root, "setup"), setupAlias),
                PortraitStore(File(root, "portraits")),
                original
            )
        } finally {
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
                deleteEntry(setupAlias)
            }
        }
    }
}
