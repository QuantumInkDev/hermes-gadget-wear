package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.timeTextCurvedText
import dev.quantumink.hermesgadget.client.CuePreferences
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.TransportPath
import dev.quantumink.hermesgadget.protocol.TransportPreference
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun WaitingScreen() {
    RoundPage { item { Heading(stringResource(R.string.preparing)) } }
}

@Composable
fun WatchScreen(
    service: GadgetService,
    connect: () -> Unit,
    startRecording: () -> Unit,
    permissions: () -> Unit = {},
    navigation: WatchNavigation = remember { WatchNavigation() }
) {
    val watch by service.state.collectAsStateWithLifecycle()
    var route by navigation.route
    var editing by navigation.editing
    var newProfile by navigation.newProfile
    var imported by navigation.imported
    var appearanceReturn by navigation.appearanceReturn
    val context = LocalContext.current
    fun openAppearance() {
        appearanceReturn = route
        route = "appearance"
    }
    androidx.activity.compose.BackHandler(route == "appearance") { route = appearanceReturn }
    fun selectActivePage() {
        val current = service.state.value
        val page = ProfilePages.merge(current.profiles, current.catalog).firstOrNull {
            it.approved?.endpoint?.url == current.profile?.endpoint?.url
        }
        navigation.page.value = page?.key.orEmpty()
        if (page != null) service.rememberPage(page.key)
    }
    val textDraft = watch.draft
    val editingText = route == "text"
    DisposableEffect(editingText) { onDispose { if (editingText) service.finishTextInput() } }
    val feedback = LocalHapticFeedback.current
    var haptic by remember { mutableLongStateOf(watch.haptic) }
    LaunchedEffect(watch.haptic) {
        if (watch.haptic != haptic) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        haptic = watch.haptic
    }
    when {
        watch.loading -> WaitingScreen()
        route == "appearance" -> WatchAppearancePage(context) { route = appearanceReturn }
        route == "reset" -> RoundPage {
            item { Heading(stringResource(R.string.reset_setup)) }
            item { Body(stringResource(R.string.reset_warning)) }
            item {
                Primary(stringResource(R.string.reset_setup), enabled = !watch.saving) {
                    service.reset()
                    navigation.clearSetup()
                    navigation.clearPages()
                    route =
                        "home"
                }
            }
            item { Secondary(stringResource(R.string.back), onClick = { route = "setup" }) }
        }
        route == "profiles" -> RoundPage {
            item { Heading(stringResource(R.string.profiles_title)) }
            watch.profiles.forEach { profile ->
                item {
                    Primary(profile.label, enabled = !watch.saving) {
                        service.selectProfile(profile.endpoint.url) {
                            selectActivePage()
                            route = "home"
                            connect()
                        }
                    }
                }
            }
            item {
                Secondary(
                    stringResource(R.string.add_profile),
                    enabled = !watch.saving,
                    onClick = {
                        navigation.clearSetup()
                        editing = null
                        newProfile = true
                        imported = false
                        route = "setup"
                    }
                )
            }
            item {
                Secondary(
                    stringResource(R.string.import_profiles),
                    enabled = !watch.importing,
                    onClick = {
                        service.importProfiles()
                        route = "imports"
                    }
                )
            }
            if (watch.profile != null) {
                item {
                    Secondary(
                        stringResource(R.string.remove_profile),
                        onClick = { route = "remove" },
                        enabled = !watch.saving
                    )
                }
                item {
                    Secondary(stringResource(R.string.back), onClick = {
                        route = "home"
                        connect()
                    })
                }
            }
            if (watch.setupError.isNotEmpty()) item { Body(watch.setupError) }
        }
        route == "imports" -> RoundPage {
            item { Heading(stringResource(R.string.import_profiles)) }
            item { Body(stringResource(R.string.import_review)) }
            if (watch.importing) item { Body(stringResource(R.string.preparing)) }
            watch.imports.forEach { profile ->
                item {
                    Secondary(profile.label, onClick = {
                        navigation.clearSetup()
                        editing = profile
                        newProfile = false
                        imported = true
                        route = "setup"
                    })
                }
            }
            if (!watch.importing && watch.imports.isEmpty()) {
                item { Body(stringResource(R.string.no_imports)) }
            }
            if (watch.setupError.isNotEmpty()) item { Body(watch.setupError) }
            item { Secondary(stringResource(R.string.back), onClick = { route = "profiles" }) }
        }
        route == "remove" -> RoundPage {
            item { Heading(stringResource(R.string.remove_profile)) }
            item { Body(stringResource(R.string.remove_warning)) }
            item {
                Primary(stringResource(R.string.remove_profile), enabled = !watch.saving) {
                    watch.profile?.let { profile ->
                        service.removeProfile(profile.endpoint.url) {
                            selectActivePage()
                            route = "home"
                            connect()
                        }
                    }
                }
            }
            item { Secondary(stringResource(R.string.back), onClick = { route = "profiles" }) }
        }
        (watch.profile == null && watch.catalog.isEmpty()) || route == "setup" -> SetupPage(
            watch.copy(profile = if (newProfile) null else editing ?: watch.profile),
            draft = navigation.setupFor(
                if (newProfile) null else editing ?: watch.profile,
                imported
            ),
            onSave = { url, label, token, local, accepted, transport ->
                service.save(url, label, token, local, accepted, transport) {
                    route = "home"
                    navigation.clearSetup()
                    selectActivePage()
                    editing = null
                    newProfile = false
                    imported = false
                    connect()
                }
            },
            onBack = { route = "home" },
            onReset = { route = "reset" },
            permissions = permissions,
            profiles = { route = "profiles" },
            appearance = ::openAppearance
        )
        route == "text" -> TextPage(
            text = textDraft,
            changed = service::updateDraft,
            connection = watch.connection,
            connect = connect,
            send = { value, completed ->
                service.text(value) { accepted ->
                    if (accepted) {
                        if (service.state.value.draft == value) service.updateDraft("")
                        route = "home"
                    }
                    completed(accepted)
                }
            },
            back = { route = "home" }
        )
        else -> ProfileCarousel(
            watch,
            navigation,
            service,
            connect,
            review = { page ->
                service.disconnect()
                navigation.clearSetup()
                editing = page.approved ?: page.discovered?.profile?.gadgetUrl?.let { url ->
                    EndpointProfile(
                        dev.quantumink.hermesgadget.protocol.Endpoint.parse(url),
                        page.label,
                        ""
                    )
                }
                newProfile = editing == null
                imported = false
                route = "setup"
            }
        ) { page, pageScroll, _ ->
            key(page.key, watch.connection.conversation.prompt?.id) {
                ConversationPage(
                    watch,
                    service,
                    connect,
                    startRecording,
                    settings = {
                        service.disconnect()
                        navigation.clearSetup()
                        editing = null
                        newProfile = false
                        imported = false
                        route = "setup"
                    },
                    type = { if (service.prepareTextInput()) route = "text" },
                    profiles = {
                        service.disconnect()
                        route = "profiles"
                    },
                    appearance = ::openAppearance,
                    page = page,
                    pageScroll = pageScroll
                )
            }
        }
    }
}

@Composable
private fun SetupPage(
    watch: WatchState,
    onSave: (String, String, String, Boolean, Boolean, TransportPreference) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    permissions: () -> Unit,
    profiles: () -> Unit,
    appearance: () -> Unit,
    draft: SetupDraft
) {
    val profile = watch.profile
    val cleartext = draft.url.trim().startsWith("ws:", ignoreCase = true)
    val context = LocalContext.current
    val soundCues by CuePreferences.enabled.collectAsStateWithLifecycle()
    RoundPage {
        item { Heading(stringResource(R.string.setup_title)) }
        item { Body(stringResource(R.string.setup_hint), muted = true) }
        item {
            Input(stringResource(R.string.endpoint_name), draft.label, {
                draft.label =
                    it.take(32)
            })
        }
        item {
            Input(stringResource(R.string.endpoint_url), draft.url, {
                if (it != draft.url) draft.accepted = false
                draft.url = it.take(2048)
            }, KeyboardType.Uri)
        }
        item {
            Toggle(stringResource(R.string.local_network), draft.local) {
                draft.local = !draft.local
                draft.accepted =
                    false
            }
        }
        if (cleartext) {
            item { Body(stringResource(R.string.cleartext_warning)) }
            item {
                Toggle(stringResource(R.string.cleartext_accept), draft.accepted) {
                    draft.accepted =
                        !draft.accepted
                }
            }
        }
        if (draft.showToken) {
            item {
                Input(stringResource(R.string.access_token), draft.token, {
                    draft.token = it.take(2048)
                }, KeyboardType.Password)
            }
        } else {
            item {
                Secondary(stringResource(R.string.access_token), onClick = {
                    draft.showToken = true
                })
            }
        }
        item {
            Secondary(
                stringResource(
                    when (draft.transport) {
                        TransportPreference.AUTO -> R.string.transport_auto
                        TransportPreference.RELAY -> R.string.transport_phone
                        TransportPreference.DIRECT -> R.string.transport_direct
                    }
                ),
                onClick = {
                    draft.transport =
                        TransportPreference.entries[
                            (draft.transport.ordinal + 1) %
                                TransportPreference.entries.size
                        ]
                }
            )
        }
        if (draft.transport == TransportPreference.RELAY &&
            cleartext
        ) {
            item { Body(stringResource(R.string.relay_tls_required)) }
        }
        if (watch.setupError.isNotEmpty()) item { Body(watch.setupError) }
        item {
            Primary(
                stringResource(R.string.save_connect),
                enabled =
                !watch.saving && draft.url.isNotBlank() && draft.label.isNotBlank() &&
                    (!cleartext || (draft.local && draft.accepted)) &&
                    !(draft.transport == TransportPreference.RELAY && cleartext)
            ) {
                onSave(
                    draft.url,
                    draft.label,
                    draft.token,
                    draft.local,
                    draft.accepted && cleartext,
                    draft.transport
                )
            }
        }
        item { Secondary(stringResource(R.string.profiles_title), profiles) }
        item { Secondary(stringResource(R.string.permissions), permissions) }
        item { Secondary("Appearance", appearance) }
        item {
            Toggle("Recording sound cues", soundCues) { CuePreferences.set(context, !soundCues) }
        }
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
internal fun ConversationPage(
    watch: WatchState,
    service: GadgetService,
    connect: () -> Unit,
    startRecording: () -> Unit,
    settings: () -> Unit,
    type: () -> Unit,
    profiles: () -> Unit,
    appearance: () -> Unit,
    page: ProfilePage,
    pageScroll: TransformingLazyColumnState
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
    val promptScroll = remember(prompt?.id) { TransformingLazyColumnState() }
    RoundPage(
        topPadding = if (prompt == null) 28.dp else 40.dp,
        state = if (prompt == null) pageScroll else promptScroll
    ) {
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
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ProfileIdentity(
                        page,
                        watch.artwork[page.key],
                        watch,
                        true,
                        modifier = Modifier.recordingPortrait(
                            paired &&
                                page.status ==
                                dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE &&
                                (
                                    !conversation.busy ||
                                        conversation.mode == ConversationMode.LISTENING
                                    ),
                            conversation.mode == ConversationMode.LISTENING,
                            startRecording,
                            service::finishRecording,
                            service::cancelRecording
                        ),
                        recordingHint = if (paired &&
                            page.status ==
                            dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE
                        ) {
                            if (conversation.mode ==
                                ConversationMode.LISTENING
                            ) {
                                stringResource(R.string.release_send)
                            } else if (!conversation.busy) {
                                stringResource(R.string.hold_talk)
                            } else {
                                ""
                            }
                        } else {
                            ""
                        }
                    )
                    Text(
                        when {
                            watch.screenNotice.isNotEmpty() -> "Check conversation"
                            paired && conversation.mode == ConversationMode.LISTENING -> {
                                stringResource(R.string.release_send)
                            }
                            paired && !conversation.busy -> stringResource(R.string.hold_talk)
                            else -> stringResource(title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (watch.screenNotice.isNotEmpty()) item { Body(watch.screenNotice) }
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
            item {
                Secondary("Refresh profiles", {
                    service.refreshCatalog()
                }, enabled = !watch.catalogRefreshing)
            }
            if (watch.catalogNotice.isNotBlank()) item { Body(watch.catalogNotice, muted = true) }
            item { Secondary(stringResource(R.string.profiles_title), profiles) }
            item { Secondary(stringResource(R.string.settings), settings) }
            item {
                Secondary(
                    "Appearance",
                    appearance,
                    enabled = !watch.saving && conversation.prompt == null &&
                        conversation.mode != ConversationMode.LISTENING
                )
            }
        }
    }
}

@Composable
internal fun RoundPage(
    modifier: Modifier = Modifier,
    topPadding: Dp = 40.dp,
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    content: TransformingLazyColumnScope.() -> Unit
) {
    AppScaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        timeText = {
            val style = TimeTextDefaults.timeTextStyle(
                color = MaterialTheme.colorScheme.onBackground
            )
            val density = androidx.compose.ui.platform.LocalDensity.current
            val first = state.layoutInfo.visibleItems.firstOrNull { it.index == 0 }
            // AndroidX restores TimeText at idle even at the end of the list. Only the
            // original top placement reserves its region; never paint over scrolled actions.
            if (first != null && first.offset >= with(density) { topPadding.roundToPx() }) {
                TimeText(modifier = Modifier.testTag("page-clock")) { time ->
                    timeTextCurvedText(time, style)
                }
            }
        }
    ) {
        ScreenScaffold(
            state,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 36.dp,
                top = topPadding,
                end = 36.dp,
                bottom = 40.dp
            )
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
internal fun Heading(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun Body(value: String, muted: Boolean = false) {
    Text(
        value,
        color = if (muted) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun Primary(value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth(), enabled = enabled) {
        Text(value, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Secondary(value: String, onClick: () -> Unit, enabled: Boolean = true) {
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
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
        BasicTextField(
            value, changed,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(
                    RoundedCornerShape(16.dp)
                ).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)
                .semantics { contentDescription = label },
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
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
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun CancelControl(cancel: (Boolean) -> Unit) {
    val label = stringResource(R.string.cancel)
    val newSession = stringResource(R.string.new_session)
    val current by rememberUpdatedState(cancel)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
