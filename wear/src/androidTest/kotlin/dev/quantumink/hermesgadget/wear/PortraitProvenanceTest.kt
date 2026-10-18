package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitProvenanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun png(color: Int): ByteArray =
        Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).let { image ->
            image.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                image.recycle()
                output.toByteArray()
            }
        }
    private fun descriptor(bytes: ByteArray): PortraitDescriptor {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        return PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
    }
    private fun page(id: String, endpoint: String, portrait: PortraitDescriptor?): ProfilePage {
        val profile = EndpointProfile(Endpoint.parse(endpoint), "Same name", "")
        val catalog =
            CatalogPage(
                "provenance",
                "Synthetic fixture",
                CatalogProfile(id, "Same name", endpoint, portrait),
                CatalogStatus.AVAILABLE,
                1
            )
        return ProfilePage(catalog.key, "Same name", "Synthetic fixture", profile, catalog)
    }

    @Test fun reusedPageWithAbsentOrUnfetchableReplacementShowsPlaceholderAndRetainsABytes() {
        val id = "case-${System.nanoTime()}"
        val bytes = png(Color.BLUE)
        val original = descriptor(bytes)
        val a = page(id, "wss://a.example.invalid/gadget", original)
        val directory = File(context.cacheDir, "provenance-$id")
        val cache = PortraitCache(directory)
        cache.store(a.key, original, bytes)
        val retained = File(directory, "${original.sha256}.png")
        val published = LinkedBlockingQueue<Map<String, PageArtwork>>()
        val controller =
            WatchCatalogController(context, PetCache(context), {
                true
            }, { _, _ -> }, { _, _ -> }, { published.offer(it) }, cache)
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            for (replacement in listOf(null, descriptor(png(Color.RED)))) {
                val b = page(id, "wss://b.example.invalid/gadget", replacement)
                // No reachable phone is configured; a replacement transfer cannot succeed.
                controller.prepare(listOf(b))
                val art = requireNotNull(published.poll(5, TimeUnit.SECONDS))[b.key]
                assertNotNull("The actual prepare must complete", art)
                assertNull("B must not inherit A's page-key-only portrait", art?.portrait)
                assertNull(art?.portraitIdentity)
                assertNull(art?.animation)
                assertNull(
                    ProfileTileSnapshot.build(
                        TilePins(1, b.key, listOf(null, null, null, null)),
                        listOf(b),
                        cache
                    ).focus.pixels
                )
                assertArrayEquals(bytes, retained.readBytes())
                instrumentation.runOnMainSync {
                    activity.setContent {
                        WatchAppearance(Appearance.DARK) {
                            ProfileIdentity(b, art, WatchState(), true)
                        }
                    }
                }
                fun placeholder(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.contentDescription?.toString() == "Same name: no portrait") return true
                    return (0 until node.childCount).any { placeholder(node.getChild(it)) }
                }
                repeat(30) {
                    if (!placeholder(
                            instrumentation.uiAutomation.rootInActiveWindow
                        )
                    ) {
                        SystemClock.sleep(50)
                    }
                }
                assertTrue(
                    "B must render its honest placeholder",
                    placeholder(instrumentation.uiAutomation.rootInActiveWindow)
                )
            }
            controller.prepare(listOf(a))
            val restoredA = requireNotNull(published.poll(5, TimeUnit.SECONDS))[a.key]
            assertEquals(Color.BLUE, restoredA?.portrait?.getPixel(0, 0))
            assertEquals(a.presentationKey(), restoredA?.portraitIdentity)
            val currentPublication = WatchState(
                profiles = listOf(requireNotNull(a.approved)),
                catalog = listOf(requireNotNull(a.discovered))
            ).withPublishedArtwork(mapOf(a.key to requireNotNull(restoredA)))
            assertEquals(
                Color.BLUE,
                a.presentationArtwork(currentPublication)?.portrait?.getPixel(0, 0)
            )
            assertEquals(a.presentationKey(), currentPublication.artworkIdentities[a.key])
            assertNull(
                currentPublication.withPublishedArtwork(
                    mapOf(a.key to PageArtwork(restoredA.portrait, null))
                ).artwork[a.key]
            )
            val b = page(id, "wss://b.example.invalid/gadget", null)
            val stalePublication = WatchState(
                profiles = listOf(requireNotNull(b.approved)),
                catalog = listOf(requireNotNull(b.discovered))
            )
                .withPublishedArtwork(mapOf(a.key to requireNotNull(restoredA)))
            assertNull(
                "An old correctly tagged callback cannot become B's raw artwork",
                stalePublication.artwork[b.key]
            )
            assertArrayEquals(bytes, retained.readBytes())
            val reopened = PortraitCache(directory)
            assertEquals(
                Color.BLUE,
                reopened.owned(a.key, a.presentationKey(), null)?.bitmap?.getPixel(0, 0)
            )
            assertNull(reopened.owned(b.key, b.presentationKey(), null))
        } finally {
            controller.close()
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun scopedReplacementRetainsBothOwnersAcrossRestartAndIgnoresLegacyIndexChanges() {
        val directory = File(context.cacheDir, "owned-replacement-${System.nanoTime()}")
        val cache = PortraitCache(directory)
        val blue = png(Color.BLUE)
        val red = png(Color.RED)
        val a = page("retained", "wss://a.example.invalid/gadget", descriptor(blue))
        val b = page("retained", "wss://b.example.invalid/gadget", descriptor(red))
        cache.store(a.key, requireNotNull(a.portrait), blue, a.presentationKey())
        cache.store(b.key, requireNotNull(b.portrait), red, b.presentationKey())
        val reopened = PortraitCache(directory)
        assertEquals(
            Color.BLUE,
            reopened.owned(a.key, a.presentationKey(), null)?.bitmap?.getPixel(0, 0)
        )
        assertEquals(
            Color.RED,
            reopened.owned(b.key, b.presentationKey(), null)?.bitmap?.getPixel(0, 0)
        )
        assertArrayEquals(blue, File(directory, "${a.portrait?.sha256}.png").readBytes())
        assertArrayEquals(red, File(directory, "${b.portrait?.sha256}.png").readBytes())
        cache.retain(emptySet())
        assertArrayEquals(blue, File(directory, "${a.portrait?.sha256}.png").readBytes())
        assertArrayEquals(red, File(directory, "${b.portrait?.sha256}.png").readBytes())
        cache.retain(setOf(a.key))
        assertEquals(
            Color.BLUE,
            cache.owned(a.key, a.presentationKey(), null)?.bitmap?.getPixel(0, 0)
        )
    }

    @Test fun corruptOwnerMetadataCannotBeReplacedByCurrentRequestOrLegacyAttribution() {
        val directory = File(context.cacheDir, "owned-corrupt-${System.nanoTime()}")
        val cache = PortraitCache(directory)
        val bytes = png(Color.BLUE)
        val a = page("corrupt", "wss://a.example.invalid/gadget", descriptor(bytes))
        cache.store(a.key, requireNotNull(a.portrait), bytes, a.presentationKey())
        val metadata = File(directory, "owners-v1")
        metadata.writeText("synthetic corrupt provenance")
        assertNull(cache.owned(a.key, a.presentationKey(), a.portrait))
        assertThrows(Exception::class.java) {
            cache.store(a.key, requireNotNull(a.portrait), bytes, a.presentationKey())
        }
        assertEquals("synthetic corrupt provenance", metadata.readText())
        assertArrayEquals(bytes, File(directory, "${a.portrait?.sha256}.png").readBytes())
    }

    @Test fun cacheCapacityRefusesNewOwnerWithoutDeletingRetainedArtwork() {
        val directory = File(context.cacheDir, "owned-capacity-${System.nanoTime()}")
        val cache = PortraitCache(directory)
        repeat(64) { index ->
            val bytes = png(Color.rgb(index, 30, 50))
            val a = page("capacity", "wss://owner-$index.example.invalid/gadget", descriptor(bytes))
            cache.store(a.key, requireNotNull(a.portrait), bytes, a.presentationKey())
        }
        val before = directory.listFiles().orEmpty().associate {
            it.name to it.readBytes().toList()
        }
        val next = page("capacity", "wss://next.example.invalid/gadget", null)
        val extra = png(Color.WHITE)
        assertThrows(Exception::class.java) {
            cache.store(next.key, descriptor(extra), extra, next.presentationKey())
        }
        assertEquals(
            before,
            directory.listFiles().orEmpty().associate {
                it.name to
                    it.readBytes().toList()
            }
        )
    }

    @Test fun staleAdmissionCannotPromoteBytesAfterSamePageRosterReplacement() {
        val directory = File(context.cacheDir, "owned-admission-${System.nanoTime()}")
        val cache = PortraitCache(directory)
        val bytes = png(Color.BLUE)
        val a = page("admission", "wss://a.example.invalid/gadget", descriptor(bytes))
        cache.retain(setOf(a.key))
        val admission = requireNotNull(cache.admission(a.key))
        cache.retain(setOf(a.key))
        var fetched = false
        assertFalse(
            cache.fetchAndStore(a.key, requireNotNull(a.portrait), admission, a.presentationKey()) {
                fetched =
                    true
                bytes
            }
        )
        assertFalse(fetched)
        assertNull(cache.owned(a.key, a.presentationKey(), null))
    }

    @Test fun concurrentCacheInstancesCannotLoseEachOthersDurableOwnerBindings() {
        val directory = File(context.cacheDir, "owned-concurrent-${System.nanoTime()}")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val pages = (0 until 16).map { index ->
                val bytes = png(Color.rgb(index, 45, 70))
                page(
                    "concurrent",
                    "wss://owner-$index.example.invalid/gadget",
                    descriptor(bytes)
                ) to
                    bytes
            }
            val jobs = pages.map { (page, bytes) ->
                pool.submit {
                    PortraitCache(
                        directory
                    ).store(page.key, requireNotNull(page.portrait), bytes, page.presentationKey())
                }
            }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            val reopened = PortraitCache(directory)
            pages.forEach { (page, _) ->
                assertNotNull(reopened.owned(page.key, page.presentationKey(), null))
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
