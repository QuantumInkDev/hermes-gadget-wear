package dev.quantumink.hermesgadget.client

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only a nonsecret UI boolean crosses this optional versioned Data Layer path. */
object CuePreferences {
    const val DATA_PATH = "/hermes/ui/cues/v1"
    private val mutableEnabled = MutableStateFlow(true)
    val enabled = mutableEnabled.asStateFlow()
    private var preferences: SharedPreferences? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener {
            prefs,
            _
        ->
        mutableEnabled.value = prefs.getBoolean("enabled", true)
    }

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("recording-cues", Context.MODE_PRIVATE)
        preferences = prefs
        mutableEnabled.value = prefs.getBoolean("enabled", true)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        val client = Wearable.getDataClient(app)
        client.addListener(
            DataClient.OnDataChangedListener { events ->
                for (event in events) {
                    if (event.dataItem.uri.path ==
                        DATA_PATH
                    ) {
                        accept(event.dataItem)
                    }
                }
            }
        )
        client.getDataItems("wear://*$DATA_PATH".toUri(), DataClient.FILTER_LITERAL)
            .addOnSuccessListener { items ->
                try {
                    for (item in items) accept(item)
                } finally {
                    items.release()
                }
            }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun accept(item: com.google.android.gms.wearable.DataItem) {
        if ((item.data?.size ?: 513) > 512) return
        runCatching {
            val map = DataMapItem.fromDataItem(item).dataMap
            val bundle = map.toBundle()
            require(map.keySet() == setOf("version", "enabled", "changed"))
            require(
                bundle.get("version") == 1 && bundle.get("enabled") is Boolean &&
                    bundle.get("changed") is Long
            )
            val changed = map.getLong("changed")
            require(changed in 1..System.currentTimeMillis() + 86_400_000)
            val prefs = requireNotNull(preferences)
            if (changed > prefs.getLong("changed", 0)) {
                prefs.edit {
                    putBoolean("enabled", map.getBoolean("enabled"))
                    putLong("changed", changed)
                }
                mutableEnabled.value = map.getBoolean("enabled")
            }
        }
    }

    @Synchronized fun set(context: Context, enabled: Boolean) {
        initialize(context)
        val prefs = requireNotNull(preferences)
        val changed = maxOf(
            System.currentTimeMillis(),
            prefs.getLong("changed", 0) + 1
        )
        prefs.edit {
            putBoolean("enabled", enabled)
            putLong("changed", changed)
        }
        mutableEnabled.value = enabled
        val request = PutDataMapRequest.create(DATA_PATH)
        request.dataMap.putInt("version", 1)
        request.dataMap.putBoolean("enabled", enabled)
        request.dataMap.putLong("changed", changed)
        Wearable.getDataClient(
            context.applicationContext
        ).putDataItem(request.asPutDataRequest().setUrgent())
    }
}
