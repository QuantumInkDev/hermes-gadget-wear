package dev.quantumink.hermesgadget.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.TileBuilders

/** Fixed geometry fits the inscribed safe square of a 192-dp round watch. */
object ProfileTileLayout {
    fun tile(context: Context, snapshot: ProfileTileSnapshot, grid: Boolean): TileBuilders.Tile {
        val content = if (grid) {
            LayoutElementBuilders.Column.Builder().apply {
                for (row in 0..1) {
                    if (row >
                        0
                    ) {
                        addContent(
                            LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()
                        )
                    }
                    addContent(
                        LayoutElementBuilders.Row.Builder()
                            .addContent(cell(context, snapshot.grid[row * 2], true, row * 2))
                            .addContent(
                                LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build()
                            )
                            .addContent(
                                cell(context, snapshot.grid[row * 2 + 1], true, row * 2 + 1)
                            ).build()
                    )
                }
            }.build()
        } else {
            cell(context, snapshot.focus, false, 0)
        }
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setBackground(
                    ModifiersBuilders.Background.Builder().setColor(
                        argb(0xff000000.toInt())
                    ).build()
                ).build()
            )
            .addContent(content).build()
        return TileBuilders.Tile.Builder().setResourcesVersion(snapshot.version)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder().addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder().setLayout(
                        LayoutElementBuilders.Layout.Builder().setRoot(root).build()
                    ).build()
                ).build()
            ).build()
    }

    @Suppress("DEPRECATION") // Stable resource IDs remain compatible with installed tile renderers.
    private fun cell(
        context: Context,
        entry: ProfileTileEntry,
        grid: Boolean,
        index: Int
    ): LayoutElementBuilders.Column {
        val activity = ActionBuilders.AndroidActivity.Builder().setPackageName(
            context.packageName
        ).setClassName(MainActivity::class.java.name)
        // Removed keys open Unavailable; they never redirect to a neighbour.
        entry.key?.let {
            activity.addKeyToExtraMapping(
                TileProfileLaunch.EXTRA_PAGE_KEY,
                ActionBuilders.AndroidStringExtra.Builder().setValue(it).build()
            )
        }
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            activity.build()
        ).build()
        val size = if (grid) 48f else 80f
        val portrait = entry.resourceId?.let { resource ->
            LayoutElementBuilders.Image.Builder().setResourceId(
                resource
            ).setWidth(dp(size)).setHeight(dp(size)).build()
        } ?: LayoutElementBuilders.Box.Builder().setWidth(dp(size)).setHeight(dp(size))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setBackground(
                    ModifiersBuilders.Background.Builder()
                        .setColor(
                            argb(0xff18222f.toInt())
                        ).setCorner(
                            ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build()
                        ).build()
                ).build()
            )
            .addContent(
                text(
                    if (entry.key ==
                        null
                    ) {
                        "+"
                    } else {
                        entry.label.take(1).uppercase()
                    },
                    if (grid) 22f else 32f
                )
            ).build()
        val picture = if (grid && entry.status == "Offline") {
            LayoutElementBuilders.Box.Builder().setWidth(dp(size)).setHeight(dp(size))
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(portrait)
                .addContent(
                    LayoutElementBuilders.Box.Builder()
                        .setModifiers(
                            ModifiersBuilders.Modifiers.Builder().setBackground(
                                ModifiersBuilders.Background.Builder().setColor(
                                    argb(0xff000000.toInt())
                                ).build()
                            ).build()
                        ).addContent(text("Offline", 10f)).build()
                ).build()
        } else {
            portrait
        }
        return LayoutElementBuilders.Column.Builder().setWidth(
            dp(if (grid) 60f else 128f)
        ).setHeight(dp(if (grid) 68f else 120f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(
                    ModifiersBuilders.Clickable.Builder().setId(
                        "profile-$index"
                    ).setOnClick(launch).build()
                )
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder().setContentDescription(
                            if (entry.key == null) {
                                "Open app, then pin from profile picker"
                            } else {
                                "${entry.label}. ${entry.status}. Tap to open"
                            }
                        ).build()
                    ).build()
            )
            .addContent(picture).addContent(text(entry.label, if (grid) 12f else 16f))
            .apply {
                if (!grid) addContent(text(entry.status.ifEmpty { "Choose a profile" }, 12f))
            }.build()
    }
    private fun text(value: String, size: Float) =
        LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(
                    sp(size)
                ).setColor(argb(0xfff1f5fc.toInt())).build()
            )
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER).build()
    fun resources(snapshot: ProfileTileSnapshot): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(snapshot.version).apply {
            for (entry in (listOf(snapshot.focus) + snapshot.grid).distinctBy { it.resourceId }) {
                val pixels = entry.pixels ?: continue
                addIdToImageMapping(
                    requireNotNull(entry.resourceId),
                    ResourceBuilders.ImageResource.Builder()
                        .setInlineResource(
                            ResourceBuilders.InlineImageResource.Builder().setWidthPx(
                                ProfileTileSnapshot.IMAGE_SIZE
                            ).setHeightPx(ProfileTileSnapshot.IMAGE_SIZE)
                                .setFormat(
                                    ResourceBuilders.IMAGE_FORMAT_ARGB_8888
                                ).setData(pixels).build()
                        ).build()
                )
            }
        }.build()
}
