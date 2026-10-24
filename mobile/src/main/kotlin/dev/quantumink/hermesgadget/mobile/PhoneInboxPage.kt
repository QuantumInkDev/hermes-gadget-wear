package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.OutboxPayload
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.Task
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.session.InboxInput
import dev.quantumink.hermesgadget.protocol.relay.session.InboxRoute

/**
 * Existing phone theme, deliberate refresh/actions and retained read-only detail. No playback or
 * launch action.
 */
@Composable
internal fun PhoneInboxPage(
    owner: PhoneInboxOwner,
    route: InboxRoute?,
    locked: Boolean,
    input: InboxInput?,
    inputNotice: String,
    onOpen: (String) -> Unit,
    onContinue: () -> Unit,
    onInput: () -> Unit,
    onAnswer: (PromptAnswer) -> Unit,
    onNotifications: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot by owner.state.collectAsStateWithLifecycle()
    val status by owner.status.collectAsStateWithLifecycle()
    val job by InboxJobs.status.collectAsStateWithLifecycle()
    LaunchedEffect(owner) { owner.load() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Task inbox", style = MaterialTheme.typography.headlineMedium)
        Text(status)
        Text(
            "Retained on this phone. Missing pages and unavailable boxes keep their prior results."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ owner.refresh(null) }, enabled = !locked) { Text("Refresh inbox") }
            TextButton(onNotifications) { Text("Allow notifications") }
        }
        val s = snapshot
        if (s ==
            null
        ) {
            Text("Inbox unavailable or still loading. Stored bytes are preserved.")
        } else {
            InboxSwitch("Background updates", s.background) { owner.background(it) }
            Text(job)
            Text(
                "Text retention: ${s.retentionDays} days. " +
                    "Server copies expire separately after 24 hours."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (days in listOf(0, 7, 30, 90, 365)) {
                    TextButton({ owner.retention(days) }) { Text("$days days") }
                }
            }
            val route = route?.let { old ->
                InboxRoute.resolve(
                    s,
                    old.copy?.handle ?: old.task?.handle.orEmpty()
                )
            }
            if (route != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${route.profile.box} • ${route.profile.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Profile ${route.namespace.profileId} / " +
                                "review ${route.namespace.reviewEpoch}"
                        )
                        Text("Conversation ${route.conversation}")
                        route.copy?.let { copy ->
                            Text(
                                "${if (copy.unread) "Unread" else "Read"} recovered copy • " +
                                    "received ${stamp(
                                        copy.receivedAtMs
                                    )}"
                            )
                            if (copy.unread) {
                                TextButton({
                                    owner.markRead(copy.handle)
                                }) { Text("Mark read") }
                            }
                            Text(
                                "Created ${stamp(
                                    copy.item.createdAtMs
                                )} / server expiry ${stamp(copy.item.expiresAtMs)}"
                            )
                            when (val payload = copy.item.payload) {
                                is OutboxPayload.FinalText -> Text(payload.text)
                                is OutboxPayload.TaskChange.Relay -> TaskText(
                                    payload.task.state,
                                    payload.task.outcome?.wire
                                )
                                is OutboxPayload.TaskChange.Existing -> {
                                    Text("Existing source: ${payload.task.source}")
                                    TaskText(payload.task.state, payload.task.outcome?.wire)
                                }
                            }
                        }
                        route.task?.let { TaskText(it.state, it.outcome) }
                        if (route.canonicalConversation ==
                            null
                        ) {
                            Text("Opaque recovered reply • read-only")
                        } else {
                            Text(
                                "Recovered input is display-only. " +
                                    "Fetch current input before answering."
                            )
                            Button(onContinue, enabled = !locked) {
                                Text("Continue exact conversation")
                            }
                            TextButton(onInput, enabled = !locked) { Text("Get current input") }
                        }
                        Text(inputNotice)
                        input?.let { current ->
                            Text(current.prompt.text.orEmpty())
                            if (!current.canAnswer) {
                                Text(
                                    "Continue this conversation, " +
                                        "then fetch current input again to answer."
                                )
                            }
                            for (choice in current.prompt.choices) {
                                Button(
                                    { onAnswer(PromptAnswer.Choice(choice)) },
                                    enabled =
                                    current.canAnswer && !locked
                                ) { Text(choice) }
                            }
                            if (current.prompt.freeText) {
                                var text by remember(current) { mutableStateOf("") }
                                OutlinedTextField(text, {
                                    text = it.take(8192)
                                }, label = { Text("Your answer") })
                                Button(
                                    { onAnswer(PromptAnswer.Text(text)) },
                                    enabled =
                                    current.canAnswer && !locked && text.isNotBlank()
                                ) {
                                    Text("Send answer")
                                }
                            }
                        }
                    }
                }
            }
            if (s.profiles.isEmpty()) {
                Text(
                    "Enable Relay for a reviewed phone profile and connect once to add its inbox."
                )
            }
            for (p in s.profiles) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("${p.box} • ${p.label}", style = MaterialTheme.typography.titleMedium)
                        Text("${p.namespace.endpointUrl} / profile ${p.namespace.profileId}")
                        Text(
                            "Last full sync: ${if (p.lastSyncMs == 0L) {
                                "not confirmed"
                            } else {
                                stamp(
                                    p.lastSyncMs
                                )
                            }}"
                        )
                        InboxSwitch("Profile inbox", p.enabled) {
                            owner.configure(p.copy(enabled = it))
                        }
                        InboxSwitch("Quiet completion notifications", p.completionAlerts) {
                            owner.configure(p.copy(completionAlerts = it))
                        }
                        InboxSwitch("Quiet input notifications", p.inputAlerts) {
                            owner.configure(p.copy(inputAlerts = it))
                        }
                        InboxSwitch("Include existing Hermes tasks", p.existingTasks) {
                            owner.configure(p.copy(existingTasks = it))
                        }
                        InboxSwitch("Include existing Hermes history", p.existingHistory) {
                            owner.configure(p.copy(existingHistory = it))
                        }
                        if (p.existingTasks &&
                            !p.existingAvailable
                        ) {
                            Text(
                                "Existing tasks unavailable until current " +
                                    "grants and delegation are confirmed."
                            )
                        }
                        for (t in s.tasks.filter {
                            it.namespace == p.namespace
                        }.sortedByDescending { it.receivedAtMs }) {
                            TextButton({
                                onOpen(t.handle)
                            }) {
                                Text(
                                    "${if (t.existing == null) "Relay" else "Existing"} • " +
                                        "${t.state.wire} • " +
                                        "${t.outcome ?: "outcome unknown"} • ${t.id}"
                                )
                            }
                        }
                        for (c in s.copies.filter {
                            it.namespace == p.namespace
                        }.sortedByDescending { it.receivedAtMs }) {
                            TextButton({
                                onOpen(c.handle)
                            }) {
                                val kind =
                                    if (c.item is OutboxItem.Final) "reply" else "task change"
                                Text(
                                    "Recovered $kind • " +
                                        "${stamp(
                                            c.receivedAtMs
                                        )}"
                                )
                            }
                        }
                        for (h in s.histories.filter { it.namespace == p.namespace }) {
                            Text(
                                "${if (h.existing) "Existing" else "Relay"} " +
                                    "retained conversation ${h.conversation}"
                            )
                            for (m in h.messages) Text("${m.role.name.lowercase()}: ${m.text}")
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun InboxSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change)
    }
}

@Composable private fun TaskText(state: TaskState, outcome: String?) {
    Text("${state.wire} • ${outcome ?: "outcome not known"}")
}
private fun stamp(at: Long): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
