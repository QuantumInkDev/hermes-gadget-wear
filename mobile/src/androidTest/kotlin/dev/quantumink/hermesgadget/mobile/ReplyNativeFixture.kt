package dev.quantumink.hermesgadget.mobile

import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue

/** Task2-only synthetic host. Never opens the production service, vault or network. */
internal class ReplyNativeFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    init {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val emulator = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText().trim() }
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && emulator == "1")
    }

    var activity = instrumentation.startActivitySync(
        Intent(target, AppearanceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as AppearanceTestActivity
        private set
    val launches = mutableListOf<Intent>()
    var failLaunch = false
    val intercepted = object : ContextWrapper(activity) {
        override fun startActivity(intent: Intent) {
            if (failLaunch) {
                throw android.content.ActivityNotFoundException(
                    "Synthetic missing handler"
                )
            }
            launches += intent
        }
    }
    val captures = File(target.cacheDir, "n6-task2-fix2-" + java.util.UUID.randomUUID()).apply {
        mkdirs()
    }

    fun show(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync {
            activity.setContent {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().safeDrawingPadding()
                ) { content() }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    fun recreate() {
        val previous = activity
        instrumentation.runOnMainSync { previous.recreate() }
        await {
            instrumentation.runOnMainSync {
                val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance().getActivitiesInStage(
                        androidx.test.runner.lifecycle.Stage.RESUMED
                    )
                resumed.filterIsInstance<AppearanceTestActivity>().firstOrNull {
                    it !== previous
                }?.let { activity = it }
            }
            activity !== previous
        }
    }

    fun find(text: String, refresh: Boolean = false): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (refresh && !node.refresh()) return null
            if (node.isVisibleToUser && (
                    node.text?.toString() == text ||
                        node.contentDescription?.toString() == text
                    )
            ) {
                return node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    fun await(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            SystemClock.sleep(30)
        }
        error("Task2 native assertion did not settle")
    }

    fun scroll(forward: Boolean, refresh: Boolean = false): Boolean {
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (refresh && !node.refresh()) return false
            val id = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.isScrollable && node.performAction(id)) return true
            for (i in 0 until node.childCount) if (walk(node.getChild(i))) return true
            return false
        }
        val moved = walk(instrumentation.uiAutomation.rootInActiveWindow)
        SystemClock.sleep(100)
        return moved
    }

    fun reach(text: String) {
        repeat(200) {
            if (find(text) != null) return
            scroll(true)
        }
        error("Task2 native control missing: $text")
    }

    fun top() {
        repeat(30) { if (!scroll(false)) return }
    }

    fun click(text: String) {
        reach(text)
        var node = find(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Task2 control disabled/missing: $text", node?.isEnabled == true)
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    @Suppress("DEPRECATION")
    fun checked(text: String, refresh: Boolean = false): Boolean {
        if (!refresh) reach(text)
        var value: Boolean? = null
        await {
            var node = find(text, refresh)
            while (node != null && !node.isCheckable) node = node.parent
            value = node?.isChecked
            value != null
        }
        return requireNotNull(value)
    }

    fun enabled(text: String): Boolean {
        reach(text)
        var value: Boolean? = null
        await {
            var node = find(text)
            while (node != null && !node.isClickable) node = node.parent
            value = node?.isEnabled
            value != null
        }
        return requireNotNull(value)
    }

    /** Physical screen coordinates after real system/IME/cutout insets and round-mask bounds. */
    fun safeViewport(): android.graphics.Rect {
        lateinit var safe: android.graphics.Rect
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val inset = requireNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            safe = android.graphics.Rect(
                origin[0] + inset.left + 2,
                origin[1] + inset.top + 2,
                origin[0] + view.width - inset.right - 2,
                origin[1] + view.height - inset.bottom - 2
            )
            if (activity.resources.configuration.isScreenRound) {
                val radius = minOf(view.width, view.height) / 2f - 2f
                val half = kotlin.math.floor(radius / kotlin.math.sqrt(2f)).toInt()
                val cx = origin[0] + view.width / 2
                val cy = origin[1] + view.height / 2
                check(
                    safe.intersect(
                        android.graphics.Rect(
                            cx - half,
                            cy - half,
                            cx + half,
                            cy + half
                        )
                    )
                )
            }
        }
        return safe
    }

    private fun fits(rect: android.graphics.RectF, safe: android.graphics.Rect): Boolean =
        rect.width() > 0 && rect.height() > 0 && rect.left >= safe.left &&
            rect.top >= safe.top && rect.right <= safe.right && rect.bottom <= safe.bottom

    private fun finger(action: Int, x: Float, y: Float, down: Long) {
        val properties = android.view.MotionEvent.PointerProperties().apply {
            id = 0
            toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = android.view.MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = android.view.MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    /** Short real pointer scrolls place partly visible targets inside the safe viewport. */
    private fun adjust(delta: Float) {
        val safe = safeViewport()
        val amount = delta.coerceIn(-safe.height() / 3f, safe.height() / 3f)
        val x = safe.exactCenterX()
        val start = safe.exactCenterY() + amount / 2
        val end = safe.exactCenterY() - amount / 2
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) = finger(action, x, y, down)
        event(android.view.MotionEvent.ACTION_DOWN, start)
        for (i in 1..4) {
            SystemClock.sleep(16)
            event(android.view.MotionEvent.ACTION_MOVE, start + (end - start) * i / 4)
        }
        SystemClock.sleep(150)
        event(android.view.MotionEvent.ACTION_MOVE, end)
        event(android.view.MotionEvent.ACTION_UP, end)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    fun reachFully(text: String): android.graphics.RectF {
        top()
        repeat(250) {
            val node = find(text, refresh = true)
            if (node == null) {
                adjust(safeViewport().height() / 3f)
            } else {
                val rect = android.graphics.RectF(
                    android.graphics.Rect().also(node::getBoundsInScreen)
                )
                val safe = safeViewport()
                if (fits(rect, safe)) return rect
                check(rect.width() <= safe.width() && rect.height() <= safe.height()) {
                    "Target cannot fit safe viewport: $rect / $safe"
                }
                adjust(rect.centerY() - safe.exactCenterY())
            }
        }
        capture("fix2-target-not-reached")
        error("Target never fully entered the native safe viewport: $text")
    }

    @Suppress("DEPRECATION")
    private fun characterBounds(text: String, suffix: String): List<android.graphics.RectF>? {
        check(text.endsWith(suffix))
        val node = find(text, refresh = true) ?: return null
        val args = android.os.Bundle().apply {
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,
                text.length - suffix.length
            )
            putInt(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
                suffix.length
            )
        }
        if (!node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                args
            )
        ) {
            return null
        }
        val array =
            node.extras.getParcelableArray(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
            )
                ?: return null
        if (array.size != suffix.length || array.any { it !is android.graphics.RectF }) return null
        return array.map { android.graphics.RectF(it as android.graphics.RectF) }
    }

    fun captureTail(name: String, fullNodeText: String, suffix: String) {
        repeat(700) {
            val boxes = characterBounds(fullNodeText, suffix)
            val safe = safeViewport()
            if (boxes != null && boxes.all { fits(it, safe) }) {
                val union = android.graphics.RectF(boxes.first())
                boxes.drop(1).forEach(union::union)
                captureGeometry(name, union, boxes)
                return
            }
            if (boxes != null && boxes.all { it.width() > 0 && it.height() > 0 }) {
                val union = android.graphics.RectF(boxes.first())
                boxes.drop(1).forEach(union::union)
                adjust(union.centerY() - safe.exactCenterY())
            } else {
                check(scroll(true, refresh = true)) {
                    "Maximum text tail unavailable after native scrolling reached its end"
                }
            }
        }
        capture("fix2-tail-not-reached")
        error("All tail glyphs were not fully visible inside native safe viewport")
    }

    fun captureTarget(name: String, text: String) {
        captureGeometry(name, reachFully(text), emptyList())
    }

    fun tapFully(text: String) {
        val rect = reachFully(text)
        val down = SystemClock.uptimeMillis()
        val actions = listOf(
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_UP
        )
        for (action in actions) {
            finger(action, rect.centerX(), rect.centerY(), down)
            SystemClock.sleep(40)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun captureGeometry(
        name: String,
        target: android.graphics.RectF,
        characters: List<android.graphics.RectF>
    ) {
        val safe = safeViewport()
        assertTrue("Target outside actual safe viewport", fits(target, safe))
        characters.forEach { assertTrue(fits(it, safe)) }
        val json = org.json.JSONObject().apply {
            put(
                "safeViewport",
                org.json.JSONArray(listOf(safe.left, safe.top, safe.right, safe.bottom))
            )
            put(
                "target",
                org.json.JSONArray(listOf(target.left, target.top, target.right, target.bottom))
            )
            put("round", activity.resources.configuration.isScreenRound)
            put("fontScale", activity.resources.configuration.fontScale)
            put("screenWidthDp", activity.resources.configuration.screenWidthDp)
            put("glyphCount", characters.size)
            put(
                "characterBounds",
                org.json.JSONArray(
                    characters.map {
                        org.json.JSONArray(listOf(it.left, it.top, it.right, it.bottom))
                    }
                )
            )
        }
        File(captures, "$name.bounds.json").writeText(json.toString(2))
        capture(name)
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                putString("n6_bounds", File(captures, "$name.bounds.json").path)
            }
        )
    }

    fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(captures, name + ".png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                putString("n6_capture", File(captures, name + ".png").path)
            }
        )
    }

    override fun close() {
        instrumentation.runOnMainSync {
            activity.setContent {}
            activity.finish()
        }
    }
}
