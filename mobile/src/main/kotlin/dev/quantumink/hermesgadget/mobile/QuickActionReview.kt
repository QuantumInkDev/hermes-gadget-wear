package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions

@Composable
internal fun QuickActionReview(
    action: QuickAction,
    pages: List<CatalogPage>,
    locked: Boolean,
    transfer: (QuickAction, Map<String, String>) -> Unit,
    edit: () -> Unit,
    back: () -> Unit,
    resetScroll: () -> Unit = {}
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var values by remember(action) { mutableStateOf(emptyMap<String, String>()) }
    var preview by remember(action) { mutableStateOf<String?>(null) }
    val page = QuickActions.resolve(action, pages)
    val expanded = runCatching { QuickActions.expand(action, values) }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review action", style = MaterialTheme.typography.titleMedium)
        Text(action.title, style = MaterialTheme.typography.headlineSmall)
        TextButton(back) { Text("Back to quick actions") }
        if (action.profileKey == null) {
            Text("Choose a profile in Edit action before reviewing.")
        } else if (page == null) {
            Text("This profile is missing or withdrawn. Choose a profile in Edit action.")
        } else {
            Text(quickActionProfileLabel(page))
            Text("Profile key: ${page.key}", style = MaterialTheme.typography.bodySmall)
            if (preview == null) {
                QuickActions.inputs(action.template).forEach { name ->
                    OutlinedTextField(
                        values[name].orEmpty(),
                        {
                            values = values + (name to it.take(8192))
                            preview = null
                        },
                        label = { Text(name) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button({
                    focus.clearFocus(force = true)
                    keyboard?.hide()
                    preview = expanded
                    resetScroll()
                }, enabled = expanded != null) { Text("Preview prompt") }
                if (expanded ==
                    null
                ) {
                    Text(
                        "Complete all inputs. The expanded prompt must fit within 8192 characters."
                    )
                }
            }
            if (preview != null && preview == expanded) {
                Text("Expanded prompt", style = MaterialTheme.typography.titleMedium)
                Text(requireNotNull(preview))
                TextButton({
                    preview = null
                    resetScroll()
                }) { Text("Change inputs") }
                Text(
                    "Use this prompt replaces this profile’s saved draft. Connect and Send remain deliberate controls in Talk/Chat."
                )
                Button({
                    transfer(action, values.toMap())
                }, enabled = !locked) { Text("Use prompt in Talk/Chat") }
            }
        }
        if (locked) Text("Finish the current turn or approval before using an action.")
        TextButton(edit) { Text("Edit action") }
    }
}

internal fun quickActionProfileLabel(page: CatalogPage): String =
    "${page.profile.name} · ${page.sourceLabel} · ${page.status.name.lowercase()}"
