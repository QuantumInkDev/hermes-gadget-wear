package dev.quantumink.hermesgadget.wear

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.TilePinEdit
import dev.quantumink.hermesgadget.protocol.TilePinRequest
import dev.quantumink.hermesgadget.protocol.TilePinWire
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TilePinSyncTest {
    @Test fun authenticatedRoutingCurrentMembershipConflictAndOriginalDuplicateAck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tile-sync-${java.util.UUID.randomUUID()}")
        try {
            val store = TilePinStore(context, file)
            val keys = setOf("box:a", "box:b")
            val request =
                TilePinRequest("watch", "one", TilePinEdit(0, "one", "box:a", listOf("box:b")))
            val bytes = TilePinWire.request(request)
            assertNull(
                TilePinSync.respond("unrelated", "watch", setOf("phone"), bytes, store) {
                    keys
                }
            )
            assertNull(
                TilePinSync.respond("phone", "watch", setOf("phone", "other"), bytes, store) {
                    keys
                }
            )
            assertNull(
                TilePinSync.respond("phone", "different", setOf("phone"), bytes, store) {
                    keys
                }
            )
            assertEquals(0L, store.read().revision)
            fun send(payload: ByteArray, membership: Set<String> = keys) = TilePinWire.response(
                requireNotNull(
                    TilePinSync.respond("phone", "watch", setOf("phone"), payload, store) {
                        membership
                    }
                )
            )
            val accepted = send(bytes)
            assertEquals("accepted", accepted.result)
            assertEquals(1L, accepted.current.pins.revision)
            store.editLocal("box:b", emptyList())
            val duplicate = send(bytes)
            assertEquals("current", duplicate.result)
            assertEquals(1L, duplicate.acknowledged.revision)
            assertEquals(2L, duplicate.current.pins.revision)
            val stale =
                send(
                    TilePinWire.request(
                        request.copy(
                            requestId = "two",
                            edit = request.edit!!.copy(requestId = "two")
                        )
                    )
                )
            assertEquals("conflict", stale.result)
            assertEquals("box:b", store.read().focus)
            val removed =
                send(
                    TilePinWire.request(
                        TilePinRequest(
                            "watch",
                            "three",
                            TilePinEdit(2, "three", "box:a", emptyList())
                        )
                    ),
                    emptySet()
                )
            assertEquals("rejected", removed.result)
            assertEquals(2L, store.read().revision)
            assertThrows(Exception::class.java) {
                send(" ".repeat(TilePinWire.MAX_BYTES + 1).toByteArray())
            }
        } finally {
            file.delete()
        }
    }
}
