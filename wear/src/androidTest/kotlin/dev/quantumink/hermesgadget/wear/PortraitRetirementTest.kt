package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitRetirementTest {
    @Test fun delayedRetiredPortraitCannotTakeTheSlotNeededByItsReplacementAtCapacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "portrait-retirement-" + UUID.randomUUID())
        val cache = PortraitCache(directory)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val bytes = try {
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        val descriptor = PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
        val initial = (0 until 32).map { "source:p$it" }.toSet()
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            initial.forEach { cache.store(it, descriptor, bytes) }
            cache.retain(initial)
            val delayed = worker.submit<Boolean> {
                cache.fetchAndStore("source:p0", descriptor) {
                    fetching.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    bytes
                }
            }
            assertTrue(fetching.await(1, TimeUnit.SECONDS))
            cache.retain(initial - "source:p0" + "source:new")
            release.countDown()
            assertFalse(delayed.get(3, TimeUnit.SECONDS))
            cache.store("source:new", descriptor, bytes)
            assertNull(cache.currentHash("source:p0"))
            assertEquals(hash, cache.currentHash("source:new"))
            val reopened = PortraitCache(directory)
            assertNull(reopened.currentHash("source:p0"))
            assertEquals(hash, reopened.currentHash("source:new"))
        } finally {
            release.countDown()
            worker.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun delayedFetchCannotOverwriteTheSameKeyAfterRosterReplacementOrReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "portrait-epoch-" + UUID.randomUUID())
        val cache = PortraitCache(directory)
        val worker = Executors.newSingleThreadExecutor()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }
            return try {
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
        fun descriptor(bytes: ByteArray): PortraitDescriptor {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
            return PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
        }
        val blue = png(android.graphics.Color.BLUE)
        val green = png(android.graphics.Color.GREEN)
        val old = descriptor(blue)
        val newest = descriptor(green)
        try {
            repeat(2) { reset ->
                cache.retain(setOf("source:a"))
                cache.store("source:a", old, blue)
                val fetching = CountDownLatch(1)
                val release = CountDownLatch(1)
                val delayed = worker.submit<Boolean> {
                    cache.fetchAndStore("source:a", old) {
                        fetching.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        blue
                    }
                }
                try {
                    assertTrue(fetching.await(1, TimeUnit.SECONDS))
                    if (reset == 0) cache.retain(emptySet()) else cache.reset()
                    cache.retain(setOf("source:a"))
                    cache.store("source:a", newest, green)
                    release.countDown()
                    assertFalse(delayed.get(3, TimeUnit.SECONDS))
                    assertEquals(newest.sha256, cache.currentHash("source:a"))
                    assertEquals(
                        android.graphics.Color.GREEN,
                        cache.load("source:a")?.getPixel(0, 0)
                    )
                } finally {
                    release.countDown()
                }
            }
        } finally {
            worker.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
