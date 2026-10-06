package dev.quantumink.hermesgadget.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local snapshot only. A Tile never starts a socket or microphone. */
class GadgetTileService : TileService() {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private fun <T : Any> future(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completion ->
            try {
                val work = worker.submit {
                    try {
                        completion.set(block())
                    } catch (
                        failure: Exception
                    ) {
                        completion.setException(failure)
                    }
                }
                completion.addCancellationListener({ work.cancel(true) }, { it.run() })
            } catch (failure: Exception) {
                completion.setException(failure)
            }
            "Hermes local tile"
        }
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = future { tile(WatchSurfaces.read(this)) }
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = future {
        ResourceBuilders.Resources.Builder().setVersion("1").build()
    }
    internal fun tile(
        snapshot: SurfaceSnapshot,
        context: android.content.Context = this
    ): TileBuilders.Tile {
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName)
                .setClassName(MainActivity::class.java.name).build()
        ).build()
        val click = ModifiersBuilders.Clickable.Builder().setId("ask").setOnClick(launch).build()
        fun text(value: String, size: Float, color: Int, lines: Int = 2) =
            LayoutElementBuilders.Text.Builder()
                .setText(value).setMaxLines(lines).setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(
                            DimensionBuilders.sp(size)
                        ).setColor(ColorBuilders.argb(color)).build()
                )
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER).build()
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder().setClickable(click)
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription("Open Hermes, then hold to ask").build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder().setAll(
                            DimensionBuilders.dp(36f)
                        ).build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF000000.toInt())).build()
                    ).build()
            )
            .addContent(text(snapshot.label, 14f, 0xFFB6ED93.toInt(), 1))
            .addContent(text(snapshot.status, 14f, 0xFFB3B8B0.toInt(), 1))
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(12f)).build()
            )
            .addContent(text("Tap to open", 22f, 0xFFF0F3EB.toInt()))
            .addContent(text("Then hold to ask", 14f, 0xFFB6ED93.toInt()))
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(12f)).build()
            )
            .addContent(
                text(
                    snapshot.reply.ifEmpty { "Your last reply appears here" },
                    13f,
                    0xFFB3B8B0.toInt()
                )
            ).build()
        return TileBuilders.Tile.Builder().setResourcesVersion(
            "1"
        ).setTileTimeline(
            TimelineBuilders.Timeline.Builder().addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder().setLayout(
                    LayoutElementBuilders.Layout.Builder().setRoot(column).build()
                ).build()
            ).build()
        ).build()
    }
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
