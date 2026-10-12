package dev.quantumink.hermesgadget.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class WatchPickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun eighteenProfilesSelectExactStableKeyAtLargeType() = pickerScenario(shrink = false)

    @Test fun openPickerRetainsSurvivingKeyWhenCatalogShrinks() = pickerScenario(shrink = true)

    private fun pickerScenario(shrink: Boolean) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val pages = (1..18).map {
            CatalogPage(
                "synthetic",
                "Public fixture",
                CatalogProfile("id-$it", "Profile $it", null, null),
                CatalogStatus.OFFLINE,
                1
            )
        }
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val ready = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
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
        var original = ""
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val service = bound.get()
            repeat(50) { if (service.state.value.loading) SystemClock.sleep(100) }
            original = service.state.value.selectedPage
            val navigation = WatchNavigation()
            val catalog = mutableStateOf(pages)
            instrumentation.runOnMainSync {
                service.rememberPage(pages.first().key)
                activity.setContent {
                    val state by service.state.collectAsStateWithLifecycle()
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        WatchAppearance(Appearance.DARK) {
                            ProfileCarousel(
                                WatchState(
                                    loading = false,
                                    catalog = catalog.value,
                                    selectedPage = state.selectedPage
                                ),
                                navigation,
                                service,
                                connect = {
                                    error("Synthetic picker must not connect")
                                },
                                review = {}
                            ) { _, _, _ -> error("Synthetic pages need no enrollment") }
                        }
                    }
                }
            }
            tapRoundPicker()
            click("Profile 17")
            repeat(50) {
                if (service.state.value.selectedPage !=
                    pages[16].key
                ) {
                    SystemClock.sleep(100)
                }
            }
            assertEquals(pages[16].key, service.state.value.selectedPage)
            node("Profile 17 of 18", scroll = false)
            click("Choose a profile")
            assertTrue(nodes().none { it.text?.toString()?.startsWith("Pin ") == true })
            if (shrink) {
                instrumentation.runOnMainSync { catalog.value = listOf(pages.first(), pages[16]) }
                instrumentation.waitForIdleSync()
                var selected = node("Profile 17")
                while (!selected.isCheckable) selected = requireNotNull(selected.parent)
                @Suppress("DEPRECATION") // The Boolean accessor supports older Wear API levels.
                val checked = selected.isChecked
                assertTrue("The surviving stable key stays checked", checked)
                assertEquals(pages[16].key, service.state.value.selectedPage)
                click("Profile 1")
                node("Profile 1 of 2", scroll = false)
                assertEquals(pages.first().key, service.state.value.selectedPage)
                click("Choose a profile")
                click("Profile 17")
                node("Profile 2 of 2", scroll = false)
                assertEquals(pages[16].key, service.state.value.selectedPage)
                return
            }
            instrumentation.runOnMainSync { catalog.value = pages.reversed() }
            click("Profile 2")
            repeat(50) {
                if (service.state.value.selectedPage !=
                    pages[1].key
                ) {
                    SystemClock.sleep(100)
                }
            }
            assertEquals(
                "Refreshing picker order must never retarget a cached index",
                pages[1].key,
                service.state.value.selectedPage
            )
            node("Profile 17 of 18", scroll = false)
            assertTrue(
                nodes().none {
                    it.contentDescription?.toString() in
                        setOf("Previous profile", "Next profile") &&
                        it.isClickable
                }
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                bound.get()?.rememberPage(original)
                activity.finish()
            }
            context.unbindService(binding)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(root)
            for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(walk(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)?.toList().orEmpty()
    }

    private fun node(label: String, scroll: Boolean = true): AccessibilityNodeInfo {
        repeat(35) {
            val all = nodes()
            all.firstOrNull {
                it.isVisibleToUser &&
                    (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }?.let { return it }
            if (scroll) {
                all.firstOrNull {
                    it.isScrollable && it.isFocused
                }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        error("Watch picker control missing: $label")
    }

    private fun tapRoundPicker() {
        val target = node("Choose a profile", scroll = false)
        val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
        val display = context.resources.displayMetrics
        val minimum = 48 * display.density
        assertTrue("Full 48dp picker width", bounds.width() >= minimum - 1)
        assertTrue("Full 48dp picker height", bounds.height() >= minimum - 1)
        val cx = display.widthPixels / 2f
        val cy = display.heightPixels / 2f
        val radius = minOf(cx, cy)
        for (x in listOf(bounds.left, bounds.right)) {
            for (y in listOf(bounds.top, bounds.bottom)) {
                val distance = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                assertTrue(
                    "Picker corner must fit inside the round display",
                    distance <= radius * radius
                )
            }
        }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                time,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0
            )
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        instrumentation.uiAutomation.waitForIdle(200, 3000)
    }

    private fun click(label: String) {
        var target = node(label)
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }
}
