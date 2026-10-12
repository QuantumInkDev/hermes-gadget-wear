package dev.quantumink.hermesgadget.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.Text
import dev.quantumink.hermesgadget.client.Appearance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WatchAppearanceNavigationTest {
    @Test fun setupOriginAndDraftSurviveAmbientUnmountForChooserAndSystemBack() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val ready = CountDownLatch(1)
        val service = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.set((binder as GadgetService.LocalBinder).service)
                ready.countDown()
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
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val gadget = requireNotNull(service.get())
            waitUntil { !gadget.state.value.loading }
            for (systemBack in listOf(false, true)) {
                val navigation = WatchNavigation()
                navigation.route.value = "setup"
                val draft = navigation.setupFor(gadget.state.value.profile, false)
                draft.label = "Retained synthetic review"
                val interactive = mutableStateOf(true)
                instrumentation.runOnMainSync {
                    activity.setContent {
                        WatchAppearance(Appearance.DARK) {
                            if (interactive.value) {
                                WatchScreen(
                                    gadget,
                                    connect = { error("Appearance must not connect") },
                                    startRecording = { error("Appearance must not record") },
                                    navigation = navigation
                                )
                            } else {
                                Text("Synthetic ambient replacement")
                            }
                        }
                    }
                }
                click("Appearance")
                waitUntil { navigation.route.value == "appearance" }
                instrumentation.runOnMainSync { interactive.value = false }
                node("Synthetic ambient replacement")
                instrumentation.runOnMainSync { interactive.value = true }
                node("Appearance")
                if (systemBack) {
                    instrumentation.sendKeyDownUpSync(
                        KeyEvent.KEYCODE_BACK
                    )
                } else {
                    click("Back")
                }
                waitUntil { navigation.route.value != "appearance" }
                assertEquals(
                    "Back after ambient must restore Setup",
                    "setup",
                    navigation.route.value
                )
                assertSame(draft, navigation.setupFor(gadget.state.value.profile, false))
                assertEquals("Retained synthetic review", draft.label)
                assertTrue(!activity.isFinishing)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            context.unbindService(binding)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Appearance navigation did not settle")
    }

    private fun node(label: String): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(root)
            for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(nodes(it)) }
        }
        repeat(15) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val all = root?.let(::nodes)?.toList().orEmpty()
            all.firstOrNull {
                it.isVisibleToUser && it.text?.toString() == label
            }?.let { return it }
            all.firstOrNull {
                it.isScrollable
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        error("Appearance navigation control missing: $label")
    }

    private fun click(label: String) {
        var target = node(label)
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
