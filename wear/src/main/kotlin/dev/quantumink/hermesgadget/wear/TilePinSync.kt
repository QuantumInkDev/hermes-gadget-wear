package dev.quantumink.hermesgadget.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.quantumink.hermesgadget.protocol.TilePinResponse
import dev.quantumink.hermesgadget.protocol.TilePinResult
import dev.quantumink.hermesgadget.protocol.TilePinSnapshot
import dev.quantumink.hermesgadget.protocol.TilePinWire
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded worker, separate from conversation/audio and tile rendering. */
internal object TilePinSync {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private fun <T> wait(task: com.google.android.gms.tasks.Task<T>): T =
        Tasks.await(task, 5, TimeUnit.SECONDS)
    private fun keys(context: Context): Set<String> = ProfilePages.merge(
        IdentityVault(context).profiles(),
        WatchCatalogVault(context).pages()
    ).map { it.key }.toSet()

    /** Source comes from authenticated Data Layer metadata, never from the payload. */
    fun respond(
        source: String,
        local: String,
        peers: Set<String>,
        bytes: ByteArray,
        store: TilePinStore,
        known: () -> Set<String>
    ): ByteArray? {
        if (peers != setOf(source)) return null
        val request = TilePinWire.request(bytes)
        if (request.target != local) return null
        val membership = known()
        val result = request.edit?.let { store.apply(it, membership) }
        val current = store.read()
        val status = when (result) {
            is TilePinResult.Accepted -> "accepted"
            is TilePinResult.Current -> "current"
            is TilePinResult.Conflict -> "conflict"
            is TilePinResult.Rejected -> "rejected"
            null -> "snapshot"
        }
        return TilePinWire.response(
            TilePinResponse(
                source,
                request.requestId,
                status,
                result?.pins ?: current,
                TilePinSnapshot(current, membership)
            )
        )
    }
    fun receive(context: Context, source: String, bytes: ByteArray) {
        if (bytes.size !in 1..TilePinWire.MAX_BYTES) return
        val app = context.applicationContext
        runCatching {
            worker.execute {
                runCatching {
                    val nodes = Wearable.getNodeClient(app)
                    val response =
                        respond(
                            source,
                            wait(nodes.localNode).id,
                            wait(nodes.connectedNodes).map {
                                it.id
                            }.toSet(),
                            bytes,
                            TilePinStore(app)
                        ) { keys(app) }
                    if (response != null) publish(app, source, response)
                }
            }
        }
    }
    fun reconnect(context: Context) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                runCatching reconnect@{
                    val nodes = Wearable.getNodeClient(app)
                    val peer = wait(nodes.connectedNodes).singleOrNull() ?: return@reconnect
                    val uri = android.net.Uri.Builder().scheme(
                        "wear"
                    ).authority(peer.id).path(TilePinWire.REQUEST_PATH).build()
                    val item =
                        wait(Wearable.getDataClient(app).getDataItem(uri)) ?: return@reconnect
                    val bytes = item.data ?: return@reconnect
                    val response =
                        respond(
                            peer.id,
                            wait(nodes.localNode).id,
                            setOf(peer.id),
                            bytes,
                            TilePinStore(app)
                        ) {
                            keys(app)
                        }
                    if (response != null) publish(app, peer.id, response)
                }
            }
        }
    }
    fun changed(context: Context) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                runCatching {
                    val peer =
                        wait(Wearable.getNodeClient(app).connectedNodes).singleOrNull()
                            ?: return@runCatching
                    val pins = TilePinStore(app).read()
                    publish(
                        app,
                        peer.id,
                        TilePinWire.response(
                            TilePinResponse(
                                peer.id,
                                UUID.randomUUID().toString(),
                                "snapshot",
                                pins,
                                TilePinSnapshot(pins, keys(app))
                            )
                        )
                    )
                }
            }
        }
    }
    private fun publish(context: Context, target: String, bytes: ByteArray) {
        wait(
            Wearable.getDataClient(
                context
            ).putDataItem(
                PutDataRequest.create(TilePinWire.RESPONSE_PATH).setData(bytes).setUrgent()
            )
        )
        wait(
            Wearable.getMessageClient(context).sendMessage(target, TilePinWire.RESPONSE_PATH, bytes)
        )
    }
}

class TilePinSyncService : WearableListenerService() {
    override fun onCapabilityChanged(capability: com.google.android.gms.wearable.CapabilityInfo) {
        if (capability.name == "hermes_tile_phone_v1") TilePinSync.reconnect(this)
    }
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val item = event.dataItem
            if (event.type == DataEvent.TYPE_CHANGED && item.uri.path == TilePinWire.REQUEST_PATH) {
                TilePinSync.receive(this, item.uri.host ?: continue, item.data ?: continue)
            }
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path ==
            TilePinWire.REQUEST_PATH
        ) {
            TilePinSync.receive(this, event.sourceNodeId, event.data)
        }
    }
}
