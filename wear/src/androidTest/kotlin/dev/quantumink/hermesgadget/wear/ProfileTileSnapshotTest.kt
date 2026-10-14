package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTileSnapshotTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localStaticPixelsAreBoundedVersionedAndRemovedKeyDoesNotRedirect() {
        val directory = File(context.cacheDir, "tile-art-${System.nanoTime()}")
        try {
            val cache = PortraitCache(directory)
            val page = ProfilePage("fixture:a", "Alpha", "Fixture", null, null)
            val pins = TilePins(1, page.key, listOf(page.key, "fixture:removed", null, null))
            val missing = ProfileTileSnapshot.build(pins, listOf(page), cache)
            assertEquals("Alpha", missing.focus.label)
            assertNull(missing.focus.pixels)
            assertEquals("fixture:removed", missing.grid[1].key)
            assertEquals("Unavailable", missing.grid[1].status)
            assertFalse(missing.grid[1].available)
            assertEquals("Set up", missing.grid[2].label)
            fun art(color: Int) {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                val bytes = ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
                bitmap.recycle()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
                cache.store(
                    page.key,
                    PortraitDescriptor(sha, bytes.size, "/assets/$sha.png"),
                    bytes
                )
            }
            art(android.graphics.Color.RED)
            val first = ProfileTileSnapshot.build(pins, listOf(page), cache)
            assertEquals(96 * 96 * 4, first.focus.pixels!!.size)
            assertTrue(first.focus.pixels.take(4).all { it == 0.toByte() })
            assertTrue(
                first.focus.pixels.slice(4 * (48 * 96 + 48)..4 * (48 * 96 + 48) + 3).any {
                    it !=
                        0.toByte()
                }
            )
            assertNotEquals(missing.version, first.version)
            assertEquals(
                first.version,
                ProfileTileSnapshot.build(pins, listOf(page), cache).version
            )
            val offline =
                CatalogPage(
                    "fixture",
                    "Fixture",
                    CatalogProfile("a", "Alpha", null, null),
                    CatalogStatus.OFFLINE,
                    1
                )
            val offlineSnapshot = ProfileTileSnapshot.build(
                pins,
                ProfilePages.merge(emptyList(), listOf(offline)),
                cache
            )
            assertEquals("Offline", offlineSnapshot.focus.status)
            assertTrue(offlineSnapshot.focus.available)
            assertNotEquals(first.version, offlineSnapshot.version)
            assertTrue(offlineSnapshot.focus.pixels!!.contentEquals(first.focus.pixels))
            art(android.graphics.Color.BLUE)
            assertNotEquals(
                first.version,
                ProfileTileSnapshot.build(pins, listOf(page), cache).version
            )
            assertNotEquals(
                first.version,
                ProfileTileSnapshot.build(pins.copy(revision = 2), listOf(page), cache).version
            )
            val removed = ProfileTileSnapshot.build(pins, emptyList(), cache)
            assertFalse(removed.focus.available)
            assertEquals(page.key, removed.focus.key)
        } finally {
            directory.deleteRecursively()
        }
    }
}
