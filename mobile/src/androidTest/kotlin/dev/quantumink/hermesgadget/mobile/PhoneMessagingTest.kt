package dev.quantumink.hermesgadget.mobile

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhoneMessagingTest {
    @Test fun releaseShortPressAndPointerCancellationHaveOneTerminalOutcome() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        for (kind in listOf("release", "short", "cancel", "disconnect")) {
            NativePhoneFixture().use { ui ->
                val state = mutableStateOf(ready())
                var starts = 0
                var sends = 0
                var cancels = 0
                ui.show { modifier ->
                    ConversationControls(state.value, {}, {}, {
                        starts++
                        state.value = state.value.copy(
                            connection = state.value.connection.copy(
                                conversation = ConversationState(mode = ConversationMode.LISTENING)
                            )
                        )
                    }, { sends++ }, { cancels++ }, {}, {}, false, modifier)
                }
                ui.await { mic(ui) != null }
                val bounds = Rect().also(requireNotNull(mic(ui))::getBoundsInScreen)
                val start = SystemClock.uptimeMillis()
                fun event(action: Int) {
                    val event = MotionEvent.obtain(
                        start,
                        SystemClock.uptimeMillis(),
                        action,
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        0
                    )
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(ui.instrumentation.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                event(MotionEvent.ACTION_DOWN)
                if (kind == "short") {
                    SystemClock.sleep(30)
                } else {
                    repeat(5) { index ->
                        SystemClock.sleep(70)
                        state.value = state.value.copy(
                            connection = state.value.connection.copy(
                                conversation = state.value.connection.conversation.copy(
                                    level =
                                    index / 5f
                                )
                            )
                        )
                    }
                }
                if (kind == "disconnect") {
                    state.value =
                        state.value.copy(
                            connection = ConnectionState(status = ConnectionStatus.OFFLINE)
                        )
                    SystemClock.sleep(100)
                }
                event(if (kind == "cancel") MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
                ui.instrumentation.waitForIdleSync()
                assertEquals(kind, 1, starts)
                assertEquals(kind, if (kind == "release") 1 else 0, sends)
                assertEquals(kind, if (kind == "release") 0 else 1, cancels)
            }
        }
    }

    @Test fun accessibilityCanStartFinishAndCancelWithoutHoldingOrDoubleSending() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val state = mutableStateOf(ready())
            var starts = 0
            var sends = 0
            var cancels = 0
            ui.show { modifier ->
                ConversationControls(state.value, {}, {}, {
                    starts++
                    state.value = state.value.copy(
                        connection = state.value.connection.copy(
                            conversation = ConversationState(mode = ConversationMode.LISTENING)
                        )
                    )
                }, { sends++ }, { cancels++ }, {}, {}, false, modifier)
            }
            ui.click("Hold to record", true)
            ui.click("Cancel recording", true)
            state.value = ready()
            ui.await { ui.find("Hold to record", true) != null }
            ui.click("Hold to record", true)
            ui.click("Finish and send recording", true)
            val stale = requireNotNull(ui.find("Finish and send recording", true))
            assertFalse(
                stale.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            )
            assertEquals(2, starts)
            assertEquals(1, sends)
            assertEquals(1, cancels)
        }
    }

    @Test fun micSharesTheTextFieldsBaselineInsteadOfHangingBelowSend() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            ui.show { modifier ->
                ConversationControls(ready(), {}, {}, {}, {}, {}, {}, {}, false, modifier)
            }
            ui.await { mic(ui) != null }
            val bounds = Rect().also(requireNotNull(mic(ui))::getBoundsInScreen)
            val density = ui.context.resources.displayMetrics.density
            assertTrue(
                "Mic is still a standalone full-width surface: $bounds",
                bounds.width() <= 72 * density
            )
        }
    }

    @Test fun slideLeftCancelsWithoutSendingEvenAfterReturningToTheMic() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val state = mutableStateOf(ready())
            var starts = 0
            var sends = 0
            var cancels = 0
            ui.show { modifier ->
                ConversationControls(state.value, {}, {}, {
                    starts++
                    state.value = state.value.copy(
                        connection = state.value.connection.copy(
                            conversation = ConversationState(mode = ConversationMode.LISTENING)
                        )
                    )
                }, { sends++ }, { cancels++ }, {}, {}, false, modifier)
            }
            ui.await { mic(ui) != null }
            val bounds = Rect().also(requireNotNull(mic(ui))::getBoundsInScreen)
            val start = SystemClock.uptimeMillis()
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            fun event(action: Int, atX: Float) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, atX, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(ui.instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            event(MotionEvent.ACTION_DOWN, x)
            SystemClock.sleep(400)
            event(MotionEvent.ACTION_MOVE, x - 75 * ui.context.resources.displayMetrics.density)
            event(MotionEvent.ACTION_MOVE, x)
            event(MotionEvent.ACTION_UP, x)
            ui.instrumentation.waitForIdleSync()
            assertEquals(1, starts)
            assertEquals(1, cancels)
            assertEquals(0, sends)
        }
    }

    @Test fun visibleCancelCannotBeFollowedByReleaseToSend() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val state = mutableStateOf(ready())
            var sent = 0
            var cancelled = 0
            ui.show { modifier ->
                ConversationControls(state.value, {}, {}, {
                    state.value = state.value.copy(
                        connection = state.value.connection.copy(
                            conversation = ConversationState(mode = ConversationMode.LISTENING)
                        )
                    )
                }, { sent++ }, { cancelled++ }, {}, {}, false, modifier)
            }
            ui.await { mic(ui) != null }
            val bounds = Rect().also(requireNotNull(mic(ui))::getBoundsInScreen)
            val start = SystemClock.uptimeMillis()
            fun event(action: Int) {
                val event = MotionEvent.obtain(
                    start,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0
                )
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(ui.instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            event(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(400)
            ui.click("Cancel recording", description = true)
            event(MotionEvent.ACTION_UP)
            ui.instrumentation.waitForIdleSync()
            assertEquals(1, cancelled)
            assertEquals(0, sent)
        }
    }

    private fun mic(ui: NativePhoneFixture) =
        ui.find("Hold to record", description = true) ?: ui.find("Hold to talk")

    private fun ready() = PhoneConversationState(
        visible = true,
        connection = ConnectionState(status = ConnectionStatus.PAIRED)
    )
}
