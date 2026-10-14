package dev.quantumink.hermesgadget.mobile.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.mobile.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeWidgetsPage(back: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var widgets by remember { mutableStateOf(emptyList<Triple<Int, HomeWidgetPreset, Int>>()) }
    LaunchedEffect(refresh) {
        widgets =
            withContext(Dispatchers.IO) {
                HomeWidgets.instances(context).map { (id, preset) ->
                    Triple(
                        id,
                        preset,
                        HomeWidgets.store(context).config(id)?.slots?.filterNotNull()?.size ?: 0
                    )
                }
            }
    }
    TextButton(back) { Text("Back to Settings") }
    Text("Home screen widgets", style = MaterialTheme.typography.headlineLarge)
    Text(
        "Your bots, one tap away. Sizes use rows × columns.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsSection {
        Text("Add a widget", style = MaterialTheme.typography.titleLarge)
        Text(
            "Touch and hold an empty place on your phone's home screen, choose Widgets, then Quantum Relay for Hermes. Pick a layout and choose its bots."
        )
        Text(
            "Five-column layouts need a home screen grid with enough columns.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    widgets.forEach { (id, preset, count) ->
        SettingsSection {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preset.label, style = MaterialTheme.typography.titleMedium)
                Text("$count of ${preset.capacity} bots chosen")
                OutlinedButton({
                    context.startActivity(HomeWidgets.editIntent(context, id))
                }, Modifier.fillMaxWidth()) { Text("Edit widget") }
            }
        }
    }
    if (widgets.isEmpty()) Text("No home screen widgets yet.")
    TextButton({ refresh++ }) { Text("Refresh widgets") }
}
