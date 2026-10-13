package dev.quantumink.hermesgadget.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.Prompt
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** No enrollment, connection, microphone, or personal image is involved in these surfaces. */
class WatchPickerSurfaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val reply = "Your next step is ready. This public synthetic reply checks " +
        "comfortable reading on a round watch."

    @Test fun pickerBackRetainsMountedConversationAndLocksRejectSelection() = fixture {
            activity,
            service
        ->
        val endpoint = Endpoint.parse("wss://synthetic.example.invalid/gadget")
        val profile = EndpointProfile(endpoint, "Profile 1", "")
        val pages = (1..18).map {
            CatalogPage(
                if (it == 1) "manual" else "synthetic",
                "Public fixture",
                CatalogProfile(
                    if (it ==
                        1
                    ) {
                        ClientSpeech.endpointId(endpoint)
                    } else {
                        "id-$it"
                    },
                    "Profile $it",
                    null,
                    null
                ),
                CatalogStatus.AVAILABLE,
                1
            )
        }
        val navigation = WatchNavigation()
        val watch = mutableStateOf(
            WatchState(
                profile = profile,
                profiles = listOf(profile),
                catalog = pages,
                loading = false,
                selectedPage = pages.first().key,
                connection = ConnectionState(status = ConnectionStatus.PAIRED),
                artwork = mapOf(pages.first().key to PageArtwork(portrait(), null))
            )
        )
        val appearance = mutableStateOf(Appearance.DARK)
        val scale = mutableStateOf(1f)
        var mounts = 0
        var disposals = 0
        val footerOnly = InstrumentationRegistry.getArguments().getString("footerOnly") == "true"
        instrumentation.runOnMainSync {
            service.rememberPage(pages.first().key)
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale.value)
                ) {
                    WatchAppearance(appearance.value) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.semantics {
                                testTagsAsResourceId =
                                    true
                            }
                        ) {
                            ProfileCarousel(watch.value, navigation, service, connect = {
                                error("Must not connect")
                            }, review = {}) { page, scroll, jump ->
                                DisposableEffect(Unit) {
                                    mounts++
                                    onDispose { disposals++ }
                                }
                                ConversationPage(
                                    watch.value, service,
                                    connect = {
                                        error("Must not connect")
                                    }, startRecording = { error("Must not record") },
                                    settings = {}, type = {}, profiles = {}, appearance = {},
                                    page = page, pageScroll = scroll, jump = jump
                                )
                            }
                        }
                    }
                }
            }
        }
        assertClearPortrait()
        assertEquals(1, mounts)
        for (theme in Appearance.entries) {
            for (font in listOf(1f, 1.3f)) {
                instrumentation.runOnMainSync {
                    appearance.value = theme
                    scale.value = font
                }
                val scroll = navigation.scrollFor(pages.first().key)
                instrumentation.runOnMainSync { }
                // A font reflow preserves the old pixel offset. Clamp to the list start
                // before comparing first-view geometry; later Back/rotary checks retain it.
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                runBlocking {
                    withContext(Dispatchers.Main) { scroll.scrollToItem(0, -10000) }
                }
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                SystemClock.sleep(250)
                assertClearPortrait()
                assertTrue(
                    "Initial clock must be present",
                    nodes().any {
                        it.viewIdResourceName ==
                            "page-clock"
                    }
                )
                assertTrue(nodes().any { it.stateDescription?.toString() == "Profile 1 of 18" })
                assertTrue(
                    nodes().any {
                        it.actionList.any { action ->
                            action.label?.toString() == "Choose a profile"
                        }
                    }
                )
                assertNoIndicatorTextOverlap()
                capture("${theme.name}-$font-portrait")
                runBlocking {
                    withContext(Dispatchers.Main) {
                        scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
                    }
                }
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                assertRoundPickerBounds()
                if (footerOnly) {
                    capture("${theme.name}-$font-end-padding")
                    continue
                }
                click("Choose a profile")
                capture("${theme.name}-$font-picker")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                SystemClock.sleep(250)
                assertEquals(0, disposals)
                assertEquals(1, mounts)
                assertTrue(!activity.isFinishing)
                scroll()
                assertSettledClockHidden()
                instrumentation.runOnMainSync {
                    watch.value = watch.value.copy(
                        connection = watch.value.connection.copy(
                            conversation = ConversationState(
                                mode = ConversationMode.REPLY,
                                reply = reply
                            )
                        )
                    )
                }
                runBlocking { withContext(Dispatchers.Main) { scroll.scrollToItem(1) } }
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                node(reply, scroll = false)
                assertNoIndicatorTextOverlap()
                assertTrue(
                    "Scrolled content must not retain a floating picker over reply/actions",
                    nodes().none { it.contentDescription?.toString() == "Choose a profile" }
                )
                assertSettledClockHidden()
                capture("${theme.name}-$font-reply")
                SystemClock.sleep(400)
                val before = scroll.anchorItemIndex to scroll.anchorItemScrollOffset
                val accessiblePicker = nodes().first {
                    it.stateDescription?.toString() == "Profile 1 of 18"
                }
                val openPicker = accessiblePicker.actionList.first {
                    it.label?.toString() == "Choose a profile"
                }
                assertTrue(accessiblePicker.performAction(openPicker.id))
                node("Choose a profile", scroll = false, textOnly = true)
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                SystemClock.sleep(250)
                assertEquals(before, scroll.anchorItemIndex to scroll.anchorItemScrollOffset)
                repeat(3) {
                    rotaryPulse(-0.5f)
                    SystemClock.sleep(80)
                }
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                assertTrue(
                    "Rotary focus must return to the mounted conversation after picker dismissal",
                    before != (scroll.anchorItemIndex to scroll.anchorItemScrollOffset)
                )
                assertEquals(pages.first().key, service.state.value.selectedPage)
                assertEquals(0, disposals)
                assertSettledClockHidden()
                runBlocking {
                    withContext(Dispatchers.Main) {
                        scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
                    }
                }
                instrumentation.uiAutomation.waitForIdle(200, 3000)
                SystemClock.sleep(300)
                node("Profile 1 of 18", scroll = false)
                assertNoIndicatorTextOverlap()
                assertSettledClockHidden()
                capture("${theme.name}-$font-end-padding")
            }
        }
        val lockedStates = listOf(
            watch.value.copy(saving = true),
            watch.value.copy(
                connection = watch.value.connection.copy(
                    conversation = ConversationState(busy = true, mode = ConversationMode.THINKING)
                )
            ),
            watch.value.copy(
                connection = watch.value.connection.copy(
                    conversation = ConversationState(mode = ConversationMode.LISTENING)
                )
            ),
            watch.value.copy(
                connection = watch.value.connection.copy(
                    conversation = ConversationState(
                        prompt = Prompt("fixture", "Approval", "Synthetic approval", 0, null)
                    )
                )
            )
        )
        for (locked in lockedStates) {
            instrumentation.runOnMainSync {
                watch.value = locked
                scale.value = 1f
                navigation.pickerOpen.value = true
            }
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            repeat(6) { scroll(backward = true) }
            node("Finish the current turn to change profiles")
            var row = node("Profile 2")
            while (!row.isCheckable) row = requireNotNull(row.parent)
            assertTrue("Locked picker row must be disabled", !row.isEnabled)
            row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            assertEquals(pages.first().key, service.state.value.selectedPage)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            SystemClock.sleep(250)
        }
    }

    @Test fun catalogChangeDuringEveryTurnLockRetainsMountedSessionUntilIdle() = fixture {
            activity,
            service
        ->
        val profile =
            EndpointProfile(Endpoint.parse("wss://original.example.invalid/gadget"), "Original", "")
        fun catalog(url: String?) = listOf(
            CatalogPage(
                "synthetic",
                "Box",
                CatalogProfile("p", "Original", url, null),
                CatalogStatus.AVAILABLE,
                1
            )
        )
        val original = catalog(profile.endpoint.url)
        val navigation = WatchNavigation()
        val watch =
            mutableStateOf(
                WatchState(
                    profile = profile,
                    profiles = listOf(profile),
                    catalog = original,
                    selectedPage = "synthetic:p",
                    connection = ConnectionState(status = ConnectionStatus.PAIRED)
                )
            )
        var mounts = 0
        var disposals = 0
        val epoch = GadgetService::class.java.getDeclaredField("epoch").apply {
            isAccessible = true
        }
            .get(service) as java.util.concurrent.atomic.AtomicLong
        instrumentation.runOnMainSync {
            service.rememberPage("synthetic:p")
            activity.setContent {
                WatchAppearance(Appearance.DARK) {
                    val serviceState = service.state.collectAsState()
                    ProfileCarousel(
                        watch.value.copy(
                            selectedPage = serviceState.value.selectedPage
                        ),
                        navigation,
                        service,
                        {
                            error("Unexpected connection")
                        },
                        {}
                    ) { page, _, _ ->
                        DisposableEffect(page.key) {
                            mounts++
                            onDispose { disposals++ }
                        }
                        androidx.wear.compose.material3.Text("Mounted original session")
                    }
                }
            }
        }
        node("Mounted original session", scroll = false)
        val locks = listOf(
            ConversationState(mode = ConversationMode.LISTENING),
            ConversationState(
                mode = ConversationMode.REPLY,
                busy = true,
                reply = "Reply in progress"
            ),
            ConversationState(prompt = Prompt("pending", "Approval", "Keep approval", 0, null))
        )
        for (lock in locks) {
            for (changed in listOf(
                catalog("wss://replacement.example.invalid/gadget"),
                catalog(null),
                emptyList()
            )) {
                instrumentation.runOnMainSync {
                    watch.value =
                        watch.value.copy(
                            connection = ConnectionState(
                                status = ConnectionStatus.PAIRED,
                                conversation = lock
                            )
                        )
                }
                instrumentation.uiAutomation.waitForIdle(100, 2000)
                val before = epoch.get()
                val beforeDisposals = disposals
                instrumentation.runOnMainSync { watch.value = watch.value.copy(catalog = changed) }
                instrumentation.uiAutomation.waitForIdle(200, 2000)
                SystemClock.sleep(200)
                assertEquals("Catalog must not disconnect an active turn", before, epoch.get())
                assertEquals(
                    "Catalog must not dispose the active approval/recording surface",
                    beforeDisposals,
                    disposals
                )
                node("Mounted original session", scroll = false)
                assertEquals("synthetic:p", service.state.value.selectedPage)
                instrumentation.runOnMainSync {
                    watch.value =
                        watch.value.copy(
                            connection = ConnectionState(status = ConnectionStatus.PAIRED)
                        )
                }
                instrumentation.uiAutomation.waitForIdle(200, 2000)
                if (changed.isNotEmpty()) {
                    node("Review connection")
                    assertEquals(beforeDisposals + 1, disposals)
                } else {
                    repeat(40) {
                        if (!navigation.page.value.startsWith("watch:")) SystemClock.sleep(50)
                    }
                    assertTrue(
                        "Idle removal must reconcile to saved endpoint: ${navigation.page.value}",
                        navigation.page.value.startsWith("watch:")
                    )
                }
                instrumentation.runOnMainSync {
                    watch.value =
                        watch.value.copy(catalog = original, selectedPage = "synthetic:p")
                }
                node("Mounted original session", scroll = false)
                repeat(40) {
                    if (service.state.value.selectedPage != "synthetic:p") SystemClock.sleep(50)
                }
                assertEquals(
                    "Restored roster must settle before the next turn",
                    "synthetic:p",
                    service.state.value.selectedPage
                )
            }
        }
        assertTrue(mounts > 1)
    }

    @Test fun serviceCatalogProjectionSurvivesAbsentSurfaceUntilEveryTurnLockClears() = fixture {
            activity,
            service
        ->
        val original = service.state.value
        val flow = GadgetService::class.java.getDeclaredField("mutableState").apply {
            isAccessible =
                true
        }.get(service)
        val set = flow.javaClass.getMethod("setValue", Any::class.java).apply {
            isAccessible = true
        }
        val profile =
            EndpointProfile(
                Endpoint.parse("wss://absent.example.invalid/gadget"),
                "Absent fixture",
                ""
            )
        val page =
            CatalogPage(
                "synthetic",
                "Fixture",
                CatalogProfile("p", "Absent fixture", profile.endpoint.url, null),
                CatalogStatus.AVAILABLE,
                1
            )
        val changed =
            CatalogPage(
                "synthetic",
                "Fixture",
                CatalogProfile(
                    "p",
                    "Changed fixture",
                    "wss://changed.example.invalid/gadget",
                    null
                ),
                CatalogStatus.AVAILABLE,
                2
            )
        val locks = listOf(
            ConversationState(mode = ConversationMode.LISTENING),
            ConversationState(mode = ConversationMode.REPLY, busy = true),
            ConversationState(prompt = Prompt("pending", "Approval", "Keep approval", 0, null))
        )
        try {
            instrumentation.runOnMainSync { activity.setContent { } }
            for (lock in locks) {
                val active = original.copy(
                    profile = profile,
                    profiles = listOf(profile),
                    catalog = listOf(page),
                    selectedPage = page.key,
                    loading = false,
                    connection = ConnectionState(
                        status = ConnectionStatus.PAIRED,
                        conversation = lock
                    )
                )
                instrumentation.runOnMainSync {
                    set.invoke(flow, active)
                    service.applyCatalog(listOf(changed), changed.key)
                }
                assertEquals(
                    "Catalog must remain frozen even with no interactive composition",
                    profile.endpoint.url,
                    service.state.value.catalog.single().profile.gadgetUrl
                )
                assertEquals(profile.endpoint.url, service.state.value.profile?.endpoint?.url)
                instrumentation.runOnMainSync {
                    set.invoke(
                        flow,
                        service.state.value.copy(
                            connection = ConnectionState(status = ConnectionStatus.PAIRED)
                        )
                    )
                }
                repeat(40) {
                    if (service.state.value.catalog.single().profile.gadgetUrl !=
                        changed.profile.gadgetUrl
                    ) {
                        SystemClock.sleep(50)
                    }
                }
                assertEquals(
                    changed.profile.gadgetUrl,
                    service.state.value.catalog.single().profile.gadgetUrl
                )
                assertTrue(
                    ProfilePages.merge(
                        service.state.value.profiles,
                        service.state.value.catalog
                    ).first().approved ==
                        null
                )
            }
        } finally {
            instrumentation.runOnMainSync { set.invoke(flow, original) }
        }
    }

    private fun fixture(test: (AppearanceTestActivity, GadgetService) -> Unit) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val bound = AtomicReference<GadgetService>()
        val ready = CountDownLatch(1)
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
            repeat(50) { if (bound.get().state.value.loading) SystemClock.sleep(100) }
            original = bound.get().state.value.selectedPage
            test(activity, bound.get())
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                bound.get()?.rememberPage(original)
                activity.finish()
            }
            context.unbindService(binding)
        }
    }

    private fun portrait(): Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xffccacff.toInt()
        canvas.drawRoundRect(22f, 24f, 138f, 136f, 30f, 30f, paint)
        paint.color = 0xff17202c.toInt()
        canvas.drawCircle(58f, 70f, 10f, paint)
        canvas.drawCircle(102f, 70f, 10f, paint)
        canvas.drawRoundRect(57f, 103f, 103f, 111f, 4f, 4f, paint)
    }

    private fun rotaryPulse(delta: Float) {
        val source = InputDevice.SOURCE_ROTARY_ENCODER
        val device = InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull()
            .first { it.sources and source == source }
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = context.resources.displayMetrics.widthPixels / 2f
            y = context.resources.displayMetrics.heightPixels / 2f
            setAxisValue(MotionEvent.AXIS_SCROLL, delta)
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, device.id, 0, source, 0
        )
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(root)
            for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(walk(it)) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)?.toList().orEmpty()
    }

    private fun scroll(backward: Boolean = false) {
        if (!backward) {
            // Small native steps avoid an accessibility page jump skipping a round row.
            val display = context.resources.displayMetrics
            val start = SystemClock.uptimeMillis()
            for (step in 0..8) {
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    8 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val event = MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    action,
                    display.widthPixels * .5f,
                    display.heightPixels * (.7f - minOf(step, 7) * .02f),
                    0
                )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
                SystemClock.sleep(40)
            }
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            return
        }
        val target = nodes().firstOrNull { it.isScrollable && it.isFocused }
            ?: nodes().firstOrNull { it.isScrollable }
        target?.performAction(
            if (backward) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        )
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    private fun node(
        label: String,
        scroll: Boolean = true,
        textOnly: Boolean = false
    ): AccessibilityNodeInfo {
        repeat(35) {
            nodes().firstOrNull {
                it.isVisibleToUser &&
                    (
                        it.text?.toString() == label ||
                            !textOnly && it.contentDescription?.toString() == label
                        )
            }?.let { return it }
            if (scroll) scroll()
            SystemClock.sleep(100)
        }
        capture("failure")
        error("Synthetic surface missing: $label")
    }

    private fun click(label: String) {
        var target = node(label)
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    private fun assertSettledClockHidden() {
        // AndroidX restores its clock after the idle timeout; wait beyond that transition.
        SystemClock.sleep(2500)
        val clocks = nodes().filter { it.isVisibleToUser && it.viewIdResourceName == "page-clock" }
        assertTrue(
            "Settled scrolled content must not share the clock paint region: $clocks",
            clocks.isEmpty()
        )
    }

    private fun assertNoIndicatorTextOverlap() {
        val indicator = nodes().firstOrNull {
            it.contentDescription?.toString() == "Profile 1 of 18"
        } ?: return
        val band = android.graphics.Rect().also(indicator::getBoundsInScreen)
        for (text in nodes().filter { it.isVisibleToUser && !it.text.isNullOrEmpty() }) {
            val bounds = android.graphics.Rect().also(text::getBoundsInScreen)
            assertTrue(
                "Position dots must not paint across native text: ${text.text}; $band / $bounds",
                !android.graphics.Rect.intersects(band, bounds)
            )
        }
    }

    private fun assertRoundPickerBounds() {
        val target = node("Choose a profile", scroll = false)
        val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
        val display = context.resources.displayMetrics
        val minimum = 48 * display.density
        assertTrue(bounds.width() >= minimum - 1 && bounds.height() >= minimum - 1)
        val cx = display.widthPixels / 2f
        val cy = display.heightPixels / 2f
        val radius = minOf(cx, cy)
        for (x in listOf(bounds.left, bounds.right)) {
            for (y in listOf(bounds.top, bounds.bottom)) {
                assertTrue((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius)
            }
        }
    }

    private fun assertClearPortrait() {
        val portrait = node("Start recording", scroll = false)
        val bounds = android.graphics.Rect().also(portrait::getBoundsInScreen)
        nodes().filter {
            it.isVisibleToUser && it.contentDescription?.toString() == "Choose a profile"
        }.forEach { control ->
            val controlBounds = android.graphics.Rect().also(control::getBoundsInScreen)
            assertTrue(
                "The jump menu must never overlap the portrait: $bounds / $controlBounds",
                !android.graphics.Rect.intersects(bounds, controlBounds)
            )
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "picker-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
