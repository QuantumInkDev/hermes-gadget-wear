package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TilePinEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun nativeEditorPersistsFocusOrderedSlotsAndDeduplicatesMoves() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val store = TilePinStore(context)
        val original = store.read()
        store.editLocal(null, emptyList())
        val page = ProfilePage("fixture:editor", "Editor profile", "Fixture", null, null)
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    WatchAppearance(dev.quantumink.hermesgadget.client.Appearance.DARK) {
                        ProfilePinEditor(listOf(page), page.key, false) { }
                    }
                }
            }
            click("Use for Focus")
            waitUntil { store.read().focus == page.key }
            click("Slot 1: Empty")
            waitUntil { store.read().grid[0] == page.key }
            click("Slot 4: Empty")
            waitUntil { store.read().grid[3] == page.key }
            assertEquals(listOf(null, null, null, page.key), TilePinStore(context).read().grid)
            click("Slot 4: Editor profile")
            waitUntil { store.read().grid.all { it == null } }
            assertEquals(page.key, TilePinStore(context).read().focus)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            store.editLocal(original.focus, original.grid)
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk).orEmpty()
    }
    private fun click(label: String) {
        repeat(30) {
            var target = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == label }
            if (target != null) {
                while (target?.isClickable == false) target = target.parent
                assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            nodes().firstOrNull {
                it.isScrollable
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        error("Missing native pin control: $label")
    }
    private fun waitUntil(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue(condition())
    }
}
