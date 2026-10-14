package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.quantumink.hermesgadget.protocol.TilePinPhoneState
import dev.quantumink.hermesgadget.protocol.TilePinRequest
import dev.quantumink.hermesgadget.protocol.TilePinWire
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TileWatch(val id: String, val name: String)
internal interface TilePinTransport {
    fun local(): String
    fun peers(): List<TileWatch>
    fun publish(bytes: ByteArray)
    fun send(peer: String, bytes: ByteArray)
}
internal class WearTilePinTransport(private val context: Context) : TilePinTransport {
    private fun <T> wait(task: com.google.android.gms.tasks.Task<T>): T =
        Tasks.await(task, 5, TimeUnit.SECONDS)
    override fun local(): String = wait(Wearable.getNodeClient(context).localNode).id
    override fun peers(): List<TileWatch> =
        wait(Wearable.getNodeClient(context).connectedNodes).map {
            TileWatch(it.id, it.displayName)
        }
    override fun publish(bytes: ByteArray) {
        wait(
            Wearable.getDataClient(
                context
            ).putDataItem(
                PutDataRequest.create(TilePinWire.REQUEST_PATH).setData(bytes).setUrgent()
            )
        )
    }
    override fun send(peer: String, bytes: ByteArray) {
        wait(Wearable.getMessageClient(context).sendMessage(peer, TilePinWire.REQUEST_PATH, bytes))
    }
}

/** One selected watch, one durable outbox. Changing watch explicitly discards the old binding. */
internal class TilePinSync(private val file: File, private val transport: TilePinTransport) {
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private val mutableState = MutableStateFlow<TilePinPhoneState?>(null)
    val state = mutableState.asStateFlow()
    private val mutablePeers = MutableStateFlow<List<TileWatch>>(emptyList())
    val peers = mutablePeers.asStateFlow()
    private val mutableNotice = MutableStateFlow("Connect your watch to read its tile settings.")
    val notice = mutableNotice.asStateFlow()
    private var loaded = false
    private fun load() {
        if (loaded) return
        val atomic = AtomicFile(file)
        if (file.exists() || File(file.path + ".bak").exists()) {
            mutableState.value = atomic.openRead().use {
                require(it.channel.size() in 1..TilePinWire.MAX_BYTES)
                TilePinWire.cache(it.readBytes())
            }
        }
        loaded = true
    }
    private fun save(value: TilePinPhoneState) {
        val bytes = TilePinWire.cache(value)
        check(file.parentFile?.let { it.exists() || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        mutableState.value = value
    }
    private fun work(action: () -> Unit) {
        runCatching {
            worker.execute {
                runCatching {
                    load()
                    action()
                }.onFailure {
                    mutableNotice.value =
                        "Watch unavailable. Your saved edit is kept; retry when connected."
                }
            }
        }.onFailure { mutableNotice.value = "Tile sync is busy. Try again." }
    }
    fun refresh() = work {
        mutablePeers.value = transport.peers()
        val current = mutableState.value ?: return@work
        if (current.peer !in mutablePeers.value.map { it.id }) {
            mutableNotice.value = "Watch offline. Saved edits wait for delivery."
            return@work
        }
        deliver(current)
    }
    fun select(peer: String) = work {
        val available = transport.peers()
        require(available.any { it.id == peer })
        mutablePeers.value = available
        val state = mutableState.value?.takeIf { it.peer == peer } ?: TilePinPhoneState(peer)
        save(state)
        deliver(state)
    }
    fun submit(baseRevision: Long, focus: String?, grid: List<String?>) = work {
        val next = requireNotNull(
            mutableState.value
        ).queue(UUID.randomUUID().toString(), focus, grid, baseRevision)
        save(next)
        mutableNotice.value = "Pending watch confirmation"
        deliver(next)
    }
    fun retry() = work { deliver(requireNotNull(mutableState.value)) }
    fun useWatch() = work {
        val current = requireNotNull(mutableState.value)
        require(current.issue != null)
        val accepted = current.copy(pending = null, issue = null)
        save(accepted)
        mutableNotice.value = "Using the watch configuration"
        deliver(accepted)
    }
    private fun deliver(current: TilePinPhoneState) {
        val pending = current.pending?.takeIf { current.issue == null }
        val request = TilePinWire.request(
            TilePinRequest(
                current.peer,
                pending?.requestId ?: UUID.randomUUID().toString(),
                pending
            )
        )
        // Durable DataItem queues the edit even while disconnected. Messages make retry idempotent
        // when an unchanged DataItem would not emit a second event.
        // Read requests replace obsolete durable edits when switching watch or accepting its state.
        transport.publish(request)
        transport.send(current.peer, request)
        mutableNotice.value =
            if (pending == null) "Reading watch settings…" else "Pending watch confirmation"
    }
    fun receive(source: String, bytes: ByteArray) {
        if (bytes.size !in 1..TilePinWire.MAX_BYTES) return
        work {
            val current = mutableState.value ?: return@work
            if (source != current.peer || transport.peers().none { it.id == source }) return@work
            val response = TilePinWire.response(bytes)
            if (response.target != transport.local()) return@work
            val next = current.receive(source, response)
            save(next)
            mutableNotice.value = when {
                next.issue == "conflict" ->
                    "Watch changed. Review both versions before applying your edit."
                next.issue == "rejected" ->
                    "The watch rejected this edit. Choose profiles currently on the watch."
                next.pending != null -> "Pending watch confirmation"
                else -> "Confirmed by watch · revision ${next.snapshot?.pins?.revision}"
            }
        }
    }
    companion object {
        @Volatile private var instance: TilePinSync? = null
        fun get(context: Context): TilePinSync = instance ?: synchronized(this) {
            instance
                ?: TilePinSync(
                    File(context.applicationContext.filesDir, "phone-tile-pins"),
                    WearTilePinTransport(context.applicationContext)
                ).also {
                    instance =
                        it
                }
        }
    }
}

/** Play Services verifies paired-device package/signature. We also verify source and target. */
class TilePinSyncService : WearableListenerService() {
    override fun onCapabilityChanged(capability: com.google.android.gms.wearable.CapabilityInfo) {
        if (capability.name == "hermes_tile_watch_v1") TilePinSync.get(this).refresh()
    }
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val item = event.dataItem
            if (event.type == DataEvent.TYPE_CHANGED &&
                item.uri.path == TilePinWire.RESPONSE_PATH
            ) {
                val source = item.uri.host ?: continue
                val bytes = item.data ?: continue
                TilePinSync.get(this).receive(source, bytes)
            }
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path ==
            TilePinWire.RESPONSE_PATH
        ) {
            TilePinSync.get(this).receive(event.sourceNodeId, event.data)
        }
    }
}
