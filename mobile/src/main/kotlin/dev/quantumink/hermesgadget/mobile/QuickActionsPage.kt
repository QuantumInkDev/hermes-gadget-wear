package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun QuickActionsScreen(
    actionId: String?,
    locked: Boolean,
    transfer: (QuickAction, Map<String, String>, () -> Boolean) -> Unit,
    back: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { QuickActionStore(context) }
    val repository = remember { CatalogRepository.get(context) }
    val pages by repository.pages.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var actions by remember { mutableStateOf<List<QuickAction>?>(null) }
    var review by remember(actionId) { mutableStateOf(actionId) }
    var editing by remember { mutableStateOf<QuickAction?>(null) }
    var notice by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                repository.savedPages()
                store.load()
            }
        }
            .onSuccess {
                actions = it
                notice = QuickActionShortcuts.sync(context, it).orEmpty()
            }
            .onFailure {
                notice =
                    "Saved actions could not be opened. Existing encrypted data was preserved."
            }
    }
    fun save(next: List<QuickAction>) {
        val previous = actions ?: return
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    store.save(next, expected = previous)
                }
            }.onSuccess {
                actions = next
                editing = null
                notice = QuickActionShortcuts.sync(context, next).orEmpty()
            }.onFailure {
                notice =
                    "Actions could not be saved. Reopen to review the latest data; " +
                    "existing data was preserved."
            }
            saving = false
        }
    }
    val route = when {
        editing != null -> "edit:${editing?.id}"
        review != null -> "review:$review"
        else -> "list"
    }
    val scroll = androidx.compose.runtime.key(route) { rememberScrollState() }
    Column(
        modifier.verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val current = actions
        when {
            current == null -> Text("Quick actions unavailable while saved data is loading.")
            editing != null -> QuickActionEditor(
                requireNotNull(
                    editing
                ),
                pages,
                !saving,
                { changed ->
                    val existing = current.indexOfFirst { it.id == changed.id }
                    save(
                        if (existing <
                            0
                        ) {
                            current + changed
                        } else {
                            current.toMutableList().apply { set(existing, changed) }
                        }
                    )
                },
                { editing = null }
            )
            review != null -> {
                val selected = current.singleOrNull { it.id == review }
                if (selected == null) {
                    Text("This action is missing or was removed. Nothing was sent.")
                    TextButton({ review = null }) { Text("Back to quick actions") }
                } else {
                    QuickActionReview(selected, pages, locked || saving, transfer, {
                        editing = selected
                        review = null
                    }, { review = null }, { scope.launch { scroll.scrollTo(0) } })
                }
            }
            else -> QuickActionsPage(current, pages, !saving, ::save, { review = it.id }, {
                editing =
                    it
            }, {
                notice = QuickActionShortcuts.pin(context, it)
            }, back)
        }
        if (current == null) TextButton(back) { Text("Back") }
    }
}

@Composable
internal fun QuickActionsPage(
    actions: List<QuickAction>,
    pages: List<CatalogPage>,
    enabled: Boolean,
    save: (List<QuickAction>) -> Unit,
    review: (QuickAction) -> Unit,
    edit: (QuickAction) -> Unit,
    pin: (QuickAction) -> Unit,
    back: () -> Unit
) {
    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
    Text("Choose a profile, fill in inputs and review each prompt. Nothing runs in the background.")
    Button(
        {
            edit(
                QuickAction(
                    UUID.randomUUID().toString(),
                    null,
                    "New action",
                    "Help me with {{topic}}"
                )
            )
        },
        enabled =
        enabled && actions.size < QuickActions.MAX_ACTIONS
    ) { Text("Create action") }
    TextButton(back) { Text("Back to settings") }
    actions.forEachIndexed { index, action ->
        SettingsSection {
            Text(action.title, style = MaterialTheme.typography.titleSmall)
            Text(
                QuickActions.resolve(action, pages)?.let(::quickActionProfileLabel)
                    ?: "Choose a profile · unavailable until configured"
            )
            TextButton({ review(action) }, enabled = enabled) { Text("Review ${action.title}") }
            TextButton({ edit(action) }, enabled = enabled) { Text("Edit ${action.title}") }
            TextButton({
                pin(action)
            }, enabled = enabled) { Text("Add shortcut for ${action.title}") }
            TextButton(
                {
                    save(actions.toMutableList().apply { add(index - 1, removeAt(index)) })
                },
                enabled =
                enabled && index > 0
            ) { Text("Move ${action.title} up") }
            TextButton(
                {
                    save(actions.toMutableList().apply { add(index + 1, removeAt(index)) })
                },
                enabled =
                enabled && index < actions.lastIndex
            ) { Text("Move ${action.title} down") }
            TextButton({
                save(actions.filterNot { it.id == action.id })
            }, enabled = enabled) { Text("Remove ${action.title}") }
        }
    }
}

@Composable
internal fun QuickActionEditor(
    original: QuickAction,
    pages: List<CatalogPage>,
    enabled: Boolean,
    save: (QuickAction) -> Unit,
    cancel: () -> Unit
) {
    var title by remember(original) { mutableStateOf(original.title) }
    var template by remember(original) { mutableStateOf(original.template) }
    var profileKey by remember(original) { mutableStateOf(original.profileKey) }
    val options = pages.filter {
        it.status != CatalogStatus.WITHDRAWN &&
            it.profile.gadgetUrl != null
    }
    val candidate = runCatching {
        QuickAction(original.id, profileKey, title, template)
    }.getOrNull()
    Text("Edit action", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(title, {
        title = it.take(64)
    }, label = { Text("Action title") }, modifier = Modifier.fillMaxWidth())
    SettingsChoice(
        "Profile",
        options.singleOrNull { it.key == profileKey }?.let(::quickActionProfileLabel)
            ?: "Choose explicitly",
        options.map {
            quickActionProfileLabel(it) + " · " + it.key
        },
        enabled,
        {
            profileKey =
                options[it].key
        }
    )
    Text("Selected key: ${profileKey ?: "none"}", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(template, {
        template = it.take(4096)
    }, label = { Text("Prompt template") }, minLines = 4, modifier = Modifier.fillMaxWidth())
    Text(
        "Use {{topic}} or other lowercase input names (up to eight). Inputs are required at launch and are not saved with the action. Do not put credentials in prompts."
    )
    if (candidate == null) Text("Use a title and prompt with valid {{input_name}} placeholders.")
    if (options.none {
            it.key == profileKey
        }
    ) {
        Text("Choose an available or offline profile before saving.")
    }
    Button(
        { candidate?.let(save) },
        enabled =
        enabled && candidate != null && options.any {
            it.key == profileKey
        }
    ) { Text("Save action") }
    TextButton(cancel, enabled = enabled) { Text("Cancel edit") }
}
