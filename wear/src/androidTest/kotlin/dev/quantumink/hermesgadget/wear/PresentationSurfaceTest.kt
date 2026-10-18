@file:Suppress("DEPRECATION")

package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Production native controls, original solid-color fixtures; no endpoint or enrollment. */
class PresentationSurfaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test fun nativeModesMissingPetFallbackAndFinalJumpRemainReachableAtLargeFonts() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val portrait = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val file = File(context.cacheDir, "presentation-surface-${System.nanoTime()}")
        val store = PresentationStore(file)
        val page =
            ProfilePage(
                "synthetic:long",
                "A very long synthetic profile label for native watch layout",
                "Original synthetic fixture",
                null,
                null
            )
        val watch =
            mutableStateOf(
                WatchState(
                    artwork = mapOf(page.key to PageArtwork(portrait, null)),
                    artworkIdentities = mapOf(page.key to page.presentationKey())
                )
            )
        val theme = mutableStateOf(Appearance.LIGHT)
        val scale = mutableStateOf(1f)
        var jumped = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, scale.value)
                    ) {
                        WatchAppearance(theme.value) {
                            RoundPage {
                                item {
                                    ProfileIdentity(
                                        page,
                                        watch.value.artwork[page.key],
                                        watch.value,
                                        true
                                    )
                                }
                                presentationOptions(page, watch.value) { key, mode ->
                                    watch.value =
                                        watch.value.copy(presentation = store.set(key, mode))
                                }
                                item { Secondary("Jump to profile", { jumped = true }) }
                            }
                        }
                    }
                }
            }
            for (appearance in Appearance.entries) {
                instrumentation.runOnMainSync {
                    theme.value = appearance
                    scale.value = 1.3f
                }
                SystemClock.sleep(300)
                repeat(6) {
                    nodes().firstOrNull {
                        it.isScrollable
                    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    SystemClock.sleep(80)
                }
                await { nodes().any { it.text?.toString() == page.label } }
                capture("${appearance.name.lowercase()}-long-label-first-view")
                scrollTo("Portrait")
                await {
                    var radio = nodes().firstOrNull { it.text?.toString() == "Portrait" }
                    while (radio != null && !radio.isCheckable) radio = radio.parent
                    radio?.isSelected == true || radio?.isChecked == true
                }
                capture("${appearance.name.lowercase()}-portrait-footer")
                click("Pet")
                await { watch.value.presentation[page.presentationKey()] == PresentationMode.PET }
                scrollTo(
                    "Pet unavailable or unsupported. Showing retained portrait or placeholder."
                )
                capture("${appearance.name.lowercase()}-pet-fallback")
                scrollTo("Jump to profile")
                click("Jump to profile")
                assertTrue(jumped)
                jumped = false
                capture("${appearance.name.lowercase()}-final-jump")
                clickAfterScroll("Portrait")
                await {
                    watch.value.presentation[page.presentationKey()] ==
                        PresentationMode.PORTRAIT
                }
            }
            instrumentation.runOnMainSync { watch.value = watch.value.copy(artwork = emptyMap()) }
            scrollTo("No portrait yet. You can choose Pet below.")
            capture("missing-portrait-option")
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            portrait.recycle()
            file.delete()
        }
    }

    @Test fun roundPetSurfaceContainsNonSquareFramesWithClearMargins() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val frame = Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Color.Black
                        ),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        PetView(
                            WatchState(
                                pet = PetAtlas(
                                    "Contain fixture",
                                    1100,
                                    mapOf("idle" to listOf(frame))
                                )
                            ),
                            animated = false,
                            portraitSurface = true
                        )
                    }
                }
            }
            SystemClock.sleep(400)
            val screenshot = requireNotNull(automation.takeScreenshot())
            var left = screenshot.width
            var top = screenshot.height
            var right = 0
            var bottom = 0
            for (y in 0 until screenshot.height) {
                for (x in 0 until screenshot.width) {
                    if (screenshot.getPixel(x, y) == Color.RED) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                        top = minOf(top, y)
                        bottom = maxOf(bottom, y)
                    }
                }
            }
            val width = right - left + 1
            val height = bottom - top + 1
            assertTrue(
                "Frame aspect must remain 96:104, actual $width:$height",
                kotlin.math.abs(width.toFloat() / height - 96f / 104) < 0.015f
            )
            val node = nodes().first {
                it.contentDescription?.toString()?.startsWith("Contain fixture") ==
                    true
            }
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue(
                left > bounds.left && top > bounds.top && right < bounds.right - 1 &&
                    bottom < bounds.bottom - 1
            )
            screenshot.recycle()
            capture("pet-contain-clear-margins")
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            frame.recycle()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { walk(node.getChild(it)) }
        }
        walk(automation.rootInActiveWindow)
    }
    private fun scrollTo(text: String) {
        repeat(30) {
            if (nodes().any { it.text?.toString() == text }) return
            nodes().firstOrNull {
                it.isScrollable
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(80)
        }
        repeat(30) {
            if (nodes().any { it.text?.toString() == text }) return
            nodes().firstOrNull {
                it.isScrollable
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(80)
        }
        error("Missing native control: $text")
    }
    private fun clickAfterScroll(text: String) {
        scrollTo(text)
        click(text)
    }
    private fun click(text: String) {
        val node = nodes().first { it.text?.toString() == text }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }
    private fun await(condition: () -> Boolean) {
        repeat(60) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Native presentation did not settle")
    }
    private fun capture(name: String) {
        val directory = File(context.filesDir, "o1-captures").apply { mkdirs() }
        requireNotNull(automation.takeScreenshot()).let { image ->
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
