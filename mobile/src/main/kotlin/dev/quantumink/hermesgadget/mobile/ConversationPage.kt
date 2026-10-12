package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.delay

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
    LaunchedEffect(state.visible, state.speaking) {
        model.refreshMediaVolume()
        while (state.visible && state.speaking) {
            delay(500)
            model.refreshMediaVolume()
        }
    }
    ConversationContent(
        state, page, connect, record, back, model::draft, model::send,
        model::finishRecording, model::cancelRecording, model::stopPlayback,
        { model.cancel() }, { model.cancel(true) }, model::answer, modifier
    )
}

@Composable
internal fun ConversationContent(
    state: PhoneConversationState,
    page: dev.quantumink.hermesgadget.protocol.CatalogPage?,
    connect: () -> Unit,
    record: () -> Unit,
    back: () -> Unit,
    draft: (String) -> Unit,
    send: () -> Unit,
    finish: () -> Unit,
    cancelRecording: () -> Unit,
    stopVoice: () -> Unit,
    cancelTurn: () -> Unit,
    newSession: () -> Unit,
    answer: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val conversation = state.connection.conversation
    val listening = conversation.mode == ConversationMode.LISTENING
    val paired = state.connection.status == ConnectionStatus.PAIRED
    var details by remember(state.profile?.endpoint?.url) { mutableStateOf(false) }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(back) {
                Icon(painterResource(R.drawable.ic_back), "Back to profiles")
            }
            Text(
                "Conversation",
                Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton({ details = true }) {
                Icon(painterResource(R.drawable.ic_more), "Connection details")
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val controlsHeight = if (keyboard) maxHeight else maxHeight * .65f
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        page?.let {
                            DirectoryPortrait(
                                CatalogRepository.get(
                                    androidx.compose.ui.platform.LocalContext.current
                                ),
                                it
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                state.profile?.label.orEmpty(),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                when {
                                    listening -> "Listening"
                                    state.speaking -> "Speaking"
                                    conversation.busy -> "Working"
                                    paired -> "Ready to talk"
                                    else -> state.connection.status.name.lowercase()
                                        .replaceFirstChar {
                                            it.uppercase()
                                        }
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (state.connection.status == ConnectionStatus.OFFLINE ||
                        state.connection.status == ConnectionStatus.ERROR
                    ) {
                        Text(
                            "Connect to start a private phone conversation.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button({
                            details = true
                        }, enabled = state.profile?.endpoint?.isTls == true) {
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
                    if (state.error.isNotBlank()) {
                        Text(
                            state.error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                                answer(prompt.id, true)
                            }, enabled = conversation.canAnswer) { Text("Approve") }
                            Button({
                                answer(prompt.id, false)
                            }, enabled = conversation.canAnswer) { Text("Decline") }
                        }
                    }

                    if (paired && conversation.reply.isBlank() &&
                        conversation.transcript.isBlank() &&
                        !conversation.busy
                    ) {
                        Text(
                            "What would you like to work on?",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            "Hold to talk, or write a message below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ConversationControls(
                        state, draft, send, record, finish, cancelRecording,
                        stopVoice, cancelTurn, keyboard,
                        Modifier.heightIn(
                            max = controlsHeight
                        ).verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Phone connection") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.profile?.endpoint?.url.orEmpty())
                    Text(
                        "Phone pairing is separate from the watch. " +
                            "Audio and the phone connection stop when you leave this screen " +
                            "or lock the phone."
                    )
                    Text("Replies use this profile’s configured server voice.")
                    TextButton({
                        newSession()
                        details = false
                    }, enabled = paired && !listening) { Text("New session") }
                }
            },
            confirmButton = {
                if (state.connection.status in
                    setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)
                ) {
                    Button(
                        {
                            details = false
                            connect()
                        },
                        enabled =
                        state.profile?.endpoint?.isTls == true
                    ) {
                        Text("Connect this phone")
                    }
                }
            },
            dismissButton = { TextButton({ details = false }) { Text("Close") } }
        )
    }
}
