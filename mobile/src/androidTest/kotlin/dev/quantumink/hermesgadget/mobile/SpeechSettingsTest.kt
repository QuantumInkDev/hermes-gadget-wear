package dev.quantumink.hermesgadget.mobile

import android.os.Bundle
import android.view.KeyEvent
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
                    fixture.scrollTo("Per-profile API key")
                    assertTrue(WidgetUi.nodes().any { it.isEditable && it.isPassword })
                    fixture.capture("task2-profile-${appearance.name}")
                    setPassword("syntheticReplacement")
                    fixture.await {
                        fixture.instrumentation.uiAutomation.windows.any {
                            it.type ==
                                AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        }
                    }
                    fixture.scrollTo("Save server voice mode")
                    fixture.assertAboveKeyboard("Save server voice mode")
                    fixture.capture("task2-ime-${appearance.name}")
                    fixture.click("Save server voice mode")
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
                    fixture.scrollTo("Check selected saved key quota")
                    fixture.click("Check selected saved key quota")
                    fixture.await { !session.state.value.busy && quota.get() == 1 }
                    assertEquals("syntheticReplacement", vault.read(id).key)
                    assertEquals(1, published.get())
                    fixture.scrollTo("Delete per-profile key")
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
                    fixture.scrollTo("Voice source: Mode: per-profile BYOK key")
                    fixture.click("Voice source: Mode: per-profile BYOK key")
                    fixture.click("Mode: server voice")
                    fixture.scrollTo("Save server voice mode")
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
                fixture.scrollTo("Manual server URL (wss)")
                fixture.enter("wss://one.example.invalid/gadget")
                fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                fixture.scrollTo("Load saved server settings")
                fixture.click("Load saved server settings")
                fixture.await { session.state.value.endpoint == id && !session.state.value.busy }
                assertEquals(SpeechMode.PROFILE, session.state.value.mode)
                fixture.scrollTo("Voice source: Mode: per-profile BYOK key")
                assertNotNull(fixture.find("Voice source: Mode: per-profile BYOK key"))
                scrollTop()
                fixture.scrollTo("Manual server URL (wss)")
                fixture.enter("wss://two.example.invalid/gadget")
                fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                fixture.await { session.state.value.endpoint == null && !session.state.value.busy }
                fixture.scrollTo("Load saved server settings")
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
