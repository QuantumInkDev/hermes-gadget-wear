package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.WakeView
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeAnswer
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTarget
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeUnavailableReason

internal fun wakeAnswerText(answer: WakeAnswer): String = when (answer) {
    WakeAnswer.ACCEPTED ->
        "Wake sent. The box may take a minute or two to come up; this does not confirm it is ready."
    WakeAnswer.UNAVAILABLE -> "Unavailable: the host could not send the wake right now."
    WakeAnswer.UNSUPPORTED_TARGET -> "Unavailable: this host cannot wake that box."
    WakeAnswer.NOT_OFFERED ->
        "Not offered: remote wake is off on this server or this phone has no wake permission."
    WakeAnswer.NOT_PERMITTED -> "Not permitted: this phone is not allowed to do that."
    WakeAnswer.EXPIRED -> "Expired: the request arrived too late and nothing was woken. Try again."
    WakeAnswer.TOO_SOON -> "Too soon: a box can be woken once a minute. Try again shortly."
    WakeAnswer.NOT_FOUND -> "That box is no longer shared with this phone. Refresh the list."
    WakeAnswer.BUSY -> "The host is busy. Try again in a moment."
    WakeAnswer.TURNED_OFF -> "Remote wake is turned off on this server."
    WakeAnswer.REFUSED -> "The server refused the request. Nothing was woken."
    WakeAnswer.NO_CLOCK -> "The server clock is not available yet. Nothing was sent."
    WakeAnswer.NOT_SENT -> "Not sent. Check the connection and try again."
    WakeAnswer.NOT_CONFIRMED ->
        "No answer. The wake may or may not have been sent; nothing is retried by itself."
}

/** A failed list read dispatches nothing, so its words never suggest that a wake may have been sent. */
internal fun wakeListText(answer: WakeAnswer): String = when (answer) {
    WakeAnswer.NOT_CONFIRMED, WakeAnswer.NOT_SENT ->
        "The list could not be read. Refresh to try again."
    else -> wakeAnswerText(answer)
}

private fun unavailableText(reason: WakeUnavailableReason?): String = when (reason) {
    WakeUnavailableReason.UNSUPPORTED -> "This host cannot wake it."
    WakeUnavailableReason.UNAVAILABLE, null -> "Not ready to be woken."
}

/**
 * "Wake a box", inside the Relay section of the chosen profile: collapsed by default, and every server call is a tap.
 * Opening it reads the list once; each Wake is confirmed and sends exactly one request through that profile's host.
 */
@Composable
internal fun WakeControls(relay: RelayUiState, actions: RelayActions) {
    val wake = relay.wake
    var open by remember(relay.identity, relay.foregroundSession) { mutableStateOf(false) }
    var confirm by remember(relay.identity, relay.foregroundSession) {
        mutableStateOf<WakeTarget?>(null)
    }
    TextButton({
        open = !open
        if (open && wake.canList && wake.targets == null && !wake.listing) actions.listWake()
    }) { Text(if (open) "Hide wake" else "Wake a box") }
    if (!open) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WakeBody(wake, actions) { confirm = it }
    }
    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Wake ${target.label}?") },
            text = {
                Text(
                    "This profile's host sends one wake request now. It does not confirm that " +
                        "the box finished starting."
                )
            },
            confirmButton = {
                Button({
                    confirm = null
                    actions.wake(target.targetId.value)
                }) { Text("Wake") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WakeBody(wake: WakeView, actions: RelayActions, ask: (WakeTarget) -> Unit) {
    if (!wake.offered) {
        Text(
            "Remote wake is not offered to this phone. The server may have it turned off, " +
                "or this phone has no wake permission.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    if (!wake.canList) {
        Text("This phone may not list boxes, so there is nothing to wake from here.")
        return
    }
    if (!wake.canRequest) {
        Text(
            "This phone can see boxes but is not permitted to wake them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (wake.listing) Text("Checking which boxes can be woken.")
    wake.listAnswer?.let { Text(wakeListText(it), color = MaterialTheme.colorScheme.error) }
    val targets = wake.targets
    if (targets != null && targets.isEmpty()) Text("No boxes are shared with this phone.")
    targets?.forEach { target ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(target.label)
                if (!target.available) {
                    Text(
                        unavailableText(target.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                { ask(target) },
                enabled = wake.canRequest && target.available && wake.sending == null
            ) {
                Text(if (wake.sending == target.targetId.value) "Waking…" else "Wake")
            }
        }
        wake.answers[target.targetId.value]?.let {
            Text(
                wakeAnswerText(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (it == WakeAnswer.ACCEPTED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
    TextButton(actions.listWake, enabled = !wake.listing) { Text("Refresh boxes") }
}
