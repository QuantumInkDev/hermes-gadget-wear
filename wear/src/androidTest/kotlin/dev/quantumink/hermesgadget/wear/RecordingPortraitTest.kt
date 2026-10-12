package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecordingPortraitTest {
    @Test fun portraitOffersVisibleStartWithoutScrolling() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val profile =
            EndpointProfile(Endpoint.parse("ws://localhost:1/gadget", true, true), "Synthetic", "")
        val page = ProfilePages.merge(listOf(profile), emptyList()).single()
        val picture = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val watch =
            WatchState(
                loading = false,
                profile = profile,
                profiles = listOf(profile),
                connection = ConnectionState(status = ConnectionStatus.PAIRED),
                artwork = mapOf(page.key to PageArtwork(picture, null))
            )
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    WatchAppearance(Appearance.DARK) {
                        ConversationPage(watch, GadgetService(), {
                        }, {}, {}, {}, {}, {}, page, rememberTransformingLazyColumnState())
                    }
                }
            }
            SystemClock.sleep(700)
            fun visibleStart(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser &&
                    node.contentDescription?.toString()?.contains("Start recording") == true
                ) {
                    return true
                }
                return (0 until node.childCount).any { visibleStart(node.getChild(it)) }
            }
            assertTrue(
                "Portrait must offer Start recording before scrolling",
                visibleStart(instrumentation.uiAutomation.rootInActiveWindow)
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            picture.recycle()
        }
    }
}
