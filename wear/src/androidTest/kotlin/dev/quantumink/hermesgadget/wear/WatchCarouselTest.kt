package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchCarouselTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun startupRestoresUnapprovedSelectionBeforeConnecting() = runScenario(true)

    @Test fun threePagesSwipeWithSeparateDraftsPortraitsAndRecordingProtection() = runScenario(
        false
    )

    @Test fun stationaryPagePromotesRefreshedPortrait() = runScenario(false, refreshPortrait = true)

    @Test fun verticalTouchScrollKeepsControlsReachable() = runScenario(
        false,
        scrollRegression = true
    )

    @Test fun changedManualEndpointRequiresReviewWithoutOldConnection() =
        runScenario(false, changeEndpoint = true)

    @Test fun oversizedRestoredCatalogLeavesSavedSetupUsable() =
        runScenario(false, oversizedRestore = true)

    @Test fun manualSetupCannotExceedCombinedPageCapacity() = runScenario(false, capacity = true)

    private fun runScenario(
        unapprovedStart: Boolean,
        refreshPortrait: Boolean = false,
        changeEndpoint: Boolean = false,
        oversizedRestore: Boolean = false,
        capacity: Boolean = false,
        scrollRegression: Boolean = false
    ) {
        val urls = listOf(
            "syntheticEndpoint",
            "syntheticSecondEndpoint",
            "syntheticThirdEndpoint"
        ).map {
            InstrumentationRegistry.getArguments().getString(it).orEmpty()
        }
        assumeTrue(
            "Three independent synthetic stock servers required",
            urls.all { it.startsWith("ws://localhost:") } && urls.distinct().size == 3
        )
        assumeTrue(
            "Never overwrite an existing enrollment",
            File(context.filesDir, "gadget-vault").listFiles().isNullOrEmpty()
        )
        val pets = InstrumentationRegistry.getArguments().getString("syntheticCompanion") == "true"
        val vault = IdentityVault(context)
        val portraitCache = PortraitCache(File(context.filesDir, "portrait-cache"))
        val pages = urls.mapIndexed { index, url ->
            val endpoint = Endpoint.parse(url, true, true)
            vault.save(EndpointProfile(endpoint, "Agent", ""))
            val bitmap = android.graphics.BitmapFactory.decodeFile(
                File(context.filesDir, "synthetic-portrait-$index.png").path
            ) ?: Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                eraseColor(
                    listOf(
                        android.graphics.Color.BLUE,
                        android.graphics.Color.GREEN,
                        android.graphics.Color.MAGENTA
                    )[index]
                )
            }
            val bytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                bitmap.recycle()
            }.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
            val portrait = PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
            val page =
                CatalogPage(
                    "manual",
                    "Synthetic box ${index + 1}",
                    CatalogProfile(ClientSpeech.endpointId(endpoint), "Agent", null, portrait),
                    CatalogStatus.AVAILABLE,
                    1
                )
            portraitCache.store(page.key, portrait, bytes)
            page
        }
        val pending = CatalogPage(
            "box",
            "Synthetic pending box",
            CatalogProfile("pending", "Pending agent", null, null),
            CatalogStatus.OFFLINE,
            1
        )
        vault.select(urls.first())
        WatchCatalogVault(context).apply {
            val extra = (0 until if (oversizedRestore) 32 else 29).map { index ->
                CatalogPage(
                    "box",
                    "Pending box",
                    CatalogProfile("pending$index", "Pending agent", null, null),
                    CatalogStatus.OFFLINE,
                    1
                )
            }
            savePages(
                when {
                    oversizedRestore -> extra
                    capacity -> pages + extra
                    unapprovedStart -> pages + pending
                    else -> pages
                }
            )
            select(if (unapprovedStart) pending.key else pages.first().key)
        }
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO).forEach {
            automation.grantRuntimePermission(context.packageName, it)
        }
        if (Build.VERSION.SDK_INT >=
            37
        ) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val latch = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
                latch.countDown()
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
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(bound.get())
            waitUntil { !service.state.value.loading }
            if (oversizedRestore || capacity) {
                assertEquals(3, service.state.value.profiles.size)
                val before = File(context.filesDir, "gadget-vault").listFiles().orEmpty()
                    .filter { it.name.startsWith("identity-") }.map { it.name }.toSet()
                if (oversizedRestore) {
                    assertTrue(service.state.value.catalog.isEmpty())
                    assertTrue(service.state.value.catalogNotice.contains("32"))
                } else {
                    assertEquals(
                        32,
                        ProfilePages.merge(
                            service.state.value.profiles,
                            service.state.value.catalog
                        ).size
                    )
                    instrumentation.runOnMainSync {
                        service.save(
                            "wss://new.example.invalid/gadget",
                            "New agent",
                            "",
                            false,
                            false
                        ) {
                        }
                    }
                    waitUntil {
                        !service.state.value.saving &&
                            service.state.value.setupError.isNotEmpty()
                    }
                    assertEquals(3, vault.profiles().size)
                }
                val edited = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    service.save(urls.first(), "Renamed", "", true, true) { edited.countDown() }
                }
                assertTrue("Existing setup remains editable", edited.await(5, TimeUnit.SECONDS))
                assertEquals(
                    before,
                    File(context.filesDir, "gadget-vault").listFiles().orEmpty()
                        .filter { it.name.startsWith("identity-") }.map { it.name }.toSet()
                )
                return
            }
            if (unapprovedStart) {
                waitUntil { service.state.value.catalog.any { it.key == pending.key } }
                SystemClock.sleep(1200)
                assertEquals(pending.key, service.state.value.selectedPage)
                assertEquals(ConnectionStatus.OFFLINE, service.state.value.connection.status)
                waitUntil { find { it.text?.toString() == "Synthetic pending box" } != null }
                return
            }
            waitUntil { service.state.value.connection.status == ConnectionStatus.PAIRED }
            waitUntil { find { it.text?.toString() == "Synthetic box 1" } != null }
            waitUntil { service.state.value.artwork[pages.first().key]?.portrait != null }
            if (scrollRegression) {
                repeat(4) {
                    if (find { node ->
                            node.text?.toString() == context.getString(R.string.type_message)
                        } == null
                    ) {
                        verticalSwipe()
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(500)
                    }
                }
                val target =
                    find { it.text?.toString() == context.getString(R.string.type_message) }
                assertTrue(
                    "Vertical touch scrolling must keep lower controls reachable",
                    target != null
                )
                val before = android.graphics.Rect().also {
                    requireNotNull(target).getBoundsInScreen(it)
                }
                SystemClock.sleep(1000)
                val after = find { it.text?.toString() == context.getString(R.string.type_message) }
                    ?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
                assertEquals(
                    "A settled vertical list must not jump back to the portrait",
                    before,
                    after
                )
                assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
                swipe(true)
                waitUntil {
                    service.state.value.profile?.endpoint?.url == urls[1] &&
                        service.state.value.connection.status == ConnectionStatus.PAIRED
                }
                swipe(false)
                waitUntil {
                    service.state.value.profile?.endpoint?.url == urls.first() &&
                        service.state.value.connection.status == ConnectionStatus.PAIRED
                }
                waitUntil {
                    find { it.text?.toString() == context.getString(R.string.type_message) } != null
                }
                assertTrue(
                    "Returning to a profile must restore its lower controls",
                    find { it.text?.toString() == context.getString(R.string.type_message) } != null
                )
                swipe(true, aborted = true)
                assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
                assertTrue(
                    "An aborted pager drag must retain vertical position",
                    find { it.text?.toString() == context.getString(R.string.type_message) } != null
                )
                instrumentation.runOnMainSync {
                    service.applyCatalog(
                        pages.map {
                            CatalogPage(it.sourceId, it.sourceLabel, it.profile, it.status, 2)
                        },
                        pages.first().key
                    )
                }
                SystemClock.sleep(600)
                assertTrue(
                    "A metadata refresh must retain vertical position",
                    find { it.text?.toString() == context.getString(R.string.type_message) } != null
                )
                click { it.text?.toString() == context.getString(R.string.type_message) }
                waitUntil { find { it.isEditable } != null }
                return
            }
            if (refreshPortrait) {
                val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.RED)
                }
                val bytes = ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                bitmap.recycle()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
                val descriptor = PortraitDescriptor(hash, bytes.size, "/assets/$hash.png")
                portraitCache.store(pages.first().key, descriptor, bytes)
                val original = pages.first()
                val refreshed =
                    listOf(
                        CatalogPage(
                            original.sourceId,
                            original.sourceLabel,
                            CatalogProfile(
                                original.profile.id,
                                original.profile.name,
                                null,
                                descriptor
                            ),
                            original.status,
                            2
                        )
                    ) +
                        pages.drop(1)
                instrumentation.runOnMainSync { service.applyCatalog(refreshed, original.key) }
                waitUntil {
                    service.state.value.artwork[original.key]?.portrait?.getPixel(0, 0) ==
                        android.graphics.Color.RED
                }
                assertEquals(original.key, service.state.value.selectedPage)
                assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
                return
            }
            if (changeEndpoint) {
                val original = pages.first()
                val changed =
                    listOf(
                        CatalogPage(
                            original.sourceId,
                            original.sourceLabel,
                            CatalogProfile(
                                original.profile.id,
                                original.profile.name,
                                "wss://unapproved.example.invalid/gadget",
                                original.profile.portrait
                            ),
                            original.status,
                            2
                        )
                    ) +
                        pages.drop(1)
                instrumentation.runOnMainSync { service.applyCatalog(changed, original.key) }
                waitUntil { service.state.value.connection.status == ConnectionStatus.OFFLINE }
                assertEquals(original.key, service.state.value.selectedPage)
                scrollTo { it.text?.toString() == "Review connection before pairing" }
                return
            }
            capture("01-portrait-first")
            if (pets) {
                waitUntil { service.state.value.pet?.rows?.containsKey("talking") == true }
                waitUntil {
                    find {
                        it.contentDescription?.toString()?.startsWith(
                            "Carousel robot 1:"
                        ) == true
                    } != null
                }
                SystemClock.sleep(350)
                capture("07-pet-first")
            }
            instrumentation.runOnMainSync { service.updateDraft("Unsent first") }
            swipe(true)
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls[1] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.draft)
            waitUntil { find { it.text?.toString() == "Synthetic box 2" } != null }
            assertEquals("", service.state.value.connection.conversation.reply)
            capture("02-portrait-second")
            if (pets) {
                waitUntil { service.state.value.pet?.name == "Carousel robot 2" }
                SystemClock.sleep(350)
                capture("08-pet-second")
            }
            instrumentation.runOnMainSync { service.updateDraft("Unsent second") }
            swipe(false)
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls.first() &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("Unsent first", service.state.value.draft)
            if (pets) assertEquals("Carousel robot 1", service.state.value.pet?.name)
            instrumentation.runOnMainSync { service.startRecording() }
            waitUntil {
                service.state.value.connection.conversation.mode ==
                    dev.quantumink.hermesgadget.protocol.ConversationMode.LISTENING
            }
            if (pets) capture("10-listening")
            swipe(true)
            SystemClock.sleep(700)
            assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
            instrumentation.runOnMainSync { service.cancelRecording() }
            waitUntil {
                service.state.value.connection.conversation.mode !=
                    dev.quantumink.hermesgadget.protocol.ConversationMode.LISTENING
            }
            instrumentation.runOnMainSync {
                service.text("Synthetic carousel approval") {}
            }
            waitUntil { service.state.value.connection.conversation.prompt != null }
            swipe(true)
            SystemClock.sleep(700)
            assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
            val prompt = requireNotNull(service.state.value.connection.conversation.prompt)
            instrumentation.runOnMainSync { service.answer(prompt.id, false) }
            waitUntil { service.state.value.connection.conversation.prompt == null }
            // The ask-only fixture does not finish its text turn when approval clears.
            // Keep that turn locked until an explicit cancellation ends it.
            assertTrue(service.state.value.connection.conversation.busy)
            swipe(true)
            assertEquals(urls.first(), service.state.value.profile?.endpoint?.url)
            instrumentation.runOnMainSync { service.cancel() }
            waitUntil { !service.state.value.connection.conversation.busy }
            waitUntil {
                find { it.contentDescription?.toString()?.contains("Start recording") == true } !=
                    null
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            swipe(true)
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls[1] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED &&
                    find { it.text?.toString() == "Synthetic box 2" } != null
            }
            val beforeRotary = android.graphics.Rect().also {
                requireNotNull(find { it.text?.toString() == "Synthetic box 2" })
                    .getBoundsInScreen(it)
            }
            // Exercise a short sequence of crown pulses rather than an isolated shell event.
            repeat(3) {
                rotaryPulse(-0.5f)
                SystemClock.sleep(80)
            }
            waitUntil {
                val afterRotary = find { it.text?.toString() == "Synthetic box 2" }?.let { node ->
                    android.graphics.Rect().also { node.getBoundsInScreen(it) }
                }
                beforeRotary != afterRotary
            }
            assertEquals(urls[1], service.state.value.profile?.endpoint?.url)
            val chooser = scrollTo(backward = false) {
                it.contentDescription?.toString() == "Choose a profile"
            }
            var actionNode: AccessibilityNodeInfo? = chooser
            while (actionNode != null && actionNode.actionList.none {
                    it.label?.toString() == "Next profile"
                }
            ) {
                actionNode = actionNode.parent
            }
            val accessibleChooser = requireNotNull(actionNode) {
                "The native picker must expose the Next profile selection action"
            }
            val next = accessibleChooser.actionList.single {
                it.label?.toString() == "Next profile"
            }
            assertTrue(accessibleChooser.performAction(next.id))
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls[2] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.draft)
            waitUntil { find { it.text?.toString() == "Synthetic box 3" } != null }
            capture("03-portrait-third")
            if (pets) {
                waitUntil { service.state.value.pet?.rows?.containsKey("talking") == true }
                assertEquals("Carousel robot 3", service.state.value.pet?.name)
                SystemClock.sleep(350)
                capture("09-pet-third")
            }
            assertEquals(
                3,
                urls.map {
                    vault.identity(Endpoint.parse(it, true, true)).deviceId
                }.distinct().size
            )
            assertEquals("", service.state.value.connection.conversation.reply)
            scrollTo(backward = true) { it.text?.toString() == "Synthetic box 3" }
            scrollTo { it.text?.toString() == context.getString(R.string.settings) }
            click { it.text?.toString() == context.getString(R.string.settings) }
            val editor = scrollTo {
                it.isEditable && it.text?.toString() == "Agent"
            }
            assertTrue(
                editor.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            "Unfinished review"
                        )
                    }
                )
            )
            capture("04-review-before-ambient")
            automation.executeShellCommand("input keyevent 223").use { }
            SystemClock.sleep(1500)
            capture("05-ambient")
            automation.executeShellCommand("input keyevent 224").use { }
            SystemClock.sleep(700)
            scrollTo { it.isEditable && it.text?.toString() == "Unfinished review" }
            assertEquals(urls[2], service.state.value.profile?.endpoint?.url)
            assertEquals(ConnectionStatus.OFFLINE, service.state.value.connection.status)
            capture("06-review-restored")
        } catch (failure: Throwable) {
            capture("failure")
            val state = bound.get()?.state?.value
            println(
                "Synthetic failure: profile=${urls.indexOf(
                    state?.profile?.endpoint?.url
                )} status=${state?.connection?.status} mode=${state?.connection?.conversation?.mode} saving=${state?.saving} prompt=${state?.connection?.conversation?.prompt != null}"
            )
            fun report(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString().orEmpty()
                if (node.isScrollable || node.isFocused) {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    println(
                        "Synthetic focus: scroll=${node.isScrollable} focused=${node.isFocused} bounds=$bounds"
                    )
                }
                if (text.startsWith("Synthetic box") ||
                    text in setOf("Ready", "Type a message", "Settings")
                ) {
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    println(
                        "Synthetic control: $text bounds=$bounds visible=${node.isVisibleToUser}"
                    )
                }
                for (index in 0 until node.childCount) report(node.getChild(index))
            }
            report(automation.rootInActiveWindow)
            throw failure
        } finally {
            bound.get()?.let { service -> instrumentation.runOnMainSync { service.disconnect() } }
            context.unbindService(binding)
        }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(automation.rootInActiveWindow)
    }
    private fun waitUntil(predicate: () -> Boolean) {
        repeat(150) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Carousel did not reach the expected state")
    }
    private fun scrollTo(
        backward: Boolean = false,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        repeat(20) {
            find(predicate)?.let { return it }
            // The pager contains the focused rotary/vertical list. Compose exposes generic
            // forward/back actions for both, so target the focused inner list.
            val list = find { it.isScrollable && it.isFocused }
                ?: error("Focused watch list missing")
            list.performAction(
                if (backward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            )
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
        }
        error("Watch control missing")
    }
    private fun click(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node = find(predicate) ?: error("Watch control missing")
        while (!node.isClickable) node = node.parent ?: error("Clickable control missing")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    private fun swipe(next: Boolean, aborted: Boolean = false) {
        val start = if (aborted) {
            240f
        } else if (next) {
            390f
        } else {
            90f
        }
        val end = if (aborted) {
            195f
        } else if (next) {
            90f
        } else {
            390f
        }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, time: Long) {
            val touch = MotionEvent.obtain(down, time, action, x, 245f, 0)
            touch.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(touch, true)
            touch.recycle()
        }
        event(MotionEvent.ACTION_DOWN, start, down)
        for (i in 1..12) {
            SystemClock.sleep(20)
            event(
                MotionEvent.ACTION_MOVE,
                start + (end - start) * i / 12,
                SystemClock.uptimeMillis()
            )
        }
        event(MotionEvent.ACTION_UP, end, SystemClock.uptimeMillis())
        SystemClock.sleep(350)
    }
    private fun verticalSwipe() {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 240f, y, 0)
            touch.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(touch, true)
            touch.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 350f)
        for (i in 1..12) {
            SystemClock.sleep(20)
            event(MotionEvent.ACTION_MOVE, 350f - 230f * i / 12)
        }
        // Stop the drag before release. A fast release legitimately flings past Type;
        // this regression measures stationary controls rather than kinetic scrolling.
        SystemClock.sleep(150)
        event(MotionEvent.ACTION_MOVE, 120f)
        event(MotionEvent.ACTION_UP, 120f)
    }
    private fun rotaryPulse(delta: Float) {
        val source = android.view.InputDevice.SOURCE_ROTARY_ENCODER
        val device = android.view.InputDevice.getDeviceIds().map {
            android.view.InputDevice.getDevice(it)
        }.filterNotNull().first { it.sources and source == source }
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = 240f
            y = 240f
            setAxisValue(MotionEvent.AXIS_SCROLL, delta)
        }
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, device.id, 0, source, 0
        )
        try {
            assertTrue(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
    private fun capture(name: String) {
        val directory = File(context.filesDir, "carousel-captures").apply { mkdirs() }
        automation.takeScreenshot()?.let { image ->
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
