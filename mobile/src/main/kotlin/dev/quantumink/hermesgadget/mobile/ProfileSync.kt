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
    }
}
