package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode

@Composable
internal fun ConversationControls(
    state: PhoneConversationState,
    draft: (String) -> Unit,
    send: () -> Unit,
    record: () -> Unit,
    finishRecording: () -> Unit,
    cancelRecording: () -> Unit,
    stopVoice: () -> Unit,
    cancelTurn: () -> Unit,
    keyboardVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val conversation = state.connection.conversation
    val listening = conversation.mode == ConversationMode.LISTENING
    val paired = state.connection.status == ConnectionStatus.PAIRED
    val ready = paired && !state.sending && (!conversation.busy || listening)
    val start by rememberUpdatedState(record)
    val finish by rememberUpdatedState(finishRecording)
    val cancel by rememberUpdatedState(cancelRecording)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.speaking || listening) {
            AudioWaveform(
                if (listening) conversation.level else state.playbackLevel,
                if (listening) "Microphone" else "Response"
            )
        }
        if (state.mediaMuted) {
            Text(
                "Media volume is muted. Use your volume buttons to hear replies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            state.draft,
            draft,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !listening,
            minLines = 1,
            maxLines = 4,
            shape = RoundedCornerShape(20.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.speaking) {
                TextButton(stopVoice, Modifier.weight(1f)) { Text("Stop voice") }
            } else if (listening) {
                TextButton(cancelRecording, Modifier.weight(1f)) { Text("Cancel recording") }
            } else if (conversation.busy) {
                TextButton(cancelTurn, Modifier.weight(1f), enabled = paired) {
                    Text("Cancel turn")
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(
                send,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Send message" },
                enabled =
                paired && !conversation.busy && !state.sending && state.draft.isNotBlank()
            ) {
                Text(if (state.sending) "Sending…" else "Send")
            }
        }
        if (!keyboardVisible || listening) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .background(
                        if (ready) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        RoundedCornerShape(24.dp)
                    )
                    .semantics {
                        role = Role.Button
                        if (!ready) disabled()
                        onClick(if (listening) "Finish recording" else "Start recording") {
                            if (ready) {
                                if (listening) finish() else start()
                                true
                            } else {
                                false
                            }
                        }
                    }.pointerInput(ready) {
                        if (ready) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                start()
                                var ended = false
                                try {
                                    while (!ended) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || change.isConsumed ||
                                            change.position.y - down.position.y > 60.dp.toPx()
                                        ) {
                                            cancel()
                                            ended = true
                                        } else if (!change.pressed) {
                                            finish()
                                            ended = true
                                        }
                                        change?.consume()
                                    }
                                } finally {
                                    if (!ended) cancel()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (listening) "Listening · release to send" else "Hold to talk",
                    color = if (ready) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Text(
                "Drag down to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
