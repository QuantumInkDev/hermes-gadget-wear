package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic emulator gate; never replaces an existing endpoint. */
@RunWith(AndroidJUnit4::class)
class WatchDraftTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test
    fun offlineDraftSurvivesReconnectAndRequiresFreshSend() {
        val endpoint = InstrumentationRegistry.getArguments()
            .getString("syntheticEndpoint").orEmpty()
        assumeTrue("Supply a synthetic localhost devserver", endpoint.startsWith("ws://localhost:"))
        assumeTrue(
            "Do not overwrite an existing saved setup",
            File(context.filesDir, "gadget-vault").listFiles().isNullOrEmpty()
        )
        automation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= 37) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val connected = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, GadgetService::class.java),
                binding,
                Context.BIND_AUTO_CREATE
            )
        )
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(bound.get())
            waitUntil { !service.state.value.loading }
            instrumentation.runOnMainSync {
                service.save(endpoint, "Synthetic draft test", "", true, true) { service.connect() }
            }
            waitUntil { service.state.value.connection.status == ConnectionStatus.PAIRED }
            scrollTo { text(it, context.getString(R.string.type_message)) }
            click { text(it, context.getString(R.string.type_message)) }
            val draft = "Keep this synthetic draft."
            val editor = waitFor { it.isEditable && it.isVisibleToUser }
                ?: error("Message editor missing")
            assertTrue(
                editor.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            draft
                        )
                    }
                )
            )
            instrumentation.waitForIdleSync()
            assertTrue(waitFor { it.isEditable && it.text?.toString() == draft } != null)
            instrumentation.runOnMainSync { service.disconnect() }
            waitUntil { service.state.value.connection.status == ConnectionStatus.OFFLINE }
            scrollTo { text(it, context.getString(R.string.message_reconnect)) }
            scrollTo { text(it, context.getString(R.string.send)) }
            assertTrue(
                waitFor {
                    text(it, context.getString(R.string.send)) && disabled(it)
                } != null
            )
            scrollTo(backward = true) {
                it.isEditable && it.isVisibleToUser && it.text?.toString() == draft
            }
            assertTrue(waitFor { it.isEditable && it.text?.toString() == draft } != null)
            scrollTo { text(it, context.getString(R.string.connect)) }
            click { text(it, context.getString(R.string.connect)) }
            waitUntil { service.state.value.connection.status == ConnectionStatus.PAIRED }
            assertEquals("", service.state.value.connection.conversation.reply)
            scrollTo(backward = true) {
                it.isEditable && it.isVisibleToUser && it.text?.toString() == draft
            }
            assertTrue(waitFor { it.isEditable && it.text?.toString() == draft } != null)
            scrollTo { text(it, context.getString(R.string.send)) }
            assertTrue(
                waitFor {
                    text(it, context.getString(R.string.send)) && !disabled(it)
                } != null
            )
            click { text(it, context.getString(R.string.send)) }
            waitUntil { service.state.value.connection.conversation.reply == "You said: $draft" }
            scrollTo(backward = true) { text(it, "You said: $draft") }
            assertTrue(waitFor { text(it, "You said: $draft") } != null)
        } finally {
            bound.get()?.let { service ->
                instrumentation.runOnMainSync { service.disconnect() }
            }
            context.unbindService(binding)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        error("Watch state did not reach the expected condition")
    }

    private fun text(node: AccessibilityNodeInfo, value: String): Boolean =
        node.text?.toString() == value && node.isVisibleToUser

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

    private fun disabled(node: AccessibilityNodeInfo): Boolean =
        !node.isEnabled || node.parent?.let(::disabled) == true

    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val node = waitFor(predicate) ?: error("Watch control missing")
        assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
