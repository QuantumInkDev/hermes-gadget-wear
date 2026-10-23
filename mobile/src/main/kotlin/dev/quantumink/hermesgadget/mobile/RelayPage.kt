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
    val retryStorage: () -> Unit = {}
) {
    companion object {
        val NONE = RelayActions()

        fun of(model: PhoneConversation) = RelayActions(
            model::relayEnable,
            model::relayRefresh,
            model::relayMore,
            model::relayCreate,
            model::relayDiscardCreate,
            model::relaySelect,
            model::relayLeave,
            model::relayCheck,
            model::relayResend,
            model::relayAbandon,
            model::relayClearSettled,
            model::relayAcceptReview,
            model::relayReauthorize,
            model::relayRetryStorage
        )
    }
}

internal fun relayNoticeText(notice: RelayNotice): String = when (notice) {
    RelayNotice.NONE -> ""
    RelayNotice.STORAGE_FULL_PENDING_ITEMS ->
        "Can't save more until pending or unconfirmed messages are reviewed. Nothing was sent."
    RelayNotice.STORAGE_WRITE_FAILED -> "Relay could not save its state. Nothing was sent."
    RelayNotice.STORAGE_UNCERTAIN -> "Relay could not confirm that its state was saved. Nothing was sent."
    RelayNotice.SEND_REFUSED_STORAGE -> "Relay state is not saved yet. Try again."
    RelayNotice.CLOCK_UNAVAILABLE -> "The server clock is not available yet."
    RelayNotice.LEDGER_FULL -> "Too many messages are tracked. Clear finished ones to continue."
    RelayNotice.TURN_REFUSED -> "That message cannot be sent."
    RelayNotice.CONVERSATION_NOT_CONTINUABLE -> "This conversation cannot be continued from the phone."
    RelayNotice.CREATE_PENDING -> "A new conversation is still being created. It will not be created twice."
    RelayNotice.CREATE_REFUSED -> "The server refused to create the conversation."
    RelayNotice.CREATE_EXPIRED -> "The new conversation could not be confirmed in time. Discard it and try again."
    RelayNotice.CREATE_FAILED -> "The new conversation could not be created. Discard it and try again."
    RelayNotice.NAME_INVALID -> "That name is not valid."
    RelayNotice.BUSY_TRY_AGAIN -> "The server is busy. Retrying."
    RelayNotice.RECONNECTING -> "Reconnecting."
    RelayNotice.HISTORY_INCOMPLETE -> "Only part of this conversation's history is shown."
    RelayNotice.PROMPT_ELSEWHERE -> "Hermes is waiting for an answer on another device."
    RelayNotice.PERMISSION_DENIED -> "This phone no longer has access. Cached Relay content was removed."
    RelayNotice.PROFILE_CHANGED -> "The server or profile changed. Review it before continuing."
    RelayNotice.SERVER_ERROR -> "The server could not do that."
    RelayNotice.ANOMALY -> "The server answered something unexpected. Nothing was changed."
    RelayNotice.LINK_FAILED -> "The connection to Relay failed. Try again."
    RelayNotice.CONVERSATION_DELETED -> "This conversation no longer exists."
    RelayNotice.VOICE_UNAVAILABLE -> "Voice is not available in a Relay conversation yet."
}

internal fun relayReasonText(reason: RelayReason): String = when (reason) {
    RelayReason.NONE -> ""
    RelayReason.NOT_OFFERED -> "This server does not offer Relay conversations. The normal chat still works."
    RelayReason.MALFORMED_WELCOME -> "The server's Relay answer was not understood. Relay is off for this connection."
    RelayReason.NOT_PAIRED -> "Approve this phone on the box first, then reconnect."
    RelayReason.MISSING_METHODS -> "This server's Relay grant does not allow conversations from the phone."
    RelayReason.NO_SERVER_TIME -> "This Relay grant has no conversation access."
    RelayReason.INFO_FAILED -> "Relay could not be set up with this server."
    RelayReason.CLOCK_UNAVAILABLE -> "The server clock could not be read."
    RelayReason.TLS_REQUIRED -> "Relay conversations require a wss endpoint."
    RelayReason.TOO_MANY_ENROLLMENTS -> "Relay supports at most 16 phone enrollments."
}

internal fun relayStorageText(problem: StoreProblem): String = when (problem) {
    StoreProblem.KEY_UNAVAILABLE -> "The key for Relay's saved state is unavailable. The saved state was kept."
    StoreProblem.STORAGE_UNAVAILABLE -> "Relay's saved state cannot be read or written. It was kept."
    StoreProblem.RECORD_MISSING -> "Relay's saved state went missing. Nothing was replaced."
    StoreProblem.CORRUPT -> "Relay's saved state could not be verified. It was kept untouched."
    StoreProblem.UNSUPPORTED_VERSION -> "Relay's saved state is from a newer version. It was kept untouched."
    StoreProblem.TOO_LARGE -> "Relay's saved state is larger than this version supports. It was kept untouched."
    StoreProblem.UNRESTORABLE -> "Relay's saved state cannot be restored by this version. It was kept untouched."
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
                Text(relay.storage?.let(::relayStorageText).orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(actions.retryStorage) { Text("Check again") }
            }
            RelayPhase.CONNECTING -> Text("Relay is on. Connecting.")
            RelayPhase.UNAVAILABLE -> {
                Text(relayReasonText(relay.reason))
                TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
            }
            RelayPhase.REVIEW_REQUIRED -> RelayReview(relay, actions)
            RelayPhase.STOPPED -> {
                Text("This phone's Relay access was removed by the server. Nothing is sent and cached content is gone.")
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
    }
}

@Composable
private fun RelayOff(requested: Boolean, reason: RelayReason, actions: RelayActions) {
    Text(
        "Continue a Hermes conversation from this phone with typed messages. Off by default; " +
            "the normal chat is unchanged. Voice in a Relay conversation comes later.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (reason != RelayReason.NONE) Text(relayReasonText(reason), color = MaterialTheme.colorScheme.error)
    if (requested) {
        Text("Relay is on. It starts when you connect.")
        TextButton({ actions.enable(false) }) { Text("Turn off Relay") }
    } else {
        Button({ actions.enable(true) }) { Text("Turn on Relay") }
    }
}

@Composable
private fun RelayReview(relay: RelayUiState, actions: RelayActions) {
    Text("The server or profile this phone talks to has changed. Nothing is sent until you review it.")
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
    relay.conversations.forEach { RelayConversationRow(it, actions) }
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
    relay.tasksWorking?.let { working ->
        val waiting = relay.tasksNeedInput ?: 0
        if (working > 0 || waiting > 0) Text("Working: $working, needs input elsewhere: $waiting")
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
            if (turn.canResend) TextButton({ actions.resend(turn.requestId) }) { Text("Send again") }
            if (turn.canAbandon) TextButton({ actions.abandon(turn.requestId) }) { Text("Stop tracking") }
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
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
