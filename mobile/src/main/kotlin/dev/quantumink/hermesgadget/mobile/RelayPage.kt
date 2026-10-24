package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.session.ConversationItem
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import dev.quantumink.hermesgadget.protocol.relay.session.MetadataStatus
import dev.quantumink.hermesgadget.protocol.relay.session.RelayNotice
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayReason
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.session.TurnView
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus

/** The user's deliberate Relay controls. Nothing here runs by itself; the defaults do nothing. */
internal class RelayActions(
    val enable: (Boolean) -> Unit = {},
    val refresh: () -> Unit = {},
    val more: () -> Unit = {},
    val create: (String) -> Unit = {},
    val discardCreate: () -> Unit = {},
    val select: (String) -> Unit = {},
    val leave: () -> Unit = {},
    val check: (String) -> Unit = {},
    val resend: (String) -> Unit = {},
    val abandon: (String) -> Unit = {},
    val clearSettled: () -> Unit = {},
    val review: () -> Unit = {},
    val reauthorize: () -> Unit = {},
    val retryStorage: () -> Unit = {},
    val searchOnline: (String, Boolean?) -> Unit = { _, _ -> },
    val editMetadata: (String, String?, Boolean?) -> Unit = { _, _, _ -> },
    val retryMetadata: (String) -> Unit = {},
    val abandonMetadata: (String) -> Unit = {},
    val searchRetained: (String) -> Unit = {},
    val openRetained: (String) -> Unit = {},
    val closeRetained: () -> Unit = {},
    val reviewCache: (String?) -> Unit = {},
    val confirmCacheReview: (
        dev.quantumink.hermesgadget.protocol.relay.session.CacheReviewConfirmation
    ) -> Unit = {},
    val cancelCacheReview: () -> Unit = {},
    val retention: (Int) -> Unit = {}
) {
    companion object {
        val NONE = RelayActions()

        fun of(model: PhoneConversation) = model.relayActions()
    }
}

internal fun retainedProblemText(problem: HistoryProblem): String = when (problem) {
    HistoryProblem.LOCAL_REVISION, HistoryProblem.STALE_REVIEW ->
        "Retained history changed. Review the current bytes again."
    HistoryProblem.STALE_METADATA, HistoryProblem.METADATA_CONFLICT ->
        "The canonical title or bookmark changed. Refresh before retaining it."
    HistoryProblem.MISSING_ANCHOR, HistoryProblem.SEQUENCE_CONFLICT,
    HistoryProblem.REBUILD_REQUIRED ->
        "Only a fragment is retained. Review a cache-only rebuild before refetching."
    HistoryProblem.CAPACITY -> "Retained history is full. Review local bytes before freeing space."
    HistoryProblem.KEY_UNAVAILABLE ->
        "The history encryption key is unavailable. Bytes are preserved."
    HistoryProblem.UNREADABLE -> "Retained history cannot be read. Bytes are preserved for review."
    HistoryProblem.RECORD_MISSING ->
        "The history record is missing. No empty replacement was made."
    HistoryProblem.UNSUPPORTED, HistoryProblem.LEGACY_UNBOUND ->
        "Older or unsupported retained history needs an explicit review. Bytes are preserved."
    HistoryProblem.STORAGE -> "Retained history storage is unavailable. Removal is not confirmed."
    HistoryProblem.WRITE_UNCERTAIN ->
        "Saving retained history is unconfirmed. Durable progress was not advanced."
    HistoryProblem.INVALID_VALUE -> "Canonical text or metadata could not be retained exactly."
    HistoryProblem.UNBOUND_PROVENANCE -> "This cache has no reviewed server provenance for export."
}

internal fun relayNoticeText(notice: RelayNotice): String = when (notice) {
    RelayNotice.NONE -> ""
    RelayNotice.STORAGE_FULL_PENDING_ITEMS ->
        "Can't save more until pending or unconfirmed messages are reviewed."
    RelayNotice.STORAGE_WRITE_FAILED ->
        "Relay could not save its state. The requested change was refused."
    RelayNotice.STORAGE_UNCERTAIN ->
        "Relay could not confirm that its state was saved. The requested change was refused."
    RelayNotice.OBSERVATION_UNSAVED ->
        "The server response is visible but could not be saved. " +
            "Requests may already have reached the server."
    RelayNotice.OBSERVATION_UNCERTAIN ->
        "The server response is visible; saving is unconfirmed. " +
            "Requests may already have reached the server."
    RelayNotice.SEND_REFUSED_STORAGE ->
        "This message was not sent because Relay state is not saved yet."
    RelayNotice.CLOCK_UNAVAILABLE -> "The server clock is not available yet."
    RelayNotice.LEDGER_FULL -> "Too many messages are tracked. Clear finished ones to continue."
    RelayNotice.TURN_REFUSED -> "That message cannot be sent."
    RelayNotice.CONVERSATION_NOT_CONTINUABLE ->
        "This conversation cannot be continued from the phone."
    RelayNotice.CREATE_PENDING ->
        "A new conversation is still being created. It will not be created twice."
    RelayNotice.CREATE_REFUSED -> "The server refused to create the conversation."
    RelayNotice.CREATE_EXPIRED ->
        "The new conversation could not be confirmed in time. Discard it and try again."
    RelayNotice.CREATE_FAILED ->
        "The new conversation could not be created. Discard it and try again."
    RelayNotice.NAME_INVALID -> "That name is not valid."
    RelayNotice.BUSY_TRY_AGAIN -> "The server is busy. Retrying."
    RelayNotice.RECONNECTING -> "Reconnecting."
    RelayNotice.SNAPSHOT_INCOMPLETE -> "The task snapshot is incomplete. Refresh to try again."
    RelayNotice.HISTORY_INCOMPLETE -> "Only part of this conversation's history is shown."
    RelayNotice.PROMPT_ELSEWHERE -> "Hermes is waiting for an answer on another device."
    RelayNotice.PERMISSION_DENIED ->
        "This phone no longer has access. Sending is stopped; " +
            "saved content removal is being checked."
    RelayNotice.PROFILE_CHANGED -> "The server or profile changed. Review it before continuing."
    RelayNotice.SERVER_ERROR -> "The server could not do that."
    RelayNotice.ANOMALY -> "The server answered something unexpected. Nothing was changed."
    RelayNotice.LINK_FAILED -> "The connection to Relay failed. Try again."
    RelayNotice.CONVERSATION_DELETED -> "This conversation no longer exists."
    RelayNotice.VOICE_UNAVAILABLE -> "Voice is not available in a Relay conversation yet."
}

internal fun relayStoppedText(relay: RelayUiState): String = if (relay.contentEvictionPending) {
    "Relay access was removed. Sending is stopped. Saved Relay content still needs removal."
} else {
    "Relay access was removed. Sending is stopped. Saved Relay content was removed."
}

internal fun relayReasonText(reason: RelayReason): String = when (reason) {
    RelayReason.NONE -> ""
    RelayReason.NOT_OFFERED ->
        "This server does not offer Relay conversations. The normal chat still works."
    RelayReason.MALFORMED_WELCOME ->
        "The server's Relay answer was not understood. Relay is off for this connection."
    RelayReason.NOT_PAIRED -> "Approve this phone on the box first, then reconnect."
    RelayReason.MISSING_METHODS ->
        "This server's Relay grant does not allow conversations from the phone."
    RelayReason.NO_SERVER_TIME -> "This Relay grant has no conversation access."
    RelayReason.INFO_FAILED -> "Relay could not be set up with this server."
    RelayReason.ACCESS_DENIED -> "The server does not give this phone Relay access."
    RelayReason.CLOCK_UNAVAILABLE -> "The server clock could not be read."
    RelayReason.TLS_REQUIRED -> "Relay conversations require a wss endpoint."
    RelayReason.TOO_MANY_ENROLLMENTS -> "Relay supports at most 16 phone enrollments."
}

internal fun relayStorageText(problem: StoreProblem): String = when (problem) {
    StoreProblem.KEY_UNAVAILABLE ->
        "The key for Relay's saved state is unavailable. The saved state was kept."
    StoreProblem.STORAGE_UNAVAILABLE ->
        "Relay's saved state cannot be read or written. It was kept."
    StoreProblem.RECORD_MISSING -> "Relay's saved state went missing. Nothing was replaced."
    StoreProblem.CORRUPT -> "Relay's saved state could not be verified. It was kept untouched."
    StoreProblem.UNSUPPORTED_VERSION ->
        "Relay's saved state is from a newer version. It was kept untouched."
    StoreProblem.TOO_LARGE ->
        "Relay's saved state is larger than this version supports. It was kept untouched."
    StoreProblem.UNRESTORABLE ->
        "Relay's saved state cannot be restored by this version. It was kept untouched."
}

internal fun turnStatusText(status: TurnStatus): String = when (status) {
    TurnStatus.NOT_SENT -> "Not sent"
    TurnStatus.SENDING -> "Sending"
    TurnStatus.UNCERTAIN -> "Not confirmed"
    TurnStatus.CHECKING -> "Checking"
    TurnStatus.QUEUED -> "Waiting to run"
    TurnStatus.RUNNING -> "Running"
    TurnStatus.UNKNOWN_ADMITTED -> "Accepted, status unknown"
    TurnStatus.FINISHED_COMPLETED -> "Done"
    TurnStatus.FINISHED_OTHER -> "Ended"
    TurnStatus.FAILED -> "Failed"
    TurnStatus.TERMINAL_UNKNOWN_OUTCOME -> "Ended, outcome unknown"
    TurnStatus.REFUSED -> "Refused by the server"
}

/** Relay mode on the phone conversation screen. Off by default; the normal chat is untouched until it is turned on. */
@Composable
internal fun RelaySection(
    relay: RelayUiState,
    requested: Boolean,
    actions: RelayActions,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Relay conversations", style = MaterialTheme.typography.titleMedium)
        when (relay.phase) {
            RelayPhase.OFF -> RelayOff(requested, relay.reason, actions)
            RelayPhase.CHECKING -> Text("Checking saved Relay state.")
            RelayPhase.STORAGE_PROBLEM -> {
                Text(
                    relay.storage?.let(::relayStorageText).orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(actions.retryStorage) { Text("Check again") }
            }
            RelayPhase.CONNECTING -> Text("Relay is on. Connecting.")
            RelayPhase.UNAVAILABLE -> {
                Text(relayReasonText(relay.reason))
                TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
            }
            RelayPhase.REVIEW_REQUIRED -> RelayReview(relay, actions)
            RelayPhase.STOPPED -> {
                Text(relayStoppedText(relay))
                Button(actions.reauthorize) { Text("Allow Relay again") }
                TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
            }
            RelayPhase.READY -> RelayReady(relay, actions)
            RelayPhase.ATTACHING, RelayPhase.SELECTED -> RelaySelected(relay, actions)
        }
        if (relay.notice != RelayNotice.NONE) {
            Text(relayNoticeText(relay.notice), color = MaterialTheme.colorScheme.error)
        }
        if (relay.unsaved) {
            Text("Some changes are not saved yet.", color = MaterialTheme.colorScheme.error)
            TextButton(actions.retryStorage) { Text("Save again") }
        }
        if (relay.enabled && relay.phase != RelayPhase.STOPPED &&
            relay.phase != RelayPhase.REVIEW_REQUIRED
        ) {
            if (relay.phase == RelayPhase.READY ||
                relay.phase == RelayPhase.SELECTED
            ) {
                OnlineHistoryControls(relay, actions)
            }
            MetadataIntents(relay, actions)
            RetainedHistoryControls(relay, actions)
        }
    }
}

@Composable
private fun RelayOff(requested: Boolean, reason: RelayReason, actions: RelayActions) {
    Text(
        "Continue a Hermes conversation from this phone with typed messages. Off by default; " +
            "the normal chat is unchanged. Voice in a Relay conversation comes later.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (reason !=
        RelayReason.NONE
    ) {
        Text(relayReasonText(reason), color = MaterialTheme.colorScheme.error)
    }
    if (requested) {
        Text("Relay is on. It starts when you connect.")
        TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
    } else {
        Button({ actions.enable(true) }) { Text("Turn on Relay") }
    }
}

@Composable
private fun RelayReview(relay: RelayUiState, actions: RelayActions) {
    Text(
        "The server or profile this phone talks to has changed. Nothing is sent until you review it."
    )
    Text("Remembered profile: " + relay.pinnedDigest.orEmpty())
    Text("Profile now: " + (relay.observedDigest ?: "unknown, reconnect to see it"))
    Button(actions.review, enabled = relay.observedDigest != null) { Text("Review and continue") }
    TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
}

@Composable
private fun RelayReady(relay: RelayUiState, actions: RelayActions) {
    var title by remember { mutableStateOf("") }
    relay.resumable?.let { item ->
        Text("Last conversation: " + (item.title ?: "Untitled"))
        Button({ actions.select(item.id) }) { Text("Resume") }
    }
    relay.pendingCreate?.let {
        Text("A new conversation (" + (it.title ?: "no name") + ") is being created.")
        TextButton(actions.discardCreate) { Text("Discard") }
    } ?: run {
        OutlinedTextField(
            title,
            { title = it.take(120) },
            label = { Text("New conversation name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button({
            actions.create(title)
            title = ""
        }) { Text("Start a new conversation") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(actions.refresh, enabled = !relay.listing) { Text("Refresh list") }
        if (relay.hasMore) TextButton(actions.more) { Text("More") }
    }
    if (relay.conversations.isEmpty() && !relay.listing) Text("No conversations yet.")
    relay.conversations.forEach {
        RelayConversationRow(it, actions)
        MetadataControls(it, relay, actions)
    }
    TurnList(relay, actions)
    TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
}

@Composable
private fun RelayConversationRow(item: ConversationItem, actions: RelayActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(item.title ?: "Untitled", Modifier.weight(1f).padding(end = 8.dp))
        TextButton({ actions.select(item.id) }, enabled = item.canContinue) {
            Text(if (item.canContinue) "Open" else "Read only")
        }
    }
}

@Composable
private fun RelaySelected(relay: RelayUiState, actions: RelayActions) {
    Text(
        "In: " + (relay.selected?.title ?: "Untitled"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    if (relay.phase == RelayPhase.ATTACHING) Text("Loading the conversation.")
    relay.selected?.let { MetadataControls(it, relay, actions) }
    relay.tasksWorking?.let { working ->
        val waiting = relay.tasksNeedInput ?: 0
        if (working > 0 || waiting > 0) Text("Working: $working, needs input elsewhere: $waiting")
    }
    if (relay.refreshOwed && !relay.busy) {
        Text("This conversation view still needs a refresh.")
        TextButton({ relay.selected?.id?.let(actions.select) }) { Text("Retry refresh") }
    }
    relay.transcript.forEach { line ->
        Text(
            (if (line.role == HistoryRole.USER) "You: " else "Hermes: ") + line.text,
            color = if (line.role == HistoryRole.USER) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
    TurnList(relay, actions)
    Text(
        "Voice is not available in a Relay conversation yet. You can type below.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(actions.leave) { Text("Leave conversation") }
        TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
    }
}

@Composable
private fun OnlineHistoryControls(relay: RelayUiState, actions: RelayActions) {
    var query by remember(relay.identity, relay.foregroundSession) {
        mutableStateOf(relay.onlineQuery)
    }
    var filter by remember(relay.identity, relay.foregroundSession) {
        mutableStateOf(relay.bookmarkFilter)
    }
    Text("Online Relay search", style = MaterialTheme.typography.labelLarge)
    Text(
        "Online human text and titles. Existing Hermes sources need separate opt-in.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(query, {
        query = it
    }, Modifier.fillMaxWidth(), label = { Text("Online search") }, maxLines = 2)
    Row {
        TextButton({ filter = null }) { Text(if (filter == null) "All ✓" else "All") }
        TextButton({ filter = true }) { Text(if (filter == true) "Bookmarks ✓" else "Bookmarks") }
        TextButton({ filter = false }) {
            Text(
                if (filter ==
                    false
                ) {
                    "Unbookmarked ✓"
                } else {
                    "Unbookmarked"
                }
            )
        }
    }
    Button({
        actions.searchOnline(query, filter)
    }, enabled = !relay.listing) { Text("Search online") }
    if (relay.listing) Text("Searching online…")
    if (!relay.listing && relay.conversations.isEmpty()) Text("No rows in this online view.")
    if (relay.listUnavailableCount >
        0
    ) {
        Text("${relay.listUnavailableCount} conversation views are pending or unavailable.")
    }
    if (relay.hasMore) {
        TextButton(actions.more, enabled = !relay.listing) {
            Text("More online results")
        }
    }
    if (relay.phase ==
        RelayPhase.SELECTED
    ) {
        relay.conversations.forEach { RelayConversationRow(it, actions) }
    }
}

@Composable
private fun MetadataControls(item: ConversationItem, relay: RelayUiState, actions: RelayActions) {
    val pending = relay.metadataIntents.any { it.conversationId.value == item.id }
    val allowed =
        relay.canEditMetadata && item.canContinue && item.metadataState == null &&
            item.revision >= 1 &&
            !pending
    var renaming by remember(item.id, relay.foregroundSession) { mutableStateOf(false) }
    var title by remember(item.id, item.revision, relay.foregroundSession) {
        mutableStateOf(item.title.orEmpty())
    }
    if (item.metadataState != null) Text("Metadata unavailable; this row is read only.")
    Row {
        TextButton({ renaming = !renaming }, enabled = allowed) { Text("Rename") }
        TextButton({ actions.editMetadata(item.id, null, !item.bookmarked) }, enabled = allowed) {
            Text(if (item.bookmarked) "Remove bookmark" else "Bookmark")
        }
    }
    if (renaming) {
        OutlinedTextField(title, {
            title = it
        }, Modifier.fillMaxWidth(), label = { Text("New name") }, maxLines = 2)
        Text(
            "Hermes validates this name. The returned stored name appears after confirmation.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            {
                actions.editMetadata(item.id, title, null)
                renaming = false
            },
            enabled =
            allowed && title.isNotBlank()
        ) { Text("Request rename") }
    }
}

@Composable
private fun MetadataIntents(relay: RelayUiState, actions: RelayActions) {
    relay.metadataIntents.forEach { intent ->
        Text(
            when (intent.status) {
                MetadataStatus.PENDING, MetadataStatus.UNKNOWN ->
                    "Edit unconfirmed; it may have applied."
                MetadataStatus.TRANSIENT -> "Edit is busy or temporarily unavailable."
                MetadataStatus.ACCEPTED -> "Hermes confirmed the edit."
                MetadataStatus.SUPERSEDED -> "Later edit superseded this. Refresh before editing."
                MetadataStatus.RETIRED -> "The conversation was retired."
                MetadataStatus.STALE_SNAPSHOT -> "Stale revision. Refresh; no automatic rebase."
                MetadataStatus.UNAVAILABLE -> "Route unavailable. Stop retrying this edit."
                MetadataStatus.EXPIRED -> "Edit ID spent. Refresh before a new edit."
                MetadataStatus.REFUSED -> "Hermes refused this edit."
            }
        )
        intent.coreCommitted?.let {
            Text(
                if (it) {
                    "The core committed the rename."
                } else {
                    "No core rename was committed by this outcome."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (intent.retryable) {
            TextButton({
                actions.retryMetadata(intent.requestId.value)
            }, enabled = relay.canEditMetadata) {
                Text("Retry same edit (may apply it)")
            }
        }
        TextButton({
            actions.abandonMetadata(intent.requestId.value)
        }) { Text("Stop tracking edit") }
    }
}

@Composable
private fun RetainedHistoryControls(relay: RelayUiState, actions: RelayActions) {
    var query by remember(relay.identity, relay.foregroundSession) { mutableStateOf("") }
    var days by remember(relay.retentionDays) {
        mutableStateOf(relay.retentionDays?.toString().orEmpty())
    }
    Text("Retained on this phone", style = MaterialTheme.typography.titleSmall)
    Text(
        "Local Relay text; read only. Copies grant no attach, send or control permission.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedTextField(query, {
        query = it
    }, Modifier.fillMaxWidth(), label = { Text("Search retained text") }, maxLines = 2)
    TextButton({ actions.searchRetained(query) }) { Text("Search this phone") }
    relay.retainedProblem?.let {
        Text(
            retainedProblemText(it),
            color = MaterialTheme.colorScheme.error
        )
    }
    if (relay.retentionDays == 0) Text("Retention disabled. Online text is not retained.")
    if (relay.retainedSelected == null) {
        if (relay.retained.isEmpty()) {
            Text(
                "No retained matches in this exact profile and review scope."
            )
        }
        relay.retained.forEach { item ->
            TextButton({ actions.openRetained(item.id) }) {
                Text(
                    (item.title.ifBlank { "Untitled" }) +
                        if (item.complete) " · retained" else " · partial"
                )
            }
        }
    } else {
        val item = requireNotNull(relay.retainedSelected)
        Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.labelLarge)
        Text(
            if (item.complete) {
                "Complete at last listing; read only."
            } else {
                "Retained fragment; incomplete history."
            }
        )
        item.messages.forEach {
            Text(
                (
                    if (it.role ==
                        dev.quantumink.hermesgadget.protocol.relay.session.RetainedRole.USER
                    ) {
                        "You: "
                    } else {
                        "Hermes: "
                    }
                    ) +
                    it.text
            )
        }
        TextButton(actions.closeRetained) { Text("Close retained view") }
        TextButton({ actions.reviewCache(item.id) }) { Text("Review cache-only erase and rebuild") }
    }
    if (relay.retainedProblem !=
        null
    ) {
        TextButton({
            actions.reviewCache(relay.selected?.id)
        }) { Text("Review affected retained bytes") }
    }
    if (relay.retentionDays != null) {
        OutlinedTextField(days, {
            days = it
        }, Modifier.fillMaxWidth(), label = {
            Text("All phone profiles: retention days (0–365)")
        }, singleLine = true)
        TextButton(
            { days.toIntOrNull()?.let(actions.retention) },
            enabled =
            days.toIntOrNull() in 0..365
        ) { Text("Review retention change") }
    }
    relay.cacheReviewConfirmation?.let { rendered ->
        val review = rendered.review
        Text(
            if (review.allProfiles) {
                "This affects ALL retained profiles on this phone."
            } else {
                "This affects the reviewed namespace and " +
                    "${review.conversationId ?: "all its conversations"}."
            }
        )
        Text(
            "Record version ${review.version ?: "unknown"}, " +
                "revision ${review.revision ?: "unreadable"}, ${review.recordBytes} bytes. " +
                "SHA-256 ${review.recordHash}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            review.retentionDays?.let {
                "Apply $it days globally (0 clears text and disables retention)."
            }
                ?: (
                    "Erase local bytes; canonical history is unchanged. " +
                        "Rebuild needs authenticated refetch."
                    )
        )
        Row {
            Button({ actions.confirmCacheReview(rendered) }) { Text("Confirm reviewed change") }
            TextButton(actions.cancelCacheReview) { Text("Cancel") }
        }
    }
    if (relay.contentEvictionPending) {
        Text(
            "Local content removal is still pending confirmation.",
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun TurnList(relay: RelayUiState, actions: RelayActions) {
    if (relay.turns.isEmpty()) return
    Text("Your messages", style = MaterialTheme.typography.labelLarge)
    relay.turns.forEach { TurnRow(it, actions) }
    TextButton(actions.clearSettled) { Text("Clear finished") }
}

@Composable
private fun TurnRow(turn: TurnView, actions: RelayActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(turn.preview.ifEmpty { "(removed)" })
        Text(
            (if (turn.previousProfile) "Earlier profile, " else "") + turnStatusText(turn.status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (turn.canCheck) TextButton({ actions.check(turn.requestId) }) { Text("Check") }
            if (turn.canResend) {
                TextButton({
                    actions.resend(turn.requestId)
                }) { Text("Send again") }
            }
            if (turn.canAbandon) {
                TextButton({
                    actions.abandon(turn.requestId)
                }) { Text("Stop tracking") }
            }
        }
    }
}

/** The typed composer that replaces the stock controls inside a selected Relay conversation (no microphone). */
@Composable
internal fun RelayComposer(
    state: PhoneConversationState,
    draft: (String) -> Unit,
    send: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            state.draft,
            draft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message to Hermes") },
            enabled = state.relay.canSend && !state.sending,
            maxLines = 4
        )
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        Button(
            send,
            enabled = state.relay.canSend && !state.sending && state.draft.isNotBlank()
        ) { Text("Send") }
    }
}
