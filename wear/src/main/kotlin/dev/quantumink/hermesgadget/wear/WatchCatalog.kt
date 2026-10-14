package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WatchCatalog(private val context: Context) {
    private val data = Wearable.getDataClient(context)
    fun cached(node: String): List<CatalogPage>? {
        val uri = Uri.Builder().scheme(
            "wear"
        ).authority(node).path(ProfileCatalog.DATA_PATH).build()
        val item = Tasks.await(data.getDataItem(uri), 5, TimeUnit.SECONDS) ?: return null
        return ProfileCatalog.decodePages(
            DataMapItem.fromDataItem(item).dataMap.getString("pages").orEmpty()
        )
    }
    fun refresh(node: String, force: Boolean): List<CatalogPage> {
        val client = Wearable.getChannelClient(context)
        val abandoned = AtomicBoolean()
        val task = client.openChannel(node, ProfileCatalog.REFRESH_PATH)
        task.addOnSuccessListener { if (abandoned.get()) client.close(it) }
        val channel = try {
            Tasks.await(task, 5, TimeUnit.SECONDS)
        } catch (
            failure: Exception
        ) {
            abandoned.set(true)
            throw failure
        }
        val clock = Executors.newSingleThreadScheduledExecutor()
        val deadline = clock.schedule({ client.close(channel) }, 20, TimeUnit.SECONDS)
        try {
            val output = Tasks.await(client.getOutputStream(channel), 5, TimeUnit.SECONDS)
            val input = Tasks.await(client.getInputStream(channel), 5, TimeUnit.SECONDS)
            output.write(if (force) 1 else 0)
            output.flush()
            val reader = DataInputStream(input)
            val length = reader.readInt()
            require(length in 1..ProfileCatalog.MAX_BYTES)
            val bytes = ByteArray(length)
            reader.readFully(bytes)
            return ProfileCatalog.decodePages(bytes.toString(Charsets.UTF_8))
        } finally {
            abandoned.set(true)
            deadline.cancel(false)
            client.close(channel)
            clock.shutdownNow()
        }
    }
    fun portrait(node: String, descriptor: PortraitDescriptor): ByteArray? {
        val uri = Uri.Builder().scheme(
            "wear"
        ).authority(node).path(ProfileCatalog.PORTRAIT_PATH + descriptor.sha256).build()
        val item = Tasks.await(data.getDataItem(uri), 5, TimeUnit.SECONDS) ?: return null
        val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("portrait") ?: return null
        val response = Tasks.await(data.getFdForAsset(asset), 5, TimeUnit.SECONDS)
        try {
            return response.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= descriptor.bytes) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            descriptor.bytes + 1 - output.size()
                        )
                    )
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            response.release()
        }
    }

    fun animations(
        node: String
    ): Map<String, dev.quantumink.hermesgadget.protocol.AnimationDescriptor>? {
        val uri = Uri.Builder().scheme("wear").authority(node)
            .path(dev.quantumink.hermesgadget.protocol.PortraitAnimations.DATA_PATH).build()
        val item = Tasks.await(data.getDataItem(uri), 5, TimeUnit.SECONDS) ?: return null
        require(
            requireNotNull(item.data).size <=
                dev.quantumink.hermesgadget.protocol.PortraitAnimations.MAX_BYTES + 512
        )
        val json =
            DataMapItem.fromDataItem(item).dataMap.getString("animations")
                ?: error("Missing animation metadata")
        return dev.quantumink.hermesgadget.protocol.PortraitAnimations.decode(json)
    }

    fun animation(
        node: String,
        descriptor: dev.quantumink.hermesgadget.protocol.AnimationDescriptor
    ): ByteArray? {
        val uri = Uri.Builder().scheme("wear").authority(node)
            .path(
                dev.quantumink.hermesgadget.protocol.PortraitAnimations.ASSET_PATH +
                    descriptor.sha256
            ).build()
        val item = Tasks.await(data.getDataItem(uri), 5, TimeUnit.SECONDS) ?: return null
        require(requireNotNull(item.data).size <= 1024)
        val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("animation") ?: return null
        val response = Tasks.await(data.getFdForAsset(asset), 5, TimeUnit.SECONDS)
        try {
            return response.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= descriptor.bytes) {
                    val count = input.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            descriptor.bytes + 1 - output.size()
                        )
                    )
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { require(it.size == descriptor.bytes) }
            }
        } finally {
            response.release()
        }
    }
}
