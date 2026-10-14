package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences

@Composable
fun AppearancePage(context: Context, back: () -> Unit) {
    val preferences = remember(context) { AppearancePreferences(context) }
    val mode by preferences.mode.collectAsStateWithLifecycle()
    Text("Appearance", style = MaterialTheme.typography.headlineMedium)
    Text("Choose the appearance for this device.")
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Appearance.entries.forEach { option ->
            val label = when (option) {
                Appearance.LIGHT -> "Light"
                Appearance.DARK -> "Dark"
                Appearance.MOONLIGHT -> "Electronic Moonlight"
            }
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = mode == option,
                    role = Role.RadioButton,
                    onClick = { preferences.set(option) }
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
    }
    TextButton(back) { Text("Back") }
}
