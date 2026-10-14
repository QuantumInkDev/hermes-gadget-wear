package dev.quantumink.hermesgadget.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue

/** Synthetic debug host only; production FLAG_SECURE is never touched. */
internal class NativePhoneFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
    init {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags =
                flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val directory = File(target.cacheDir, "native-phone-" + UUID.randomUUID()).apply {
        mkdirs()
    }
    val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
        override fun getSharedPreferences(
            name: String,
            mode: Int
        ): android.content.SharedPreferences =
            super.getSharedPreferences(directory.name + name, mode)
    }
    private val activity = instrumentation.startActivitySync(
        Intent(target, AppearanceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as AppearanceTestActivity

    fun show(
        mode: Appearance = Appearance.DARK,
        destination: String = "conversation",
        scroll: Boolean = false,
        back: () -> Unit = {},
        content: @Composable (Modifier) -> Unit
    ) {
        instrumentation.runOnMainSync {
            activity.setContent {
                PhoneAppearance(mode) {
                    androidx.activity.compose.BackHandler { back() }
                    PhoneScaffold(topBar = {
                        Column(Modifier.statusBarsPadding()) {
                            if (destination != "conversation") PhoneBrandHeader()
                        }
                    }, bottomBar = {
                        if (WindowInsets.ime.getBottom(LocalDensity.current) ==
                            0
                        ) {
                            PhoneNavigationBar(destination, {})
                        }
                    }) { padding ->
                        val modifier = Modifier.fillMaxSize().padding(
                            padding
                        ).consumeWindowInsets(padding).imePadding()
                        if (scroll) {
                            Column(
                                modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                content(Modifier)
                            }
                        } else {
                            content(modifier)
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
    }

    fun find(text: String, description: Boolean = false): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            val value = if (description) node.contentDescription else node.text
            if (node.isVisibleToUser && value?.toString() == text) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    fun await(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            SystemClock.sleep(30)
        }
        error("Native phone presentation did not settle")
    }
    fun click(text: String, description: Boolean = false) {
        await { find(text, description) != null }
        var node = find(text, description)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(
            "No click action for $text",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        )
        instrumentation.waitForIdleSync()
    }
    fun scrollTo(text: String, description: Boolean = false) {
        repeat(20) {
            if (find(text, description) != null) return
            fun scroll(node: AccessibilityNodeInfo?): Boolean {
                node ?: return false
                if (node.isScrollable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ) {
                    return true
                }
                for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                return false
            }
            scroll(instrumentation.uiAutomation.rootInActiveWindow)
            SystemClock.sleep(100)
        }
        error("Cannot scroll to $text")
    }

    fun enter(value: String) {
        fun field(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && node.isEditable) return node
            for (index in 0 until node.childCount) field(node.getChild(index))?.let { return it }
            return null
        }
        val input = field(instrumentation.uiAutomation.rootInActiveWindow) ?: error("No composer")
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(
            input.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        )
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        // Android's keyboard may explain its changed font size after the large-type fixture.
        fun acknowledge(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.text?.toString() == "OK") {
                var action: AccessibilityNodeInfo? = node
                while (action != null && !action.isClickable) action = action.parent
                return action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            for (index in 0 until node.childCount) {
                if (acknowledge(
                        node.getChild(index)
                    )
                ) {
                    return true
                }
            }
            return false
        }
        instrumentation.uiAutomation.windows.filter {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }.forEach { acknowledge(it.root) }
        SystemClock.sleep(300)
    }
    fun assertAboveKeyboard(text: String, description: Boolean = false) {
        val node = requireNotNull(find(text, description))
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val keyboard = instrumentation.uiAutomation.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: error("Native keyboard is not visible")
        val keyboardBounds = android.graphics.Rect().also(keyboard::getBoundsInScreen)
        assertTrue(
            "$text is clipped by the keyboard: $bounds / $keyboardBounds",
            bounds.bottom <= keyboardBounds.top
        )
    }

    fun capture(name: String) {
        SystemClock.sleep(150)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(target.cacheDir, "task-3-captures").apply { mkdirs() }
        File(
            output,
            "$name${InstrumentationRegistry.getArguments().getString(
                "messaging_suffix"
            ).orEmpty()}.png"
        ).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    override fun close() {
        // A failed gesture assertion must not leave InputDispatcher's injected pointer down.
        val now = SystemClock.uptimeMillis()
        val cancel = android.view.MotionEvent.obtain(
            now,
            now,
            android.view.MotionEvent.ACTION_CANCEL,
            0f,
            0f,
            0
        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try {
            instrumentation.uiAutomation.injectInputEvent(cancel, true)
        } finally {
            cancel.recycle()
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = originalAccessibilityFlags
        }
        instrumentation.runOnMainSync {
            activity.setContent {}
            activity.finish()
        }
        directory.deleteRecursively()
    }
}
