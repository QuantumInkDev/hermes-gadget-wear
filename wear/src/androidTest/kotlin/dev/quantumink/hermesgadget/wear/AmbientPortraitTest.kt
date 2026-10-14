package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AmbientPortraitTest {
    @Test fun ambientUsesStaticMonochromePortraitAndKeepsPetPriority() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val portrait = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val petFrame = Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        val lowBit = mutableStateOf(false)
        val pet = mutableStateOf<PetAtlas?>(null)
        fun capture() = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.runOnMainSync {
                activity.setContent { AmbientPet(pet.value, 0, lowBit.value, true, portrait) }
            }
            SystemClock.sleep(350)
            val image = capture()
            val center = image.getPixel(image.width / 2, (image.height * 0.42f).toInt())
            image.recycle()
            assertTrue("Ambient portrait must remain visible and dim", Color.red(center) in 1..90)
            assertEquals(Color.red(center), Color.green(center))
            assertEquals(Color.green(center), Color.blue(center))
            instrumentation.runOnMainSync { lowBit.value = true }
            SystemClock.sleep(250)
            val monochrome = capture()
            val values = mutableListOf<Int>()
            val centerX = monochrome.width / 2
            val centerY = (monochrome.height * 0.42f).toInt()
            for (y in centerY - 10..centerY + 10) {
                for (x in centerX - 10..centerX + 10) values.add(monochrome.getPixel(x, y))
            }
            monochrome.recycle()
            assertTrue(values.all { it == Color.BLACK || it == Color.WHITE })
            assertTrue(
                "Low-bit fallback must retain sparse portrait pixels",
                values.any {
                    it ==
                        Color.WHITE
                }
            )
            assertTrue(values.any { it == Color.BLACK })
            instrumentation.runOnMainSync {
                pet.value = PetAtlas("Ambient fixture", 500, mapOf("idle" to listOf(petFrame)))
            }
            SystemClock.sleep(250)
            val withPet = capture()
            assertEquals(
                Color.BLACK,
                withPet.getPixel(withPet.width / 2, (withPet.height * 0.42f).toInt())
            )
            withPet.recycle()
            assertEquals(
                "Source portrait must not be modified",
                Color.WHITE,
                portrait.getPixel(32, 32)
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            portrait.recycle()
            petFrame.recycle()
        }
    }
}
