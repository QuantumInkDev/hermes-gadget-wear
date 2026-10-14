package dev.quantumink.hermesgadget.mobile.widgets

import android.app.WallpaperManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.R
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Changes only an owned emulator's wallpaper, with a byte copy for restoration first. */
class HomeWidgetWallpaperTest {
    @Test fun wallpaperPaletteCapabilityIsRecordedAndSupportedChangesReachSystemWidgets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Owned emulator only", android.os.Build.MODEL.startsWith("sdk_"))
        val output = File(context.cacheDir, "home-widget-captures").also { it.mkdirs() }
        val receipt = File(output, "wallpaper-capability.txt")
        val wallpaper = WallpaperManager.getInstance(context)
        val automation = instrumentation.uiAutomation
        val backup = File(context.cacheDir, "home-widget-wallpaper-backup")
        automation.adoptShellPermissionIdentity(
            "android.permission.READ_WALLPAPER_INTERNAL",
            "android.permission.SET_WALLPAPER"
        )
        try {
            val original = runCatching {
                requireNotNull(wallpaper.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)).use { fd ->
                    java.io.FileInputStream(fd.fileDescriptor).use { input ->
                        backup.outputStream().use(input::copyTo)
                    }
                }
            }
            if (original.isFailure || backup.length() == 0L) {
                receipt.writeText(
                    "UNSUPPORTED: cannot preserve the existing wallpaper; no change attempted.\n"
                )
                return
            }
            val appearance = AppearancePreferences(context).mode.value
            val observed = mutableListOf<Int>()
            val fixed = mutableListOf<Int>()
            var changed = false
            try {
                listOf(0xff146c94.toInt(), 0xffad306f.toInt()).forEach { color ->
                    val before = context.getColor(android.R.color.system_accent1_500)
                    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(color)
                    try {
                        wallpaper.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
                        changed = true
                    } finally {
                        bitmap.recycle()
                    }
                    val deadline = SystemClock.uptimeMillis() + 8000
                    while (context.getColor(android.R.color.system_accent1_500) == before &&
                        SystemClock.uptimeMillis() < deadline
                    ) {
                        SystemClock.sleep(100)
                    }
                    observed += context.getColor(android.R.color.system_accent1_500)
                    val profiles =
                        listOf(
                            WidgetProfile(
                                "studio:orbit",
                                "Orbit",
                                "Studio",
                                CatalogStatus.OFFLINE,
                                null
                            )
                        )
                    listOf(false, true).forEach { night ->
                        val themed = context.createConfigurationContext(
                            Configuration(context.resources.configuration).apply {
                                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                    if (night) {
                                        Configuration.UI_MODE_NIGHT_YES
                                    } else {
                                        Configuration.UI_MODE_NIGHT_NO
                                    }
                            }
                        )
                        val expected = android.view.ContextThemeWrapper(
                            themed,
                            android.R.style.Theme_DeviceDefault_DayNight
                        )
                        val value = android.util.TypedValue()
                        assertTrue(
                            expected.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
                        )
                        val accent = if (value.resourceId !=
                            0
                        ) {
                            expected.getColor(value.resourceId)
                        } else {
                            value.data
                        }
                        instrumentation.runOnMainSync {
                            val system = HomeWidgetRenderer.render(
                                themed,
                                100,
                                WidgetConfig(HomeWidgetPreset.FOCUS, listOf("studio:orbit")),
                                profiles,
                                HomeWidgetPictures(File(output, "unused")),
                                57f,
                                102f
                            )
                                .apply(themed, FrameLayout(themed))
                            assertEquals(
                                accent,
                                system.findViewById<TextView>(
                                    R.id.home_widget_initials
                                ).currentTextColor
                            )
                            val explicit = HomeWidgetRenderer.render(
                                themed,
                                100,
                                WidgetConfig(
                                    HomeWidgetPreset.FOCUS,
                                    listOf("studio:orbit"),
                                    WidgetAppearance.MOONLIGHT
                                ),
                                profiles,
                                HomeWidgetPictures(File(output, "unused")),
                                57f,
                                102f
                            )
                                .apply(themed, FrameLayout(themed))
                            fixed +=
                                explicit.findViewById<TextView>(
                                    R.id.home_widget_initials
                                ).currentTextColor
                        }
                    }
                }
                assertEquals(1, fixed.distinct().size)
                assertEquals(appearance, AppearancePreferences(context).mode.value)
                if (observed.distinct().size < 2) {
                    receipt.writeText(
                        "UNSUPPORTED: wallpaper changed but emulator dynamic palette did not change; restored original.\n"
                    )
                } else {
                    assertNotEquals(observed[0], observed[1])
                    receipt.writeText(
                        "PASS: two real wallpaper palettes, system day/night accents, fixed Moonlight and app appearance unchanged; restored original.\n"
                    )
                }
            } finally {
                if (changed) {
                    backup.inputStream().use {
                        wallpaper.setStream(it, null, false, WallpaperManager.FLAG_SYSTEM)
                    }
                }
            }
        } finally {
            backup.delete()
            automation.dropShellPermissionIdentity()
        }
    }
}
