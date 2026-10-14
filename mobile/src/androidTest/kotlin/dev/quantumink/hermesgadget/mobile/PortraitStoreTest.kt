package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortraitStoreTest {
    @Test fun repeatedUncommittedPortraitsRemainBoundedAndKeepProtectedFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "portrait-quota-${UUID.randomUUID()}")
        try {
            val store = PortraitStore(directory)
            val random = java.util.Random(7)
            var protected: String? = null
            repeat(48) {
                val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(IntArray(250 * 250) { random.nextInt() }, 0, 250, 0, 0, 250, 250)
                val raw = ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                bitmap.recycle()
                val descriptor = store.normalize(raw)
                if (protected ==
                    null
                ) {
                    protected = descriptor.sha256
                    store.protect(setOf(descriptor.sha256))
                }
                assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 8 * 1024 * 1024)
                assertTrue(store.file(requireNotNull(protected)) != null)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun photosNormalizeAndCorruptInputNeverReplacesVerifiedArtwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "portrait-test-${UUID.randomUUID()}")
        try {
            val store = PortraitStore(directory)
            val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val raw = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            val descriptor = store.normalize(raw)
            val file = requireNotNull(store.file(descriptor.sha256))
            val decoded = BitmapFactory.decodeFile(file.path)
            assertEquals(256, decoded.width)
            assertEquals(171, decoded.height)
            decoded.recycle()
            assertTrue(file.length() <= 262144)
            assertThrows(Exception::class.java) { store.normalize("broken PNG".toByteArray()) }
            assertEquals(file.length().toInt(), descriptor.bytes)
            assertTrue(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
