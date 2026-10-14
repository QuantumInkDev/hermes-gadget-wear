package dev.quantumink.hermesgadget.mobile.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus

@Composable
internal fun HomeWidgetChooser(
    slot: Int,
    slots: List<String?>,
    pages: List<CatalogPage>,
    choose: (String?) -> Unit,
    move: (Int) -> Unit,
    close: () -> Unit,
    portrait: @Composable (CatalogPage, Modifier) -> Unit
) {
    var query by rememberSaveable(slot) { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = {
        Text("Choose bot · slot ${slot + 1}")
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, {
                query = it.take(64)
            }, label = {
                Text("Search bots")
            }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ move(-1) }, enabled = slot > 0) { Text("Move earlier") }
                TextButton({ move(1) }, enabled = slot < slots.lastIndex) { Text("Move later") }
            }
            TextButton({ choose(null) }) { Text("Remove bot") }
            LazyColumn(
                Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pages.filter {
                    it.profile.name.contains(query, true) ||
                        it.sourceLabel.contains(query, true)
                }.groupBy { it.sourceId }.values.forEach { group ->
                    item(key = "source:${group.first().sourceId}") {
                        Text(
                            group.first().sourceLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(group, key = { it.key }) { page ->
                        val chosenElsewhere =
                            page.key in slots.filterIndexed { index, _ -> index != slot }
                        val enabled = !chosenElsewhere && page.status != CatalogStatus.WITHDRAWN
                        Row(
                            Modifier.fillMaxWidth().heightIn(
                                min = 64.dp
                            ).clickable(enabled = enabled, role = Role.Button) {
                                choose(page.key)
                            }.semantics {
                                contentDescription =
                                    "Choose ${page.profile.name} · ${page.sourceLabel}"
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            portrait(page, Modifier.size(40.dp))
                            Column {
                                Text(page.profile.name)
                                Text(
                                    when {
                                        chosenElsewhere -> "Already in this widget"
                                        page.status ==
                                            CatalogStatus.WITHDRAWN -> "Unavailable"
                                        page.status ==
                                            CatalogStatus.OFFLINE -> "Offline · picture kept"
                                        else -> page.sourceLabel
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("Close") } })
}
