package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePinSyncTest {
    private val keys = setOf("box:a", "box:b")
    private fun response(id: String, result: String, pins: TilePins, current: TilePins = pins) =
        TilePinResponse("phone", id, result, pins, TilePinSnapshot(current, keys))

    @Test fun offlineQueueReconnectDuplicateAndReorderedAcknowledgements() {
        val start = TilePinPhoneState("watch", TilePinSnapshot(TilePins(), keys))
        val queued = start.queue("one", "box:a", listOf("box:b"))
        assertEquals(0L, queued.snapshot!!.pins.revision)
        assertEquals("one", queued.pending!!.requestId)
        assertEquals(queued, TilePinWire.cache(TilePinWire.cache(queued)))
        val delivered = TilePinWire.request(
            TilePinWire.request(TilePinRequest("watch", "one", queued.pending))
        )
        val watch = TilePinState().apply(delivered.edit!!, keys).first
        val ack = response("one", "accepted", watch.pins)
        val phone = queued.receive("watch", ack)
        assertNull(phone.pending)
        assertEquals(watch.pins, phone.snapshot!!.pins)
        val newer = phone.queue("two", "box:b", emptyList())
        assertEquals(newer, newer.receive("watch", ack))
        val local = watch.editLocal("box:b", emptyList())
        val duplicate = local.apply(delivered.edit, keys).second
        val late = newer.receive("watch", response("one", "current", duplicate.pins, local.pins))
        assertEquals(2L, late.snapshot!!.pins.revision)
        assertEquals("two", late.pending!!.requestId)
        assertEquals(late, late.receive("watch", ack))
    }

    @Test fun concurrentWatchEditConflictsAndNeedsExplicitReview() {
        val queued = TilePinPhoneState("watch", TilePinSnapshot(TilePins(), keys))
            .queue("one", "box:a", emptyList())
        val local = TilePinState().editLocal("box:b", emptyList())
        val result = local.apply(queued.pending!!, keys).second
        assertTrue(result is TilePinResult.Conflict)
        val conflict = queued.receive("watch", response("one", "conflict", result.pins))
        assertEquals("conflict", conflict.issue)
        assertEquals(queued.pending, conflict.pending)
        assertEquals("box:b", conflict.snapshot!!.pins.focus)
        val reviewed = conflict.queue("reviewed", "box:a", emptyList())
        assertEquals(1L, reviewed.pending!!.baseRevision)
        assertNull(reviewed.issue)
        assertEquals(reviewed, reviewed.receive("watch", response("one", "conflict", result.pins)))
    }

    @Test fun rejectsUnknownCapacityPeerAndMalformedWire() {
        val start = TilePinPhoneState("watch", TilePinSnapshot(TilePins(), keys))
        assertThrows(IllegalArgumentException::class.java) {
            start.queue("one", "box:unknown", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            start.queue("one", null, List(5) { null })
        }
        assertEquals(start, start.receive("unrelated", response("one", "snapshot", TilePins(99))))
        val edit = TilePinEdit(0, "one", "box:a", listOf("box:b"))
        val request = TilePinRequest("watch", "one", edit)
        assertEquals(request, TilePinWire.request(TilePinWire.request(request)))
        val ack = response("one", "accepted", TilePins(1, "box:a"))
        assertEquals(ack, TilePinWire.response(TilePinWire.response(ack)))
        for (bad in listOf(
            "{}",
            " ".repeat(TilePinWire.MAX_BYTES + 1),
            String(TilePinWire.request(request)).replace("\"version\":1", "\"version\":2"),
            String(TilePinWire.request(request)).replace("\"version\":1", "\"version\":\"1\""),
            String(
                TilePinWire.request(request)
            ).replace("[\"box:b\"]", "[null,null,null,null,null]"),
            String(TilePinWire.request(request)).replace("\"box:a\"", "\"wss://bad\"")
        )) {
            assertThrows(Exception::class.java) { TilePinWire.request(bad.toByteArray()) }
        }
    }
}
