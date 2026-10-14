package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.GifPortrait
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PortraitInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test fun holdReleaseDragCancellationAndDisposalAreSingleOwnedActions() = fixture { f ->
        f.down()
        SystemClock.sleep(800)
        assertEquals(listOf("start"), f.events)
        assertTrue(
            nodes().any {
                it.contentDescription?.toString()?.contains("Finish recording") ==
                    true
            }
        )
        f.up()
        await { f.events == listOf("start", "finish") }
        f.events.clear()
        f.down()
        SystemClock.sleep(800)
        f.move(0f, 100f)
        f.move(0f, 110f)
        f.up()
        await { f.events == listOf("start", "cancel") }
        f.events.clear()
        f.down()
        SystemClock.sleep(800)
        assertEquals("Capture began before disposal", listOf("start"), f.events)
        instrumentation.runOnMainSync { f.shown.value = false }
        SystemClock.sleep(300)
        assertEquals("Disposal must cancel, never send", listOf("start", "cancel"), f.events)
        f.up()
        assertEquals(listOf("start", "cancel"), f.events)
    }

    @Test fun tapsPreholdHorizontalVerticalDragsAndDisabledPortraitNeverRecord() = fixture { f ->
        f.down()
        f.up()
        for (horizontal in listOf(true, false)) {
            f.down()
            repeat(8) {
                SystemClock.sleep(20)
                f.move(
                    if (horizontal) -15f * (it + 1) else 0f,
                    if (horizontal) {
                        0f
                    } else {
                        -15f *
                            (it + 1)
                    }
                )
            }
            f.up()
        }
        assertEquals(
            "No capture from taps, pre-hold drags or disabled portrait",
            emptyList<String>(),
            f.events
        )
        instrumentation.runOnMainSync { f.enabled.value = false }
        SystemClock.sleep(100)
        f.down()
        SystemClock.sleep(800)
        f.up()
        assertEquals(
            "No capture from taps, pre-hold drags or disabled portrait",
            emptyList<String>(),
            f.events
        )
    }

    @Test fun secondPointerCancelsOnlyOwnedHold() = fixture { f ->
        for (owned in listOf(false, true)) {
            f.events.clear()
            f.down()
            if (owned) {
                SystemClock.sleep(800)
                assertEquals(listOf("start"), f.events)
            }
            if (!owned) {
                SystemClock.sleep(100)
                assertTrue("Second pointer arrives before hold ownership", f.events.isEmpty())
            }
            f.secondPointer(down = true)
            SystemClock.sleep(800)
            f.secondPointer(down = false)
            f.up()
            assertEquals(
                "Second pointer must not send or duplicate cancellation; owned=$owned",
                if (owned) listOf("start", "cancel") else emptyList<String>(),
                f.events
            )
        }
    }

    @Test fun actionCancelCancelsOnlyOwnedHold() = fixture { f ->
        for (owned in listOf(false, true)) {
            f.events.clear()
            f.down()
            if (owned) {
                SystemClock.sleep(800)
                assertEquals(listOf("start"), f.events)
            }
            f.cancel()
            SystemClock.sleep(800)
            assertEquals(
                "ACTION_CANCEL must not send or duplicate cancellation; owned=$owned",
                if (owned) listOf("start", "cancel") else emptyList<String>(),
                f.events
            )
        }
        // A cancelled stream must release ownership for the next deliberate hold.
        f.events.clear()
        f.down()
        SystemClock.sleep(800)
        f.up()
        assertEquals(listOf("start", "finish"), f.events)
    }

    @Test fun ringsAcrossThemesArtworkAndReducedMotion() = fixture { f ->
        val scale = shell("settings get global animator_duration_scale")
        try {
            for (appearance in Appearance.entries) {
                for (kind in 0..2) {
                    instrumentation.runOnMainSync {
                        f.appearance.value = appearance
                        f.kind.value = kind
                        f.recording.value = false
                    }
                    await {
                        nodes().none {
                            it.stateDescription?.toString()?.startsWith("Recording,") ==
                                true
                        }
                    }
                    instrumentation.runOnMainSync { f.recording.value = true }
                    await {
                        nodes().any {
                            it.stateDescription?.toString() ==
                                "Recording, outward rings"
                        }
                    }
                    if (kind == 1) assertGifFrames()
                    capture("recording-${appearance.name.lowercase()}-$kind")
                    if (kind == 0) {
                        SystemClock.sleep(200)
                        capture("recording-${appearance.name.lowercase()}-$kind-phase2")
                    }
                }
                fun bounds(label: String): Rect? = nodes().firstOrNull {
                    it.text?.toString() == label
                }?.let { Rect().also(it::getBoundsInScreen) }
                val originalHeight = requireNotNull(bounds("Synthetic")).height()
                instrumentation.runOnMainSync { f.fontScale.value = 1.3f }
                await { (bounds("Synthetic")?.height() ?: 0) > originalHeight * 1.15f }
                assertTrue(
                    nodes().any {
                        it.isVisibleToUser &&
                            it.contentDescription?.toString()?.contains("Finish recording") == true
                    }
                )
                capture("recording-${appearance.name.lowercase()}-large-font")
                val list = nodes().first { it.isScrollable }
                assertTrue(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                await { (bounds("Local fixture")?.bottom ?: Int.MAX_VALUE) < 410 }
                capture("recording-${appearance.name.lowercase()}-large-font-scrolled")
                assertTrue(
                    nodes().first {
                        it.isScrollable
                    }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                )
                instrumentation.runOnMainSync { f.fontScale.value = 1f }
                await { bounds("Synthetic")?.height() == originalHeight }
            }
            shell("settings put global animator_duration_scale 0")
            await {
                nodes().any { it.stateDescription?.toString() == "Recording, static level" }
            }
            capture("recording-static-reduced-motion")
            instrumentation.runOnMainSync { f.selected.value = false }
            await {
                nodes().none {
                    it.stateDescription?.toString()?.startsWith("Recording,") == true
                }
            }
        } finally {
            if (scale ==
                "null"
            ) {
                shell("settings delete global animator_duration_scale")
            } else {
                shell("settings put global animator_duration_scale $scale")
            }
        }
    }

    @Test fun powerSaverUsesStaticRingsAndRestoresFlowAfterExit() = fixture { f ->
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val original = power.isPowerSaveMode
        assumeTrue(
            "Do not change an existing synthetic battery override",
            !shell("dumpsys battery").contains("UPDATES STOPPED")
        )
        try {
            instrumentation.runOnMainSync { f.recording.value = true }
            shell("cmd battery unplug")
            shell("cmd power set-mode 1")
            SystemClock.sleep(500)
            assumeTrue(
                "This AVD does not expose an enabled PowerManager battery saver",
                power.isPowerSaveMode
            )
            await { nodes().any { it.stateDescription?.toString() == "Recording, static level" } }
            capture("recording-static-power-saver")
            shell("cmd power set-mode 0")
            await { !power.isPowerSaveMode }
            await { nodes().any { it.stateDescription?.toString() == "Recording, outward rings" } }
        } finally {
            shell("cmd battery reset")
            shell("cmd power set-mode " + if (original) "1" else "0")
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(45, 100, 115))
        }
        val gif = File(context.cacheDir, "recording-fixture.gif").apply {
            writeBytes(
                GifPortrait.normalize(
                    instrumentation.context.assets.open("original-two-frame.gif").use {
                        it.readBytes()
                    }
                ).bytes
            )
        }
        val f = Fixture()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides
                            Density(LocalDensity.current.density, f.fontScale.value)
                    ) {
                        WatchAppearance(f.appearance.value) {
                            if (f.shown.value) {
                                RoundPage(topPadding = androidx.compose.ui.unit.Dp(28f)) {
                                    item {
                                        FixturePortrait(f, bitmap, gif)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await {
                nodes().any {
                    it.contentDescription?.toString()?.contains("Start recording") ==
                        true
                }
            }
            val node = nodes().first {
                it.contentDescription?.toString()?.contains("Start recording") ==
                    true
            }
            val rect = Rect().also(node::getBoundsInScreen)
            f.x = rect.exactCenterX()
            f.y = rect.exactCenterY()
            test(f)
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            bitmap.recycle()
            gif.delete()
        }
    }

    @Composable
    private fun FixturePortrait(f: Fixture, bitmap: Bitmap, gif: File) {
        val pet = if (f.kind.value == 2) {
            PetAtlas("Synthetic", 1000, mapOf("idle" to listOf(bitmap)))
        } else {
            null
        }
        val mode = if (f.recording.value) ConversationMode.LISTENING else ConversationMode.READY
        val watch = WatchState(
            captureActive = f.recording.value,
            pet = pet,
            connection = ConnectionState(conversation = ConversationState(mode = mode))
        )
        ProfileIdentity(
            ProfilePage("synthetic:p", "Synthetic", "Local fixture", null, null),
            PageArtwork(bitmap, pet, if (f.kind.value == 1) gif else null),
            watch,
            f.selected.value,
            Modifier.recordingPortrait(f.enabled.value, f.recording.value, {
                f.events.add("start")
                f.recording.value = true
            }, {
                f.events.add("finish")
                f.recording.value = false
            }, {
                f.events.add("cancel")
                f.recording.value = false
            })
        )
    }

    private inner class Fixture {
        val shown = mutableStateOf(true)
        val recording = mutableStateOf(false)
        val selected = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val appearance = mutableStateOf(Appearance.DARK)
        val kind = mutableStateOf(0)
        val fontScale = mutableStateOf(1f)
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var x = 0f
        var y = 0f
        var downTime = 0L
        private var dx = 0f
        private var dy = 0f
        fun down() {
            downTime = SystemClock.uptimeMillis()
            dx = 0f
            dy = 0f
            event(MotionEvent.ACTION_DOWN)
        }
        fun move(x: Float, y: Float) {
            dx = x
            dy = y
            event(MotionEvent.ACTION_MOVE)
        }
        fun up() {
            event(MotionEvent.ACTION_UP)
            SystemClock.sleep(100)
        }
        fun cancel() = event(MotionEvent.ACTION_CANCEL)
        fun secondPointer(down: Boolean) {
            val properties = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    x = this@Fixture.x + index * 20f
                    y = this@Fixture.y
                    pressure = 1f
                    size = 1f
                }
            }
            val action = if (down) {
                MotionEvent.ACTION_POINTER_DOWN
            } else {
                MotionEvent.ACTION_POINTER_UP
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            assertTrue("Native second pointer injection", automation.injectInputEvent(event, true))
            event.recycle()
        }
        private fun event(action: Int) {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = this@Fixture.x + dx
                y = this@Fixture.y + dy
                pressure = 1f
                size = 1f
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }
    private fun assertGifFrames() {
        val expected = setOf(0xff7dddd2.toInt(), 0xff9dabff.toInt())
        val observed = mutableSetOf<Int>()
        repeat(50) {
            // Known 160dp image at28dp top inset, plus4dp internal vertical centering.
            // Sample an8x8 source-pixel interior, away from the outer rings.
            automation.takeScreenshot()?.let { bitmap ->
                val pixel = bitmap.getPixel(bitmap.width * 3 / 8, bitmap.height * 41 / 120)
                if (pixel in expected) observed.add(pixel)
                bitmap.recycle()
            }
            if (observed == expected) return
            SystemClock.sleep(40)
        }
        assertEquals(
            "GIF must render both actual fixture frames before capture",
            expected,
            observed
        )
    }

    private fun capture(name: String) {
        val directory = File(context.filesDir, "carousel-captures").apply { mkdirs() }
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        return automation.rootInActiveWindow?.let(::walk).orEmpty()
    }
    private fun await(check: () -> Boolean) {
        repeat(50) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error("Portrait condition did not settle")
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
}
