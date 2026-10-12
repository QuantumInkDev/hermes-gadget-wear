package dev.quantumink.hermesgadget.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.TilePinPhoneState
import dev.quantumink.hermesgadget.protocol.TilePinResponse
import dev.quantumink.hermesgadget.protocol.TilePinSnapshot
import dev.quantumink.hermesgadget.protocol.TilePinWire
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TilePinSyncTest {
    @Test fun queuedEditSurvivesRecreationAndChangedPeerCannotInheritIt() {
        NativePhoneFixture().use { ui ->
            val transport = FakeTileTransport()
            val file = File(ui.context.filesDir, "tiles")
            val sync = TilePinSync(file, transport)
            sync.select("watch")
            ui.await { sync.state.value != null }
            val snapshot = TilePinSnapshot(TilePins(), setOf("box:a", "box:b"))
            fun respond(
                source: String,
                target: String,
                id: String,
                result: String,
                pins: TilePins
            ) = sync.receive(
                source,
                TilePinWire.response(
                    TilePinResponse(target, id, result, pins, snapshot.copy(pins = pins))
                )
            )
            respond("unrelated", "phone", "read", "snapshot", TilePins(50))
            respond("watch", "unrelated", "read", "snapshot", TilePins(50))
            respond("watch", "phone", "read", "snapshot", TilePins())
            ui.await { sync.state.value?.snapshot != null }
            assertEquals(0L, sync.state.value!!.snapshot!!.pins.revision)
            transport.online = false
            sync.submit(0, "box:a", listOf("box:b"))
            ui.await { sync.state.value?.pending != null && transport.published.isNotEmpty() }
            val queued = sync.state.value!!
            val recreated = TilePinSync(file, transport)
            recreated.refresh()
            ui.await { recreated.state.value?.pending != null }
            assertEquals(queued, recreated.state.value)
            transport.online = true
            recreated.retry()
            ui.await {
                transport.sent.any {
                    TilePinWire.request(it).edit?.requestId ==
                        queued.pending!!.requestId
                }
            }
            recreated.select("other")
            ui.await { recreated.state.value?.peer == "other" }
            ui.await { TilePinWire.request(transport.published.last()).target == "other" }
            assertNull(TilePinWire.request(transport.published.last()).edit)
            assertEquals(TilePinPhoneState("other"), recreated.state.value)
            recreated.receive(
                "watch",
                TilePinWire.response(
                    TilePinResponse(
                        "phone",
                        queued.pending!!.requestId,
                        "accepted",
                        TilePins(1, "box:a"),
                        snapshot.copy(pins = TilePins(1, "box:a"))
                    )
                )
            )
            recreated.refresh()
            ui.await { transport.sent.count { TilePinWire.request(it).target == "other" } >= 2 }
            assertEquals(TilePinPhoneState("other"), recreated.state.value)
        }
    }
}

/** Run explicitly for environmental evidence, separate from fixture acceptance. */
class TilePinTransportAvailabilityTest {
    @Test fun recordRealDataLayerAvailability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 5, TimeUnit.SECONDS)
        android.util.Log.i("TileTransportGate", "connectedPeers=${nodes.size}")
        File(
            context.cacheDir,
            "task6-transport-availability.txt"
        ).writeText(
            "Connected Data Layer peers: ${nodes.size}\nActual transport acceptance requires an associated phone/watch pair.\n"
        )
    }
}

private class FakeTileTransport : TilePinTransport {
    @Volatile var online = true
    val published = CopyOnWriteArrayList<ByteArray>()
    val sent = CopyOnWriteArrayList<ByteArray>()
    override fun local() = "phone"
    override fun peers() = if (online) {
        listOf(
            TileWatch("watch", "Owned watch"),
            TileWatch("other", "Other watch")
        )
    } else {
        emptyList()
    }
    override fun publish(bytes: ByteArray) {
        published.add(bytes)
    }
    override fun send(peer: String, bytes: ByteArray) {
        check(online)
        sent.add(bytes)
    }
}
