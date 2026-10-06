package dev.quantumink.hermesgadget.mobile

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import java.util.concurrent.TimeUnit

object PhoneSpeech {
    fun publish(context: Context, vault: TtsVault) {
        val tasks = vault.endpoints().map { endpoint ->
            val settings = vault.selected(endpoint)
            val record = PutDataMapRequest.create(ClientSpeech.SETTINGS_PATH + endpoint)
            record.dataMap.putBoolean(
                "ready",
                settings.mode != SpeechMode.SERVER && settings.key.isNotEmpty()
            )
            record.dataMap.putString("mode", settings.mode.name)
            Wearable.getDataClient(context).putDataItem(record.asPutDataRequest().setUrgent())
        }
        if (tasks.isNotEmpty()) Tasks.await(Tasks.whenAll(tasks), 5, TimeUnit.SECONDS)
    }
}
