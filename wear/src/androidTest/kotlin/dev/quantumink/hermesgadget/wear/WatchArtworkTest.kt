package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Original in-memory artwork only; no catalog credentials or pairing. */
@RunWith(AndroidJUnit4::class)
class WatchArtworkTest {
    @Test fun petArrivalKeepsIdentityInPlaceAndOnlySelectedForegroundFramesMove() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val portrait = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val atlas = PetAtlas(
            "Original fixture",
            300,
            listOf("idle", "sleeping").associateWith {
                listOf(Color.RED, Color.GREEN, Color.YELLOW).map { color ->
                    Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(color)
                    }
                }
            }
        )
        val artwork = mutableStateOf(PageArtwork(portrait, null))
        val selected = mutableStateOf(true)
        val page = ProfilePage("synthetic:p", "Fixture Agent", "Synthetic box", null, null)
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && predicate(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(automation.rootInActiveWindow)
        }
        fun label(): Rect {
            repeat(40) {
                find { it.text?.toString() == "Fixture Agent" }?.let { node ->
                    return Rect().also { node.getBoundsInScreen(it) }
                }
                SystemClock.sleep(50)
            }
            error("Profile identity missing")
        }
        fun centerPixel(): Int = requireNotNull(automation.takeScreenshot()).let {
            val color = it.getPixel(it.width / 2, 100)
            it.recycle()
            color
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        ProfileIdentity(
                            page,
                            artwork.value,
                            WatchState(
                                connection = ConnectionState(status = ConnectionStatus.PAIRED),
                                pet = artwork.value.pet
                            ),
                            selected.value
                        )
                    }
                }
            }
            val before = label()
            assertTrue(
                find {
                    it.contentDescription?.toString()?.endsWith("profile picture") == true
                } !=
                    null
            )
            // A rejected pet leaves the last good portrait in place.
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                PetCache(context).decode(
                    dev.quantumink.hermesgadget.protocol.PetSheet(
                        "base",
                        "0".repeat(64),
                        3,
                        192,
                        208,
                        listOf(dev.quantumink.hermesgadget.protocol.PetRow("idle", 0, 1))
                    ),
                    byteArrayOf(1, 2, 3)
                )
            }
            assertEquals(before, label())
            instrumentation.runOnMainSync { artwork.value = PageArtwork(portrait, atlas) }
            SystemClock.sleep(350)
            assertEquals(before, label())
            val first = centerPixel()
            SystemClock.sleep(130)
            assertNotEquals(first, centerPixel())
            instrumentation.runOnMainSync { selected.value = false }
            SystemClock.sleep(350)
            val frozen = centerPixel()
            SystemClock.sleep(140)
            assertEquals(frozen, centerPixel())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
