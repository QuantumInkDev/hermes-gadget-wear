package dev.quantumink.hermesgadget.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.quantumink.hermesgadget.client.ConnectionHealth
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsReport
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsSnapshot

/** No file, logger, transport, raw-state or conversation dependency. Explicit text-only export. */
internal object PhoneConnectionReport {
    fun copy(context: Context, snapshot: ConnectionDiagnosticsSnapshot) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Connection report", ConnectionDiagnosticsReport.encode(snapshot))
        )
    }

    fun share(snapshot: ConnectionDiagnosticsSnapshot): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, ConnectionDiagnosticsReport.encode(snapshot))
    }
}

@Composable
internal fun ConnectionHealthDialog(
    snapshot: ConnectionDiagnosticsSnapshot,
    speaking: Boolean,
    preparingSpeech: Boolean,
    shareAllowed: Boolean,
    close: () -> Unit
) {
    val context = LocalContext.current
    // Preview is ephemeral and frozen. A changed live snapshot cannot silently change an export.
    var preview by remember { mutableStateOf<ConnectionDiagnosticsSnapshot?>(null) }
    var notice by remember { mutableStateOf("") }
    Dialog(onDismissRequest = close) {
        BoxWithConstraints {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(
                    max = maxHeight * .85f
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "Connection health",
                            Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(close) { Text("Close") }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("This phone only. Watch diagnostics are displayed on the watch.")
                        ConnectionHealth.details(snapshot).forEach { Text(it) }
                        Text(ConnectionHealth.playback(speaking, preparingSpeech))
                        Text(ConnectionHealth.AUDIO_LIMIT)
                        Text(ConnectionHealth.MEASUREMENT_LIMIT)
                        TextButton({
                            preview = snapshot
                            notice = ""
                        }) {
                            Text(
                                if (preview ==
                                    null
                                ) {
                                    "Preview safe report"
                                } else {
                                    "Refresh report preview"
                                }
                            )
                        }
                        preview?.let { selected ->
                            Text(
                                "Report preview (frozen)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                ConnectionDiagnosticsReport.encode(selected),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton({
                                PhoneConnectionReport.copy(context, selected)
                                notice = "Report copied"
                            }) { Text("Copy report") }
                            if (!shareAllowed) {
                                Text(
                                    "Sharing switches apps. Finish the current request, pairing, approval or voice playback first. Preview and Copy stay available."
                                )
                            }
                            TextButton({
                                try {
                                    context.startActivity(
                                        Intent.createChooser(
                                            PhoneConnectionReport.share(selected),
                                            "Share connection report"
                                        )
                                    )
                                    notice = ""
                                } catch (_: android.content.ActivityNotFoundException) {
                                    notice = "No sharing app available"
                                }
                            }, enabled = shareAllowed) { Text("Share report") }
                        }
                        if (notice.isNotEmpty()) Text(notice)
                    }
                }
            }
        }
    }
}

/** Sharing can stop the foreground phone activity. Never use it to interrupt active work. */
internal fun connectionReportShareAllowed(state: PhoneConversationState): Boolean =
    !speechSettingsLocked(state) &&
        state.connection.status !in setOf(
            dev.quantumink.hermesgadget.protocol.ConnectionStatus.CONNECTING,
            dev.quantumink.hermesgadget.protocol.ConnectionStatus.PAIRING
        ) && state.connection.diagnostics.requestStage !in setOf(
            dev.quantumink.hermesgadget.protocol.RequestStage.RECORDING,
            dev.quantumink.hermesgadget.protocol.RequestStage.UPLOADING,
            dev.quantumink.hermesgadget.protocol.RequestStage.WAITING,
            dev.quantumink.hermesgadget.protocol.RequestStage.TRANSCRIBED,
            dev.quantumink.hermesgadget.protocol.RequestStage.RECEIVING
        )
