package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.AppearanceTestActivity
import dev.quantumink.hermesgadget.mobile.R
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual platform host inflation, with its external launcher padding excluded explicitly. */
class HomeWidgetHostTest {
    @Test fun existingHostsReinflateForSystemNightWhileExplicitPalettesStayFixed() {
        assertTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val modes = context.getSystemService(android.app.UiModeManager::class.java)
        val original = modes.nightMode
        val appearance = dev.quantumink.hermesgadget.client.AppearancePreferences(
            context
        ).mode.value
        val host = WidgetHostFixture(context)
        val directory = File(context.cacheDir, "widget-night-${UUID.randomUUID()}")
        val views = mutableMapOf<WidgetAppearance, AppWidgetHostView>()
        val published = mutableMapOf<WidgetAppearance, RemoteViews>()
        val observed = mutableMapOf<WidgetAppearance, MutableList<List<Int>>>()
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        fun night(value: Int) {
            automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
            modes.setNightMode(value)
            val expected = if (value == android.app.UiModeManager.MODE_NIGHT_YES) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                expected &&
                android.os.SystemClock.uptimeMillis() < deadline
            ) {
                android.os.SystemClock.sleep(40)
            }
            assertEquals(
                expected,
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            )
        }
        fun background(view: View): Int {
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val drawable = view.findViewById<View>(android.R.id.background).background
            drawable.setBounds(0, 0, 32, 32)
            drawable.draw(Canvas(image))
            return image.getPixel(16, 16).also { image.recycle() }
        }
        try {
            night(android.app.UiModeManager.MODE_NIGHT_NO)
            val profiles =
                listOf(
                    WidgetProfile("studio:orbit", "Orbit", "Studio", CatalogStatus.OFFLINE, null)
                )
            instrumentation.runOnMainSync {
                WidgetAppearance.entries.forEach { mode ->
                    val id = host.bind(HomeWidgetPreset.FOCUS)
                    val remote = HomeWidgetRenderer.render(
                        context,
                        id,
                        WidgetConfig(HomeWidgetPreset.FOCUS, listOf("studio:orbit"), mode),
                        profiles,
                        HomeWidgetPictures(directory),
                        57f,
                        102f
                    )
                    published[mode] = remote
                    views[mode] = hosted(
                        host,
                        id,
                        context,
                        remote,
                        57,
                        102
                    )
                }
            }
            listOf(
                android.app.UiModeManager.MODE_NIGHT_NO,
                android.app.UiModeManager.MODE_NIGHT_YES
            ).forEach { mode ->
                night(mode)
                instrumentation.runOnMainSync {
                    views.forEach { (appearance, view) ->
                        val before = view.findViewById<TextView>(R.id.home_widget_name)
                        // Retain this host and its published RemoteViews across the actual OS change.
                        // Drop the old content before reapplying, as a host configuration rebuild does.
                        view.updateAppWidget(null)
                        view.updateAppWidget(requireNotNull(published[appearance]))
                        val caption = view.findViewById<TextView>(R.id.home_widget_name)
                        assertTrue("Host must actually reinflate", caption !== before)
                        assertEquals(
                            mode == android.app.UiModeManager.MODE_NIGHT_YES,
                            caption.resources.configuration.uiMode and
                                Configuration.UI_MODE_NIGHT_MASK ==
                                Configuration.UI_MODE_NIGHT_YES
                        )
                        observed.getOrPut(appearance) { mutableListOf() } +=
                            listOf(
                                background(view),
                                caption.currentTextColor,
                                view.findViewById<TextView>(
                                    R.id.home_widget_initials
                                ).currentTextColor
                            )
                    }
                }
            }
            val system = requireNotNull(observed[WidgetAppearance.SYSTEM])
            (0..2).forEach {
                assertTrue(
                    "System surface/text/accent must change",
                    system[0][it] != system[1][it]
                )
            }
            listOf(
                WidgetAppearance.LIGHT,
                WidgetAppearance.DARK,
                WidgetAppearance.MOONLIGHT
            ).forEach {
                assertEquals(observed[it]?.get(0), observed[it]?.get(1))
            }
            assertEquals(
                appearance,
                dev.quantumink.hermesgadget.client.AppearancePreferences(context).mode.value
            )
        } finally {
            automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
            modes.setNightMode(original)
            automation.dropShellPermissionIdentity()
            host.close()
            directory.deleteRecursively()
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sizes = listOf(
        57 to 102,
        130 to 220,
        276 to 220,
        276 to 102,
        276 to 455,
        349 to 102,
        349 to 220
    )

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        } else {
            emptyList()
        }

    private fun configured(
        font: Float,
        rtl: Boolean,
        landscape: Boolean = false,
        night: Boolean = false
    ): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            fontScale = font
            setLocale(if (rtl) Locale.forLanguageTag("ar") else Locale.ENGLISH)
            orientation = if (landscape) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    )

    private fun hosted(
        host: WidgetHostFixture,
        id: Int,
        themed: Context,
        views: RemoteViews,
        width: Int,
        height: Int
    ): AppWidgetHostView {
        val manager = AppWidgetManager.getInstance(context)
        // Exercises the real widget service transaction, as well as host-side RemoteViews inflation.
        manager.updateAppWidget(id, views)
        val result = host.host.createView(themed, id, requireNotNull(manager.getAppWidgetInfo(id)))
        result.setPadding(0, 0, 0, 0)
        result.updateAppWidget(views)
        val density = themed.resources.displayMetrics.density
        result.measure(
            View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY)
        )
        result.layout(0, 0, result.measuredWidth, result.measuredHeight)
        return result
    }

    @Test fun allPresetsKeepTargetsCaptionsAndReadingOrderInTheNativeHost() {
        val directory = File(context.cacheDir, "widget-host-${UUID.randomUUID()}")
        val host = WidgetHostFixture(context)
        val originalLocale = Locale.getDefault()
        try {
            HomeWidgetPreset.entries.forEachIndexed { index, preset ->
                val id = host.bind(preset)
                val profiles = (1..preset.capacity).map {
                    WidgetProfile(
                        "studio:bot$it",
                        "A long profile name number $it",
                        "Studio",
                        CatalogStatus.OFFLINE,
                        null
                    )
                }
                listOf(1f, 1.3f).forEach { font ->
                    listOf(false, true).forEach { rtl ->
                        listOf(false, true).forEach { landscape ->
                            listOf(1f, 1.5f).forEach { scale ->
                                val themed = configured(font, rtl, landscape)
                                val baseWidth = sizes[index].first + if (landscape) 70 else 0
                                val width = (baseWidth * scale).toInt()
                                val height = (
                                    (
                                        if (landscape) {
                                            maxOf(
                                                preset.rows * 51,
                                                sizes[index].second / 2
                                            )
                                        } else {
                                            sizes[index].second
                                        }
                                        ) *
                                        scale
                                    ).toInt()
                                instrumentation.runOnMainSync {
                                    // View's LAYOUT_DIRECTION_LOCALE uses the process locale,
                                    // rather than the configuration override's locale.
                                    Locale.setDefault(
                                        if (rtl) Locale.forLanguageTag("ar") else Locale.ENGLISH
                                    )
                                    val view =
                                        hosted(
                                            host,
                                            id,
                                            themed,
                                            HomeWidgetRenderer.render(
                                                themed,
                                                id,
                                                WidgetConfig(preset, profiles.map { it.key }),
                                                profiles,
                                                HomeWidgetPictures(directory),
                                                width.toFloat(),
                                                height.toFloat()
                                            ),
                                            width,
                                            height
                                        )
                                    val cells = descendants(view).filter {
                                        it.id ==
                                            R.id.home_widget_cell
                                    }
                                    assertEquals(preset.capacity, cells.size)
                                    cells.forEachIndexed { slot, cell ->
                                        val density = themed.resources.displayMetrics.density
                                        assertTrue(
                                            "$preset target too narrow",
                                            cell.width / density >= 48
                                        )
                                        assertTrue(
                                            "$preset target too short",
                                            cell.height / density >= 48
                                        )
                                        assertEquals(
                                            "Open ${profiles[slot].name} · Studio",
                                            cell.contentDescription
                                        )
                                        val caption = cell.findViewById<TextView>(
                                            R.id.home_widget_name
                                        )
                                        assertEquals(
                                            font,
                                            caption.resources.configuration.fontScale
                                        )
                                        assertTrue(
                                            caption.height > 0 && caption.bottom <= cell.height
                                        )
                                        assertTrue(
                                            caption.top >=
                                                cell.findViewById<View>(
                                                    R.id.home_widget_portrait
                                                ).bottom
                                        )
                                    }
                                    if (preset.columns > 1) {
                                        assertTrue(
                                            "RTL must mirror visual order without changing keys",
                                            if (rtl) {
                                                cells[0].left > cells[1].left
                                            } else {
                                                cells[0].left <
                                                    cells[1].left
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
            host.close()
            directory.deleteRecursively()
        }
    }

    @Test fun adaptiveVariantsShareTheWholeBitmapBudgetAfterServiceDelivery() {
        val directory = File(context.cacheDir, "widget-budget-${UUID.randomUUID()}").also {
            it.mkdirs()
        }
        val host = WidgetHostFixture(context)
        try {
            val pictures = HomeWidgetPictures(File(directory, "thumbs"))
            val profiles = (1..16).map { index ->
                val source = File(directory, "$index.png")
                val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                image.eraseColor(android.graphics.Color.rgb(index * 13, index * 7, index * 11))
                source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                val hash = MessageDigest.getInstance(
                    "SHA-256"
                ).digest(source.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
                assertTrue(pictures.prepare(hash, source))
                WidgetProfile(
                    "studio:bot$index",
                    "Bot $index",
                    "Studio",
                    CatalogStatus.AVAILABLE,
                    hash
                )
            }
            val id = host.bind(HomeWidgetPreset.SIXTEEN)
            val config = WidgetConfig(HomeWidgetPreset.SIXTEEN, profiles.map { it.key })
            val options = Bundle().apply {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(SizeF(276f, 455f), SizeF(400f, 240f), SizeF(500f, 600f))
                )
            }
            val parcel = Parcel.obtain()
            val remote = try {
                HomeWidgetRenderer.adaptive(
                    context,
                    id,
                    config,
                    profiles,
                    pictures,
                    options
                ).writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                RemoteViews.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
            val bitmaps = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Bitmap, Boolean>()
            )
            instrumentation.runOnMainSync {
                listOf(276 to 455, 400 to 240, 500 to 600).forEach { (width, height) ->
                    val view = hosted(host, id, context, remote, width, height)
                    descendants(view).filterIsInstance<ImageView>().forEach {
                        (it.drawable as? BitmapDrawable)?.bitmap?.let(bitmaps::add)
                    }
                }
            }
            assertEquals(16, bitmaps.size)
            val metrics = context.resources.displayMetrics
            assertTrue(
                bitmaps.sumOf { it.allocationByteCount.toLong() } <=
                    minOf(1024L * 1024, metrics.widthPixels.toLong() * metrics.heightPixels * 4)
            )
        } finally {
            host.close()
            directory.deleteRecursively()
        }
    }

    @Test fun nativeHostExportsShowSevenPresetsAndFixedThemesWithMissingArtRecovery() {
        val directory = File(context.cacheDir, "widget-art-${UUID.randomUUID()}").also {
            it.mkdirs()
        }
        val output = File(context.cacheDir, "home-widget-captures").also { it.mkdirs() }
        val host = WidgetHostFixture(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, AppearanceTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        try {
            val pictures = HomeWidgetPictures(File(directory, "thumbs"))
            val hashes = (1..3).map { index ->
                val file = File(directory, "$index.png")
                instrumentation.context.assets.open(
                    "home-widget-portrait-$index.png"
                ).use { input ->
                    file.outputStream().use(input::copyTo)
                }
                val hash = MessageDigest.getInstance(
                    "SHA-256"
                ).digest(file.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
                assertTrue(pictures.prepare(hash, file))
                hash
            }
            HomeWidgetPreset.entries.forEachIndexed { index, preset ->
                val id = host.bind(preset)
                val profiles = (1..preset.capacity).map {
                    WidgetProfile(
                        "studio:bot$it",
                        listOf("Orbit", "Echo", "Nova")[(it - 1) % 3] + if (it > 3) " $it" else "",
                        "Studio",
                        CatalogStatus.OFFLINE,
                        hashes[(it - 1) % 3]
                    )
                }
                WidgetAppearance.entries.forEach { appearance ->
                    val themed =
                        configured(1f, false, night = appearance == WidgetAppearance.SYSTEM)
                    instrumentation.runOnMainSync {
                        val view = hosted(
                            host,
                            id,
                            themed,
                            HomeWidgetRenderer.render(
                                themed,
                                id,
                                WidgetConfig(
                                    preset,
                                    profiles.map {
                                        it.key
                                    },
                                    appearance
                                ),
                                profiles,
                                pictures,
                                sizes[index].first.toFloat(),
                                sizes[index].second.toFloat()
                            ),
                            sizes[index].first,
                            sizes[index].second
                        )
                        val root = view.findViewById<View>(android.R.id.background)
                        val sample = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        root.background.setBounds(0, 0, 32, 32)
                        root.background.draw(Canvas(sample))
                        val background = sample.getPixel(16, 16)
                        sample.recycle()
                        descendants(view).filterIsInstance<TextView>().filter {
                            it.id ==
                                R.id.home_widget_name
                        }.forEach {
                            assertTrue(
                                "Caption contrast $preset/$appearance",
                                ColorUtils.calculateContrast(it.currentTextColor, background) >= 4.5
                            )
                        }
                        root.background.setBounds(0, 0, root.width, root.height)
                        val image = Bitmap.createBitmap(
                            view.width,
                            view.height,
                            Bitmap.Config.ARGB_8888
                        )
                        view.draw(Canvas(image))
                        File(
                            output,
                            "${preset.name.lowercase()}-${appearance.name.lowercase()}.png"
                        ).outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                        if (preset == HomeWidgetPreset.SIXTEEN &&
                            appearance == WidgetAppearance.MOONLIGHT
                        ) {
                            val frame = FrameLayout(activity).apply {
                                setBackgroundColor(0xff070e22.toInt())
                            }
                            frame.addView(
                                view,
                                FrameLayout.LayoutParams(
                                    view.width,
                                    view.height,
                                    android.view.Gravity.CENTER
                                )
                            )
                            activity.setContentView(frame)
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "host-screen.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            // A corrupt picture affects only its cell; withdrawn and empty cells recover to this editor.
            File(directory, "thumbs/${hashes[0]}.png").writeText("corrupt")
            val preset = HomeWidgetPreset.FOUR
            val id = host.bind(preset)
            val profiles =
                listOf(
                    WidgetProfile(
                        "studio:orbit",
                        "Orbit",
                        "Studio",
                        CatalogStatus.OFFLINE,
                        hashes[0]
                    ),
                    WidgetProfile(
                        "studio:nova",
                        "Nova",
                        "Studio",
                        CatalogStatus.WITHDRAWN,
                        hashes[1]
                    )
                )
            instrumentation.runOnMainSync {
                val view = hosted(
                    host,
                    id,
                    context,
                    HomeWidgetRenderer.render(
                        context,
                        id,
                        WidgetConfig(
                            preset,
                            listOf("studio:orbit", "studio:nova", null, "studio:lost")
                        ),
                        profiles,
                        pictures,
                        130f,
                        220f
                    ),
                    130,
                    220
                )
                val cells = descendants(view).filter { it.id == R.id.home_widget_cell }
                assertEquals(
                    listOf(
                        "Open Orbit · Studio",
                        "Unavailable · Edit widget slot 2",
                        "Add bot · slot 3",
                        "Unavailable · Edit widget slot 4"
                    ),
                    cells.map { it.contentDescription }
                )
                assertEquals(
                    View.GONE,
                    cells[0].findViewById<View>(R.id.home_widget_picture).visibility
                )
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            host.close()
            directory.deleteRecursively()
        }
    }
}
