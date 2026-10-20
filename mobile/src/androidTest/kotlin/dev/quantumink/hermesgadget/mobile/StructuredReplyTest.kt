package dev.quantumink.hermesgadget.mobile

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.ReplyPlatformActions
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.Conversation
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.Prompt
import dev.quantumink.hermesgadget.protocol.ReplyBlock
import dev.quantumink.hermesgadget.protocol.ReplyPresentation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReplyTest {
    private val source = "A useful plan.\n```kotlin\nval café = 1\n\n```\n" +
        "[Helpful label](https://example.invalid/path?q=one)\n" +
        "Address: 42 Main St & q=evil#? + Café\n" +
        "- [ ] Pack a notebook\n- [x] Bring water\nTail retained."

    @Test fun clipboardAndFixedChooserAreDeliberateAndMissingHandlersKeepAnswer() {
        ReplyNativeFixture().use { ui ->
            val actions = ReplyPlatformActions(ui.intercepted) { true }
            ui.show {
                PhoneAppearance(Appearance.DARK) {
                    androidx.compose.material3.Surface {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(
                                rememberScrollState()
                            ).padding(16.dp)
                        ) {
                            StructuredReply(
                                ConversationState(reply = source),
                                "synthetic-a",
                                true,
                                actions
                            )
                        }
                    }
                }
            }
            assertTrue(ui.launches.isEmpty())
            ui.click("Copy code")
            lateinit var clip: android.content.ClipData
            ui.instrumentation.runOnMainSync {
                clip = ui.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            }
            assertEquals("val café = 1\n\n", clip.getItemAt(0).text.toString())
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                assertTrue(
                    clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")
                )
            } else {
                assertNull(clip.description.extras)
            }
            ui.click("Open link")
            assertEquals(1, ui.launches.size)
            val link = view(ui.launches.last())
            assertEquals("https://example.invalid/path?q=one", link.data.toString())
            ui.click("Open address")
            val geo = view(ui.launches.last())
            assertEquals(
                "geo:0,0?q=" + Uri.encode("42 Main St & q=evil#? + Café"),
                geo.data.toString()
            )
            ui.failLaunch = true
            ui.top()
            ui.click("Open link")
            ui.top()
            ui.reach("No app can open this link. Copy the full response.")
            ui.capture("phone-no-handler")
            ui.top()
            ui.click("Full text")
            assertNotNull(ui.find(source))
            ui.top()
            ui.click("Copy full response")
            ui.instrumentation.runOnMainSync {
                assertEquals(
                    source,
                    ui.activity.getSystemService(ClipboardManager::class.java)
                        .primaryClip!!.getItemAt(0).text.toString()
                )
            }
            assertEquals(2, ui.launches.size)
            ui.capture("phone-full-text")
        }
    }

    @Test fun maximumPlainResponseRetainsTailAndExactFullCopy() {
        ReplyNativeFixture().use { ui ->
            val text = "a".repeat(399) + "😀" + "b".repeat(32354) + "TAIL-Ω-32768!"
            assertEquals(32768, text.length)
            val state = ConversationState(reply = text)
            ui.show {
                PhoneAppearance(Appearance.MOONLIGHT) {
                    androidx.compose.material3.Surface {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(
                                rememberScrollState()
                            ).padding(16.dp)
                        ) {
                            StructuredReply(state, "maximum", true)
                        }
                    }
                }
            }
            ui.click("Full text")
            ui.click("Copy full response")
            ui.instrumentation.runOnMainSync {
                assertEquals(
                    text,
                    ui.activity.getSystemService(ClipboardManager::class.java)
                        .primaryClip!!.getItemAt(0).text.toString()
                )
            }
            assertTrue(ui.activity.resources.configuration.screenWidthDp <= 320)
            assertTrue(ui.activity.resources.configuration.fontScale >= 1.79f)
            ui.captureTail("fix1-phone-maximum-tail", text, "TAIL-Ω-32768!")
            assertTrue(ui.launches.isEmpty())
        }
    }

    @Test fun fix1FullTextRoundTripRetainsChecksUntilIdenticalPublication() {
        ReplyNativeFixture().use { ui ->
            val reducer = Conversation().apply { setPaired(true) }
            val text = "- [ ] Pack a notebook\n- [x] Bring water"
            fun publish() {
                reducer.receive(Message.create("reply", buildJsonObject { put("text", text) }), 0)
            }
            publish()
            val state = mutableStateOf(reducer.state)
            ui.show {
                PhoneAppearance(Appearance.DARK) {
                    androidx.compose.material3.Surface {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(
                                rememberScrollState()
                            ).padding(16.dp)
                        ) {
                            StructuredReply(state.value, "fix1-mounted-profile", true)
                        }
                    }
                }
            }
            ui.capture("fix1-i1-initial-debug")
            val check = "Local check: Pack a notebook"
            assertFalse(ui.checked(check))
            ui.click(check)
            assertTrue(ui.checked(check))
            ui.top()
            ui.click("Full text")
            assertNotNull(ui.find(text))
            ui.click("Show cards")
            assertTrue("Same reply must retain the local edit after full text", ui.checked(check))
            publish()
            state.value = reducer.state
            ui.await { !ui.checked(check) }
            assertTrue(ui.checked("Local check: Bring water"))
            assertTrue(ui.launches.isEmpty())
        }
    }

    @Test fun fix1HostAndChecksFitActualSafeViewportAllThemes() {
        ReplyNativeFixture().use { ui ->
            assertTrue(ui.activity.resources.configuration.screenWidthDp <= 320)
            assertTrue(ui.activity.resources.configuration.fontScale >= 1.79f)
            Appearance.entries.forEach { theme ->
                val state = ConversationState(reply = source)
                ui.show {
                    androidx.compose.runtime.key(theme) {
                        PhoneAppearance(theme) {
                            androidx.compose.material3.Surface {
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(
                                        rememberScrollState()
                                    ).padding(16.dp)
                                ) {
                                    StructuredReply(state, "fix1-safe-viewport", true)
                                }
                            }
                        }
                    }
                }
                ui.captureTarget("fix1-phone-${theme.name}-host", "HTTPS host: example.invalid")
                ui.captureTarget("fix1-phone-${theme.name}-check", "Local check: Pack a notebook")
                assertFalse(ui.checked("Local check: Pack a notebook", refresh = true))
                ui.tapFully("Local check: Pack a notebook")
                assertTrue(ui.checked("Local check: Pack a notebook", refresh = true))
                assertTrue(ui.launches.isEmpty())
            }
        }
    }

    @Test fun fix2SafeViewportTracksActualKeyboard() {
        ReplyNativeFixture().use { ui ->
            lateinit var editor: android.widget.EditText
            ui.show {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        android.widget.EditText(context).apply {
                            setText("IME geometry probe")
                            editor = this
                        }
                    }
                )
            }
            val before = ui.safeViewport()
            ui.instrumentation.runOnMainSync {
                editor.requestFocus()
                ui.activity.getSystemService(
                    android.view.inputmethod.InputMethodManager::class.java
                )
                    .showSoftInput(
                        editor,
                        0
                    )
            }
            val visible = android.graphics.Rect()
            ui.await {
                ui.instrumentation.runOnMainSync {
                    ui.activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                }
                visible.bottom < before.bottom - 100
            }
            val withKeyboard = ui.safeViewport()
            val receipt = java.io.File(ui.captures, "fix2-api29-ime.json")
            receipt.writeText(
                org.json.JSONObject().apply {
                    put("sdk", android.os.Build.VERSION.SDK_INT)
                    put("before", before.toShortString())
                    put("withKeyboard", withKeyboard.toShortString())
                    put("actualVisibleWindow", visible.toShortString())
                }.toString(2)
            )
            ui.instrumentation.sendStatus(
                0,
                android.os.Bundle().apply { putString("n6_ime", receipt.path) }
            )
            assertTrue(withKeyboard.height() > 0)
            assertTrue(withKeyboard.bottom <= visible.bottom)
            assertTrue(withKeyboard.bottom < before.bottom - 100)
            ui.capture("fix2-api29-ime")
            ui.instrumentation.runOnMainSync {
                ui.activity.getSystemService(
                    android.view.inputmethod.InputMethodManager::class.java
                )
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
            ui.await { ui.safeViewport() == before }
        }
    }

    @Test fun processLocalChecksStartUnchecked() {
        ReplyNativeFixture().use { ui ->
            val state = ConversationState(reply = "- [ ] Process-local item")
            ui.show {
                PhoneAppearance(Appearance.MOONLIGHT) {
                    androidx.compose.material3.Surface {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(
                                rememberScrollState()
                            ).padding(16.dp)
                        ) {
                            StructuredReply(state, "process", true)
                        }
                    }
                }
            }
            val label = "Local check: Process-local item"
            assertFalse(ui.checked(label))
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("n6_process") == "seed"
            ) {
                ui.click(label)
                assertTrue(ui.checked(label))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun view(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertNull(intent.selector)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags)
        return intent
    }

    @Test fun absentResolverNeverDispatchesAndCodeCannotBecomeAnIntent() {
        ReplyNativeFixture().use { ui ->
            var probes = 0
            val actions = ReplyPlatformActions(ui.intercepted) {
                probes++
                false
            }
            val parsed = ReplyPresentation.parse(source) as ReplyPresentation.Accepted
            val link = parsed.blocks.filterIsInstance<ReplyBlock.Link>().single().action
            val code = parsed.blocks.filterIsInstance<ReplyBlock.Code>().single().action
            assertFalse(actions.open(link))
            assertFalse(actions.open(code))
            assertEquals(1, probes)
            assertTrue(ui.launches.isEmpty())
        }
    }

    @Test fun localChecksResetForEqualTurnsProfilesReconnectAndRemountWhileLocksHold() {
        ReplyNativeFixture().use { ui ->
            val reducer = Conversation().apply { setPaired(true) }
            fun event(type: String, turn: String, text: String? = null) = Message.create(
                type,
                buildJsonObject {
                    put("turn", turn)
                    text?.let { put("text", it) }
                }
            )
            val text = "- [ ] Pack a notebook\n- [x] Bring water"
            fun turn(id: String, time: Long) {
                reducer.receive(event("turn.start", id), time)
                reducer.receive(event("reply", id, text), time + 1)
                reducer.receive(event("turn.end", id), time + 2)
            }
            turn("one", 1)
            val state = mutableStateOf(reducer.state)
            val profile = mutableStateOf("profile-one")
            val enabled = mutableStateOf(true)
            fun render() {
                ui.show {
                    PhoneAppearance(Appearance.MOONLIGHT) {
                        androidx.compose.material3.Surface {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(
                                    rememberScrollState()
                                ).padding(16.dp)
                            ) {
                                StructuredReply(state.value, profile.value, enabled.value)
                            }
                        }
                    }
                }
            }
            render()
            val check = "Local check: Pack a notebook"
            assertFalse(ui.checked(check))
            assertTrue(ui.checked("Local check: Bring water"))
            ui.click(check)
            assertTrue(ui.checked(check))
            // Level/status redraws keep local checks; retired late replies do not replace them.
            reducer.receive(event("reply", "retired", "late"), 5)
            state.value = reducer.state.copy(level = .4f)
            assertTrue(ui.checked(check))
            turn("two", 10)
            state.value = reducer.state
            ui.await { !ui.checked(check) }
            ui.click(check)
            profile.value = "profile-two"
            ui.await { !ui.checked(check) }
            ui.click(check)
            reducer.disconnected(20)
            state.value = reducer.state
            ui.await { !ui.checked(check) }
            ui.click(check)
            ui.recreate()
            render()
            assertFalse(ui.checked(check))
            state.value = state.value.copy(replyChanging = true)
            ui.await { !ui.enabled(check) }
            state.value = state.value.copy(replyChanging = false, busy = true)
            ui.await { !ui.enabled(check) }
            state.value =
                state.value.copy(busy = false, prompt = Prompt("q", "Review", "Continue?", 0, null))
            ui.await { !ui.enabled(check) }
            state.value = state.value.copy(prompt = null)
            enabled.value = false
            ui.await { !ui.enabled(check) }
            assertTrue(ui.launches.isEmpty())
        }
    }

    @Test fun allThemesKeepCardsActualHostAndMalformedFallbackReadable() {
        ReplyNativeFixture().use { ui ->
            Appearance.entries.forEach { theme ->
                val state = mutableStateOf(ConversationState(reply = source))
                ui.show {
                    PhoneAppearance(theme) {
                        androidx.compose.material3.Surface {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(
                                    rememberScrollState()
                                ).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StructuredReply(state.value, "synthetic", true)
                            }
                        }
                    }
                }
                ui.reach("HTTPS host: example.invalid")
                ui.capture("phone-${theme.name}-host")
                ui.reach("Local check: Pack a notebook")
                ui.capture("phone-${theme.name}-checks")
                ui.top()
                ui.click("Full text")
                assertNotNull(ui.find(source))
                ui.capture("phone-${theme.name}-plain")
                val malformed = "```unclosed\nintent://bad/#Intent;component=bad;end\n" +
                    "- [ ] stays plain\nTAIL Ω"
                state.value = ConversationState(reply = malformed)
                ui.await { ui.find(malformed) != null }
                ui.capture("phone-${theme.name}-fallback")
                assertTrue(ui.launches.isEmpty())
            }
        }
    }

    @Test fun conversationInsertionKeepsComposerApprovalAndPairingLocks() {
        ReplyNativeFixture().use { ui ->
            val profile =
                EndpointProfile(
                    Endpoint.parse("wss://synthetic.example.invalid/gadget"),
                    "Research",
                    ""
                )
            val state = mutableStateOf(
                PhoneConversationState(
                    profile = profile,
                    visible = true,
                    draft = "Keep draft",
                    connection = ConnectionState(
                        status = ConnectionStatus.PAIRED,
                        conversation = ConversationState(reply = "- [ ] Pack a notebook")
                    )
                )
            )
            var sent = 0
            var answers = 0
            ui.show {
                PhoneAppearance(Appearance.DARK) {
                    androidx.compose.material3.Surface {
                        ConversationContent(
                            state.value, null, {
                            }, {}, {}, {}, { sent++ }, {}, {}, {}, {}, {},
                            { _, _ -> answers++ }
                        )
                    }
                }
            }
            val check = "Local check: Pack a notebook"
            ui.reach(check)
            assertTrue(ui.enabled(check))
            state.value = state.value.copy(
                connection = state.value.connection.copy(
                    conversation = state.value.connection.conversation.copy(busy = true)
                )
            )
            ui.await { !ui.enabled(check) }
            state.value = state.value.copy(
                connection = state.value.connection.copy(
                    conversation = state.value.connection.conversation.copy(
                        busy = false,
                        prompt = Prompt("q", "Review", "Continue?", 0, null)
                    )
                )
            )
            ui.await { !ui.enabled(check) }
            ui.reach("Approve")
            assertFalse(ui.enabled("Approve"))
            state.value =
                state.value.copy(
                    connection = state.value.connection.copy(
                        status = ConnectionStatus.PAIRING,
                        pairingCode = "SYNTHETIC",
                        conversation = state.value.connection.conversation.copy(prompt = null)
                    )
                )
            ui.top()
            ui.reach("Pairing code: SYNTHETIC")
            assertEquals("Keep draft", state.value.draft)
            assertEquals(0, sent)
            assertEquals(0, answers)
            assertTrue(ui.launches.isEmpty())
        }
    }
}
