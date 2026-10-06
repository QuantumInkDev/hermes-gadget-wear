package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.SpeechRequest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WatchSpeech(
    private val context: Context,
    private val endpoint: Endpoint,
    private val node: String,
    private val opus: Boolean
) : AutoCloseable {
    private val client = Wearable.getChannelClient(context)
    private val callbackLock = Any()
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    private val active = ConcurrentHashMap<ChannelClient.Channel, AutoCloseable>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val clock = Executors.newSingleThreadScheduledExecutor()
    fun speak(
        id: String,
        text: String,
        voice: String,
        start: (ByteArray?) -> Unit,
        data: (ByteArray) -> Unit,
        finish: () -> Unit,
        failed: () -> Unit
    ) {
        stop()
        val version = epoch.get()
        if (closed.get()) return
        runCatching {
            worker.execute {
                var channel: ChannelClient.Channel? = null
                var deadline: java.util.concurrent.ScheduledFuture<*>? = null
                try {
                    if (version != epoch.get() || closed.get()) return@execute
                    val abandoned = AtomicBoolean()
                    val task = client.openChannel(node, ClientSpeech.CHANNEL_PATH)
                    task.addOnSuccessListener {
                        if (abandoned.get() || version != epoch.get() ||
                            closed.get()
                        ) {
                            client.close(it)
                        }
                    }
                    channel =
                        try {
                            Tasks.await(task, 10, TimeUnit.SECONDS)
                        } catch (
                            failure: Exception
                        ) {
                            abandoned.set(true)
                            throw failure
                        }
                    val opened = channel
                    deadline = clock.schedule({ client.close(opened) }, 75, TimeUnit.SECONDS)
                    val input = Tasks.await(client.getInputStream(opened), 5, TimeUnit.SECONDS)
                    val output = Tasks.await(client.getOutputStream(opened), 5, TimeUnit.SECONDS)
                    active[opened] =
                        AutoCloseable {
                            runCatching { input.close() }
                            runCatching { output.close() }
                            client.close(opened)
                        }
                    check(version == epoch.get() && !closed.get())
                    val format = if (opus) "opus" else "pcm16"
                    ClientSpeech.request(
                        output,
                        SpeechRequest(ClientSpeech.endpointId(endpoint), id, text, voice, format)
                    )
                    ClientSpeech.receive(input, format, { deliver(version) { start(it) } }, {
                        check(version == epoch.get() && !closed.get())
                        deliver(version) { data(it) }
                    })
                    deliver(version, finish)
                } catch (_: Exception) {
                    deliver(version, failed)
                } finally {
                    deadline?.cancel(false)
                    channel?.let {
                        active.remove(it)?.close()
                        client.close(it)
                    }
                }
            }
        }.onFailure { deliver(version, failed) }
    }
    private fun deliver(version: Long, callback: () -> Unit) {
        synchronized(callbackLock) {
            if (version == epoch.get() && !closed.get()) callback()
        }
    }
    fun stop() {
        val closing = synchronized(callbackLock) {
            epoch.incrementAndGet()
            worker.queue.clear()
            active.values.toList().also { active.clear() }
        }
        closing.forEach { it.close() }
    }
    override fun close() {
        closed.set(true)
        stop()
        worker.shutdownNow()
        clock.shutdownNow()
    }
    companion object {
        fun ready(context: Context, endpoint: Endpoint, node: String): Boolean = runCatching {
            val uri = Uri.Builder().scheme(
                "wear"
            ).authority(
                node
            ).path(ClientSpeech.SETTINGS_PATH + ClientSpeech.endpointId(endpoint)).build()
            val item = Tasks.await(
                Wearable.getDataClient(context).getDataItem(uri),
                3,
                TimeUnit.SECONDS
            )
            val map = DataMapItem.fromDataItem(item).dataMap
            map.getBoolean("ready") && map.getString("mode") in setOf("SHARED", "PROFILE")
        }.getOrDefault(false)
    }
}
