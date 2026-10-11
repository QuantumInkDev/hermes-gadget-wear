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

    fun publishCatalog(
        context: Context,
        pages: List<dev.quantumink.hermesgadget.protocol.CatalogPage>,
        portraits: PortraitStore
    ) {
        val client = Wearable.getDataClient(context)
        val tasks = pages.mapNotNull { it.profile.portrait?.sha256 }.distinct().mapNotNull { hash ->
            portraits.file(hash)?.let { file ->
                val item = PutDataMapRequest.create(
                    dev.quantumink.hermesgadget.protocol.ProfileCatalog.PORTRAIT_PATH + hash
                )
                item.dataMap.putAsset(
                    "portrait",
                    com.google.android.gms.wearable.Asset.createFromBytes(file.readBytes())
                )
                client.putDataItem(item.asPutDataRequest())
            }
        }
        if (tasks.isNotEmpty()) Tasks.await(Tasks.whenAll(tasks), 5, TimeUnit.SECONDS)
        val record = PutDataMapRequest.create(
            dev.quantumink.hermesgadget.protocol.ProfileCatalog.DATA_PATH
        )
        record.dataMap.putString(
            "pages",
            dev.quantumink.hermesgadget.protocol.ProfileCatalog.encodePages(pages)
        )
        Tasks.await(client.putDataItem(record.asPutDataRequest().setUrgent()), 5, TimeUnit.SECONDS)
    }
}
