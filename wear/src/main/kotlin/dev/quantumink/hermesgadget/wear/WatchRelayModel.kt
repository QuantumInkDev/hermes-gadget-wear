package dev.quantumink.hermesgadget.wear

import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
import dev.quantumink.hermesgadget.protocol.relay.session.InboxCopy
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.RelayNotice
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayReason
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus

/** One watch inbox line. It names a task state and a profile, never reply or prompt text. */
internal data class InboxLine(
    val handle: String,
    val namespace: HistoryNamespace,
    val profile: String,
    /** Null for a recovered final reply. */
    val state: TaskState?,
    val atMs: Long,
    val unread: Boolean,
    /** A canonical conversation exists that this watch can continue. */
    val canOpen: Boolean
) {
    override fun toString(): String = "InboxLine(redacted)"
}

/** The private notification text of one copy: a public version without the profile, a private one without content. */
internal data class InboxAlert(
    val title: String,
    val text: String,
    val publicTitle: String,
    val publicText: String
)

/** Pure watch-side Relay presentation: enrollment label, inbox lines, alert text and short status words. */
internal object WatchRelayModel {
    const val MAX_LINES = 50
    const val MAX_TRANSCRIPT = 24

    /** The watch's OWN enrollment for one profile: its public key-derived id; the key itself never leaves the vault. */
    fun identity(endpoint: Endpoint, device: DeviceIdentity): EnrollmentIdentity? =
        if (endpoint.isTls) EnrollmentIdentity(endpoint.url, "watch:" + device.deviceId) else null

    /** The saved watch profile that owns [namespace]: same canonical endpoint AND this watch's own enrollment label. */
    fun <P> owner(namespace: HistoryNamespace, profiles: List<Pair<P, EnrollmentIdentity?>>): P? =
        profiles.singleOrNull { (_, who) ->
            who != null && who.endpointUrl == namespace.endpointUrl &&
                who.enrollmentLabel == namespace.enrollmentLabel
        }?.first

    /** Newest first, one line per task (a newer copy of the same task replaces an older line), bounded. */
    fun lines(snapshot: InboxSnapshot?): List<InboxLine> {
        val s = snapshot ?: return emptyList()
        fun label(namespace: HistoryNamespace) =
            s.profiles.firstOrNull { it.namespace == namespace }?.label.orEmpty()
        val byTask = LinkedHashMap<Pair<HistoryNamespace, String>, InboxLine>()
        val finals = ArrayList<InboxLine>()
        fun keep(key: Pair<HistoryNamespace, String>, line: InboxLine) {
            val old = byTask[key]
            byTask[key] = if (old == null || line.atMs >= old.atMs) {
                line.copy(unread = line.unread || old?.unread == true)
            } else {
                old.copy(unread = old.unread || line.unread)
            }
        }
        for (task in s.tasks) {
            keep(
                task.namespace to task.id,
                InboxLine(
                    task.handle,
                    task.namespace,
                    label(task.namespace),
                    task.state,
                    task.receivedAtMs,
                    false,
                    task.existing == null
                )
            )
        }
        for (copy in s.copies) {
            val line = InboxLine(
                copy.handle,
                copy.namespace,
                label(copy.namespace),
                state(copy),
                copy.receivedAtMs,
                copy.unread,
                copy.canonicalConversation != null && !copy.existing
            )
            when (val payload = copy.item.payload) {
                is OutboxPayload.TaskChange.Relay -> keep(
                    copy.namespace to payload.task.taskId.value,
                    line
                )
                is OutboxPayload.TaskChange.Existing -> keep(
                    copy.namespace to payload.task.taskId.value,
                    line
                )
                is OutboxPayload.FinalText -> finals.add(line)
            }
        }
        return (byTask.values + finals).sortedByDescending { it.atMs }.take(MAX_LINES)
    }

    fun state(copy: InboxCopy): TaskState? = when (val payload = copy.item.payload) {
        is OutboxPayload.TaskChange.Relay -> payload.task.state
        is OutboxPayload.TaskChange.Existing -> payload.task.state
        is OutboxPayload.FinalText -> null
    }

    fun stateText(state: TaskState?): String = when (state) {
        TaskState.WORKING -> "Working"
        TaskState.NEEDS_INPUT -> "Needs input"
        TaskState.FINISHED -> "Finished"
        TaskState.FAILED -> "Failed"
        TaskState.UNKNOWN -> "Unknown"
        null -> "Reply ready"
    }

    /** No reply, prompt or conversation text: the private body names only the profile and the state. */
    fun alert(copy: InboxCopy, profile: String?): InboxAlert {
        val title = profile?.takeIf { it.isNotBlank() }?.take(40) ?: "Hermes"
        return InboxAlert(
            title,
            stateText(state(copy)) + ". Open to review.",
            "Hermes update",
            "Open the app to review."
        )
    }

    fun phaseText(ui: RelayUiState): String = when (ui.phase) {
        RelayPhase.OFF -> if (ui.reason ==
            RelayReason.NONE
        ) {
            "Relay is off."
        } else {
            reasonText(ui.reason)
        }
        RelayPhase.CHECKING -> "Checking saved Relay state."
        RelayPhase.STORAGE_PROBLEM -> "Saved Relay state can't be used. It was kept."
        RelayPhase.CONNECTING -> "Relay is on. Connect to continue."
        RelayPhase.UNAVAILABLE -> reasonText(ui.reason)
        RelayPhase.REVIEW_REQUIRED -> "The server or profile changed. Review before continuing."
        RelayPhase.STOPPED -> "Relay access was removed. Sending is stopped."
        RelayPhase.READY -> "Ready."
        RelayPhase.ATTACHING -> "Loading the conversation."
        RelayPhase.SELECTED -> ""
    }

    fun reasonText(reason: RelayReason): String = when (reason) {
        RelayReason.NONE -> ""
        RelayReason.NOT_OFFERED -> "This server does not offer Relay. Normal chat still works."
        RelayReason.MALFORMED_WELCOME -> "The server's Relay answer was not understood."
        RelayReason.NOT_PAIRED -> "Approve this watch on the box first."
        RelayReason.MISSING_METHODS -> "This watch's grant does not allow conversations."
        RelayReason.NO_SERVER_TIME -> "This grant has no conversation access."
        RelayReason.INFO_FAILED -> "Relay could not be set up."
        RelayReason.ACCESS_DENIED -> "The server does not give this watch Relay access."
        RelayReason.CLOCK_UNAVAILABLE -> "The server clock could not be read."
        RelayReason.TLS_REQUIRED -> "Relay needs a wss endpoint."
        RelayReason.TOO_MANY_ENROLLMENTS -> "Relay supports at most 16 watch profiles."
    }

    fun noticeText(notice: RelayNotice): String = when (notice) {
        RelayNotice.NONE -> ""
        RelayNotice.STORAGE_FULL_PENDING_ITEMS, RelayNotice.LEDGER_FULL ->
            "Too many tracked messages. Clear finished ones."
        RelayNotice.STORAGE_WRITE_FAILED, RelayNotice.STORAGE_UNCERTAIN,
        RelayNotice.SEND_REFUSED_STORAGE -> "Relay could not save its state. Nothing was sent."
        RelayNotice.OBSERVATION_UNSAVED, RelayNotice.OBSERVATION_UNCERTAIN ->
            "The reply is shown but not saved yet."
        RelayNotice.CLOCK_UNAVAILABLE -> "The server clock is not available yet."
        RelayNotice.TURN_REFUSED -> "That message cannot be sent."
        RelayNotice.CONVERSATION_NOT_CONTINUABLE -> "This conversation is read only here."
        RelayNotice.CREATE_PENDING -> "A new conversation is still being created."
        RelayNotice.CREATE_REFUSED, RelayNotice.CREATE_FAILED, RelayNotice.CREATE_EXPIRED ->
            "The new conversation could not be created. Discard and retry."
        RelayNotice.NAME_INVALID -> "That name is not valid."
        RelayNotice.BUSY_TRY_AGAIN -> "The server is busy. Retrying."
        RelayNotice.RECONNECTING -> "Reconnecting."
        RelayNotice.HISTORY_INCOMPLETE, RelayNotice.SNAPSHOT_INCOMPLETE -> "Only part is shown."
        RelayNotice.PROMPT_ELSEWHERE -> "Hermes is waiting for an answer on another device."
        RelayNotice.PERMISSION_DENIED -> "This watch no longer has access. Sending is stopped."
        RelayNotice.PROFILE_CHANGED -> "The server or profile changed. Review first."
        RelayNotice.SERVER_ERROR -> "The server could not do that."
        RelayNotice.ANOMALY -> "Unexpected server answer. Nothing changed."
        RelayNotice.LINK_FAILED -> "The Relay link failed. Try again."
        RelayNotice.CONVERSATION_DELETED -> "This conversation no longer exists."
        RelayNotice.VOICE_UNAVAILABLE -> "Voice is not available here. Type or dictate."
    }

    /** A settled turn is already part of the transcript; only the others get their own status line. */
    fun settled(status: TurnStatus): Boolean = status in setOf(
        TurnStatus.FINISHED_COMPLETED,
        TurnStatus.FINISHED_OTHER,
        TurnStatus.FAILED,
        TurnStatus.TERMINAL_UNKNOWN_OUTCOME,
        TurnStatus.REFUSED
    )

    fun turnText(status: TurnStatus): String = when (status) {
        TurnStatus.NOT_SENT -> "Not sent"
        TurnStatus.SENDING -> "Sending"
        TurnStatus.UNCERTAIN -> "Not confirmed"
        TurnStatus.CHECKING -> "Checking"
        TurnStatus.QUEUED -> "Waiting"
        TurnStatus.RUNNING -> "Working"
        TurnStatus.UNKNOWN_ADMITTED -> "Accepted"
        TurnStatus.FINISHED_COMPLETED -> "Done"
        TurnStatus.FINISHED_OTHER -> "Ended"
        TurnStatus.FAILED -> "Failed"
        TurnStatus.TERMINAL_UNKNOWN_OUTCOME -> "Ended, outcome unknown"
        TurnStatus.REFUSED -> "Refused"
    }
}
