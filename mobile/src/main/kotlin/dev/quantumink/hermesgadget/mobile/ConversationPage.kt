package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode

@Composable
fun ConversationPage(
    model: PhoneConversation,
    page: dev.quantumink.hermesgadget.protocol.CatalogPage?,
    connect: () -> Unit,
    record: () -> Unit,
    back: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by model.state.collectAsStateWithLifecycle()
    val conversation = state.connection.conversation
    val listening = conversation.mode == ConversationMode.LISTENING
    val paired = state.connection.status == ConnectionStatus.PAIRED
    val ready = paired && !state.sending && (!conversation.busy || listening)
    val start by rememberUpdatedState(record)
    val finish by rememberUpdatedState(model::finishRecording)
    val cancel by rememberUpdatedState(model::cancelRecording)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(back) { Text("Back to profiles") }
        page?.let {
            DirectoryPortrait(
                CatalogRepository.get(androidx.compose.ui.platform.LocalContext.current),
                it
            )
        }
        Text(state.profile?.label.orEmpty(), style = MaterialTheme.typography.headlineMedium)
        Text(state.profile?.endpoint?.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Text(
            "Phone pairing is separate from the watch. " +
                "Audio and the phone connection stop when you leave this screen or lock the phone."
        )
        Text("Phone: " + state.connection.status.name.lowercase())
        if (state.connection.status == ConnectionStatus.OFFLINE ||
            state.connection.status == ConnectionStatus.ERROR
        ) {
            Button(connect, enabled = state.profile?.endpoint?.isTls == true) {
                Text("Connect this phone")
            }
            if (state.profile?.endpoint?.isTls !=
                true
            ) {
                Text("Phone conversations require a wss endpoint.")
            }
        }
        if (state.connection.pairingCode.isNotBlank()) {
            Text("Approve this phone on the selected box:")
            Text(state.connection.pairingCommand)
            Text("Pairing code: " + state.connection.pairingCode)
        }
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.connection.error.isNotBlank()) {
            Text(
                state.connection.error,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (conversation.status.isNotBlank()) Text(conversation.status)
        if (conversation.notice.isNotBlank()) Text(conversation.notice)
        if (conversation.transcript.isNotBlank()) {
            Text("You said", style = MaterialTheme.typography.labelLarge)
            Text(conversation.transcript)
        }
        if (conversation.reply.isNotBlank()) {
            Text("Response", style = MaterialTheme.typography.labelLarge)
            Text(conversation.reply)
        }
        conversation.card?.let {
            Text(it.title, style = MaterialTheme.typography.titleMedium)
            Text(it.body)
        }
        conversation.picture?.let { picture ->
            val image = androidx.compose.runtime.remember(picture) {
                android.graphics.Bitmap.createBitmap(
                    picture.argb(),
                    picture.width,
                    picture.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
            }
            androidx.compose.foundation.Image(
                image.asImageBitmap(),
                "Profile response image",
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
            )
        }
        conversation.prompt?.let { prompt ->
            Text(prompt.title, style = MaterialTheme.typography.titleMedium)
            Text(prompt.text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({
                    model.answer(prompt.id, true)
                }, enabled = conversation.canAnswer) { Text("Approve") }
                Button({
                    model.answer(prompt.id, false)
                }, enabled = conversation.canAnswer) { Text("Decline") }
            }
        }
        OutlinedTextField(
            state.draft,
            model::draft,
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !listening,
            minLines = 2,
            maxLines = 6
        )
        Button(
            model::send,
            enabled =
            paired && !conversation.busy && !state.sending && state.draft.isNotBlank()
        ) {
            Text(if (state.sending) "Sending…" else "Send message")
        }
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
            "Drag down to cancel. Screen readers can tap Start/Finish recording.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.speaking) TextButton(model::stopPlayback) { Text("Stop voice") }
            TextButton({ model.cancel() }, enabled = paired) { Text("Cancel turn") }
            TextButton({
                model.cancel(true)
            }, enabled = paired && !listening) { Text("New session") }
        }
    }
}
