package dev.quantumink.hermesgadget.mobile.widgets

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.appearanceTokens
import dev.quantumink.hermesgadget.mobile.R
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import kotlin.math.sqrt

object HomeWidgetRenderer {
    /** Every size shares the exact same decoded bitmap objects and total budget. */
    @Suppress("DEPRECATION")
    fun adaptive(
        context: Context,
        id: Int,
        config: WidgetConfig,
        profiles: List<WidgetProfile>,
        pictures: HomeWidgetPictures,
        options: android.os.Bundle
    ): RemoteViews {
        val decoded = mutableMapOf<String, Bitmap?>()
        fun at(width: Float, height: Float) = compose(
            context,
            id,
            config,
            profiles,
            pictures,
            width,
            height,
            decoded
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val sizes = options.getParcelableArrayList<android.util.SizeF>(
                android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES
            ).orEmpty().filter {
                it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0
            }.distinct().take(16)
            if (sizes.isNotEmpty()) {
                return RemoteViews(
                    sizes.associateWith {
                        at(it.width, it.height)
                    }
                )
            }
        }
        val width = options.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            config.preset.columns * 73 - 16
        ).coerceAtLeast(1).toFloat()
        val height = options.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            config.preset.rows * 118 - 16
        ).coerceAtLeast(1).toFloat()
        val wide = options.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            width.toInt()
        ).coerceAtLeast(1).toFloat()
        val short = options.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            height.toInt()
        ).coerceAtLeast(1).toFloat()
        return RemoteViews(at(wide, short), at(width, height))
    }

    fun render(
        context: Context,
        id: Int,
        config: WidgetConfig,
        profiles: List<WidgetProfile>,
        pictures: HomeWidgetPictures,
        widthDp: Float,
        heightDp: Float
    ): RemoteViews = compose(
        context,
        id,
        config,
        profiles,
        pictures,
        widthDp,
        heightDp,
        mutableMapOf()
    )

    private fun compose(
        context: Context,
        id: Int,
        config: WidgetConfig,
        profiles: List<WidgetProfile>,
        pictures: HomeWidgetPictures,
        widthDp: Float,
        heightDp: Float,
        decoded: MutableMap<String, Bitmap?>
    ): RemoteViews {
        require(id > 0 && widthDp.isFinite() && heightDp.isFinite())
        val root = RemoteViews(context.packageName, R.layout.home_widget_root)
        val mode = when (config.appearance) {
            WidgetAppearance.SYSTEM -> null
            WidgetAppearance.LIGHT -> Appearance.LIGHT
            WidgetAppearance.DARK -> Appearance.DARK
            WidgetAppearance.MOONLIGHT -> Appearance.MOONLIGHT
        }
        val tokens = mode?.let { appearanceTokens(it) }
        if (mode != null) {
            root.setInt(
                android.R.id.background,
                "setBackgroundResource",
                when (mode) {
                    Appearance.LIGHT -> R.drawable.home_widget_background_light
                    Appearance.DARK -> R.drawable.home_widget_background_dark
                    Appearance.MOONLIGHT -> R.drawable.home_widget_background_moonlight
                }
            )
        }
        val padding = if (widthDp / config.preset.columns >= 56 &&
            heightDp / config.preset.rows >= 56
        ) {
            4
        } else {
            0
        }
        val pixels = (padding * context.resources.displayMetrics.density).toInt()
        root.setViewPadding(android.R.id.background, pixels, pixels, pixels, pixels)
        val cellWidth = (widthDp - 2 * padding) / config.preset.columns
        val cellHeight = (heightDp - 2 * padding) / config.preset.rows
        root.removeAllViews(R.id.home_widget_rows)
        if (cellWidth < 48 || cellHeight < 48) {
            root.setViewVisibility(R.id.home_widget_rows, View.GONE)
            root.setViewVisibility(R.id.home_widget_recovery, View.VISIBLE)
            root.setTextViewText(R.id.home_widget_recovery, "Open widget settings")
            root.setContentDescription(R.id.home_widget_recovery, "Open widget settings")
            root.setOnClickPendingIntent(
                R.id.home_widget_recovery,
                HomeWidgets.editPendingIntent(context, id)
            )
            tokens?.let { root.setTextColor(R.id.home_widget_recovery, it.text) }
            return root
        }
        root.setViewVisibility(R.id.home_widget_rows, View.VISIBLE)
        root.setViewVisibility(R.id.home_widget_recovery, View.GONE)
        val metrics = context.resources.displayMetrics
        val budget = minOf(1024L * 1024, metrics.widthPixels.toLong() * metrics.heightPixels * 4)
        val side = sqrt(budget / (config.preset.capacity * 4.0)).toInt().coerceIn(16, 256)
        repeat(config.preset.rows) { row ->
            val line = RemoteViews(context.packageName, R.layout.home_widget_row)
            repeat(config.preset.columns) { column ->
                val slot = row * config.preset.columns + column
                val key = config.slots[slot]
                val profile = profiles.singleOrNull {
                    it.key == key &&
                        it.status != CatalogStatus.WITHDRAWN
                }
                val cell = RemoteViews(context.packageName, R.layout.home_widget_cell)
                val name = profile?.name ?: if (key == null) "Add bot" else "Unavailable"
                cell.setTextViewText(R.id.home_widget_name, name)
                cell.setInt(
                    R.id.home_widget_name,
                    "setMaxLines",
                    if (cellHeight >=
                        88 * context.resources.configuration.fontScale
                    ) {
                        2
                    } else {
                        1
                    }
                )
                cell.setContentDescription(
                    R.id.home_widget_cell,
                    when {
                        profile != null -> "Open ${profile.name} · ${profile.source}"
                        key == null -> "Add bot · slot ${slot + 1}"
                        else -> "Unavailable · Edit widget slot ${slot + 1}"
                    }
                )
                cell.setOnClickPendingIntent(
                    R.id.home_widget_cell,
                    if (profile ==
                        null
                    ) {
                        HomeWidgets.editPendingIntent(context, id)
                    } else {
                        HomeWidgets.profilePendingIntent(context, id, slot, key!!, config.revision)
                    }
                )
                val bitmap = profile?.portraitHash?.let { hash ->
                    decoded.getOrPut(hash) { pictures.bitmap(hash, side) }
                }
                cell.setViewVisibility(
                    R.id.home_widget_picture,
                    if (bitmap ==
                        null
                    ) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                )
                cell.setViewVisibility(
                    R.id.home_widget_initials,
                    if (bitmap ==
                        null
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                )
                if (bitmap != null) cell.setImageViewBitmap(R.id.home_widget_picture, bitmap)
                cell.setTextViewText(
                    R.id.home_widget_initials,
                    if (profile ==
                        null
                    ) {
                        "+"
                    } else {
                        String(Character.toChars(name.codePointAt(0))).uppercase()
                    }
                )
                tokens?.let {
                    cell.setTextColor(R.id.home_widget_name, it.text)
                    cell.setTextColor(R.id.home_widget_initials, it.accent)
                }
                line.addView(R.id.home_widget_row, cell)
            }
            root.addView(R.id.home_widget_rows, line)
        }
        return root
    }
}
