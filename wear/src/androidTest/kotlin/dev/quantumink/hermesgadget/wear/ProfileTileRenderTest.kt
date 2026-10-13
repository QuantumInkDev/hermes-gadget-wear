package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.tiles.renderer.TileRenderer
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Renders the production ProtoLayout tree, without a service, socket or stored profile. */
class ProfileTileRenderTest {
    @Test fun focusAndGridRenderNativeStaticResourcesWithReachableTargets() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val directory = File(context.cacheDir, "tile-render-${System.nanoTime()}")
        val cache = PortraitCache(directory)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(125, 221, 210))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(29, 38, 61)
        }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawCircle(88f, 100f, 16f, paint)
        canvas.drawCircle(168f, 100f, 16f, paint)
        canvas.drawRoundRect(78f, 150f, 178f, 170f, 10f, 10f, paint)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
        cache.store(
            "synthetic:alpha",
            PortraitDescriptor(sha, bytes.size, "/assets/$sha.png"),
            bytes
        )
        val pages = listOf("alpha" to "Alpha", "beta" to "Beta").map { (key, label) ->
            CatalogPage(
                "synthetic",
                "Original fixture",
                CatalogProfile(key, label, null, null),
                if (key == "alpha") CatalogStatus.OFFLINE else CatalogStatus.AVAILABLE,
                1
            )
        }
        val snapshot = ProfileTileSnapshot.build(
            TilePins(
                1,
                "synthetic:alpha",
                listOf(
                    "synthetic:alpha",
                    "synthetic:beta",
                    "synthetic:removed",
                    null
                )
            ),
            ProfilePages.merge(emptyList(), pages),
            cache
        )
        assertTrue(
            "Production normalization must retain transparent corners",
            requireNotNull(snapshot.focus.pixels).take(4).all { it == 0.toByte() }
        )
        try {
            for (grid in listOf(false, true)) {
                val layout = ProfileTileLayout.tile(context, snapshot, grid)
                    .tileTimeline!!.timelineEntries.single().layout!!
                lateinit var parent: FrameLayout
                lateinit var renderer: TileRenderer
                lateinit var future: com.google.common.util.concurrent.ListenableFuture<View>
                instrumentation.runOnMainSync {
                    parent = FrameLayout(activity)
                    activity.setContentView(parent)
                    renderer = TileRenderer(instrumentation.context, activity.mainExecutor) {
                        error("Static rendering must not request a load action")
                    }
                    future = renderer.inflateAsync(
                        layout,
                        ProfileTileLayout.resources(snapshot),
                        parent
                    )
                }
                future.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                instrumentation.runOnMainSync {
                    val views = walk(parent)
                    val targets = views.filter { it.isClickable }
                    assertEquals(if (grid) 4 else 1, targets.size)
                    val density = activity.resources.displayMetrics.density
                    val bounds = targets.map { target ->
                        Rect().also { rect ->
                            assertTrue(target.getGlobalVisibleRect(rect))
                            assertTrue(rect.width() >= 48 * density)
                            assertTrue(rect.height() >= 48 * density)
                        }
                    }
                    for (first in bounds.indices) {
                        for (second in first + 1 until bounds.size) {
                            assertTrue(!Rect.intersects(bounds[first], bounds[second]))
                        }
                    }
                    val texts = views.filterIsInstance<TextView>()
                    assertTrue(texts.any { it.text.toString() == "Alpha" })
                    texts.filter { it.text.isNotEmpty() }.forEach {
                        assertTrue("Native text must be visible", it.getGlobalVisibleRect(Rect()))
                        assertTrue("Native text must have measured lines", it.lineCount > 0)
                    }
                }
                val name = if (grid) "tile-pinned-profiles" else "tile-focus"
                val directory = File(context.filesDir, "native-acceptance").apply { mkdirs() }
                val capture = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(directory, "$name.png").outputStream().use {
                    capture.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                capture.recycle()
                instrumentation.runOnMainSync { parent.removeAllViews() }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            directory.deleteRecursively()
        }
    }

    private fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { walk(view.getChildAt(it)) }
    } else {
        emptyList()
    }
}
