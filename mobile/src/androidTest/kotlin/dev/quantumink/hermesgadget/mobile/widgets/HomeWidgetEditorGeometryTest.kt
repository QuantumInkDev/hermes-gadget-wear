package dev.quantumink.hermesgadget.mobile.widgets

import android.content.Intent
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.AppearanceTestActivity
import dev.quantumink.hermesgadget.mobile.PhoneAppearance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetEditorGeometryTest {
    @Test fun fiveColumnEditorKeepsFortyEightDpTargetsOnASmallPhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val ready = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    PhoneAppearance(Appearance.LIGHT) {
                        Box(Modifier.width(320.dp)) {
                            HomeWidgetEditor(
                                WidgetConfig(
                                    HomeWidgetPreset.STRIP_FIVE
                                ),
                                emptyList(),
                                false,
                                "",
                                {
                                },
                                {},
                                {},
                                Modifier.fillMaxSize()
                            ) { _, _ -> }
                        }
                        SideEffect { ready.countDown() }
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            WidgetUi.await("Edit widget slot 1")
            (1..5).forEach { index ->
                val bounds = Rect().also(
                    requireNotNull(WidgetUi.find("Edit widget slot $index"))::getBoundsInScreen
                )
                assertTrue(
                    "Slot $index target width=${bounds.width()}",
                    bounds.width() / context.resources.displayMetrics.density >= 48
                )
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }
}
