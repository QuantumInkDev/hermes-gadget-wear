package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DirectoryPresentationTest {
    @Test fun topInstructionsHaveReadableContrastOutsideCards() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val rendered = CountDownLatch(1)
        val foreground = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        PhoneScaffold {
                            val color = LocalContentColor.current.toArgb()
                            SideEffect {
                                foreground.set(color)
                                rendered.countDown()
                            }
                            Text("Choose a profile to talk or chat.")
                        }
                    }
                }
            }
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            val contrast = ColorUtils.calculateContrast(foreground.get(), 0xff11170f.toInt())
            assertTrue("Top text contrast is only $contrast:1", contrast >= 4.5)
            instrumentation.sendStatus(
                0,
                android.os.Bundle().apply {
                    putFloat("top_text_contrast", contrast.toFloat())
                }
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun fullBoxHeadersExpandIndependentlyAndExposeTheirState() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.isVisibleToUser &&
                    node.text?.toString()?.contains(label) == true
                ) {
                    return node
                }
                for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                return null
            }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun waitUntil(check: () -> Boolean) {
            repeat(50) {
                if (check()) return
                SystemClock.sleep(40)
            }
            error("Box presentation did not settle")
        }
        fun header(label: String): AccessibilityNodeInfo {
            var node = requireNotNull(find(label))
            while (!node.isClickable) node = node.parent ?: error("Tappable box header missing")
            return node
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        PhoneScaffold { padding ->
                            Column(Modifier.padding(padding)) {
                                DirectoryBoxHeader(
                                    "Box One",
                                    "12 profiles",
                                    "Catalog available",
                                    first.value,
                                    first.value,
                                    true,
                                    { first.value = !first.value }
                                )
                                if (first.value) Text("First box profile")
                                DirectoryBoxHeader(
                                    "Box Two",
                                    "4 profiles",
                                    "Catalog offline",
                                    second.value,
                                    second.value,
                                    true,
                                    { second.value = !second.value }
                                )
                                if (second.value) Text("Second box profile")
                            }
                        }
                    }
                }
            }
            waitUntil { find("Box One") != null }
            assertEquals("Collapsed", header("Box One").stateDescription?.toString())
            assertTrue(
                header("Box One").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("First box profile") != null }
            assertEquals("Expanded", header("Box One").stateDescription?.toString())
            assertEquals("Collapsed", header("Box Two").stateDescription?.toString())
            assertNull(find("Second box profile"))
            assertTrue(
                header("Box Two").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("Second box profile") != null }
            assertTrue(
                header("Box One").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("First box profile") == null }
            assertNotNull(find("Second box profile"))
            assertEquals("Collapsed", header("Box One").stateDescription?.toString())
            assertEquals("Expanded", header("Box Two").stateDescription?.toString())
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }
}
