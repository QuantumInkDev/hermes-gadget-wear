package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ConversationScreenWindowTest {
    @Test fun actualWindowClearsForCompletionAmbientHiddenDisposalAndOriginalDeadline() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val started = mutableStateOf<Long?>(SystemClock.elapsedRealtime())
        val active = mutableStateOf(false)
        val ambient = mutableStateOf(false)
        val shown = mutableStateOf(true)
        val expired = AtomicInteger()
        fun held(): Boolean {
            var value = false
            instrumentation.runOnMainSync {
                value =
                    activity.window.attributes.flags and
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON !=
                    0
            }
            return value
        }
        fun await(expected: Boolean) {
            repeat(30) {
                if (held() == expected) return
                SystemClock.sleep(50)
            }
            assertEquals(expected, held())
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    if (shown.value) {
                        KeepConversationVisible(
                            activity.window,
                            started.value,
                            active.value,
                            ambient.value
                        ) {
                            expired.incrementAndGet()
                        }
                    }
                }
            }
            await(false)
            instrumentation.runOnMainSync { active.value = true }
            await(true)
            instrumentation.runOnMainSync { active.value = false }
            await(false)
            instrumentation.runOnMainSync {
                active.value = true
                ambient.value = true
            }
            await(false)
            instrumentation.runOnMainSync { ambient.value = false }
            await(true)
            instrumentation.runOnMainSync { shown.value = false }
            await(false)
            instrumentation.runOnMainSync { shown.value = true }
            await(true)
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            await(false)
            context.startActivity(
                Intent(
                    context,
                    AppearanceTestActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
            await(true)
            // Simulate a deadline near its cap using the same real monotonic origin and window.
            instrumentation.runOnMainSync { started.value = SystemClock.elapsedRealtime() - 119700 }
            await(true)
            SystemClock.sleep(450)
            await(false)
            assertEquals(1, expired.get())
            instrumentation.runOnMainSync {
                active.value = false
                active.value = true
            }
            assertFalse(held())
            instrumentation.runOnMainSync { started.value = SystemClock.elapsedRealtime() }
            await(true)
            assertTrue(held())
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }
}
