package dev.quantumink.hermesgadget.mobile

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.EndpointProfiles
import java.util.concurrent.TimeUnit

object ProfileSync {
    fun publish(context: Context, vault: ProfileVault) {
        val record = PutDataMapRequest.create(EndpointProfiles.SETUP_PATH)
        record.dataMap.putString("profiles", EndpointProfiles.encode(vault.profiles()))
        Tasks.await(
            Wearable.getDataClient(context).putDataItem(record.asPutDataRequest().setUrgent()),
            5,
            TimeUnit.SECONDS
        )
        CatalogRepository.get(context).refresh(true)
    }

    @Synchronized fun publishCatalog(
        context: Context,
        pages: List<dev.quantumink.hermesgadget.protocol.CatalogPage>,
        portraits: PortraitStore,
        animations: Map<String, dev.quantumink.hermesgadget.protocol.AnimationDescriptor> =
            emptyMap(),
        animationCache: dev.quantumink.hermesgadget.client.AnimationCache? = null
    ) {
        val client = Wearable.getDataClient(context)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        fun <T> await(task: com.google.android.gms.tasks.Task<T>): T {
            val remaining = deadline - System.nanoTime()
            require(remaining > 0) { "Catalog sync deadline exceeded." }
            return Tasks.await(task, remaining, TimeUnit.NANOSECONDS)
        }
        val owner = await(Wearable.getNodeClient(context).localNode).id
        CatalogPublication.publish(
            pages,
            { hash -> portraits.file(hash)?.readBytes() },
            object : CatalogPublicationTransport {
                override val owner = owner
                private val assets = mutableListOf<com.google.android.gms.tasks.Task<*>>()
                private val deletions = mutableListOf<com.google.android.gms.tasks.Task<*>>()
                override fun portrait(hash: String, bytes: ByteArray) {
                    val item = PutDataMapRequest.create(
                        dev.quantumink.hermesgadget.protocol.ProfileCatalog.PORTRAIT_PATH + hash
                    )
                    item.dataMap.putAsset(
                        "portrait",
                        com.google.android.gms.wearable.Asset.createFromBytes(bytes)
                    )
                    assets.add(client.putDataItem(item.asPutDataRequest()))
                }
                override fun pages(json: String) {
                    if (assets.isNotEmpty()) await(Tasks.whenAll(assets))
                    val record = PutDataMapRequest.create(
                        dev.quantumink.hermesgadget.protocol.ProfileCatalog.DATA_PATH
                    )
                    record.dataMap.putString("pages", json)
                    await(client.putDataItem(record.asPutDataRequest().setUrgent()))
                }
                override fun items(): List<CatalogDataItem> {
                    val buffer = await(client.dataItems)
                    return try {
                        buffer.mapNotNull { item ->
                            val host = item.uri.host
                            val path = item.uri.path
                            if (host == null || path == null) null else CatalogDataItem(host, path)
                        }
                    } finally {
                        buffer.release()
                    }
                }
                override fun delete(item: CatalogDataItem) {
                    deletions.add(
                        client.deleteDataItems(
                            android.net.Uri.Builder().scheme(
                                "wear"
                            ).authority(item.owner).path(item.path).build()
                        )
                    )
                }
                override fun finishCleanup() {
                    if (deletions.isNotEmpty()) await(Tasks.whenAll(deletions))
                }
            }
        )
        if (animationCache != null) {
            AnimationPublication.publish(
                pages,
                animations,
                {
                    animationCache.file(it)?.readBytes()
                },
                object : AnimationPublicationTransport {
                    override val owner = owner
                    private val assets = mutableListOf<com.google.android.gms.tasks.Task<*>>()
                    private val deletions = mutableListOf<com.google.android.gms.tasks.Task<*>>()
                    override fun asset(hash: String, bytes: ByteArray) {
                        val item = PutDataMapRequest.create(
                            dev.quantumink.hermesgadget.protocol.PortraitAnimations.ASSET_PATH +
                                hash
                        )
                        item.dataMap.putAsset(
                            "animation",
                            com.google.android.gms.wearable.Asset.createFromBytes(bytes)
                        )
                        assets.add(client.putDataItem(item.asPutDataRequest()))
                    }
                    override fun manifest(json: String) {
                        if (assets.isNotEmpty()) await(Tasks.whenAll(assets))
                        val item = PutDataMapRequest.create(
                            dev.quantumink.hermesgadget.protocol.PortraitAnimations.DATA_PATH
                        )
                        item.dataMap.putString("animations", json)
                        await(client.putDataItem(item.asPutDataRequest().setUrgent()))
                    }
                    override fun items(): List<CatalogDataItem> {
                        val buffer = await(client.dataItems)
                        return try {
                            buffer.mapNotNull { item ->
                                val host = item.uri.host
                                val path = item.uri.path
                                if (host == null ||
                                    path == null
                                ) {
                                    null
                                } else {
                                    CatalogDataItem(host, path)
                                }
                            }
                        } finally {
                            buffer.release()
                        }
                    }
                    override fun delete(item: CatalogDataItem) {
                        deletions.add(
                            client.deleteDataItems(
                                android.net.Uri.Builder().scheme("wear")
                                    .authority(item.owner).path(item.path).build()
                            )
                        )
                    }
                    override fun finishCleanup() {
                        if (deletions.isNotEmpty()) await(Tasks.whenAll(deletions))
                    }
                }
            )
        }
    }
}
