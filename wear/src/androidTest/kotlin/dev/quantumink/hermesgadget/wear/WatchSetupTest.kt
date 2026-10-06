package dev.quantumink.hermesgadget.wear

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in emulator UI gate; refuses to replace an already configured endpoint. */
@RunWith(AndroidJUnit4::class)
class WatchSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test
    fun draftsSurviveBackgroundAndCleartextRequiresEndpointWarning() {
        val endpoint = InstrumentationRegistry.getArguments().getString(
            "syntheticEndpoint"
        ).orEmpty()
        assumeTrue(
            "Supply a synthetic devserver URL for this emulator-only UI gate",
            endpoint.startsWith("ws://localhost:")
        )
        assumeTrue(
            "Do not overwrite any existing saved setup",
            File(context.filesDir, "gadget-vault").listFiles().isNullOrEmpty()
        )
        launch()
        val editor = scrollTo { editable(it, context.getString(R.string.endpoint_url)) }
        assertTrue(
            editor.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        endpoint
                    )
                }
            )
        )
        instrumentation.waitForIdleSync()
        // Wear's full-screen keyboard and a Home transition both stop the activity.
        automation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_HOME
        )
        SystemClock.sleep(150)
        launch()
        assertEquals(
            endpoint,
            waitFor {
                editable(
                    it,
                    context.getString(R.string.endpoint_url)
                )
            }?.text?.toString()
        )
        scrollTo { text(it, context.getString(R.string.save_connect)) }
        assertFalse(
            clickable(
                waitFor {
                    text(
                        it,
                        context.getString(R.string.save_connect)
                    )
                } ?: error("Save control missing")
            ).isEnabled
        )
        scrollTo(backward = true) { text(it, "○ " + context.getString(R.string.local_network)) }
        click { text(it, "○ " + context.getString(R.string.local_network)) }
        scrollTo { text(it, "○ " + context.getString(R.string.cleartext_accept)) }
        click { text(it, "○ " + context.getString(R.string.cleartext_accept)) }
        scrollTo { text(it, context.getString(R.string.save_connect)) }
        assertTrue(
            clickable(
                waitFor {
                    text(
                        it,
                        context.getString(R.string.save_connect)
                    )
                } ?: error("Save control missing")
            ).isEnabled
        )
        click { text(it, context.getString(R.string.save_connect)) }
    }

    private fun launch() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()
    }

    private fun text(node: AccessibilityNodeInfo, value: String): Boolean =
        node.text?.toString() == value && node.isVisibleToUser

    private fun editable(node: AccessibilityNodeInfo, label: String): Boolean =
        node.isEditable && nodes(node).any { it.contentDescription?.toString() == label }

    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (index in 0 until root.childCount) {
            root.getChild(index)?.let { yieldAll(nodes(it)) }
        }
    }

    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        repeat(30) {
            automation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull(predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        return null
    }

    private fun scrollTo(
        backward: Boolean = false,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        repeat(12) {
            waitFor(predicate)?.let { return it }
            val root = automation.rootInActiveWindow ?: error("Watch window missing")
            val list = nodes(root).firstOrNull { it.isScrollable } ?: error("Watch list missing")
            assertTrue(
                list.performAction(
                    if (backward) {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                )
            )
            instrumentation.waitForIdleSync()
        }
        error("Watch control missing")
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        if (node.isClickable) node else clickable(node.parent ?: error("Clickable control missing"))

    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val node = waitFor(predicate) ?: error("Watch control missing")
        assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
