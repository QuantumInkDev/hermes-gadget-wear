package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.PetRow
import dev.quantumink.hermesgadget.protocol.PetSheet
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetCacheTest {
    @Test fun validatesAlphaBoundsAndHashBeforeCroppingWithPixelScaling() {
        val bitmap = Bitmap.createBitmap(1152, 208, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.rgb(255, 186, 102) }
        repeat(4) { column ->
            canvas.drawRect(column * 192 + 60f, 70f, column * 192 + 132f, 180f, paint)
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val bytes = stream.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        val cache = PetCache(InstrumentationRegistry.getInstrumentation().targetContext)
        val sheet = PetSheet("base", hash, bytes.size, 1152, 208, listOf(PetRow("idle", 0, 6)))
        val frames = cache.decode(sheet, bytes).getValue("idle")
        assertEquals(4, frames.size)
        assertEquals(96, frames.first().width)
        assertEquals(Color.TRANSPARENT, frames.first().getPixel(0, 0))
        assertEquals(Color.rgb(255, 186, 102), frames.first().getPixel(30, 35))
        frames.forEach(Bitmap::recycle)
        assertThrows(IllegalArgumentException::class.java) {
            cache.decode(
                sheet,
                bytes.copyOf().apply {
                    this[lastIndex] =
                        0
                }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache.decode(PetSheet("base", hash, bytes.size, 192, 208, sheet.rows), bytes)
        }
    }
}
