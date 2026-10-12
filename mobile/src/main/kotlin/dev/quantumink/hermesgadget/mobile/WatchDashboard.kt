package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Presentation only: the activity owns permissions, association and relay lifetime. */
@Composable
internal fun WatchDashboard(
    enabled: Boolean,
    status: String,
    enable: () -> Unit,
    disable: () -> Unit,
    associate: () -> Unit,
    stop: () -> Unit
) {
    var details by remember { mutableStateOf(false) }
    Text("Your watch", style = MaterialTheme.typography.headlineLarge)
    Text("A bridge to your Hermes boxes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsSection {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phone relay", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (enabled) "Enabled" else "Disabled",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(
                enabled,
                { if (it) enable() else disable() },
                Modifier.semantics { contentDescription = "Phone relay" }
            )
        }
        Text(
            status.ifBlank {
                stringResource(R.string.relay_idle)
            },
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(stop, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stop_relay))
        }
    }
    SettingsSection {
        Text("Watch association", style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.association_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(associate) { Text(stringResource(R.string.associate_watch)) }
    }
    TextButton({
        details = !details
    }) { Text(if (details) "Hide relay details" else "How relay works") }
    if (details) {
        Column(
            Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.relay_tls_hint))
            Text(stringResource(R.string.relay_vpn_hint))
            Text(
                stringResource(R.string.privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
