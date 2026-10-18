package dev.quantumink.hermesgadget.client

import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsSnapshot
import dev.quantumink.hermesgadget.protocol.ConnectionStage
import dev.quantumink.hermesgadget.protocol.DiagnosticAudioStage
import dev.quantumink.hermesgadget.protocol.DiagnosticTransport
import dev.quantumink.hermesgadget.protocol.FailureCategory
import dev.quantumink.hermesgadget.protocol.RequestStage
import dev.quantumink.hermesgadget.protocol.TurnCorrelation

/** Display-only projection of the safe snapshot. Never accepts raw connection/server text. */
object ConnectionHealth {
    const val MEASUREMENT_LIMIT =
        "Stock servers do not report separate transcription, tool or model timings. " +
            "These stages describe only what this device observed, not durable task acceptance."
    const val AUDIO_LIMIT = "Received audio describes protocol frames, not speaker playback."

    fun connection(snapshot: ConnectionDiagnosticsSnapshot): String =
        when (snapshot.connectionStage) {
            ConnectionStage.OFFLINE -> "Offline"
            ConnectionStage.CONNECTING -> "Connecting"
            ConnectionStage.SOCKET_OPEN -> "Socket open"
            ConnectionStage.AUTHENTICATING -> "Authenticating"
            ConnectionStage.PAIRING -> "Awaiting pairing approval"
            ConnectionStage.READY -> "Ready"
            ConnectionStage.RETRYING -> "Retrying connection"
            ConnectionStage.FAILED -> "Connection failed"
        }

    fun request(snapshot: ConnectionDiagnosticsSnapshot): String = when (snapshot.requestStage) {
        RequestStage.IDLE -> "No active request"
        RequestStage.RECORDING -> "Recording locally"
        RequestStage.UPLOADING -> "Uploading audio"
        RequestStage.WAITING -> "Waiting for observed response"
        RequestStage.TRANSCRIBED -> "Transcript received"
        RequestStage.RECEIVING -> "Receiving response"
        RequestStage.COMPLETE -> "Request complete"
        RequestStage.CANCELLED -> "Request cancelled locally"
        RequestStage.FAILED -> "Request interrupted"
    }

    fun summary(snapshot: ConnectionDiagnosticsSnapshot): String =
        connection(snapshot) + " · " + request(snapshot) + if (snapshot.requestStage in active) {
            " · ${duration(snapshot.elapsedMillis)} elapsed"
        } else {
            ""
        }

    fun details(snapshot: ConnectionDiagnosticsSnapshot): List<String> = listOf(
        "Route: " + when (snapshot.transport) {
            DiagnosticTransport.DIRECT -> "Direct"
            DiagnosticTransport.PHONE_RELAY -> "Phone relay"
            DiagnosticTransport.UNKNOWN -> "Not observed"
        },
        "Enrollment: " + when (snapshot.connectionStage) {
            ConnectionStage.READY -> "Paired for this connection"
            ConnectionStage.PAIRING -> "Awaiting approval on your server"
            else -> "Not confirmed for this connection"
        },
        "Connection: " + connection(snapshot),
        "Request: " + request(snapshot),
        "Failure category: " + when (snapshot.failureCategory) {
            FailureCategory.NONE -> "None observed"
            FailureCategory.TLS -> "TLS security"
            FailureCategory.ADDRESS_POLICY -> "Address policy"
            FailureCategory.REACHABILITY -> "Reachability"
            FailureCategory.AUTH -> "Authentication"
            FailureCategory.PROTOCOL -> "Protocol"
            FailureCategory.TIMEOUT -> "Timeout"
            FailureCategory.OVERLOAD -> "Capacity"
            FailureCategory.DISCONNECTED -> "Disconnected"
        },
        "Request elapsed: ${duration(snapshot.elapsedMillis)}",
        if (snapshot.requestStage in active) {
            "Last observed request progress: ${duration(snapshot.lastProgressAgeMillis)} ago"
        } else {
            "Last observed request progress: no active request"
        },
        "Received audio: " + when (snapshot.audioStage) {
            DiagnosticAudioStage.IDLE -> "None in progress"
            DiagnosticAudioStage.RECEIVING -> "Receiving frames"
            DiagnosticAudioStage.COMPLETE -> "Frame stream complete"
            DiagnosticAudioStage.INTERRUPTED -> "Frame stream interrupted"
        },
        "Turn correlation: " + when (snapshot.turnCorrelation) {
            TurnCorrelation.UNOBSERVED -> "Not observed"
            TurnCorrelation.OBSERVED -> "Observed in this connection"
            TurnCorrelation.HISTORY_LIMIT -> "Local history limit; progress may be unassigned"
        }
    ) + if (snapshot.stalled) {
        listOf("Still waiting. No recent request progress observed; server work may continue.")
    } else {
        emptyList()
    }

    fun playback(speaking: Boolean, preparing: Boolean): String = "Native playback: " + when {
        speaking -> "Playing"
        preparing -> "Preparing voice"
        else -> "Idle"
    }

    private val active = setOf(
        RequestStage.RECORDING,
        RequestStage.UPLOADING,
        RequestStage.WAITING,
        RequestStage.TRANSCRIBED,
        RequestStage.RECEIVING
    )

    private fun duration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }
}
