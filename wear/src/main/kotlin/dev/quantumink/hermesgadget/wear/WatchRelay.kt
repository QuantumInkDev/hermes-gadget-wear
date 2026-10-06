package dev.quantumink.hermesgadget.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.LoopbackConnectBridge
import dev.quantumink.hermesgadget.protocol.RelayForwarder
import dev.quantumink.hermesgadget.protocol.RelayProtocol
import dev.quantumink.hermesgadget.protocol.RelayStreams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

class WatchRelay(context: Context, endpoint: Endpoint, private val nodeId: String) : AutoCloseable {
    private val channels = Wearable.getChannelClient(context)
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<ChannelClient.Channel, RelayStreams>()
    private val clock = Executors.newSingleThreadScheduledExecutor()
    val bridge = LoopbackConnectBridge(endpoint) { open(endpoint) }
    val client: OkHttpClient = bridge.client()

    private fun open(endpoint: Endpoint): RelayStreams {
        check(!closed.get())
        val abandoned = AtomicBoolean(false)
        val task = channels.openChannel(nodeId, RelayProtocol.CHANNEL_PATH)
        task.addOnSuccessListener { channel ->
            if (closed.get() || abandoned.get()) channels.close(channel)
        }
        val channel = try {
            Tasks.await(task, 10, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            abandoned.set(true)
            throw failure
        }
        val deadline = try {
            clock.schedule({ channels.close(channel) }, 10, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            channels.close(channel)
            throw failure
        }
        try {
            check(!closed.get())
            val input = Tasks.await(channels.getInputStream(channel), 5, TimeUnit.SECONDS)
            val output = Tasks.await(channels.getOutputStream(channel), 5, TimeUnit.SECONDS)
            val stream = RelayStreams(input, output) {
                sessions.remove(channel)
                channels.close(channel)
            }
            sessions[channel] = stream
            check(!closed.get())
            return RelayForwarder.open(stream, endpoint)
        } catch (failure: Exception) {
            sessions.remove(channel)?.close()
            channels.close(channel)
            throw failure
        } finally {
            deadline.cancel(false)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bridge.close()
        sessions.values.forEach { it.close() }
        sessions.clear()
        clock.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        fun reachablePhone(context: Context): String? = runCatching {
            Tasks.await(
                Wearable.getCapabilityClient(context).getCapability(
                    RelayProtocol.CAPABILITY,
                    CapabilityClient.FILTER_REACHABLE
                ),
                5,
                TimeUnit.SECONDS
            ).nodes.sortedWith(
                compareByDescending<com.google.android.gms.wearable.Node> {
                    it.isNearby
                }.thenBy { it.id }
            ).firstOrNull()?.id
        }.getOrNull()
    }
}
