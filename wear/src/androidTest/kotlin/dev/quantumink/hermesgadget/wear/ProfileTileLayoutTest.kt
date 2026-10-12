package dev.quantumink.hermesgadget.wear

import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.protolayout.ActionBuilders.AndroidStringExtra
import androidx.wear.protolayout.ActionBuilders.LaunchAction
import androidx.wear.protolayout.DimensionBuilders.DpProp
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ResourceBuilders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTileLayoutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun oneAndFourHaveSeparatedTargetsStableKeyOnlyActionsAndStaticBoundedResources() {
        val entry = ProfileTileEntry("fixture:alpha", "Alpha", "Offline", ByteArray(96 * 96 * 4))
        val empty = ProfileTileEntry(null, "Set up", "")
        val removed = ProfileTileEntry("fixture:gone", "Unavailable", "Unavailable")
        val snapshot =
            ProfileTileSnapshot("fixture-version", entry, listOf(entry, removed, empty, empty))
        for (grid in listOf(false, true)) {
            val tile = ProfileTileLayout.tile(context, snapshot, grid)
            assertEquals(snapshot.version, tile.resourcesVersion)
            val root = tile.tileTimeline!!.timelineEntries.single().layout!!.root as Box
            val cells = walk(root).filterIsInstance<Column>().filter {
                it.modifiers?.clickable !=
                    null
            }
            assertEquals(if (grid) 4 else 1, cells.size)
            for (cell in cells) {
                assertTrue((cell.width as DpProp).value >= 48)
                assertTrue((cell.height as DpProp).value >= 48)
                val activity = activity(cell)
                assertEquals(context.packageName, activity.packageName)
                assertEquals(MainActivity::class.java.name, activity.className)
                assertTrue(
                    activity.keyToExtraMapping.keys.all {
                        it ==
                            TileProfileLaunch.EXTRA_PAGE_KEY
                    }
                )
            }
            val firstAction = activity(cells.first())
            assertEquals(
                entry.key,
                pageKey(firstAction)
            )
            if (grid) {
                val gone = activity(cells[1])
                assertEquals(
                    removed.key,
                    pageKey(gone)
                )
                val rows = walk(root).filterIsInstance<Row>()
                assertEquals(2, rows.size)
                for (row in rows) {
                    assertEquals(
                        4f,
                        ((row.contents[1] as Spacer).width as DpProp).value
                    )
                }
            }
        }
        val resources = ProfileTileLayout.resources(snapshot)
        assertEquals(1, resources.idToImageMapping.size)
        val inline = resources.idToImageMapping.values.single().inlineResource!!
        assertEquals(96, inline.widthPx)
        assertEquals(96, inline.heightPx)
        assertEquals(96 * 96 * 4, inline.data.size)
        assertEquals(ResourceBuilders.IMAGE_FORMAT_ARGB_8888, inline.format)
    }

    @Test fun emptyAndFourPopulatedSlotsStayInsideTheRoundSafeArea() {
        val empty = ProfileTileEntry(null, "Set up", "")
        val full = (1..4).map { ProfileTileEntry("fixture:$it", "Profile $it", "Open") }
        for (entries in listOf(List(4) { empty }, full)) {
            val snapshot = ProfileTileSnapshot("safe", entries.first(), entries)
            val tile = ProfileTileLayout.tile(context, snapshot, true)
            val root = tile.tileTimeline!!.timelineEntries.single().layout!!.root!!
            val cells = walk(root).filterIsInstance<Column>().filter {
                it.modifiers?.clickable != null
            }
            assertEquals(4, cells.size)
            val width = (cells.first().width as DpProp).value * 2 + 4
            val height = (cells.first().height as DpProp).value * 2 + 4
            assertTrue(width * width + height * height <= 192f * 192f)
            for ((index, cell) in cells.withIndex()) {
                val extras = activity(cell).keyToExtraMapping
                if (entries[index].key == null) {
                    assertTrue(extras.isEmpty())
                } else {
                    assertEquals(entries[index].key, pageKey(activity(cell)))
                }
            }
        }
    }
    private fun activity(cell: Column) =
        (cell.modifiers!!.clickable!!.onClick as LaunchAction).androidActivity!!
    private fun pageKey(
        activity: androidx.wear.protolayout.ActionBuilders.AndroidActivity
    ): String {
        val extra = activity.keyToExtraMapping[TileProfileLaunch.EXTRA_PAGE_KEY]
        return (extra as AndroidStringExtra).value
    }
    private fun walk(element: LayoutElement): List<LayoutElement> =
        listOf(element) + when (element) {
            is Box -> element.contents.flatMap(::walk)
            is Row -> element.contents.flatMap(::walk)
            is Column -> element.contents.flatMap(::walk)
            else -> emptyList()
        }
}
