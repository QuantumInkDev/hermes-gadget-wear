package dev.quantumink.hermesgadget.mobile

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetUi
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsTest {
    private val page = CatalogPage(
        "boxOne",
        "Synthetic research box",
        CatalogProfile("bot", "Same named bot", "wss://one.example.invalid/gadget", null),
        CatalogStatus.OFFLINE,
        1
    )
    private val target = SpeechSettingsTarget(page, Endpoint.parse(page.profile.gadgetUrl!!))
    private val id = ClientSpeech.endpointId(target.endpoint)

    @Test fun settingsExplainBothPhoneAndWatchWithoutConnecting() {
        NativePhoneFixture().use { fixture ->
            fixture.show(destination = "settings", scroll = true) {
                SpeechSettingsPage(fixture.context) {}
            }
            assertNotNull(fixture.find("Speech providers for phone and watch"))
        }
    }

    @Test fun selectedEndpointModesMaskingAndDistinctSaveDeleteRenderEveryTheme() {
        assertTrue("Owned synthetic emulator only", isAndroidEmulator())
        Appearance.entries.forEach { appearance ->
            NativePhoneFixture().use { fixture ->
                val suffix = UUID.randomUUID().toString()
                val directory = File(fixture.context.filesDir, "speech-settings")
                val alias = "test.speech.settings.$suffix"
                val vault = TtsVault(fixture.context, directory, alias)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val published = AtomicInteger()
                val quota = AtomicInteger()
                val session = SpeechSettingsSession(
                    scope,
                    vault::read,
                    vault::save,
                    {
                        published.incrementAndGet()
                        Unit
                    },
                    {
                        quota.incrementAndGet()
                        123L
                    }
                )
                val secret = "syntheticHiddenKey"
                try {
                    vault.save(id, SpeechSettings(SpeechMode.PROFILE, secret, "profileVoice"))
                    fixture.show(appearance, "settings", scroll = true) {
                        SpeechSettingsPage(fixture.context, target, providedSession = session) {}
                    }
                    fixture.await { session.state.value.loaded && !session.state.value.busy }
                    assertNotNull(fixture.find("Same named bot"))
                    assertNotNull(fixture.find("Synthetic research box"))
                    assertFalse(
                        WidgetUi.nodes().any {
                            it.text?.toString()?.contains(secret) == true
                        }
                    )
                    fixture.capture("task2-context-${appearance.name}")
                    scrollTo(fixture, "Per-profile API key")
                    assertTrue(WidgetUi.nodes().any { it.isEditable && it.isPassword })
                    fixture.capture("task2-profile-${appearance.name}")
                    setPassword("syntheticReplacement")
                    fixture.await {
                        fixture.instrumentation.uiAutomation.windows.any {
                            it.type ==
                                AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        }
                    }
                    saveByTouch(fixture, appearance)
                    fixture.await { !session.state.value.busy && published.get() == 1 }
                    assertEquals("syntheticReplacement", vault.read(id).key)
                    assertFalse(
                        WidgetUi.nodes().any {
                            it.text?.toString()?.contains("syntheticReplacement") ==
                                true
                        }
                    )
                    fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    fixture.await {
                        fixture.instrumentation.uiAutomation.windows.none {
                            it.type ==
                                AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                                it.root?.isVisibleToUser == true
                        }
                    }
                    scrollTop()
                    scrollTo(fixture, "Check selected saved key quota")
                    fixture.click("Check selected saved key quota")
                    fixture.await { !session.state.value.busy && quota.get() == 1 }
                    assertEquals("syntheticReplacement", vault.read(id).key)
                    assertEquals(1, published.get())
                    scrollTo(fixture, "Delete per-profile key")
                    fixture.click("Delete per-profile key")
                    fixture.await { !session.state.value.busy && published.get() == 2 }
                    assertEquals("", vault.read(id).key)
                    assertEquals(SpeechMode.PROFILE, vault.read(id).mode)
                    assertFalse(session.state.value.ready)
                    // Recreate from encrypted storage; saved key text never enters the view.
                    session.close()
                    fixture.show(appearance, "settings", scroll = true) { }
                    fixture.show(appearance, "settings", scroll = true) {
                        SpeechSettingsPage(fixture.context, target, providedSession = session) {}
                    }
                    fixture.await { session.state.value.loaded && !session.state.value.busy }
                    assertEquals(SpeechMode.PROFILE, session.state.value.mode)
                    assertFalse(session.state.value.ready)
                    assertEquals(1, quota.get())
                    scrollTo(fixture, "Voice source: Mode: per-profile BYOK key")
                    fixture.click("Voice source: Mode: per-profile BYOK key")
                    fixture.click("Mode: server voice")
                    scrollTo(fixture, "Save server voice mode")
                    fixture.click("Save server voice mode")
                    fixture.await {
                        !session.state.value.busy &&
                            vault.read(id).mode == SpeechMode.SERVER
                    }
                    fixture.capture("task2-server-${appearance.name}")
                } finally {
                    session.close()
                    scope.cancel()
                    directory.deleteRecursively()
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry(alias)
                    }
                }
            }
        }
    }

    @Test fun profileKeyCanBeFoundAfterSharedControls() {
        assertTrue("Owned synthetic emulator only", isAndroidEmulator())
        NativePhoneFixture().use { fixture ->
            val directory = File(fixture.context.filesDir, "speech-search")
            val alias = "test.speech.search." + UUID.randomUUID()
            val vault = TtsVault(fixture.context, directory, alias)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val session = SpeechSettingsSession(scope, vault::read, vault::save, {
                error("No publication is authorized")
            }, { error("No quota action is authorized") })
            try {
                vault.save(
                    id,
                    SpeechSettings(SpeechMode.PROFILE, "syntheticSearch", "profileVoice")
                )
                fixture.show(Appearance.DARK, "settings", scroll = true) {
                    SpeechSettingsPage(fixture.context, target, providedSession = session) {}
                }
                fixture.await { session.state.value.loaded && !session.state.value.busy }
                scrollTo(fixture, "Delete shared key")
                assertNotNull(fixture.find("Delete shared key"))
                scrollTo(fixture, "Per-profile API key")
                assertNotNull(fixture.find("Per-profile API key"))
                assertTrue(
                    WidgetUi.nodes().any {
                        it.isVisibleToUser && it.isEditable && it.isPassword
                    }
                )
            } finally {
                session.close()
                scope.cancel()
                directory.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(alias)
                }
            }
        }
    }

    @Test fun profileInvalidationRemovesEveryControlAndDoesNotPublish() {
        NativePhoneFixture().use { fixture ->
            val available = androidx.compose.runtime.mutableStateOf(true)
            var back = false
            fixture.show(destination = "settings", scroll = true) {
                SpeechSettingsPage(fixture.context, target, available = available.value) {
                    back =
                        true
                }
            }
            fixture.instrumentation.runOnMainSync { available.value = false }
            fixture.await {
                fixture.find(fixture.context.getString(R.string.speech_unavailable)) !=
                    null
            }
            assertFalse(WidgetUi.nodes().any { it.isEditable })
            fixture.click("Back")
            assertTrue(back)
        }
    }

    @Test fun manualEndpointRequiresExplicitLoadAndReplacesTheSavedSelection() {
        NativePhoneFixture().use { fixture ->
            val alias = "test.speech.manual." + UUID.randomUUID()
            val directory = File(fixture.context.filesDir, "manual-speech")
            val vault = TtsVault(fixture.context, directory, alias)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val checks = AtomicInteger()
            val session = SpeechSettingsSession(scope, vault::read, vault::save, {}, {
                checks.incrementAndGet()
                0L
            })
            val other = ClientSpeech.endpointId(Endpoint.parse("wss://two.example.invalid/gadget"))
            try {
                vault.save(id, SpeechSettings(SpeechMode.PROFILE, "syntheticManual", "oneVoice"))
                vault.save(other, SpeechSettings(SpeechMode.SHARED, "", "twoVoice"))
                fixture.show(destination = "settings", scroll = true) {
                    SpeechSettingsPage(fixture.context, target = null, providedSession = session) {}
                }
                fixture.await { session.state.value.loaded && !session.state.value.busy }
                assertEquals(null, session.state.value.endpoint)
                scrollTo(fixture, "Manual server URL (wss)")
                fixture.enter("wss://one.example.invalid/gadget")
                fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                scrollTo(fixture, "Load saved server settings")
                fixture.click("Load saved server settings")
                fixture.await { session.state.value.endpoint == id && !session.state.value.busy }
                assertEquals(SpeechMode.PROFILE, session.state.value.mode)
                scrollTo(fixture, "Voice source: Mode: per-profile BYOK key")
                assertNotNull(fixture.find("Voice source: Mode: per-profile BYOK key"))
                scrollTop()
                scrollTo(fixture, "Manual server URL (wss)")
                fixture.enter("wss://two.example.invalid/gadget")
                fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                fixture.await { session.state.value.endpoint == null && !session.state.value.busy }
                scrollTo(fixture, "Load saved server settings")
                fixture.click("Load saved server settings")
                fixture.await { session.state.value.endpoint == other && !session.state.value.busy }
                assertEquals(SpeechMode.SHARED, session.state.value.mode)
                assertEquals("twoVoice", session.state.value.voice)
                assertEquals("syntheticManual", vault.read(id).key)
                assertEquals(0, checks.get())
            } finally {
                session.close()
                scope.cancel()
                directory.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(alias)
                }
            }
        }
    }

    private fun saveByTouch(fixture: NativePhoneFixture, appearance: Appearance) {
        var noticeDismissed = dismissKeyboardFontNotice()
        scrollTo(fixture, "Save server voice mode")
        if (dismissKeyboardFontNotice()) {
            noticeDismissed = true
            scrollTo(fixture, "Save server voice mode")
        }
        SystemClock.sleep(300)
        fixture.await { fixture.find("Save server voice mode") != null }
        fun saveBounds(): Rect {
            var action = requireNotNull(fixture.find("Save server voice mode"))
            while (!action.isClickable) action = requireNotNull(action.parent)
            return Rect().also(action::getBoundsInScreen)
        }
        var bounds = saveBounds()
        val viewport = WidgetUi.nodes().first { node ->
            node.isScrollable && node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }
        }
        val visible = Rect().also(viewport::getBoundsInScreen)
        val dy = (visible.exactCenterY() - bounds.exactCenterY()).coerceIn(
            -visible.height() * 0.4f,
            visible.height() * 0.4f
        )
        if (kotlin.math.abs(dy) > 12) {
            drag(
                fixture,
                visible.exactCenterX(),
                visible.exactCenterY() - dy / 2,
                visible.exactCenterY() + dy / 2
            )
            SystemClock.sleep(300)
            bounds = saveBounds()
        }
        assertTrue("Save must fit inside the app viewport", visible.contains(bounds))
        assertTrue(
            "Save must meet its minimum touch height",
            bounds.height() >=
                48 * fixture.context.resources.displayMetrics.density
        )
        fixture.assertAboveKeyboard("Save server voice mode")
        val keyboard = fixture.instrumentation.uiAutomation.windows.first {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val keyboardBounds = Rect().also(keyboard::getBoundsInScreen)
        assertTrue("Save must fit above the keyboard", bounds.bottom <= keyboardBounds.top)
        fixture.capture("task2-ime-${appearance.name}")
        val suffix = InstrumentationRegistry.getArguments().getString("messaging_suffix").orEmpty()
        File(
            fixture.instrumentation.targetContext.cacheDir,
            "task2-touch-${appearance.name}$suffix.json"
        )
            .writeText(
                org.json.JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
                    .put("theme", appearance.name).put("saveBounds", bounds.toShortString())
                    .put("keyboardBounds", keyboardBounds.toShortString())
                    .put("viewportBounds", visible.toShortString())
                    .put("tapX", bounds.exactCenterX()).put("tapY", bounds.exactCenterY())
                    .put("fontNoticeDismissed", noticeDismissed).toString(2)
            )
        tap(bounds)
    }

    private fun dismissKeyboardFontNotice(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }
        var dismissed = false
        SystemClock.sleep(300)
        repeat(5) {
            val notice = automation.windows.mapNotNull { it.root }.filter {
                it.packageName?.toString() == "com.google.android.inputmethod.latin"
            }.map(::nodes).firstOrNull { window ->
                window.any {
                    it.text?.toString()?.replace(Regex("\\s+"), " ")?.trim() ==
                        "Keyboard font size updated"
                }
            } ?: return dismissed
            var action = requireNotNull(notice.firstOrNull { it.text?.toString() == "OK" })
            while (!action.isClickable) action = requireNotNull(action.parent)
            tap(Rect().also(action::getBoundsInScreen))
            automation.waitForIdle(200, 3000)
            dismissed = true
        }
        error("Gboard font notice did not settle")
    }

    private fun tap(bounds: Rect) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val started = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                started,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0
            ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun scrollTo(fixture: NativePhoneFixture, text: String) {
        try {
            fixture.scrollTo(text)
            return
        } catch (failure: IllegalStateException) {
            if (failure.message != "Cannot scroll to $text") throw failure
        }
        // Full-page accessibility paging can skip a label or start below the target.
        // Use overlapping touch drags only when that search has exhausted its range.
        var forward = true
        repeat(80) {
            if (fixture.find(text) != null) return
            val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            val viewport = WidgetUi.nodes().firstOrNull { node ->
                node.isScrollable && node.actionList.any { it.id == down || it.id == up }
            }
            if (viewport == null) {
                // A closing choice menu can briefly own the accessibility window.
                SystemClock.sleep(100)
                return@repeat
            }
            if (viewport.actionList.none { it.id == if (forward) down else up }) {
                forward = !forward
            }
            val bounds = Rect().also(viewport::getBoundsInScreen)
            val x = bounds.exactCenterX()
            val middle = bounds.exactCenterY()
            val distance = bounds.height() * 0.2f
            val start = middle + if (forward) distance else -distance
            val end = middle - if (forward) distance else -distance
            drag(fixture, x, start, end)
        }
        error("Cannot reach settings control $text by touch")
    }

    private fun drag(fixture: NativePhoneFixture, x: Float, start: Float, end: Float) {
        val started = SystemClock.uptimeMillis()
        fun pointer(action: Int, y: Float) {
            val event = MotionEvent.obtain(
                started,
                SystemClock.uptimeMillis(),
                action,
                x,
                y,
                0
            ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try {
                fixture.instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        pointer(MotionEvent.ACTION_DOWN, start)
        for (step in 1..4) {
            SystemClock.sleep(16)
            pointer(MotionEvent.ACTION_MOVE, start + (end - start) * step / 4)
        }
        // Hold before release to avoid flinging past a short label between samples.
        SystemClock.sleep(150)
        pointer(MotionEvent.ACTION_MOVE, end)
        pointer(MotionEvent.ACTION_UP, end)
        SystemClock.sleep(100)
    }

    private fun scrollTop() {
        repeat(20) {
            WidgetUi.nodes().lastOrNull { node ->
                node.isScrollable && node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            android.os.SystemClock.sleep(30)
        }
    }

    private fun setPassword(value: String) {
        val field = WidgetUi.nodes().first { it.isVisibleToUser && it.isEditable && it.isPassword }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(700)
    }
}
