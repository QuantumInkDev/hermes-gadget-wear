package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppearanceTest {
    @Test fun productionActivityRetainsCaptureSecurityPolicyAcrossAppearanceChanges() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val secureFlag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val original = activity.window.attributes.flags and secureFlag
        // Preserve the watch activity's existing capture policy as well.
        try {
            Appearance.entries.forEach { mode ->
                val rendered = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    activity.setContent {
                        WatchAppearance(mode) { SideEffect { rendered.countDown() } }
                    }
                }
                assertTrue(rendered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    assertEquals(original, activity.window.attributes.flags and secureFlag)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun themeWrappersProvideReadableActualSemanticPairs() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            Appearance.entries.forEach { mode ->
                val rendered = CountDownLatch(1)
                var pairs = emptyList<Pair<Int, Int>>()
                instrumentation.runOnMainSync {
                    activity.setContent {
                        WatchAppearance(mode) {
                            val colors = MaterialTheme.colorScheme
                            SideEffect {
                                pairs = listOf(
                                    colors.onBackground to colors.background,
                                    colors.onSurface to colors.surfaceContainer,
                                    colors.onSurfaceVariant to colors.surfaceContainerHigh,
                                    colors.onPrimary to colors.primary,
                                    colors.onPrimaryContainer to colors.primaryContainer,
                                    colors.onSecondary to colors.secondary,
                                    colors.onSecondaryContainer to colors.secondaryContainer,
                                    colors.onTertiary to colors.tertiary,
                                    colors.onTertiaryContainer to colors.tertiaryContainer,
                                    colors.onError to colors.error,
                                    colors.onErrorContainer to colors.errorContainer
                                ).map { it.first.toArgb() to it.second.toArgb() }
                                rendered.countDown()
                            }
                        }
                    }
                }
                assertTrue(rendered.await(5, TimeUnit.SECONDS))
                pairs.forEach { (foreground, container) ->
                    val contrast = ColorUtils.calculateContrast(foreground, container)
                    assertTrue("$mode native contrast=$contrast", contrast >= 4.5)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun lightWatchTimeLabelContrastsWithItsRenderedCanvas() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            val rendered = CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.setContent {
                    WatchAppearance(Appearance.LIGHT) {
                        SideEffect { rendered.countDown() }
                        RoundPage { item { Heading("Appearance probe") } }
                    }
                }
            }
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val background = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                var maximumContrast = 1.0
                // Only the centered time glyphs and pale canvas are inside this round-safe region.
                for (x in screenshot.width * 44 / 100..screenshot.width * 56 / 100) {
                    for (y in screenshot.height * 3 / 100..screenshot.height * 9 / 100) {
                        maximumContrast = maxOf(
                            maximumContrast,
                            ColorUtils.calculateContrast(screenshot.getPixel(x, y), background)
                        )
                    }
                }
                assertTrue("Light time label contrast=$maximumContrast", maximumContrast >= 4.5)
            } finally {
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun nativeChooserChangesTheLiveThemeAndReturns() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = AppearancePreferences(context)
        val original = preferences.mode.value
        val returned = AtomicBoolean(false)
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val mode by preferences.mode.collectAsStateWithLifecycle()
                    WatchAppearance(mode) {
                        WatchAppearancePage(context) { returned.set(true) }
                    }
                }
            }
            for ((option, label) in listOf(
                Appearance.LIGHT to "Light",
                Appearance.DARK to "Dark",
                Appearance.MOONLIGHT to "Electronic Moonlight"
            )) {
                val node = chooserNode(label)
                var target = node
                while (!target.isClickable) target = requireNotNull(target.parent)
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(option, preferences.mode.value)
                assertEquals(option, AppearancePreferences(context).mode.value)
                assertEquals(
                    option.name,
                    context.getSharedPreferences("appearance-v1", Context.MODE_PRIVATE)
                        .getString("mode", null)
                )
                // Android exposes Wear radio semantics as a checkable virtual View.
                // Assert the app's actual announced state rather than a framework widget class.
                var radio: AccessibilityNodeInfo? = chooserNode(label)
                while (radio != null && !radio.isCheckable) radio = radio.parent
                assertTrue("Appearance option must expose native radio selection", radio != null)
                var selected = false
                repeat(20) {
                    var current: AccessibilityNodeInfo? = chooserNode(label)
                    while (current != null && !current.isCheckable) current = current.parent
                    @Suppress("DEPRECATION")
                    val checked = current?.isChecked == true
                    selected = checked || current?.isSelected == true
                    if (!selected) android.os.SystemClock.sleep(50)
                }
                assertTrue("Chosen appearance must be announced selected", selected)
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { screenshot ->
                    java.io.File(context.cacheDir, "appearance-wear-${option.name}.png")
                        .outputStream().use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    screenshot.recycle()
                }
            }
            var target = chooserNode("Back")
            while (!target.isClickable) target = requireNotNull(target.parent)
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertTrue(returned.get())
        } finally {
            preferences.set(original)
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    private fun chooserNode(label: String): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(root)
            for (index in 0 until root.childCount) {
                root.getChild(index)?.let { yieldAll(nodes(it)) }
            }
        }
        repeat(12) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val all = root?.let(::nodes)?.toList().orEmpty()
            all.firstOrNull {
                it.isVisibleToUser &&
                    it.text?.toString()?.removePrefix("✓ ")?.removePrefix("○ ") == label
            }?.let { return it }
            all.firstOrNull { it.isScrollable }?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            )
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(100)
        }
        error("Appearance control missing: $label")
    }

    @Test fun missingOrInvalidStoredModeUsesDarkWithoutChangingOtherKeys() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("appearance-v1", Context.MODE_PRIVATE)
        val original = store.getString("mode", null)
        try {
            assertTrue(store.edit().remove("mode").putString("unrelated", "retained").commit())
            assertEquals(Appearance.DARK, AppearancePreferences(context).mode.value)
            assertTrue(store.edit().putString("mode", "unknown-future-appearance").commit())
            assertEquals(Appearance.DARK, AppearancePreferences(context).mode.value)
            AppearancePreferences(context).set(Appearance.LIGHT)
            assertEquals("retained", store.getString("unrelated", null))
        } finally {
            val edit = store.edit().remove("unrelated")
            if (original == null) edit.remove("mode") else edit.putString("mode", original)
            assertTrue(edit.commit())
            AppearancePreferences(context)
        }
    }

    @Test fun chosenAppearanceSurvivesFreshInstanceWithoutChangingOtherPreferences() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unrelated = context.getSharedPreferences(
            "appearance-test-unrelated",
            Context.MODE_PRIVATE
        )
        assertTrue(unrelated.edit().putString("draft", "retained local draft").commit())
        val preferences = AppearancePreferences(context)
        val original = preferences.mode.value
        val subscriber = AppearancePreferences(context)
        try {
            Appearance.entries.forEach { selected ->
                preferences.set(selected)
                assertEquals(selected, preferences.mode.value)
                assertEquals(selected, subscriber.mode.value)
                assertEquals(selected, AppearancePreferences(context).mode.value)
                assertEquals("retained local draft", unrelated.getString("draft", null))
            }
        } finally {
            preferences.set(original)
            unrelated.edit().clear().commit()
        }
    }
}
