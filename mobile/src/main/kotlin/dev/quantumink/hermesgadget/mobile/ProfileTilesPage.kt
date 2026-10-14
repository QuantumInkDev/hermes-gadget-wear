package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.TilePinPhoneState

internal data class TileComposerProfile(
    val key: String,
    val label: String,
    val offline: Boolean = false
)

@Composable
internal fun ProfileTilesPage(back: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { TilePinSync.get(context) }
    val repository = remember { CatalogRepository.get(context) }
    val state by sync.state.collectAsStateWithLifecycle()
    val peers by sync.peers.collectAsStateWithLifecycle()
    val notice by sync.notice.collectAsStateWithLifecycle()
    val pages by repository.pages.collectAsStateWithLifecycle()
    var changing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { repository.savedPages() }
        }
        sync.refresh()
    }
    TextButton(back) { Text("Back to Watch") }
    if (state == null || changing) {
        SettingsSection {
            Text("Choose your watch", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tile settings belong to the selected watch. Choosing another watch clears this phone's previous tile cache and pending edit."
            )
            peers.forEach { peer ->
                Button({
                    sync.select(peer.id)
                    changing = false
                }) { Text(peer.name) }
            }
            if (peers.isEmpty()) {
                Text(
                    "No connected watch. Pair your watch in its companion app, then refresh."
                )
            }
            OutlinedButton(sync::refresh) { Text("Refresh watches") }
        }
    } else {
        TextButton({ changing = true }) { Text("Change watch") }
    }
    val profiles = state?.snapshot?.keys.orEmpty().map { key ->
        val page = pages.singleOrNull { it.key == key }
        TileComposerProfile(
            key,
            page?.profile?.name ?: "Watch profile ${key.takeLast(8)}",
            page?.status?.let { it != CatalogStatus.AVAILABLE } ?: false
        )
    }
    ProfileTilesContent(state, profiles, notice, sync::submit, sync::retry, sync::useWatch) {
            key,
            modifier
        ->
        val page = pages.singleOrNull { it.key == key }
        if (page !=
            null
        ) {
            DirectoryPortrait(repository, page, modifier)
        } else {
            TileInitials("W", modifier)
        }
    }
}

/** Draft is separate from acknowledged watch state; incoming snapshots cannot erase unsaved work. */
@Composable
internal fun ProfileTilesContent(
    state: TilePinPhoneState?,
    profiles: List<TileComposerProfile>,
    notice: String,
    save: (Long, String?, List<String?>) -> Unit,
    retry: () -> Unit,
    useWatch: () -> Unit,
    portrait: @Composable (String, Modifier) -> Unit
) {
    val acknowledged = state?.snapshot?.pins
    val initialFocus = state?.pending?.focus ?: acknowledged?.focus
    val initialGrid = state?.pending?.grid ?: acknowledged?.grid ?: List(4) { null }
    var focus by remember(state?.peer) { mutableStateOf(initialFocus) }
    var grid by remember(state?.peer) {
        mutableStateOf(initialGrid + List(4 - initialGrid.size) { null })
    }
    var draftRevision by remember(state?.peer) { mutableStateOf(acknowledged?.revision ?: 0L) }
    var dirty by remember(state?.peer) { mutableStateOf(false) }
    var savedEdit by remember(state?.peer) { mutableStateOf(state?.pending) }
    var slot by remember(state?.peer) { mutableStateOf<Int?>(null) }
    val pending = state?.pending != null
    val editable = acknowledged != null && (!pending || state.issue != null)
    LaunchedEffect(acknowledged, state?.pending) {
        if (state?.pending != savedEdit && state?.pending != null) dirty = false
        savedEdit = state?.pending
        if (!dirty) {
            draftRevision = acknowledged?.revision ?: 0L
            focus = if (state?.pending != null) state.pending?.focus else acknowledged?.focus
            val values = state?.pending?.grid ?: acknowledged?.grid ?: emptyList()
            grid = values + List(4 - values.size) { null }
        }
    }
    Text("Profile tiles", style = MaterialTheme.typography.headlineLarge)
    Text(
        "Your favorite profiles, one tap from the watch face.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsSection {
        Text("Focus", style = MaterialTheme.typography.titleLarge)
        TileSlot(focus, "Edit Focus", profiles, editable, { slot = -1 }, portrait, large = true)
    }
    SettingsSection {
        Text("Pinned profiles", style = MaterialTheme.typography.titleLarge)
        Text("Four places. Your order.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    Box(Modifier.weight(1f)) {
                        TileSlot(grid[index], "Edit slot ${index + 1}", profiles, editable, {
                            slot =
                                index
                        }, portrait)
                    }
                }
            }
        }
    }
    SettingsSection {
        Text(
            when {
                state?.issue == "conflict" -> "Review watch changes"
                state?.issue == "rejected" -> "Edit needs attention"
                pending -> "Pending delivery"
                dirty -> "Unsaved changes"
                else -> "Watch confirmation"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state?.issue != null) {
            val label: (String?) -> String = { key ->
                profiles.singleOrNull { it.key == key }?.label
                    ?: if (key == null) "Empty" else "Unavailable"
            }
            Text("On watch · Focus: ${label(acknowledged?.focus)}")
            Text(
                "On watch · Grid: ${acknowledged?.grid.orEmpty().joinToString(" · ") {
                    label(it)
                }}"
            )
            Text(
                "Your proposed version is shown above. Review it, then apply again against the watch's current revision."
            )
            OutlinedButton({
                dirty = false
                useWatch()
            }, Modifier.fillMaxWidth()) { Text("Keep watch version") }
        }
        if (editable) {
            Button(
                {
                    save(
                        if (state.issue !=
                            null
                        ) {
                            acknowledged.revision
                        } else {
                            draftRevision
                        },
                        focus,
                        grid
                    )
                },
                Modifier.fillMaxWidth(),
                enabled =
                dirty || state.issue != null
            ) {
                Text(if (state.issue != null) "Apply reviewed edit" else "Save to watch")
            }
        }
        OutlinedButton(retry, Modifier.fillMaxWidth()) {
            Text(
                if (pending &&
                    state.issue == null
                ) {
                    "Retry delivery"
                } else {
                    "Refresh from watch"
                }
            )
        }
    }
    Text(
        "Add Focus or Pinned profiles using the watch's system tile carousel. Pictures stay static; tap a portrait to open its profile.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    slot?.let { selected ->
        fun change(key: String?) {
            if (selected == -1) {
                focus = key
            } else {
                val next = grid.map { if (key != null && it == key) null else it }.toMutableList()
                next[selected] = key
                grid = next
            }
            dirty = true
            slot = null
        }
        AlertDialog(
            onDismissRequest = { slot = null },
            title = { Text(if (selected == -1) "Choose Focus" else "Choose slot ${selected + 1}") },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { TextButton({ change(null) }) { Text("Remove pin") } }
                    if (selected > 0) {
                        item {
                            TextButton({
                                grid =
                                    grid.toMutableList().also {
                                        val previous = it[selected - 1]
                                        it[
                                            selected -
                                                1
                                        ] =
                                            it[selected]
                                        it[selected] = previous
                                    }
                                dirty = true
                                slot = null
                            }) { Text("Move earlier") }
                        }
                    }
                    if (selected in 0..2) {
                        item {
                            TextButton({
                                grid =
                                    grid.toMutableList().also {
                                        val next = it[selected + 1]
                                        it[
                                            selected +
                                                1
                                        ] =
                                            it[selected]
                                        it[selected] = next
                                    }
                                dirty = true
                                slot = null
                            }) { Text("Move later") }
                        }
                    }
                    items(profiles, key = { it.key }) { profile ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(
                                min = 64.dp
                            ).clip(MaterialTheme.shapes.medium).clickable {
                                change(profile.key)
                            }.semantics {
                                contentDescription =
                                    "Choose ${profile.label}"
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            portrait(profile.key, Modifier.size(48.dp).clip(CircleShape))
                            Column {
                                Text(profile.label)
                                if (profile.offline) {
                                    Text(
                                        "Offline · picture kept",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ slot = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun TileSlot(
    key: String?,
    action: String,
    profiles: List<TileComposerProfile>,
    enabled: Boolean,
    edit: () -> Unit,
    portrait: @Composable (String, Modifier) -> Unit,
    large: Boolean = false
) {
    val profile = profiles.singleOrNull { it.key == key }
    Surface(
        Modifier.fillMaxWidth().clip(
            MaterialTheme.shapes.large
        ).clickable(enabled = enabled, onClick = edit).semantics {
            contentDescription =
                action
        },
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val image = Modifier.size(if (large) 96.dp else 64.dp).clip(CircleShape)
            if (key != null) portrait(key, image) else TileInitials("+", image)
            Text(
                profile?.label ?: if (key ==
                    null
                ) {
                    "Choose profile"
                } else {
                    "Unavailable"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (key != null &&
                    profile == null
                ) {
                    "Replace or remove"
                } else if (profile?.offline ==
                    true
                ) {
                    "Offline"
                } else if (large) {
                    "One favorite"
                } else {
                    action.removePrefix("Edit ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TileInitials(text: String, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
