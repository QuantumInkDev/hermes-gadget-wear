package dev.quantumink.hermesgadget.mobile.widgets

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetStoreTest {
    private fun directory() = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "widget-test-${UUID.randomUUID()}"
    ).also {
        it.mkdirs()
    }

    @Test fun independentInstancesPersistAndRejectStaleEditors() {
        val root = directory()
        try {
            val store = HomeWidgetStore(root)
            val first = store.save(101, WidgetConfig(HomeWidgetPreset.FOCUS, listOf("a:one")), 0)
            store.save(102, WidgetConfig(HomeWidgetPreset.FOCUS, listOf("b:one")), 0)
            assertEquals(1L, first.revision)
            assertEquals("a:one", HomeWidgetStore(root).config(101)?.slots?.single())
            assertThrows(IllegalArgumentException::class.java) {
                store.save(101, first.copy(appearance = WidgetAppearance.DARK), 0)
            }
            assertEquals("b:one", store.config(102)?.slots?.single())
            store.delete(101)
            assertNull(store.config(101))
            assertNotNull(store.config(102))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun restoredIdsNeverOverwriteAnotherWidgetOrChangeProvider() {
        val root = directory()
        try {
            val store = HomeWidgetStore(root)
            store.save(1, WidgetConfig(HomeWidgetPreset.FOCUS, listOf("a:one")), 0)
            store.save(2, WidgetConfig(HomeWidgetPreset.FOCUS, listOf("b:one")), 0)
            store.restore(intArrayOf(1), intArrayOf(2), HomeWidgetPreset.FOCUS)
            assertEquals("b:one", store.config(2)?.slots?.single())
            store.restore(intArrayOf(1), intArrayOf(3), HomeWidgetPreset.FOUR)
            assertNull(store.config(3))
            store.restore(intArrayOf(1), intArrayOf(3), HomeWidgetPreset.FOCUS)
            assertEquals("a:one", HomeWidgetStore(root).config(3)?.slots?.single())
            assertNull(store.config(1))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun corruptOrOversizedStateCannotProduceProfilesOrTargets() {
        val root = directory()
        try {
            val store = HomeWidgetStore(root)
            val profile = WidgetProfile("a:one", "Orbit", "Studio", CatalogStatus.OFFLINE, null)
            store.saveProfiles(listOf(profile))
            assertEquals(listOf(profile), HomeWidgetStore(root).profiles())
            File(root, "profiles.json").writeText("x".repeat(70000))
            assertTrue(store.profiles().isEmpty())
            File(root, "config-1.json").writeText("{bad}")
            assertNull(store.config(1))
            assertThrows(IllegalArgumentException::class.java) { store.config(-1) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun thumbnailsValidateSourceAndRemainCircularBoundedAndRetirable() {
        val root = directory()
        try {
            val source = File(root, "source.png")
            val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            original.eraseColor(Color.RED)
            source.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            original.recycle()
            val hash = MessageDigest.getInstance(
                "SHA-256"
            ).digest(source.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            val cache = HomeWidgetPictures(File(root, "thumbs"))
            assertFalse(cache.prepare("0".repeat(64), source))
            assertTrue(cache.prepare(hash, source))
            val picture = requireNotNull(cache.bitmap(hash, 80))
            assertEquals(80, picture.width)
            assertEquals(0, Color.alpha(picture.getPixel(0, 0)))
            assertEquals(Color.RED, picture.getPixel(40, 40))
            picture.recycle()
            assertThrows(IllegalArgumentException::class.java) { cache.bitmap(hash, 4096) }
            cache.retain(emptySet())
            assertNull(cache.bitmap(hash, 80))
        } finally {
            root.deleteRecursively()
        }
    }
}
