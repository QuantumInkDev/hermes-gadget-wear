package dev.quantumink.hermesgadget.mobile

import android.view.accessibility.AccessibilityNodeInfo
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionUiTest {
    private val action =
        QuickAction(UUID.randomUUID().toString(), "box:bot", "Review issue", "Explain {{issue}}")
    private val page =
        CatalogPage(
            "box",
            "Example box",
            CatalogProfile("bot", "Example bot", "wss://example.invalid/gadget", null),
            CatalogStatus.OFFLINE,
            0
        )

    @Test fun unboundAndWithdrawnActionsCannotPreviewOrTransfer() {
        NativePhoneFixture().use { fixture ->
            var transferred = false
            fixture.show(scroll = true) {
                QuickActionReview(action.copy(profileKey = null), listOf(page), false, { _, _, _ ->
                    transferred =
                        true
                }, {}, {})
            }
            assertNotNull(fixture.find("Choose a profile in Edit action before reviewing."))
            assertNull(fixture.find("Expanded prompt"))
            fixture.show(scroll = true) {
                QuickActionReview(action, emptyList(), false, { _, _, _ ->
                    transferred =
                        true
                }, {}, {})
            }
            assertNotNull(
                fixture.find(
                    "This profile is missing or withdrawn. Choose a profile in Edit action."
                )
            )
            assertFalse(transferred)
        }
    }

    @Test fun requiredInputExpandedReviewAndDeliberateTransferAcrossThemes() {
        NativePhoneFixture().use { fixture ->
            Appearance.entries.forEach { mode ->
                var result = ""
                fixture.show(mode, scroll = true) {
                    androidx.compose.runtime.key(mode) {
                        QuickActionReview(action, listOf(page), false, {
                                selected,
                                inputs,
                                current
                            ->
                            assertTrue(current())
                            result =
                                QuickActions.expand(selected, inputs)
                        }, {}, {})
                    }
                }
                fixture.scrollTo("Preview prompt")
                var previewButton = fixture.find("Preview prompt")!!
                while (!previewButton.isClickable &&
                    previewButton.parent != null
                ) {
                    previewButton = previewButton.parent
                }
                assertFalse(previewButton.isEnabled)
                fixture.scrollTo("issue")
                fixture.enter("Synthetic failure")
                fixture.scrollTo("Preview prompt")
                fixture.click("Preview prompt")
                fixture.await {
                    fixture.instrumentation.uiAutomation.windows.none { window ->
                        window.type ==
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                            window.root?.isVisibleToUser == true
                    }
                }
                scrollTop(fixture)
                fixture.scrollTo("Expanded prompt")
                assertNotNull(fixture.find("Explain Synthetic failure"))
                fixture.capture("quick-action-preview-${mode.name}")
                assertEquals("", result)
                fixture.scrollTo("Use prompt in Talk/Chat")
                fixture.click("Use prompt in Talk/Chat")
                assertEquals("Explain Synthetic failure", result)
            }
        }
    }

    @Test fun nativeManagementCreatesEditsReordersAndRemoves() {
        NativePhoneFixture().use { fixture ->
            val actions = androidx.compose.runtime.mutableStateOf(
                listOf(action, action.copy(id = UUID.randomUUID().toString(), title = "Second"))
            )
            val editor = androidx.compose.runtime.mutableStateOf<QuickAction?>(null)
            fixture.show(destination = "settings", scroll = true) {
                val current = editor.value
                if (current != null) {
                    QuickActionEditor(current, listOf(page), true, { changed ->
                        actions.value = actions.value.filterNot { it.id == changed.id } + changed
                        editor.value = null
                    }, { editor.value = null })
                } else {
                    QuickActionsPage(actions.value, listOf(page), true, {
                        actions.value = it
                    }, {}, {
                        editor.value =
                            it
                    }, {}, {})
                }
            }
            fixture.click("Create action")
            android.os.SystemClock.sleep(200)
            scrollTop(fixture)
            fixture.scrollTo("Action title")
            fixture.enter("Created action")
            fixture.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            android.os.SystemClock.sleep(250)
            fixture.scrollTo("Profile: Choose explicitly")
            fixture.click("Profile: Choose explicitly")
            fixture.click("Example bot · Example box · offline · box:bot")
            fixture.scrollTo("Save action")
            fixture.click("Save action")
            fixture.await { actions.value.size == 3 }
            assertEquals("box:bot", actions.value.last().profileKey)
            fixture.scrollTo("Move Created action up")
            fixture.click("Move Created action up")
            assertEquals("Created action", actions.value[1].title)
            scrollTop(fixture)
            fixture.scrollTo("Edit Created action")
            fixture.click("Edit Created action")
            android.os.SystemClock.sleep(200)
            scrollTop(fixture)
            fixture.scrollTo("Action title")
            fixture.enter("Edited action")
            fixture.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            android.os.SystemClock.sleep(250)
            fixture.scrollTo("Save action")
            fixture.click("Save action")
            fixture.await { actions.value.last().title == "Edited action" }
            fixture.scrollTo("Remove Edited action")
            fixture.click("Remove Edited action")
            assertEquals(2, actions.value.size)
            assertFalse(actions.value.any { it.title == "Edited action" })
        }
    }
    private fun scrollTop(fixture: NativePhoneFixture) {
        repeat(20) {
            dev.quantumink.hermesgadget.mobile.widgets.WidgetUi.nodes().lastOrNull {
                it.isScrollable &&
                    it.actionList.any { action ->
                        action.id ==
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
            }?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            android.os.SystemClock.sleep(30)
        }
        fixture.instrumentation.waitForIdleSync()
    }
}
