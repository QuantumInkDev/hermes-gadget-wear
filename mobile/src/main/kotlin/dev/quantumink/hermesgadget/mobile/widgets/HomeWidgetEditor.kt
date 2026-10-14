package dev.quantumink.hermesgadget.mobile.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.CatalogPage

/** Only the explicit Save callback can commit this recreation-safe draft. */
@Composable
internal fun HomeWidgetEditor(
    initial: WidgetConfig,
    pages: List<CatalogPage>,
    saving: Boolean,
    error: String,
    save: (WidgetConfig) -> Unit,
    cancel: () -> Unit,
    reload: () -> Unit,
    modifier: Modifier = Modifier,
    portrait: @Composable (CatalogPage, Modifier) -> Unit
) {
    var encoded by rememberSaveable(initial.preset, initial.revision) {
        mutableStateOf(WidgetJson.encodeConfig(initial))
    }
    val draft = remember(encoded) { WidgetJson.decodeConfig(encoded) }
    var slot by rememberSaveable { mutableStateOf<Int?>(null) }
    fun update(value: WidgetConfig) {
        encoded = WidgetJson.encodeConfig(value)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(cancel, enabled = !saving) { Text("Cancel") }
            Button({
                save(draft)
            }, enabled = !saving) { Text(if (saving) "Saving…" else "Save widget") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Choose bots", style = MaterialTheme.typography.headlineLarge)
            Text(
                "${initial.preset.label} · rows × columns",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap a place to choose a bot. Pictures stay clear; names sit underneath.",
                style = MaterialTheme.typography.bodyMedium
            )
            HomeWidgetPreview(draft, pages, { if (!saving) slot = it }, portrait)
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(reload, enabled = !saving) { Text("Reload widget settings") }
            }
            Text("Widget appearance", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.selectableGroup()) {
                WidgetAppearance.entries.forEach { appearance ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = draft.appearance == appearance,
                            enabled = !saving,
                            role = Role.RadioButton,
                            onClick = { update(draft.copy(appearance = appearance)) }
                        ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = draft.appearance == appearance,
                            onClick = null,
                            enabled = !saving
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(appearance.label)
                            if (appearance ==
                                WidgetAppearance.SYSTEM
                            ) {
                                Text(
                                    "Phone wallpaper colors and light/dark mode",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "This choice belongs to this widget. Your app and watch appearance stay as you set them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    slot?.takeIf { it in draft.slots.indices }?.let { selected ->
        HomeWidgetChooser(selected, draft.slots, pages, { key ->
            val values = draft.slots.toMutableList()
            values[selected] = key
            update(draft.copy(slots = values))
            slot = null
        }, { delta ->
            val target = selected + delta
            if (target in draft.slots.indices) {
                val values = draft.slots.toMutableList()
                values[selected] = values[target].also { values[target] = values[selected] }
                update(draft.copy(slots = values))
            }
            slot = null
        }, { slot = null }, portrait)
    }
}
