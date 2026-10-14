package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

internal class WidgetHostFixture(context: Context) : AutoCloseable {
    val host = AppWidgetHost(context, java.util.Random().nextInt(Int.MAX_VALUE - 1) + 1)
    private val manager = AppWidgetManager.getInstance(context)
    private val context = context
    fun bind(preset: HomeWidgetPreset): Int {
        val id = host.allocateAppWidgetId()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            assertTrue(
                manager.bindAppWidgetIdIfAllowed(
                    id,
                    ComponentName(context.packageName, preset.className)
                )
            )
        } finally {
            automation.dropShellPermissionIdentity()
        }
        return id
    }
    override fun close() {
        host.deleteHost()
    }
}

internal object WidgetUi {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
    }
    fun find(label: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser &&
            (it.text?.toString() == label || it.contentDescription?.toString() == label)
    }
    fun await(label: String) {
        val until = SystemClock.uptimeMillis() + 5000
        while (find(label) == null && SystemClock.uptimeMillis() < until) SystemClock.sleep(40)
        assertTrue("Missing $label", find(label) != null)
    }
    fun click(label: String) {
        repeat(10) {
            if (find(label) != null) return@repeat
            nodes().lastOrNull { node ->
                node.actionList.any {
                    it.id ==
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        await(label)
        var node = requireNotNull(find(label))
        while (!node.isClickable) node = requireNotNull(node.parent)
        val box = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    box.exactCenterX(),
                    box.exactCenterY(),
                    0
                ).apply {
                    source =
                        android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
            )
        }
        instrumentation.uiAutomation.waitForIdle(100, 2000)
    }
}
