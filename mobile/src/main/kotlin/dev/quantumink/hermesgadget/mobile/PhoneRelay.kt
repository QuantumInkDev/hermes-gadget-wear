package dev.quantumink.hermesgadget.mobile

import android.app.Application
import android.os.SystemClock
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayOptIn
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayTiming
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy

/** Where a typed message or a hold-to-talk press goes. Inside a SELECTED Relay conversation nothing reaches the stock chat. */
internal enum class InputRoute { STOCK, RELAY, UNAVAILABLE }

internal object PhoneRelay {
    /** The production controller: its own encrypted store, the real monotonic clock and a cryptographic id source. */
    fun controller(application: Application, listener: (RelayUiState) -> Unit): RelayController =
        (application as CompanionApplication).inbox.listener(listener)

    private val testSessions = java.util.concurrent.atomic.AtomicLong()
    fun foreground(application: Application, withdraw: () -> Unit = {}): Long =
        (application as? CompanionApplication)?.inbox?.foreground(withdraw)
            ?: testSessions.incrementAndGet()
    fun current(application: Application, id: Long): Boolean =
        (application as? CompanionApplication)?.inbox?.current(id) != false
    fun release(application: Application, id: Long) {
        (application as? CompanionApplication)?.inbox?.release(id)
    }

    /** Typed text: the Relay composer owns it once a Relay conversation is selected; otherwise the stock chat as before. */
    fun typedRoute(relay: RelayUiState): InputRoute = if (relay.retainedSelected != null) {
        InputRoute.UNAVAILABLE
    } else if (relay.relayOwnsComposer) {
        InputRoute.RELAY
    } else {
        InputRoute.STOCK
    }

    /**
     * Hold-to-talk. Stock stays exactly as it was while Relay is off or no Relay conversation is selected. Inside a selected
     * Relay conversation the microphone is unavailable until canonical voice (V5) is reviewed: its audio is NEVER routed to the
     * physical-device stock chat, and a stock transcript is never resubmitted.
     */
    fun voiceRoute(relay: RelayUiState): InputRoute =
        if (relay.relayOwnsComposer) InputRoute.UNAVAILABLE else InputRoute.STOCK

    /** The Relay opt-in of ONE connection: only when the user turned Relay on for this profile and its state is readable. */
    fun optIn(
        enabled: Boolean,
        nanos: () -> Long = SystemClock::elapsedRealtimeNanos,
        listener: (state: RelayChannelState, generation: Long) -> Unit
    ): RelayOptIn? = if (enabled) {
        RelayOptIn({ state, generation ->
            listener(state, generation)
        }, nanos)
    } else {
        null
    }

    /** True once Relay state was ever written on this phone: only then is an enrolled identity read when the screen opens. */
    fun hasState(application: Application): Boolean =
        java.io.File(application.noBackupFilesDir, EncryptedRelayStore.DIRECTORY).exists()
}
