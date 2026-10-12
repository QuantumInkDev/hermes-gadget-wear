package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TileAmbientActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test fun unavailableTileRendersBlackAmbientAndRestoresNoticeOnWake() {
        assumeTrue(Build.MODEL.startsWith("sdk_"))
        val context = instrumentation.targetContext
        val appearance = AppearancePreferences(context)
        val original = appearance.mode.value
        try {
            for ((mode, key) in listOf(
                Appearance.LIGHT to "malformed/key",
                Appearance.DARK to "removed:profile"
            )) {
                appearance.set(mode)
                shell("input keyevent 224")
                val activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(TileProfileLaunch.EXTRA_PAGE_KEY, key)
                ) as MainActivity
                try {
                    await("$mode unavailable notice before ambient") {
                        nodes().any { it.text?.toString() == "Profile unavailable" }
                    }
                    shell("input keyevent 223")
                    await("$mode actual AmbientPet output") {
                        nodes().any {
                            it.contentDescription?.toString() ==
                                "Quantum Relay for Hermes ambient profile. Wake the watch to interact."
                        }
                    }
                    val ambientNodes = nodes()
                    assertFalse(ambientNodes.any { it.text?.toString() == "Profile unavailable" })
                    assertFalse(
                        "Ambient must have no interactive actions",
                        ambientNodes.any {
                            it.isClickable && it.isVisibleToUser
                        }
                    )
                    val bitmap = requireNotNull(automation.takeScreenshot())
                    try {
                        var black = 0
                        var sampled = 0
                        for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 2) {
                            for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 2) {
                                val pixel = bitmap.getPixel(x, y)
                                if (Color.red(pixel) < 8 && Color.green(pixel) < 8 &&
                                    Color.blue(pixel) < 8
                                ) {
                                    black++
                                }
                                sampled++
                            }
                        }
                        assertTrue(
                            "$mode ambient canvas must be black: $black/$sampled",
                            black > sampled * 0.9
                        )
                    } finally {
                        bitmap.recycle()
                    }
                    shell("input keyevent 224")
                    await("$mode unavailable notice restored on wake") {
                        nodes().any { it.text?.toString() == "Profile unavailable" }
                    }
                    assertFalse(
                        nodes().any {
                            it.contentDescription?.toString()?.contains("ambient profile") == true
                        }
                    )
                } finally {
                    shell("input keyevent 224")
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            appearance.set(original)
        }
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(descend(it)) }
        }
        return automation.rootInActiveWindow?.let(::descend).orEmpty()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        repeat(60) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Missing $message; visible nodes=${nodes().map { it.text ?: it.contentDescription }}")
    }
}
