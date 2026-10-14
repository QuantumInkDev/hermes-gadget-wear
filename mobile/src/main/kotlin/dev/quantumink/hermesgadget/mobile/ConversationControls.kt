package dev.quantumink.hermesgadget.mobile

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.delay

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
    val ready = state.visible && paired && !state.sending && (!conversation.busy || listening)
    val owner = state.sessionVersion to state.profile?.endpoint?.url
    val distance = with(LocalDensity.current) { 64.dp.toPx() }
    val gesture = remember(owner, distance) { VoiceMessageGesture(distance) }
    var holding by remember(owner) { mutableStateOf(false) }
    var endAtStart by remember(owner) { mutableLongStateOf(state.recordingEndVersion) }
    var errorAtStart by remember(owner) { mutableStateOf(state.error) }
    val textDirection = LocalLayoutDirection.current
    LaunchedEffect(holding, state.recordingEndVersion, state.error) {
        if (holding && (
                state.recordingEndVersion != endAtStart ||
                    (state.error.isNotBlank() && state.error != errorAtStart)
                )
        ) {
            if (gesture.cancel() != null) cancelRecording()
            holding = false
        }
    }
    val recording = holding || listening
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(recording, state.recordingStartedAt) {
        while (recording) {
            now = SystemClock.uptimeMillis()
            delay(200)
        }
    }
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.speaking && !recording) AudioWaveform(state.playbackLevel, "Response")
        if (state.mediaMuted) {
            Text(
                "Media volume is muted. Use your volume buttons to hear replies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!recording && (state.speaking || conversation.busy)) {
            if (state.speaking) {
                TextButton(stopVoice) { Text("Stop voice") }
            } else {
                TextButton(cancelTurn, enabled = paired) { Text("Cancel turn") }
            }
        }
        // The gesture is physically left in every locale; field text retains its own direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                if (recording) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton({
                            if (gesture.cancel() != null) cancelRecording()
                            holding = false
                        }) {
                            Icon(painterResource(R.drawable.ic_voice_cancel), "Cancel recording")
                        }
                        Column {
                            val elapsed = state.recordingStartedAt?.let {
                                ((now - it) / 1000).coerceAtLeast(0)
                            }
                            Text(
                                if (elapsed ==
                                    null
                                ) {
                                    "0:00"
                                } else {
                                    "%d:%02d".format(elapsed / 60, elapsed % 60)
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                if (elapsed == null) "Starting…" else "Recording",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        key(owner) {
                            AudioWaveform(
                                if (state.recordingStartedAt == null) 0f else conversation.level,
                                "Microphone",
                                Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                        OutlinedTextField(
                            state.draft,
                            draft,
                            placeholder = { Text("Message…") },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp)
                        )
                    }
                }
                if (!recording && state.draft.isNotBlank()) {
                    FilledIconButton(
                        send,
                        Modifier.size(56.dp).semantics { contentDescription = "Send message" },
                        enabled = ready && !conversation.busy
                    ) {
                        Icon(painterResource(R.drawable.ic_voice_send), null)
                    }
                } else {
                    VoiceRecordButton(
                        owner,
                        gesture,
                        ready,
                        recording,
                        {
                            endAtStart = state.recordingEndVersion
                            errorAtStart = state.error
                            record()
                        },
                        finishRecording,
                        cancelRecording,
                        {
                            holding =
                                it
                        }
                    )
                }
            }
        }
        if (recording || state.draft.isBlank()) {
            Text(
                if (recording) {
                    "Slide left to cancel · release to send"
                } else {
                    "Hold to talk · slide left to cancel"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
