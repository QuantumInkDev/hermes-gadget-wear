package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
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
        try {
            assertTrue(original != 0)
            Appearance.entries.forEach { mode ->
                val rendered = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    activity.setContent {
                        PhoneAppearance(mode) { SideEffect { rendered.countDown() } }
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
                        PhoneAppearance(mode) {
                            val colors = MaterialTheme.colorScheme
                            SideEffect {
                                pairs = listOf(
                                    colors.onBackground to colors.background,
                                    colors.onSurface to colors.surface,
                                    colors.onSurfaceVariant to colors.surface,
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

    @Test fun phoneAppearanceUsesDarkSystemBarIconsOnLightCanvas() {
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
                instrumentation.runOnMainSync {
                    activity.setContent {
                        PhoneAppearance(mode) { SideEffect { rendered.countDown() } }
                    }
                }
                assertTrue(rendered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    val bars = androidx.core.view.WindowCompat.getInsetsController(
                        activity.window,
                        activity.window.decorView
                    )
                    assertEquals(mode == Appearance.LIGHT, bars.isAppearanceLightStatusBars)
                    assertEquals(mode == Appearance.LIGHT, bars.isAppearanceLightNavigationBars)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    // Catches an inherited Light foreground rendered over the old fixed dark Scaffold canvas.
    @Test fun scaffoldInheritedTextContrastsWithItsActualRenderedCanvas() {
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
                var foreground = 0
                var expectedCanvas = 0
                instrumentation.runOnMainSync {
                    activity.setContent {
                        PhoneAppearance(mode) {
                            PhoneScaffold {
                                val color = LocalContentColor.current.toArgb()
                                val canvas = MaterialTheme.colorScheme.background.toArgb()
                                SideEffect {
                                    foreground = color
                                    expectedCanvas = canvas
                                    rendered.countDown()
                                }
                                Text("Choose an appearance")
                            }
                        }
                    }
                }
                assertTrue(rendered.await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                // A composition side effect can precede the emulator's displayed frame.
                var container = 0
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                do {
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    container = screenshot.getPixel(screenshot.width / 2, screenshot.height * 3 / 4)
                    screenshot.recycle()
                    if (container == expectedCanvas) break
                    android.os.SystemClock.sleep(50)
                } while (android.os.SystemClock.uptimeMillis() < deadline)
                val contrast = ColorUtils.calculateContrast(foreground, container)
                assertTrue("$mode actual scaffold text contrast=$contrast", contrast >= 4.5)
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
                    PhoneAppearance(mode) {
                        PhoneScaffold { padding ->
                            androidx.compose.foundation.layout.Column(
                                androidx.compose.ui.Modifier.padding(padding).padding(20.dp)
                            ) {
                                AppearancePage(context) { returned.set(true) }
                            }
                        }
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
                var isSelected = false
                repeat(20) {
                    var selected: AccessibilityNodeInfo? = chooserNode(label)
                    while (selected != null) {
                        // Compatible with the app's Android 10 minimum API.
                        @Suppress("DEPRECATION")
                        val checked = selected.isChecked
                        isSelected = isSelected || selected.isSelected || checked
                        selected = selected.parent
                    }
                    if (!isSelected) android.os.SystemClock.sleep(50)
                }
                assertTrue("Selected radio option missing", isSelected)
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { screenshot ->
                    java.io.File(context.cacheDir, "appearance-mobile-${option.name}.png")
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
                it.isVisibleToUser && it.text?.toString() == label
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
