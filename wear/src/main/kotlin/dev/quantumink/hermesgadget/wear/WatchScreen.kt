package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.TransportPath
import dev.quantumink.hermesgadget.protocol.TransportPreference
import kotlinx.coroutines.withTimeoutOrNull

private val Accent = Color(0xFFB6ED93)
private val Muted = Color(0xFFB3B8B0)

@Composable
fun WaitingScreen() {
    RoundPage { item { Heading(stringResource(R.string.preparing)) } }
}

@Composable
fun WatchScreen(
    service: GadgetService,
    connect: () -> Unit,
    startRecording: () -> Unit,
    permissions: () -> Unit = {}
) {
    val watch by service.state.collectAsStateWithLifecycle()
    var route by remember { mutableStateOf("home") }
    var textDraft by remember(watch.profile?.endpoint?.url) { mutableStateOf("") }
    val feedback = LocalHapticFeedback.current
    var haptic by remember { mutableLongStateOf(watch.haptic) }
    LaunchedEffect(watch.loading, watch.profile?.endpoint?.url) {
        if (!watch.loading && watch.profile != null &&
            watch.connection.status == ConnectionStatus.OFFLINE
        ) {
            connect()
        }
    }
    LaunchedEffect(watch.haptic) {
        if (watch.haptic != haptic) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        haptic = watch.haptic
    }
    when {
        watch.loading -> WaitingScreen()
        route == "reset" -> RoundPage {
            item { Heading(stringResource(R.string.reset_setup)) }
            item { Body(stringResource(R.string.reset_warning)) }
            item {
                Primary(stringResource(R.string.reset_setup), enabled = !watch.saving) {
                    service.reset()
                    route =
                        "home"
                }
            }
            item { Secondary(stringResource(R.string.back), onClick = { route = "setup" }) }
        }
        watch.profile == null || route == "setup" -> SetupPage(
            watch,
            onSave = { url, label, token, local, accepted, transport ->
                service.save(url, label, token, local, accepted, transport) {
                    route = "home"
                    connect()
                }
            },
            onBack = { route = "home" },
            onReset = { route = "reset" },
            permissions = permissions
        )
        route == "text" -> TextPage(
            text = textDraft,
            changed = { textDraft = it.take(4096) },
            connection = watch.connection,
            connect = connect,
            send = { value, completed ->
                service.text(value) { accepted ->
                    if (accepted) {
                        if (textDraft == value) textDraft = ""
                        route = "home"
                    }
                    completed(accepted)
                }
            },
            back = { route = "home" }
        )
        else -> key(watch.connection.conversation.prompt?.id) {
            ConversationPage(
                watch,
                service,
                connect,
                startRecording,
                settings = {
                    service.disconnect()
                    route = "setup"
                },
                type = { if (service.prepareTextInput()) route = "text" }
            )
        }
    }
}

@Composable
private fun SetupPage(
    watch: WatchState,
    onSave: (String, String, String, Boolean, Boolean, TransportPreference) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    permissions: () -> Unit
) {
    val profile = watch.profile
    var url by remember(profile) { mutableStateOf(profile?.endpoint?.url.orEmpty()) }
    var label by remember(profile) { mutableStateOf(profile?.label ?: "My Hermes") }
    var token by remember(profile) { mutableStateOf(profile?.accessToken.orEmpty()) }
    var local by remember(profile) { mutableStateOf(profile?.endpoint?.privateNetwork ?: false) }
    var accepted by remember(profile) {
        mutableStateOf(profile?.endpoint?.cleartextAccepted ?: false)
    }
    var showToken by remember { mutableStateOf(token.isNotEmpty()) }
    var transport by remember(profile) {
        mutableStateOf(profile?.transport ?: TransportPreference.AUTO)
    }
    val cleartext = url.trim().startsWith("ws:", ignoreCase = true)
    RoundPage {
        item { Heading(stringResource(R.string.setup_title)) }
        item { Body(stringResource(R.string.setup_hint), muted = true) }
        item { Input(stringResource(R.string.endpoint_name), label, { label = it.take(32) }) }
        item {
            Input(stringResource(R.string.endpoint_url), url, {
                if (it != url) accepted = false
                url = it.take(2048)
            }, KeyboardType.Uri)
        }
        item {
            Toggle(stringResource(R.string.local_network), local) {
                local = !local
                accepted =
                    false
            }
        }
        if (cleartext) {
            item { Body(stringResource(R.string.cleartext_warning)) }
            item {
                Toggle(stringResource(R.string.cleartext_accept), accepted) {
                    accepted =
                        !accepted
                }
            }
        }
        if (showToken) {
            item {
                Input(stringResource(R.string.access_token), token, {
                    token = it.take(2048)
                }, KeyboardType.Password)
            }
        } else {
            item {
                Secondary(stringResource(R.string.access_token), onClick = { showToken = true })
            }
        }
        item {
            Secondary(
                stringResource(
                    when (transport) {
                        TransportPreference.AUTO -> R.string.transport_auto
                        TransportPreference.RELAY -> R.string.transport_phone
                        TransportPreference.DIRECT -> R.string.transport_direct
                    }
                ),
                onClick = {
                    transport =
                        TransportPreference.entries[
                            (transport.ordinal + 1) %
                                TransportPreference.entries.size
                        ]
                }
            )
        }
        if (transport == TransportPreference.RELAY &&
            cleartext
        ) {
            item { Body(stringResource(R.string.relay_tls_required)) }
        }
        if (watch.setupError.isNotEmpty()) item { Body(watch.setupError) }
        item {
            Primary(
                stringResource(R.string.save_connect),
                enabled =
                !watch.saving && url.isNotBlank() && label.isNotBlank() &&
                    (!cleartext || (local && accepted)) &&
                    !(transport == TransportPreference.RELAY && cleartext)
            ) { onSave(url, label, token, local, accepted && cleartext, transport) }
        }
        item { Secondary(stringResource(R.string.permissions), permissions) }
        if (profile != null) item { Secondary(stringResource(R.string.back), onBack) }
        item { Secondary(stringResource(R.string.reset_setup), onReset) }
    }
}

@Composable
private fun TextPage(
    text: String,
    changed: (String) -> Unit,
    connection: dev.quantumink.hermesgadget.protocol.ConnectionState,
    connect: () -> Unit,
    send: (String, (Boolean) -> Unit) -> Unit,
    back: () -> Unit
) {
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val ready = connection.status == ConnectionStatus.PAIRED &&
        connection.conversation.prompt == null
    RoundPage {
        item { Heading(stringResource(R.string.type_message)) }
        item { Input(stringResource(R.string.message), text, changed, enabled = !pending) }
        if (!ready) {
            item {
                Body(
                    if (connection.error.isNotBlank()) {
                        connection.error
                    } else {
                        stringResource(
                            when {
                                connection.conversation.prompt != null -> R.string.message_approval
                                connection.status == ConnectionStatus.PAIRING -> R.string.pairing
                                connection.status == ConnectionStatus.CONNECTING -> {
                                    R.string.connecting
                                }
                                connection.status == ConnectionStatus.RETRYING -> {
                                    R.string.reconnecting
                                }
                                else -> R.string.message_reconnect
                            }
                        )
                    }
                )
            }
        } else if (failed) {
            item {
                Body(
                    connection.conversation.notice.ifBlank {
                        stringResource(R.string.message_not_sent)
                    }
                )
            }
        }
        if (connection.status in setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)) {
            item {
                Primary(stringResource(R.string.connect), enabled = !pending, onClick = connect)
            }
        }
        item {
            Primary(
                stringResource(if (pending) R.string.sending else R.string.send),
                enabled = ready && !pending && text.isNotBlank()
            ) {
                pending = true
                failed = false
                send(text) { accepted ->
                    pending = false
                    failed = !accepted
                }
            }
        }
        item { Secondary(stringResource(R.string.back), back, enabled = !pending) }
    }
}

@Composable
private fun ConversationPage(
    watch: WatchState,
    service: GadgetService,
    connect: () -> Unit,
    startRecording: () -> Unit,
    settings: () -> Unit,
    type: () -> Unit
) {
    val connection = watch.connection
    val conversation = connection.conversation
    val prompt = conversation.prompt
    val paired = connection.status == ConnectionStatus.PAIRED
    val title = when (connection.status) {
        ConnectionStatus.PAIRED -> when (conversation.mode) {
            ConversationMode.READY -> R.string.ready
            ConversationMode.LISTENING -> R.string.listening
            ConversationMode.THINKING -> R.string.thinking
            ConversationMode.REPLY -> R.string.reply
        }
        ConnectionStatus.PAIRING -> R.string.pairing
        ConnectionStatus.CONNECTING -> R.string.connecting
        ConnectionStatus.RETRYING -> R.string.reconnecting
        ConnectionStatus.OFFLINE -> R.string.offline
        ConnectionStatus.ERROR -> R.string.connection_error
    }
    val gesture = Modifier.voiceGesture(
        paired && prompt == null,
        startRecording,
        service::finishRecording,
        service::cancelRecording
    )
    RoundPage(gesture) {
        item { PetView(watch) }
        if (prompt != null) {
            item { Heading(stringResource(R.string.approval)) }
            item { Body(prompt.title) }
            item { Body(prompt.text) }
            if (!conversation.canAnswer) {
                item {
                    Body(stringResource(R.string.approval_guard), muted = true)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        { service.answer(prompt.id, true) },
                        Modifier.weight(1f),
                        enabled = conversation.canAnswer
                    ) { Text(stringResource(R.string.yes)) }
                    FilledTonalButton(
                        { service.answer(prompt.id, false) },
                        Modifier.weight(1f),
                        enabled = conversation.canAnswer
                    ) { Text(stringResource(R.string.no)) }
                }
            }
        } else {
            item {
                Text(
                    watch.profile?.label.orEmpty(),
                    color = Accent,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            item { Heading(stringResource(title)) }
            if (connection.status == ConnectionStatus.PAIRING) {
                item {
                    Heading(
                        connection.pairingCode.ifEmpty {
                            stringResource(R.string.pairing_wait)
                        }
                    )
                }
                item { Body(stringResource(R.string.pairing_hint), muted = true) }
                if (connection.pairingCommand.isNotEmpty()) {
                    item {
                        Body(connection.pairingCommand, muted = true)
                    }
                }
            } else if (paired) {
                item {
                    Body(
                        stringResource(
                            if (connection.clientSpeech) {
                                R.string.phone_speech
                            } else {
                                R.string.server_speech
                            }
                        ),
                        muted = true
                    )
                }
                if (conversation.mode in
                    setOf(ConversationMode.READY, ConversationMode.LISTENING)
                ) {
                    item {
                        val listening = conversation.mode == ConversationMode.LISTENING
                        val actionLabel = stringResource(
                            if (listening) {
                                R.string.finish_recording
                            } else {
                                R.string.start_recording
                            }
                        )
                        Box(
                            Modifier.fillMaxWidth().semantics {
                                role = Role.Button
                                contentDescription = actionLabel
                                onClick(actionLabel) {
                                    if (listening) service.finishRecording() else startRecording()
                                    true
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            VoiceMark(conversation.level, listening)
                        }
                    }
                    item {
                        Body(
                            stringResource(
                                if (conversation.mode ==
                                    ConversationMode.LISTENING
                                ) {
                                    R.string.release_send
                                } else {
                                    R.string.hold_talk
                                }
                            )
                        )
                    }
                }
                if (conversation.status.isNotEmpty()) {
                    item {
                        Body(conversation.status, muted = true)
                    }
                }
                if (conversation.transcript.isNotEmpty()) {
                    item {
                        Body(conversation.transcript, muted = true)
                    }
                }
                val card = conversation.card
                val picture = conversation.picture
                if (card != null) {
                    item { Heading(card.title) }
                    item { Body(card.body) }
                    item { Secondary(stringResource(R.string.dismiss), service::dismissDisplay) }
                } else if (picture != null) {
                    item {
                        val bitmap = remember(picture) {
                            Bitmap.createBitmap(
                                picture.argb(),
                                picture.width,
                                picture.height,
                                Bitmap.Config.ARGB_8888
                            ).asImageBitmap()
                        }
                        Image(bitmap, stringResource(R.string.agent_image), Modifier.fillMaxWidth())
                    }
                    item { Secondary(stringResource(R.string.dismiss), service::dismissDisplay) }
                } else if (conversation.reply.isNotEmpty()) {
                    item { Body(conversation.reply) }
                }
                item { Secondary(stringResource(R.string.type_message), type) }
                item { CancelControl(service::cancel) }
                item { Body(stringResource(R.string.new_session_hint), muted = true) }
            }
            if (connection.error.isNotEmpty()) item { Body(connection.error) }
            if (conversation.notice.isNotEmpty()) item { Body(conversation.notice) }
            item {
                Body(
                    stringResource(
                        if (watch.path == TransportPath.RELAY) {
                            R.string.phone_tls
                        } else if (watch.profile?.endpoint?.isTls ==
                            true
                        ) {
                            R.string.direct_tls
                        } else {
                            R.string.direct_private
                        }
                    ),
                    muted = true
                )
            }
            if (watch.transportNotice.isNotEmpty()) {
                item {
                    Body(watch.transportNotice, muted = true)
                }
            }
            if (connection.status in setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)) {
                item { Primary(stringResource(R.string.connect), onClick = connect) }
            } else {
                item { Secondary(stringResource(R.string.disconnect), service::disconnect) }
            }
            item { Secondary(stringResource(R.string.settings), settings) }
        }
    }
}

@Composable
private fun RoundPage(
    modifier: Modifier = Modifier,
    content: TransformingLazyColumnScope.() -> Unit
) {
    val state = rememberTransformingLazyColumnState()
    AppScaffold(containerColor = Color.Black) {
        ScreenScaffold(
            state,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 40.dp)
        ) { padding ->
            TransformingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun Heading(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Body(value: String, muted: Boolean = false) {
    Text(
        value,
        color = if (muted) Muted else Color(0xFFF0F3EB),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Primary(value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth(), enabled = enabled) {
        Text(value, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Secondary(value: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick, Modifier.fillMaxWidth(), enabled = enabled) {
        Text(value, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Toggle(value: String, checked: Boolean, onClick: () -> Unit) {
    Secondary((if (checked) "✓ " else "○ ") + value, onClick)
}

@Composable
private fun Input(
    label: String,
    value: String,
    changed: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true
) {
    val focus = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val singleLine = keyboard == KeyboardType.Uri || keyboard == KeyboardType.Password
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        BasicTextField(
            value, changed,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF20251F)).padding(12.dp)
                .semantics { contentDescription = label },
            textStyle = TextStyle(
                color = Color.White,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboardController?.hide()
                focus.clearFocus()
            }),
            singleLine = singleLine,
            visualTransformation = if (keyboard ==
                KeyboardType.Password
            ) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            minLines = 1, maxLines = if (singleLine) 1 else 4,
            cursorBrush = SolidColor(Accent)
        )
    }
}

@Composable
private fun VoiceMark(level: Float, listening: Boolean) {
    Canvas(Modifier.size(64.dp)) {
        drawCircle(Accent.copy(alpha = 0.18f), style = Stroke(3.dp.toPx()))
        drawCircle(
            Accent,
            radius =
            size.minDimension * (0.15f + if (listening) level * 0.24f else 0f)
        )
    }
}

private fun Modifier.voiceGesture(
    enabled: Boolean,
    start: () -> Unit,
    finish: () -> Unit,
    cancel: () -> Unit
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val distance = 40.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        start()
        var completed = false
        var discarded = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == held.id } ?: break
                if (!change.pressed) {
                    if (!discarded) finish()
                    completed = true
                    break
                }
                if (!discarded && change.position.y - down.position.y > distance) {
                    cancel()
                    discarded = true
                }
                change.consume()
            }
        } finally {
            if (!completed) cancel()
        }
    }
}

@Composable
private fun CancelControl(cancel: (Boolean) -> Unit) {
    val label = stringResource(R.string.cancel)
    val newSession = stringResource(R.string.new_session)
    val current by rememberUpdatedState(cancel)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF20251F))
            .semantics {
                role = Role.Button
                onClick(label) {
                    current(false)
                    true
                }
                customActions =
                    listOf(
                        CustomAccessibilityAction(newSession) {
                            current(true)
                            true
                        }
                    )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val released = withTimeoutOrNull(2000) { waitForUpOrCancellation() != null }
                    if (released == true) current(false)
                    if (released == null) {
                        current(true)
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(label) }
}
