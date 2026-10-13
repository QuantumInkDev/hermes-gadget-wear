package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real destination lifetimes in the secure main Activity; synthetic empty owned AVD only. */
class DirectoryNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk).orEmpty()
    }
    private fun find(label: String) = nodes().firstOrNull {
        it.isVisibleToUser && !android.graphics.Rect().also(it::getBoundsInScreen).isEmpty &&
            (it.text?.toString() == label || it.contentDescription?.toString() == label)
    }
    private fun await(label: String, timeout: Long = 4000) {
        val until = SystemClock.uptimeMillis() + timeout
        while (find(label) == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue("Missing $label", find(label) != null)
    }
    private fun click(label: String) {
        await(label)
        var node = requireNotNull(find(label))
        while (!node.isClickable) node = requireNotNull(node.parent)
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach {
            instrumentation.sendPointerSync(
                android.view.MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    it,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0
                ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            )
        }
    }
    private fun search(value: String) {
        val field = nodes().first { it.isEditable }
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        )
        SystemClock.sleep(250)
    }
    private fun swipeUp() {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action = when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN
                12 -> android.view.MotionEvent.ACTION_UP
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            instrumentation.sendPointerSync(
                android.view.MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    540f,
                    1800f - step * 65f,
                    0
                ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            )
            SystemClock.sleep(25)
        }
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        SystemClock.sleep(200)
    }
    private fun scrollTo(label: String) {
        repeat(25) {
            if (find(label) != null) return
            swipeUp()
        }
        await(label)
    }
    private fun top() {
        repeat(25) {
            if (nodes().any { it.isEditable }) return
            nodes().first {
                it.isScrollable
            }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(80)
        }
    }
    private fun position(): Pair<String, android.graphics.Rect> {
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        SystemClock.sleep(200)
        val node = nodes().first {
            it.isVisibleToUser && it.isEnabled &&
                android.graphics.Rect().also(it::getBoundsInScreen).height() > 60 &&
                it.contentDescription?.toString()?.startsWith("Open Agent") == true
        }
        return node.contentDescription.toString() to
            android.graphics.Rect().also(node::getBoundsInScreen)
    }

    private fun fixture(test: (MainActivity, CatalogRepository) -> Unit) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val vault = CatalogVault(context)
        assumeTrue(
            "Never overwrite existing catalog setup",
            vault.sources().isEmpty() && vault.pages().isEmpty()
        )
        assumeTrue(ProfileVault(context).profiles().isEmpty())
        val enabled = RelaySettings.enabled(context)
        RelaySettings.setEnabled(context, false)
        vault.saveSources(
            (1..3).map {
                CatalogSource(
                    "box$it",
                    "Box $it",
                    "https://box$it.example.invalid/catalog/v1",
                    "synthetic"
                )
            }
        )
        vault.savePages(
            (1..18).map {
                val box = (it - 1) / 6 + 1
                CatalogPage(
                    "box$box",
                    "Box $box",
                    CatalogProfile(
                        "p$it",
                        "Agent $it",
                        "wss://agent$it.example.invalid/gadget",
                        null
                    ),
                    CatalogStatus.AVAILABLE,
                    1
                )
            }
        )
        val repository = CatalogRepository.get(context)
        repository.savedPages()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            await("Open Agent 1 Talk and Chat")
            SystemClock.sleep(300)
            test(activity, repository)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            vault.saveSourceOrder(emptyList())
            repository.savedPages()
            RelaySettings.setEnabled(context, enabled)
        }
    }

    @Test fun talkBackAndSettingsBackRetainQueryCollapseAndNativeListPosition() = fixture { _, _ ->
        click("Box 1")
        SystemClock.sleep(250)
        assertTrue("Initial Box 1 collapse", find("Open Agent 1 Talk and Chat") == null)
        search("Agent")
        scrollTo("Open Agent 12 Talk and Chat")
        val before = position()
        assertTrue(
            "The roster must actually be scrolled",
            before.first != "Open Agent 1 Talk and Chat"
        )
        click(before.first)
        await("Back to profiles")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await(before.first)
        assertEquals(before, position())
        click("Settings")
        await("Appearance")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await(before.first)
        assertEquals(before, position())
        top()
        assertEquals("Agent", nodes().first { it.isEditable }.text.toString())
        search("")
        assertTrue(
            "Box 1 collapse must survive search and both destinations",
            find("Open Agent 1 Talk and Chat") == null
        )
        click("Box 1")
        await("Open Agent 1 Talk and Chat")
    }

    @Test fun pendingTalkCannotBlockNavigationOrOpenAfterDestinationChanges() = fixture {
            _,
            repository
        ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            synchronized(repository) {
                entered.countDown()
                release.await(4, TimeUnit.SECONDS)
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val began = SystemClock.uptimeMillis()
            click("Open Agent 1 Talk and Chat")
            click("Settings")
            await("Appearance", 700)
            assertTrue(
                "Main queue must not wait for repository IO",
                SystemClock.uptimeMillis() - began < 1500
            )
            release.countDown()
            worker.join(1000)
            SystemClock.sleep(300)
            await("Appearance")
            assertTrue("Stale Talk must not open over Settings", find("Back to profiles") == null)
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }

    @Test fun removedSourcesPruneOnlyTheirOwnCollapseState() = fixture { _, repository ->
        val vault = CatalogVault(context)
        val sources = vault.sources()
        val pages = vault.pages()
        click("Box 1")
        click("Box 2")
        vault.saveSources(sources.filterNot { it.id == "box1" })
        vault.savePages(pages.filterNot { it.sourceId == "box1" })
        repository.savedPages()
        await("Box 2")
        SystemClock.sleep(300)
        assertTrue(find("Open Agent 7 Talk and Chat") == null)
        vault.saveSources(sources)
        vault.savePages(pages)
        repository.savedPages()
        SystemClock.sleep(300)
        assertTrue(
            "Unrelated Box 2 must stay collapsed",
            find("Open Agent 7 Talk and Chat") == null
        )
        scrollTo("Open Agent 1 Talk and Chat")
        await("Open Agent 1 Talk and Chat")
    }

    @Test fun pendingTalkIsCancelledWhenActivityStopsAndCannotOpenOnResume() = fixture {
            activity,
            repository
        ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            synchronized(repository) {
                entered.countDown()
                release.await(4, TimeUnit.SECONDS)
            }
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            click("Open Agent 1 Talk and Chat")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            repeat(40) {
                if (activity.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                ) {
                    SystemClock.sleep(30)
                }
            }
            assertTrue(
                !activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED
                )
            )
            release.countDown()
            worker.join(1000)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
            )
            repeat(40) {
                if (!activity.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.RESUMED
                    )
                ) {
                    SystemClock.sleep(30)
                }
            }
            assertTrue(
                "Resume the same main Activity",
                activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED
                )
            )
            await("Your profiles")
            assertTrue(find("Back to profiles") == null)
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }
}
