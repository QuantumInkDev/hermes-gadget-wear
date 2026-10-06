package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSurfaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ambientOutlineIsSparseMonochromeAndLeavesTheIdleFrameUnchanged() {
        val frame = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 4..11) for (x in 4..11) frame.setPixel(x, y, android.graphics.Color.MAGENTA)
        val outline = petOutline(frame)
        try {
            val pixels = IntArray(256)
            outline.getPixels(pixels, 0, 16, 0, 0, 16, 16)
            assertEquals(28, pixels.count { it != 0 })
            assertTrue(pixels.all { it == 0 || it == -1 })
            assertEquals(0, outline.getPixel(8, 8))
            assertEquals(android.graphics.Color.MAGENTA, frame.getPixel(8, 8))
            val large = Bitmap.createBitmap(193, 209, Bitmap.Config.ARGB_8888)
            try {
                assertThrows(IllegalArgumentException::class.java) { petOutline(large) }
            } finally {
                large.recycle()
            }
        } finally {
            frame.recycle()
            outline.recycle()
        }
    }

    @Test fun tileLaunchAndComplicationStateExcludePrivateSetupAndReplyFromTheWatchFace() {
        val tile = GadgetTileService()
        try {
            val result = tile.tile(
                SurfaceSnapshot("Sample profile", "Ready", "Original reply"),
                context
            )
            val timeline = requireNotNull(result.tileTimeline)
            val layout = requireNotNull(timeline.timelineEntries.single().layout)
            val root = layout.root as LayoutElementBuilders.Column
            val action = root.modifiers!!.clickable!!.onClick as ActionBuilders.LaunchAction
            assertEquals(context.packageName, action.androidActivity!!.packageName)
            assertEquals(MainActivity::class.java.name, action.androidActivity!!.className)
            val text = root.contents.filterIsInstance<LayoutElementBuilders.Text>().map {
                it.text!!.value
            }
            assertTrue(text.contains("Original reply"))
            assertTrue(text.contains("Then hold to ask"))
            val complication = GadgetComplication.data(context, "Ready")
            assertEquals(
                "Ready",
                complication.text.getTextAt(context.resources, Instant.now()).toString()
            )
            assertEquals(
                "Hermes",
                complication.title!!.getTextAt(context.resources, Instant.now()).toString()
            )
            assertNotNull(complication.tapAction)
        } finally {
            tile.onDestroy()
        }
    }
}
