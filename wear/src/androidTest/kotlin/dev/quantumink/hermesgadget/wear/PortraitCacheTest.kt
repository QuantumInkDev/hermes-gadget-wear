package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortraitCacheTest {
    @Test fun verifiedPortraitsRemainIsolatedAndBadTransfersPreserveThePreviousImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "portraits-test-${UUID.randomUUID()}")
        val cache = PortraitCache(directory)
        fun png(color: Int, width: Int = 64): ByteArray {
            val bitmap = Bitmap.createBitmap(width, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                bitmap.recycle()
            }.toByteArray()
        }
        fun descriptor(bytes: ByteArray): PortraitDescriptor {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
            return PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
        }
        try {
            val blue = png(android.graphics.Color.BLUE)
            val green = png(android.graphics.Color.GREEN)
            val first = descriptor(blue)
            cache.store("source-a:p", first, blue)
            cache.store("source-b:p", descriptor(green), green)
            assertEquals(android.graphics.Color.BLUE, cache.load("source-a:p")?.getPixel(0, 0))
            assertEquals(android.graphics.Color.GREEN, cache.load("source-b:p")?.getPixel(0, 0))
            assertThrows(Exception::class.java) { cache.store("source-a:p", first, green) }
            val oversized = png(android.graphics.Color.RED, 257)
            assertThrows(Exception::class.java) {
                cache.store("source-a:p", descriptor(oversized), oversized)
            }
            assertEquals(android.graphics.Color.BLUE, cache.load("source-a:p")?.getPixel(0, 0))
            assertEquals(
                android.graphics.Color.BLUE,
                PortraitCache(directory).load("source-a:p")?.getPixel(0, 0)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
