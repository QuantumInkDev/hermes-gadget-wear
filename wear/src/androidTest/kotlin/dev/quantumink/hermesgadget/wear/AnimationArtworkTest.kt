package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.MaterialTheme
import dev.quantumink.hermesgadget.client.AnimationCache
import dev.quantumink.hermesgadget.client.GifDecoder
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.GifPortrait
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AnimationArtworkTest {
    private val frameColors = setOf(0xff7dddd2.toInt(), 0xff9dabff.toInt())
    private fun instrumentationContext() = InstrumentationRegistry.getInstrumentation().context

    // The 8x8 fixture alternates palette pixels. Sample a pixel center rather than the
    // enlarged image's center boundary, where interpolation blends both palette colors.
    private fun samplePoint(description: String): Pair<Int, Int> {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.graphics.Rect? {
            node ?: return null
            if (node.contentDescription?.toString() == description) {
                return android.graphics.Rect().also(node::getBoundsInScreen)
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(30) {
            find(automation.rootInActiveWindow)?.let {
                return (it.left + it.width() * 5 / 16) to (it.top + it.height() * 5 / 16)
            }
            SystemClock.sleep(50)
        }
        error("Picture is not accessible: $description")
    }

    @Test fun availableUnapprovedSelectedCarouselCardAnimates() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = java.util.concurrent.CountDownLatch(1)
        val service = java.util.concurrent.atomic.AtomicReference<GadgetService>()
        val binding = object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName,
                binder: android.os.IBinder
            ) {
                service.set((binder as GadgetService.LocalBinder).service)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, GadgetService::class.java),
                binding,
                android.content.Context.BIND_AUTO_CREATE
            )
        )
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val directory = File(context.cacheDir, "carousel-animation-" + UUID.randomUUID())
        val gif = GifPortrait.normalize(
            instrumentationContext().assets.open("original-two-frame.gif")
                .use { it.readBytes() }
        ).bytes
        val descriptor = AnimationDescriptor(AnimationCache.sha256(gif), gif.size, "a".repeat(64))
        val cache = AnimationCache(directory)
        cache.store(descriptor, gif)
        val portrait = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val page = dev.quantumink.hermesgadget.protocol.CatalogPage(
            "synthetic",
            "Synthetic box",
            dev.quantumink.hermesgadget.protocol.CatalogProfile(
                "gif",
                "Unapproved fixture",
                null,
                null
            ),
            dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE,
            0
        )
        val watch = WatchState(
            catalog = listOf(page),
            selectedPage = page.key,
            artwork = mapOf(page.key to PageArtwork(portrait, null, cache.file(descriptor)))
        )
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        ProfileCarousel(watch, WatchNavigation(), service.get(), {
                        }, {}, { _, _, _ -> })
                    }
                }
            }
            val colors = mutableSetOf<Int>()
            val observed = mutableSetOf<Int>()
            val point = samplePoint("Unapproved fixture profile picture")
            repeat(40) {
                SystemClock.sleep(40)
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val pixel = screenshot.getPixel(point.first, point.second)
                observed.add(pixel)
                pixel.takeIf { it in frameColors }
                    ?.let { colors.add(it) }
                screenshot.recycle()
            }
            assertEquals(
                "Selected unapproved carousel card did not animate: " +
                    observed.map { Integer.toHexString(it) },
                frameColors,
                colors
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            context.unbindService(binding)
            portrait.recycle()
            directory.deleteRecursively()
        }
    }

    @Test fun selectedGifMovesWhilePreviewAndPetRemainStaticAndBadAssetRetainsFallback() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "animation-" + UUID.randomUUID())
        val cache = AnimationCache(directory)
        val gif = GifPortrait.normalize(
            instrumentationContext().assets.open("original-two-frame.gif").use {
                it.readBytes()
            }
        ).bytes
        val descriptor = AnimationDescriptor(AnimationCache.sha256(gif), gif.size, "a".repeat(64))
        cache.store(descriptor, gif)
        assertNotNull(cache.file(descriptor))
        assertThrows(Exception::class.java) { cache.store(descriptor, gif.copyOf(gif.size - 1)) }
        assertNotNull(cache.file(descriptor))
        val drawable = GifDecoder.drawable(gif)
        assertTrue(drawable is android.graphics.drawable.AnimatedImageDrawable)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val portrait = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val artwork = mutableStateOf(PageArtwork(portrait, null, cache.file(descriptor)))
        val selected = mutableStateOf(true)
        fun shell(command: String): String =
            instrumentation.uiAutomation.executeShellCommand(command)
                .use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        it
                    ).bufferedReader().readText().trim()
                }
        val motionScale = shell("settings get global animator_duration_scale")
        var changedMotion = false
        fun restoreMotion() {
            if (motionScale == "null") {
                shell("settings delete global animator_duration_scale")
            } else {
                require(motionScale.toFloatOrNull() != null)
                shell("settings put global animator_duration_scale " + motionScale)
            }
        }
        val page = ProfilePage("synthetic:p", "Animation fixture", "Synthetic box", null, null)
        var point = 0 to 0
        fun pixel(): Int = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let {
            val value = it.getPixel(point.first, point.second)
            it.recycle()
            value
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        ProfileIdentity(page, artwork.value, WatchState(), selected.value)
                    }
                }
            }
            val colors = mutableSetOf<Int>()
            val observed = mutableSetOf<Int>()
            point = samplePoint("Animation fixture profile picture")
            repeat(40) {
                SystemClock.sleep(40)
                val color = pixel()
                observed.add(color)
                color.takeIf { it in frameColors }?.let { colors.add(it) }
            }
            assertEquals(
                "Selected GIF did not render distinct frames: " +
                    observed.map { Integer.toHexString(it) },
                frameColors,
                colors
            )
            shell("settings put global animator_duration_scale 0")
            changedMotion = true
            repeat(40) {
                if (pixel() != Color.GREEN) SystemClock.sleep(50)
            }
            assertEquals("Reduced motion should show the PNG fallback", Color.GREEN, pixel())
            SystemClock.sleep(200)
            assertEquals(Color.GREEN, pixel())
            restoreMotion()
            changedMotion = false
            instrumentation.runOnMainSync { selected.value = false }
            SystemClock.sleep(250)
            assertEquals(Color.GREEN, pixel())
            SystemClock.sleep(250)
            assertEquals(Color.GREEN, pixel())
            val pet =
                PetAtlas(
                    "Static fixture",
                    500,
                    mapOf(
                        "sleeping" to listOf(
                            Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(Color.MAGENTA)
                            }
                        )
                    )
                )
            instrumentation.runOnMainSync {
                artwork.value =
                    PageArtwork(portrait, pet, cache.file(descriptor))
            }
            SystemClock.sleep(350)
            assertEquals(Color.MAGENTA, pixel())
        } finally {
            if (changedMotion) restoreMotion()
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
            portrait.recycle()
            directory.deleteRecursively()
        }
    }
}
