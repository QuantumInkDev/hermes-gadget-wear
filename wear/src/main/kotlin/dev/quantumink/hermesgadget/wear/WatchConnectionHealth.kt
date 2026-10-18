package dev.quantumink.hermesgadget.wear

import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import dev.quantumink.hermesgadget.client.ConnectionHealth
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsReport
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsSnapshot

/** Inline footer items: no overlay over artwork, no navigation or device/service actions. */
internal fun TransformingLazyColumnScope.connectionHealthItems(
    snapshot: ConnectionDiagnosticsSnapshot,
    speaking: Boolean,
    preparingSpeech: Boolean,
    open: Boolean,
    toggle: () -> Unit,
    preview: ConnectionDiagnosticsSnapshot?,
    viewReport: () -> Unit
) {
    item { Secondary(if (open) "Close connection health" else "Connection health", toggle) }
    item { Body(ConnectionHealth.summary(snapshot), muted = true) }
    if (!open) return
    item { Heading("Connection health") }
    item { Body("This watch only. No report is transferred to the phone.", muted = true) }
    ConnectionHealth.details(snapshot).forEach { detail -> item { Body(detail) } }
    item { Body(ConnectionHealth.playback(speaking, preparingSpeech)) }
    item { Body(ConnectionHealth.AUDIO_LIMIT, muted = true) }
    ConnectionHealth.MEASUREMENT_LIMIT.split(". ").forEach { limit ->
        item { Body(limit, muted = true) }
    }
    item {
        Secondary(if (preview == null) "View local report" else "Refresh local report", viewReport)
    }
    preview?.let { selected ->
        item { Heading("Local report (frozen)") }
        // Bound each round-screen item; display only canonical report characters in order.
        ConnectionDiagnosticsReport.encode(selected).chunked(40).forEach { part ->
            item { Body(part, muted = true) }
        }
    }
}
