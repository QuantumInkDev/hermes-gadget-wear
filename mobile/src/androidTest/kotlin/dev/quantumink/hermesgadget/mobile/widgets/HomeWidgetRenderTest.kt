package dev.quantumink.hermesgadget.mobile.widgets

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.R
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetRenderTest {
    @Test fun landscapeUsesItsOwnCaptionAndSafeContentHeight() {
        val root = File(context.cacheDir, "widget-orientation-${UUID.randomUUID()}")
        try {
            val options = android.os.Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 57)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 127)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 51)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 102)
            }
            val config = WidgetConfig(HomeWidgetPreset.FOCUS, listOf("a:one"))
            val profiles =
                listOf(
                    WidgetProfile(
                        "a:one",
                        "A longer bot name",
                        "Studio",
                        CatalogStatus.AVAILABLE,
                        null
                    )
                )
            val views = HomeWidgetRenderer.adaptive(
                context,
                100,
                config,
                profiles,
                HomeWidgetPictures(root),
                options
            )
            val landscape = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply {
                    orientation =
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
            )
            val view = views.apply(landscape, FrameLayout(landscape))
            val density = context.resources.displayMetrics.density
            view.measure(
                View.MeasureSpec.makeMeasureSpec((127 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                    (
                        51 *
                            density
                        ).toInt(),
                    View.MeasureSpec.EXACTLY
                )
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals(1, view.findViewById<TextView>(R.id.home_widget_name).maxLines)
            assertTrue(targets(view).single().height / density >= 48)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun systemNightThemeUsesTheActualSystemAccentInsteadOfTheFixedAppPalette() {
        val root = File(context.cacheDir, "widget-colors-${UUID.randomUUID()}")
        try {
            val night = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply {
                    uiMode =
                        (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            )
            val expected = android.view.ContextThemeWrapper(
                night,
                android.R.style.Theme_DeviceDefault_DayNight
            )
            val value = android.util.TypedValue()
            assertTrue(expected.theme.resolveAttribute(android.R.attr.colorAccent, value, true))
            val accent = if (value.resourceId !=
                0
            ) {
                expected.getColor(value.resourceId)
            } else {
                value.data
            }
            val config = WidgetConfig(HomeWidgetPreset.FOCUS, listOf("a:one"))
            val profiles =
                listOf(WidgetProfile("a:one", "Orbit", "Studio", CatalogStatus.AVAILABLE, null))
            val view = HomeWidgetRenderer.render(
                night,
                100,
                config,
                profiles,
                HomeWidgetPictures(root),
                57f,
                102f
            ).apply(night, FrameLayout(night))
            assertEquals(
                accent,
                view.findViewById<TextView>(R.id.home_widget_initials).currentTextColor
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) {
            (0 until view.childCount).flatMap {
                all(view.getChildAt(it))
            }
        } else {
            emptyList()
        }
    private fun targets(view: View) = all(view).filter { it.id == R.id.home_widget_cell }
    private fun render(
        config: WidgetConfig,
        profiles: List<WidgetProfile>,
        pictures: HomeWidgetPictures,
        width: Int,
        height: Int
    ): View {
        val view = HomeWidgetRenderer.render(
            context,
            100,
            config,
            profiles,
            pictures,
            width.toFloat(),
            height.toFloat()
        ).apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        view.measure(
            View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (
                    height *
                        density
                    ).toInt(),
                View.MeasureSpec.EXACTLY
            )
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test fun registeredPresetsAdvertiseCorrectWidthHeightAndNoPolling() {
        val providers = AppWidgetManager.getInstance(context).installedProviders.filter {
            it.provider.packageName ==
                context.packageName
        }
        assertEquals(7, providers.size)
        HomeWidgetPreset.entries.forEach { preset ->
            val info = providers.single { it.provider.className == preset.className }
            assertEquals(preset.columns, info.targetCellWidth)
            assertEquals(preset.rows, info.targetCellHeight)
            assertEquals(0, info.updatePeriodMillis)
            assertTrue(info.widgetFeatures and 1 != 0)
        }
    }

    @Test fun everyPresetKeepsSafeSeparateTargetsAndNamesBeneathClearArt() {
        val root = File(context.cacheDir, "widget-render-${UUID.randomUUID()}")
        try {
            val pictures = HomeWidgetPictures(root)
            val sizes =
                listOf(
                    57 to 102,
                    130 to 220,
                    276 to 220,
                    276 to 102,
                    276 to 455,
                    349 to 102,
                    349 to 220
                )
            HomeWidgetPreset.entries.forEachIndexed { index, preset ->
                val profiles = (1..preset.capacity).map {
                    WidgetProfile(
                        "studio:bot$it",
                        "Bot $it",
                        "Studio",
                        CatalogStatus.AVAILABLE,
                        null
                    )
                }
                val view =
                    render(
                        WidgetConfig(
                            preset,
                            profiles.map {
                                it.key
                            }
                        ),
                        profiles,
                        pictures,
                        sizes[index].first,
                        sizes[index].second
                    )
                val cells = targets(view)
                assertEquals(preset.capacity, cells.size)
                cells.forEachIndexed { slot, cell ->
                    assertEquals("Open Bot ${slot + 1} · Studio", cell.contentDescription)
                    assertTrue(cell.width / context.resources.displayMetrics.density >= 48)
                    assertTrue(cell.height / context.resources.displayMetrics.density >= 48)
                    val image = cell.findViewById<View>(R.id.home_widget_portrait)
                    val caption = cell.findViewById<TextView>(R.id.home_widget_name)
                    assertTrue(caption.top >= image.bottom)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun unknownTargetsAndUndersizedHostsOfferRecoveryInsteadOfAnotherBot() {
        val root = File(context.cacheDir, "widget-render-${UUID.randomUUID()}")
        try {
            val config =
                WidgetConfig(HomeWidgetPreset.STRIP_FOUR, listOf("a:missing", null, null, null))
            val view = render(config, emptyList(), HomeWidgetPictures(root), 276, 102)
            assertEquals(
                "Unavailable · Edit widget slot 1",
                targets(view).first().contentDescription
            )
            assertEquals("Add bot · slot 2", targets(view)[1].contentDescription)
            val small = render(config, emptyList(), HomeWidgetPictures(root), 80, 40)
            assertEquals(0, targets(small).size)
            assertTrue(
                all(small).any {
                    it.contentDescription == "Open widget settings" &&
                        it.isClickable
                }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sixteenDistinctPicturesStayWithinBitmapBudget() {
        val root = File(context.cacheDir, "widget-render-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val pictures = HomeWidgetPictures(File(root, "thumbs"))
            val profiles = (1..16).map { index ->
                val source = File(root, "$index.png")
                val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                image.eraseColor(Color.rgb(index * 13, index * 7, index * 11))
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
            val view =
                render(
                    WidgetConfig(
                        HomeWidgetPreset.SIXTEEN,
                        profiles.map {
                            it.key
                        }
                    ),
                    profiles,
                    pictures,
                    349,
                    455
                )
            val bitmaps = all(view).filterIsInstance<ImageView>().mapNotNull {
                (it.drawable as? BitmapDrawable)?.bitmap
            }.distinct()
            assertEquals(16, bitmaps.size)
            assertTrue(bitmaps.sumOf { it.allocationByteCount.toLong() } <= 1024 * 1024)
        } finally {
            root.deleteRecursively()
        }
    }
}
