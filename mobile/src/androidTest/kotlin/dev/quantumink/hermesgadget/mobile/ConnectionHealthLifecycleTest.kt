@file:Suppress("DEPRECATION")

package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production ConversationContent remembered UI, deliberately no PhoneConversation network owner. */
class ConnectionHealthLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun activityRecreationClosesHealthAndDiscardsThePreview() {
        val activity = start()
        try {
            openPreview()
            instrumentation.runOnMainSync { activity.recreate() }
            await { node("Connection details", true) != null }
            assertFalse(nodes().any { it.text?.toString() == "Report preview (frozen)" })
            click("Connection details", true)
            click("Connection health")
            scrollTo("Preview safe report")
            assertNotNull(node("Preview safe report"))
            assertFalse(nodes().any { it.text?.toString() == "Copy report" })
        } finally {
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<DiagnosticsTestActivity>().forEach { it.finish() }
            }
        }
    }

    @Test fun processLaunchStartsWithoutRetainedPreview() {
        val arguments = InstrumentationRegistry.getArguments()
        val key = arguments.getString("n8_process_key")
        val seed = arguments.getString("n8_process_seed") == "true"
        val marker = key?.let {
            require(it.matches(Regex("n8-[a-zA-Z0-9-]+")))
            File(context.cacheDir, "$it.pid")
        }
        if (marker != null && !seed) {
            assertTrue(
                "A different real target process is required",
                marker.readText().toInt() != Process.myPid()
            )
        }
        val activity = start()
        try {
            assertFalse(nodes().any { it.text?.toString() == "Report preview (frozen)" })
            openPreview()
            if (seed) requireNotNull(marker).writeText(Process.myPid().toString())
        } finally {
            if (!seed) {
                instrumentation.runOnMainSync { activity.finish() }
                marker?.delete()
            }
        }
    }

    private fun start(): DiagnosticsTestActivity = (
        instrumentation.startActivitySync(
            Intent(
                context,
                DiagnosticsTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as DiagnosticsTestActivity
        ).also { await { node("Connection details", true) != null } }

    private fun openPreview() {
        click("Connection details", true)
        click("Connection health")
        scrollTo("Preview safe report")
        click("Preview safe report")
        scrollTo("Copy report")
        assertNotNull(node("Copy report"))
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        // Match the existing native fixture's API34-gated cache refresh after virtual-tree changes.
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return buildList {
            fun walk(node: AccessibilityNodeInfo?) {
                node ?: return
                if (node.isVisibleToUser) add(node)
                repeat(node.childCount) { walk(node.getChild(it)) }
            }
            walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
    }
    private fun node(text: String, description: Boolean = false): AccessibilityNodeInfo? =
        nodes().firstOrNull {
            (if (description) it.contentDescription else it.text)?.toString() ==
                text
        }
    private fun click(text: String, description: Boolean = false) {
        await { node(text, description) != null }
        var target = requireNotNull(node(text, description))
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
    }
    private fun scrollTo(text: String) {
        for (direction in listOf(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )) {
            repeat(40) {
                if (node(text) != null) return
                nodes().firstOrNull { it.isScrollable }?.performAction(direction)
                SystemClock.sleep(100)
            }
        }
        error("No $text")
    }
    private fun await(check: () -> Boolean) {
        repeat(80) {
            if (check()) return
            SystemClock.sleep(50)
        }
        error("Diagnostics lifecycle did not settle")
    }
}
