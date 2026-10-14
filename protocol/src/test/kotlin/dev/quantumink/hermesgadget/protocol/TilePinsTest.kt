package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePinsTest {
    private val keys = setOf("box:a", "box:b", "box:c", "box:d", "box:e")

    @Test fun staleEditsCannotOverwriteAndDuplicateReturnsOriginalRevision() {
        val edit = TilePinEdit(0, "request-one", "box:a", listOf("box:b", null, "box:a"))
        val first = TilePinState().apply(edit, keys)
        assertTrue(first.second is TilePinResult.Accepted)
        assertEquals(listOf("box:b", null, "box:a", null), first.first.pins.grid)
        val local = first.first.editLocal("box:b", emptyList())
        val duplicate = local.apply(edit, keys)
        assertTrue(duplicate.second is TilePinResult.Current)
        assertEquals(1L, duplicate.second.pins.revision)
        assertEquals(local, duplicate.first)
        val stale = local.apply(edit.copy(requestId = "other"), keys)
        assertTrue(stale.second is TilePinResult.Conflict)
        assertEquals(local, stale.first)
    }

    @Test fun rejectsCapacityDuplicatesUnknownKeysAndRequestReuse() {
        val state = TilePinState()
        for (edit in listOf(
            TilePinEdit(0, "one", null, keys.toList()),
            TilePinEdit(0, "two", null, listOf("box:a", "box:a")),
            TilePinEdit(0, "three", "box:injected", emptyList()),
            TilePinEdit(0, "bad/request", null, emptyList()),
            TilePinEdit(0, "four", "wss://localhost", emptyList())
        )) {
            val (after, result) = state.apply(edit, keys)
            assertTrue(result is TilePinResult.Rejected)
            assertEquals(state, after)
        }
        val accepted = state.apply(TilePinEdit(0, "same", "box:a", emptyList()), keys).first
        assertTrue(
            accepted.apply(
                TilePinEdit(1, "same", "box:b", emptyList()),
                keys
            ).second is TilePinResult.Rejected
        )
    }

    @Test fun removedPinsAreRetainedAndHistoryIsBoundedWithoutReplay() {
        var state = TilePinState().editLocal("box:a", listOf("box:b"))
        val first = TilePinEdit(state.pins.revision, "first", "box:b", emptyList())
        state = state.apply(first, keys).first
        repeat(70) { index ->
            state =
                state.apply(
                    TilePinEdit(state.pins.revision, "request-$index", null, emptyList()),
                    keys
                ).first
        }
        assertEquals(64, state.receipts.size)
        assertTrue(state.apply(first, keys).second is TilePinResult.Conflict)
        val removed = TilePinState().editLocal("box:a", listOf("box:b"))
        assertEquals("box:a", removed.pins.focus)
        assertTrue(
            removed.apply(
                TilePinEdit(1, "removed", "box:a", emptyList()),
                emptySet()
            ).second is TilePinResult.Rejected
        )
    }

    @Test fun codecRoundTripBoundsAndRevisionOverflow() {
        val state = TilePinState().apply(
            TilePinEdit(0, "one", "box:a", listOf(null, "box:b")),
            keys
        ).first
        assertEquals(state, TilePinState.decode(state.encode()))
        assertThrows(IllegalArgumentException::class.java) {
            TilePinState.decode(" ".repeat(131073))
        }
        assertThrows(IllegalArgumentException::class.java) { TilePins(-1, null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            TilePinState(TilePins(Long.MAX_VALUE, null, emptyList())).editLocal(null, emptyList())
        }
    }
}
