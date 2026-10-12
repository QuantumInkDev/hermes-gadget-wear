package dev.quantumink.hermesgadget.mobile

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ConversationPresentationTest {
    @Test fun conversationAndRemainingDestinationsRenderAllThemes() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val profile = EndpointProfile(
            Endpoint.parse("wss://synthetic.example.invalid/gadget"),
            "Research companion",
            ""
        )
        val large = InstrumentationRegistry.getArguments().getString("task3_large") == "true"
        Appearance.entries.forEach { mode ->
            NativePhoneFixture().use { fixture ->
                if (large) {
                    assertTrue(fixture.context.resources.configuration.fontScale >= 1.7f)
                    assertTrue(fixture.context.resources.configuration.screenWidthDp <= 330)
                }
                val state = androidx.compose.runtime.mutableStateOf(
                    PhoneConversationState(
                        profile = profile,
                        visible = true,
                        connection = dev.quantumink.hermesgadget.protocol.ConnectionState(
                            status = dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED,
                            conversation = dev.quantumink.hermesgadget.protocol.ConversationState(
                                transcript = "Help me plan a quieter afternoon.",
                                reply = "Start with one useful task, then leave room for a walk."
                            )
                        )
                    )
                )
                var sent = ""
                var recorded = 0
                fun render() {
                    fixture.show(mode) { modifier ->
                        ConversationContent(
                            state.value,
                            dev.quantumink.hermesgadget.protocol.CatalogPage(
                                "synthetic",
                                "Studio box",
                                dev.quantumink.hermesgadget.protocol.CatalogProfile(
                                    "research",
                                    profile.label,
                                    profile.endpoint.url,
                                    null
                                ),
                                dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE,
                                0
                            ),
                            {
                            }, { recorded++ }, {},
                            { state.value = state.value.copy(draft = it) }, {
                                sent =
                                    state.value.draft
                            },
                            {}, {}, { state.value = state.value.copy(speaking = false) }, {}, {},
                            { _, _ -> }, modifier
                        )
                    }
                }
                render()
                fixture.await { fixture.find("Hold to talk") != null }
                fixture.capture("conversation-${mode.name}" + if (large) "-large" else "")
                fixture.enter("A synthetic message")
                fixture.capture("keyboard-${mode.name}" + if (large) "-large" else "")
                fixture.assertAboveKeyboard("Send message", description = true)
                fixture.click("Send message", description = true)
                assertEquals("A synthetic message", sent)
                fixture.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(700)
                state.value =
                    state.value.copy(speaking = true, playbackLevel = .35f, mediaMuted = true)
                fixture.await { fixture.find("Stop voice") != null }
                fixture.capture("speaking-${mode.name}" + if (large) "-large" else "")
                fixture.click("Stop voice")
                assertTrue(!state.value.speaking)
                state.value = state.value.copy(error = "Microphone permission was declined.")
                fixture.capture("error-${mode.name}" + if (large) "-large" else "")
                fixture.show(mode, "watch", true) {
                    WatchDashboard(true, "Idle · waiting for watch", {}, {}, {}, {})
                }
                fixture.capture("watch-${mode.name}" + if (large) "-large" else "")
                fixture.show(mode, "settings", true) { PhoneSettingsPage(fixture.context, {}) }
                fixture.capture("settings-${mode.name}" + if (large) "-large" else "")
                fixture.show(mode, "settings", true) {
                    CatalogSettingsContent(fixture.context, CatalogRepository(fixture.context), {})
                }
                fixture.capture("boxes-${mode.name}" + if (large) "-large" else "")
                fixture.show(mode, "settings", true) { ProfilesPage(fixture.context, {}) }
                fixture.capture("manual-${mode.name}" + if (large) "-large" else "")
                fixture.show(mode, "settings", true) { SpeechSettingsPage(fixture.context, {}) }
                fixture.capture("speech-${mode.name}" + if (large) "-large" else "")
                assertEquals(0, recorded)
            }
        }
    }

    @Test fun pairingApprovalCardAndPictureKeepTheirNativeGuards() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val state = androidx.compose.runtime.mutableStateOf(
                PhoneConversationState(
                    profile = EndpointProfile(
                        Endpoint.parse("wss://synthetic.example.invalid/gadget"),
                        "Research",
                        ""
                    ),
                    visible = true,
                    connection = dev.quantumink.hermesgadget.protocol.ConnectionState(
                        status = dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED,
                        conversation = dev.quantumink.hermesgadget.protocol.ConversationState(
                            prompt = dev.quantumink.hermesgadget.protocol.Prompt(
                                "review",
                                "Review action",
                                "Continue with this task?",
                                0,
                                null
                            ),
                            card = dev.quantumink.hermesgadget.protocol.Card(
                                "Plan",
                                "One step at a time.",
                                null
                            ),
                            picture = dev.quantumink.hermesgadget.protocol.Picture(
                                1,
                                1,
                                byteArrayOf(0, 0),
                                null
                            )
                        )
                    )
                )
            )
            val answers = mutableListOf<Pair<String, Boolean>>()
            ui.show { modifier ->
                ConversationContent(
                    state.value, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    { id, yes -> answers.add(id to yes) }, modifier
                )
            }
            ui.scrollTo("Approve")
            var approve = ui.find("Approve")
            while (approve != null && !approve.isClickable) approve = approve.parent
            assertTrue(approve?.isEnabled == false)
            state.value = state.value.copy(
                connection = state.value.connection.copy(
                    conversation = state.value.connection.conversation.copy(canAnswer = true)
                )
            )
            ui.await {
                var node = ui.find("Approve")
                while (node != null && !node.isClickable) node = node.parent
                node?.isEnabled == true
            }
            ui.click("Approve")
            assertEquals(listOf("review" to true), answers)
            ui.capture("approval-guard")
            state.value =
                state.value.copy(
                    connection = dev.quantumink.hermesgadget.protocol.ConnectionState(
                        status = dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRING,
                        pairingCode = "SYNTHETIC",
                        pairingCommand = "approve SYNTHETIC"
                    )
                )
            ui.await { ui.find("Pairing code: SYNTHETIC") != null }
            ui.capture("pairing")
        }
    }

    @Test fun reducedMotionUsesAStaticMeterWithoutKeepingAudioSamples() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = android.provider.Settings.Global.getString(
            instrumentation.targetContext.contentResolver,
            "animator_duration_scale"
        ) ?: "1"
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            ).use {
                it.readBytes()
            }
        }
        try {
            shell("settings put global animator_duration_scale 0")
            NativePhoneFixture().use { ui ->
                ui.show { modifier -> AudioWaveform(.7f, "Synthetic response", modifier) }
                ui.await { ui.find("Synthetic response · meter paused", true) != null }
                ui.capture("static-meter")
            }
        } finally {
            shell("settings put global animator_duration_scale $original")
        }
    }

    @Test fun backgroundAndScreenOffCloseTheActualActivitySession() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        lateinit var model: PhoneConversation
        val profile =
            EndpointProfile(
                Endpoint.parse("wss://synthetic.example.invalid/gadget"),
                "Lifecycle",
                ""
            )
        instrumentation.runOnMainSync {
            model = androidx.lifecycle.ViewModelProvider(activity)[PhoneConversation::class.java]
            model.open(profile)
            model.draft("Lifecycle draft")
        }
        fun waitHidden() {
            repeat(100) { if (model.state.value.visible) SystemClock.sleep(30) }
            assertTrue(!model.state.value.visible)
            assertTrue(!model.state.value.speaking)
            assertEquals(0f, model.state.value.playbackLevel)
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            waitHidden()
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            instrumentation.waitForIdleSync()
            model.open(profile)
            assertEquals("Lifecycle draft", model.state.value.draft)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            waitHidden()
        } finally {
            instrumentation.uiAutomation.executeShellCommand(
                "input keyevent KEYCODE_WAKEUP"
            ).close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun endpointReviewAndPermissionNoticeRemainReachableAndBackPreservesDraft() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = PhoneConversation(context.applicationContext as Application)
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val profile =
            EndpointProfile(
                Endpoint.parse("wss://synthetic.example.invalid/gadget"),
                "Research",
                ""
            )
        model.open(profile)
        model.draft("Keep this draft")
        model.notice("Microphone permission was declined.")
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PhoneAppearance(Appearance.MOONLIGHT) {
                        PhoneScaffold { padding ->
                            ConversationPage(model, null, {
                            }, {}, model::hide, Modifier.padding(padding))
                        }
                    }
                }
            }
            fun find(text: String): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.isVisibleToUser && (
                            node.text?.toString() == text ||
                                node.contentDescription?.toString() == text
                            )
                    ) {
                        return node
                    }
                    for (index in 0 until node.childCount) {
                        walk(node.getChild(index))?.let {
                            return it
                        }
                    }
                    return null
                }
                return walk(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun click(text: String) {
                repeat(50) {
                    var node = find(text)
                    if (node != null) {
                        while (node != null && !node.isClickable) node = node.parent
                        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                        return
                    }
                    SystemClock.sleep(40)
                }
                error("Missing native control: $text")
            }
            click("Connection details")
            repeat(50) {
                if (find("wss://synthetic.example.invalid/gadget") == null) SystemClock.sleep(40)
            }
            assertTrue(find("wss://synthetic.example.invalid/gadget") != null)
            click("Close")
            click("Back to profiles")
            assertTrue(!model.state.value.visible)
            model.open(profile)
            assertEquals("Keep this draft", model.state.value.draft)
        } finally {
            model.hide()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
