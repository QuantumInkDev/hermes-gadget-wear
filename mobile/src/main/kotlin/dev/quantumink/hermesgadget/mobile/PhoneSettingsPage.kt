package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.client.CuePreferences

@Composable
fun PhoneSettingsPage(context: Context, navigate: (String) -> Unit) {
    val soundCues by CuePreferences.enabled.collectAsStateWithLifecycle()
    Text("Settings", style = MaterialTheme.typography.titleMedium)
    Text("Make yourself at home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            SettingsLink("Appearance", "Light, Dark or Electronic Moonlight") {
                navigate("appearance")
            }
            SettingsLink(
                "Home screen widgets",
                "Choose bots, arrange portraits and match your phone"
            ) {
                navigate("home-widgets")
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Recording sound cues", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Start and stop feedback",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = soundCues,
                    onCheckedChange = {
                        CuePreferences.set(context, it)
                    },
                    modifier = Modifier.semantics {
                        contentDescription =
                            "Recording sound cues"
                    }
                )
            }
        }
    }
    Text("Connections & speech", style = MaterialTheme.typography.titleMedium)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            SettingsLink("Boxes and discovery", "Catalog sources and access") { navigate("boxes") }
            SettingsLink("Manual watch endpoints", "Review saved Gadget connections") {
                navigate("manual")
            }
            SettingsLink("Watch speech providers", "Voice and provider settings") {
                navigate("speech")
            }
        }
    }
    Text("About Quantum Relay for Hermes Agents", style = MaterialTheme.typography.titleMedium)
    Text(
        "Quantum Relay for Hermes Agents is an independent QuantumInk companion " +
            "for Gadget SDK v0.2.0. " +
            "Not affiliated with or endorsed by Nous Research. " +
            "Hermes and Nous Research names and marks belong to their owners. " +
            "The Voice Q icon is original QuantumInk artwork.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painterResource(R.drawable.ic_expand_more),
            null,
            Modifier.size(20.dp).rotate(-90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Shared tonal grouping for the phone's setup and preference destinations. */
@Composable
internal fun SettingsSection(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
internal fun SettingsToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked,
            change,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription =
                    title
            }
        )
    }
}

@Composable
internal fun SettingsChoice(
    title: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    select: (Int) -> Unit
) {
    var expanded by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.OutlinedButton(
            { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text("$title: $value") }
        androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, label ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    select(index)
                })
            }
        }
    }
}
