package dev.quantumink.hermesgadget.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A local-only setting in its own file. Publish only after a successful durable write. */
class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("appearance-v1", Context.MODE_PRIVATE)
    private val current = synchronized(states) {
        states.getOrPut(preferences) { MutableStateFlow(read()) }.also { it.value = read() }
    }
    val mode: StateFlow<Appearance> = current.asStateFlow()

    // KTX edit returns Unit; this store must reject a failed commit before publishing the mode.
    @SuppressLint("UseKtx")
    fun set(mode: Appearance) {
        synchronized(states) {
            check(preferences.edit().putString("mode", mode.name).commit()) {
                "Could not save appearance"
            }
            current.value = mode
        }
    }

    private fun read(): Appearance = runCatching {
        Appearance.valueOf(preferences.getString("mode", null).orEmpty())
    }.getOrDefault(Appearance.DARK)

    private companion object {
        // Shared UI subscribers in one process; phone/watch have separate local stores.
        val states = mutableMapOf<SharedPreferences, MutableStateFlow<Appearance>>()
    }
}
