package dev.quantumink.hermesgadget.wear

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.ReplyPlatformActions
import dev.quantumink.hermesgadget.protocol.Conversation
import dev.quantumink.hermesgadget.protocol.ConversationState
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
            val state = ConversationState(reply = source)
            ui.show {
                val local = rememberWatchReply(state, "synthetic-a")
                WatchAppearance(Appearance.DARK) {
                    ReplyWatchSurface {
                        RoundPage { structuredReply(state, local, true, actions) }
                    }
                }
            }
            assertTrue(ui.launches.isEmpty())
            ui.click("Expand Code · kotlin")
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
            ui.click("Expand Link · example.invalid")
            ui.click("Open link")
            assertEquals(1, ui.launches.size)
            val link = view(ui.launches.last())
            assertEquals("https://example.invalid/path?q=one", link.data.toString())
            ui.click("Expand Address")
            ui.click("Open address")
            val geo = view(ui.launches.last())
            assertEquals(
                "geo:0,0?q=" + Uri.encode("42 Main St & q=evil#? + Café"),
                geo.data.toString()
            )
            ui.failLaunch = true
            ui.top()
            ui.click("Expand Link · example.invalid")
            ui.click("Open link")
            ui.top()
            ui.reach("No app can open this link. Copy the full response.")
            ui.capture("watch-no-handler")
            ui.top()
            ui.click("Full text")
            ui.reach(source)
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
            ui.capture("watch-full-text")
        }
    }

    @Test fun maximumPlainResponseRetainsTailAndExactFullCopy() {
        ReplyNativeFixture().use { ui ->
            val text = "a".repeat(399) + "😀" + "b".repeat(32354) + "TAIL-Ω-32768!"
            assertEquals(32768, text.length)
            val state = ConversationState(reply = text)
            ui.show {
                val local = rememberWatchReply(state, "maximum")
                WatchAppearance(Appearance.MOONLIGHT) {
                    RoundPage { structuredReply(state, local, true) }
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
            assertTrue(ui.activity.resources.configuration.isScreenRound)
            assertTrue(ui.activity.resources.configuration.fontScale >= 1.29f)
            ui.captureTail("fix1-watch-maximum-tail", text.takeLast(369), "TAIL-Ω-32768!")
            assertTrue(ui.launches.isEmpty())
        }
    }

    @Test fun fix1HostAndChecksFitActualSafeViewportAllThemes() {
        ReplyNativeFixture().use { ui ->
            assertTrue(ui.activity.resources.configuration.isScreenRound)
            assertTrue(ui.activity.resources.configuration.fontScale >= 1.29f)
            Appearance.entries.forEach { theme ->
                val state = ConversationState(reply = source)
                ui.show {
                    androidx.compose.runtime.key(theme) {
                        val local = rememberWatchReply(state, "fix1-safe-viewport")
                        WatchAppearance(theme) {
                            RoundPage { structuredReply(state, local, true) }
                        }
                    }
                }
                ui.click("Expand Link · example.invalid")
                ui.captureTarget(
                    "fix1-watch-${theme.name}-host",
                    "HTTPS host: example.invalid\nhttps://example.invalid/path?q=one"
                )
                ui.click("Expand Checklist · 2 items")
                ui.captureTarget("fix1-watch-${theme.name}-check", "Local check: Pack a notebook")
                assertFalse(ui.checked("Local check: Pack a notebook"))
                ui.click("Local check: Pack a notebook")
                assertTrue(ui.checked("Local check: Pack a notebook"))
                assertTrue(ui.launches.isEmpty())
            }
        }
    }

    @Test fun processLocalChecksStartUnchecked() {
        ReplyNativeFixture().use { ui ->
            val state = ConversationState(reply = "- [ ] Process-local item")
            ui.show {
                val local = rememberWatchReply(state, "process")
                WatchAppearance(Appearance.MOONLIGHT) {
                    RoundPage { structuredReply(state, local, true) }
                }
            }
            ui.click("Expand Checklist · 1 items")
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

    @Test fun realReplyInsertionKeepsPortraitClearJumpLastAndApprovalLocks() {
        ReplyNativeFixture().use { ui ->
            val profile = EndpointProfile(
                dev.quantumink.hermesgadget.protocol.Endpoint.parse(
                    "wss://synthetic.example.invalid/gadget"
                ),
                "Research",
                ""
            )
            val page = ProfilePage("n6-synthetic", "Research", "Synthetic fixture", profile, null)
            val portrait = android.graphics.Bitmap.createBitmap(
                160,
                160,
                android.graphics.Bitmap.Config.ARGB_8888
            )
                .apply { eraseColor(android.graphics.Color.rgb(40, 130, 190)) }
            val watch = mutableStateOf(
                WatchState(
                    profile = profile,
                    profiles = listOf(profile),
                    selectedPage = page.key,
                    artwork = mapOf(page.key to PageArtwork(portrait, null)),
                    artworkIdentities = mapOf(page.key to page.presentationKey()),
                    connection = dev.quantumink.hermesgadget.protocol.ConnectionState(
                        status = dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRED,
                        conversation = ConversationState(reply = "- [ ] Pack a notebook")
                    )
                )
            )
            // Uncreated callback receiver only; no service lifecycle, storage or transport runs.
            lateinit var callbacks: GadgetService
            ui.instrumentation.runOnMainSync { callbacks = GadgetService() }
            var jumped = false
            Appearance.entries.forEach { theme ->
                ui.show {
                    androidx.compose.runtime.key(theme) {
                        WatchAppearance(theme) {
                            ConversationPage(
                                watch.value, callbacks, {}, {}, {}, {}, {}, {}, page,
                                rememberTransformingLazyColumnState()
                            ) {
                                Secondary("Jump to profile", { jumped = true })
                            }
                        }
                    }
                }
                ui.top()
                ui.reach("Research profile picture")
                val bounds = android.graphics.Rect().also {
                    requireNotNull(ui.find("Research profile picture")).getBoundsInScreen(it)
                }
                assertEquals(320, bounds.width())
                assertEquals(320, bounds.height())
                assertNull(ui.find("Copy full response"))
                ui.capture("watch-${theme.name}-clear-portrait")
                ui.click("Expand Checklist · 1 items")
                assertFalse(ui.checked("Local check: Pack a notebook"))
                ui.scroll(true)
                ui.capture("watch-${theme.name}-integrated-reply")
                ui.reach("Jump to profile")
                ui.capture("watch-${theme.name}-final-jump")
                ui.click("Jump to profile")
                assertTrue(jumped)
                jumped = false
            }
            watch.value = watch.value.copy(
                connection = watch.value.connection.copy(
                    conversation = watch.value.connection.conversation.copy(
                        prompt = Prompt("q", "Review action", "Continue?", Long.MAX_VALUE, null)
                    )
                )
            )
            ui.top()
            ui.reach("Review action")
            assertFalse(ui.enabled("Yes"))
            assertNull(ui.find("Full text"))
            assertTrue(watch.value.selectionLocked)
            assertTrue(ui.launches.isEmpty())
            portrait.recycle()
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
                    val local = rememberWatchReply(state.value, profile.value)
                    WatchAppearance(Appearance.MOONLIGHT) {
                        ReplyWatchSurface {
                            RoundPage { structuredReply(state.value, local, enabled.value) }
                        }
                    }
                }
            }
            render()
            ui.click("Expand Checklist · 2 items")
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
            ui.top()
            ui.click("Expand Checklist · 2 items")
            assertFalse(ui.checked(check))
            ui.click(check)
            profile.value = "profile-two"
            ui.top()
            ui.click("Expand Checklist · 2 items")
            assertFalse(ui.checked(check))
            ui.click(check)
            reducer.disconnected(20)
            state.value = reducer.state
            ui.top()
            ui.click("Expand Checklist · 2 items")
            assertFalse(ui.checked(check))
            ui.click(check)
            ui.recreate()
            render()
            ui.click("Expand Checklist · 2 items")
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
                    val local = rememberWatchReply(state.value, "synthetic")
                    WatchAppearance(theme) {
                        ReplyWatchSurface {
                            RoundPage { structuredReply(state.value, local, true) }
                        }
                    }
                }
                ui.reach("Link · example.invalid")
                ui.capture("watch-${theme.name}-host")
                ui.click("Expand Checklist · 2 items")
                ui.reach("Local check: Pack a notebook")
                ui.capture("watch-${theme.name}-checks")
                ui.top()
                ui.click("Full text")
                ui.reach(source)
                ui.capture("watch-${theme.name}-plain")
                val malformed = "```unclosed\nintent://bad/#Intent;component=bad;end\n" +
                    "- [ ] stays plain\nTAIL Ω"
                state.value = ConversationState(reply = malformed)
                ui.await { ui.find(malformed) != null }
                ui.capture("watch-${theme.name}-fallback")
                assertTrue(ui.launches.isEmpty())
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReplyWatchSurface(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { content() }
}
