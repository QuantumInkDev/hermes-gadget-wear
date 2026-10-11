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

    @Test fun threePagesSwipeWithSeparateDraftsPortraitsAndRecordingProtection() {
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
        val vault = IdentityVault(context)
        val portraitCache = PortraitCache(File(context.filesDir, "portrait-cache"))
        val pages = urls.mapIndexed { index, url ->
            val endpoint = Endpoint.parse(url, true, true)
            vault.save(EndpointProfile(endpoint, "Agent", ""))
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(
                listOf(
                    android.graphics.Color.BLUE,
                    android.graphics.Color.GREEN,
                    android.graphics.Color.MAGENTA
                )[index]
            )
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
        vault.select(urls.first())
        WatchCatalogVault(context).apply {
            savePages(pages)
            select(pages.first().key)
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
            waitUntil { service.state.value.connection.status == ConnectionStatus.PAIRED }
            waitUntil { find { it.text?.toString() == "Synthetic box 1" } != null }
            waitUntil { service.state.value.artwork[pages.first().key]?.portrait != null }
            capture("01-portrait-first")
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
            instrumentation.runOnMainSync { service.updateDraft("Unsent second") }
            swipe(false)
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls.first() &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("Unsent first", service.state.value.draft)
            instrumentation.runOnMainSync { service.startRecording() }
            waitUntil {
                service.state.value.connection.conversation.mode ==
                    dev.quantumink.hermesgadget.protocol.ConversationMode.LISTENING
            }
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
            swipe(true)
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls[1] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED &&
                    find { it.text?.toString() == "Synthetic box 2" } != null
            }
            scrollTo { it.text?.toString() == "Next" }
            click { it.text?.toString() == "Next" }
            waitUntil {
                service.state.value.profile?.endpoint?.url == urls[2] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.draft)
            waitUntil { find { it.text?.toString() == "Synthetic box 3" } != null }
            capture("03-portrait-third")
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
    private fun swipe(next: Boolean) {
        val start = if (next) 390f else 90f
        val end = if (next) 90f else 390f
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
