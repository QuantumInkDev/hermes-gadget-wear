package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogOrderTest {
    private fun page(source: String, id: String, status: CatalogStatus = CatalogStatus.AVAILABLE) =
        CatalogPage(source, "Box", CatalogProfile(id, "Duplicate name", null, null), status, 1)

    private val interleaved = listOf(
        page("b", "second"),
        page("a", "same", CatalogStatus.OFFLINE),
        page("b", "same", CatalogStatus.WITHDRAWN),
        page("manual", "saved"),
        page("a", "first")
    )

    @Test fun migrationKeepsFirstSourceOccurrenceAndWithinSourceOrder() {
        val groups = CatalogOrder.group(interleaved)
        assertEquals(
            listOf(
                listOf("b:second", "b:same"),
                listOf("a:same", "a:first"),
                listOf("manual:saved")
            ),
            groups.map { group -> group.map { it.key } }
        )
        assertSame(interleaved[1], groups[1][0])
        assertSame(interleaved[2], groups[0][1])
        assertEquals(CatalogStatus.OFFLINE, groups[1][0].status)
        assertEquals(CatalogStatus.WITHDRAWN, groups[0][1].status)
    }

    @Test fun profileMovesOnlyWithinItsSourceEvenWithDuplicateIds() {
        val moved = CatalogOrder.moveProfile(interleaved, "a:first", -1)
        assertEquals(
            listOf("b:second", "b:same", "a:first", "a:same", "manual:saved"),
            moved.map { it.key }
        )
        assertEquals(
            listOf("b:second", "b:same", "a:same", "a:first", "manual:saved"),
            CatalogOrder.moveProfile(moved, "a:first", 1).map { it.key }
        )
    }

    @Test fun sourceMovesAsAWholeAndNeverChangesWithinSourceOrder() {
        assertEquals(
            listOf("a:same", "a:first", "b:second", "b:same", "manual:saved"),
            CatalogOrder.moveSource(interleaved, "a", -1).map { it.key }
        )
        assertEquals(
            listOf("b:second", "b:same", "manual:saved", "a:same", "a:first"),
            CatalogOrder.moveSource(interleaved, "manual", -1).map { it.key }
        )
    }

    @Test fun movementClampsSafelyAndRejectsUnknownStableKeys() {
        val grouped = listOf("b:second", "b:same", "a:same", "a:first", "manual:saved")
        assertEquals(
            grouped,
            CatalogOrder.moveProfile(interleaved, "b:second", Int.MIN_VALUE).map {
                it.key
            }
        )
        assertEquals(
            grouped,
            CatalogOrder.moveSource(interleaved, "manual", Int.MAX_VALUE).map {
                it.key
            }
        )
        assertEquals(grouped, CatalogOrder.moveProfile(interleaved, "a:first", 0).map { it.key })
        assertEquals(emptyList<List<CatalogPage>>(), CatalogOrder.group(emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            CatalogOrder.moveProfile(interleaved, "a:missing", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogOrder.moveSource(interleaved, "missing", 1)
        }
    }

    @Test fun newProfilesAppendInsideSourceAndOutageRecoveryKeepsOwnerOrder() {
        val source =
            CatalogSource("b", "Renamed box", "https://b.example.invalid/catalog/v1", "synthetic")
        val ordered = CatalogOrder.moveProfile(interleaved, "b:same", -1)
        val response = listOf(
            CatalogProfile("second", "New name", null, null),
            CatalogProfile("same", "Duplicate name", null, null),
            CatalogProfile("new", "Duplicate name", null, null)
        )
        val refreshed = CatalogOrder.group(
            CatalogRoster.merge(ordered, source, response, CatalogStatus.AVAILABLE, 2)
        ).flatten()
        val expected = listOf("b:same", "b:second", "b:new", "a:same", "a:first", "manual:saved")
        assertEquals(expected, refreshed.map { it.key })
        val offline = CatalogRoster.merge(refreshed, source, null, CatalogStatus.OFFLINE, 3)
        val returned = CatalogRoster.merge(offline, source, response, CatalogStatus.AVAILABLE, 4)
        assertEquals(expected, CatalogOrder.group(returned).flatten().map { it.key })
        assertEquals(
            expected,
            ProfileCatalog.decodePages(ProfileCatalog.encodePages(returned)).map {
                it.key
            }
        )
    }
}
