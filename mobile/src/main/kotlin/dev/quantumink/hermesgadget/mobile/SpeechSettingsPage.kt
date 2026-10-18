package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint

internal class SpeechSettingsTarget(val page: CatalogPage, val endpoint: Endpoint) {
    override fun toString(): String = "SpeechSettingsTarget(redacted)"
}

@Composable
fun SpeechSettingsPage(context: Context, back: () -> Unit) {
    SpeechSettingsPage(context, target = null, back = back)
}

@Composable
internal fun SpeechSettingsPage(
    context: Context,
    target: SpeechSettingsTarget?,
    available: Boolean = true,
    permitted: () -> Boolean = { true },
    providedSession: SpeechSettingsSession? = null,
    back: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestPermitted by rememberUpdatedState(permitted)
    val latestAvailable by rememberUpdatedState(available)
    val session = remember(context, scope, providedSession) {
        providedSession ?: SpeechSettingsSession(
            scope,
            { TtsVault(context).read(it) },
            { id, settings -> TtsVault(context).save(id, settings) },
            { PhoneSpeech.publish(context, TtsVault(context)) },
            { key -> ElevenLabsApi().use { it.quota(key).remaining } },
            { latestAvailable && latestPermitted() }
        )
    }
    var url by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf<String?>(null) }
    var invalidUrl by remember { mutableStateOf(false) }
    val endpoint = target?.let { ClientSpeech.endpointId(it.endpoint) } ?: manualId
    val state by session.state.collectAsStateWithLifecycle()
    var sharedKey by remember(endpoint, state.endpoint, state.sharedVersion) { mutableStateOf("") }
    var sharedVoice by remember(endpoint, state.endpoint, state.sharedVersion) {
        mutableStateOf(state.sharedVoice)
    }
    var profileKey by remember(endpoint, state.endpoint, state.profileVersion) {
        mutableStateOf("")
    }
    var voice by remember(endpoint, state.endpoint, state.profileVersion) {
        mutableStateOf(state.voice)
    }
    var mode by remember(endpoint, state.endpoint, state.profileVersion) {
        mutableStateOf(state.mode)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(session, owner, endpoint, available) {
        fun resume() {
            if (available && latestPermitted()) session.open(endpoint)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) resume()
            if (event == Lifecycle.Event.ON_STOP) {
                session.close()
                sharedKey = ""
                profileKey = ""
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) resume()
        onDispose {
            session.close()
            sharedKey = ""
            profileKey = ""
            owner.lifecycle.removeObserver(observer)
        }
    }
    fun leave() {
        session.close()
        back()
    }
    if (!available) {
        Text(stringResource(R.string.speech_unavailable))
        TextButton(::leave) { Text(stringResource(R.string.speech_back)) }
        return
    }
    val enabled = state.loaded && state.endpoint == endpoint && !state.busy && permitted()
    val profileEnabled = enabled && endpoint != null
    val labels = listOf(
        stringResource(R.string.speech_mode_server),
        stringResource(R.string.speech_mode_shared),
        stringResource(R.string.speech_mode_profile)
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.speech_title), style = MaterialTheme.typography.headlineSmall)
        target?.let {
            Text(it.page.profile.name, style = MaterialTheme.typography.titleLarge)
            Text(it.page.sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.speech_profile_id, it.page.profile.id))
        }
        Text(stringResource(R.string.speech_disclosure))
        TextButton(::leave) { Text(stringResource(R.string.speech_back)) }
        if (state.busy) Text(stringResource(R.string.speech_working))
        if (invalidUrl) Text(stringResource(R.string.speech_invalid_url))
        SpeechStatus(state)
        SettingsSection {
            Text(
                stringResource(R.string.speech_profile_title),
                style = MaterialTheme.typography.titleMedium
            )
            if (target == null) {
                Text(stringResource(R.string.speech_manual_help))
                SpeechInput(R.string.speech_endpoint, url, {
                    url = it.take(2048)
                    manualId = null
                    invalidUrl = false
                    session.close()
                    session.open(null)
                }, uri = true)
                TextButton({
                    val parsed =
                        runCatching { Endpoint.parse(url.trim()).also { require(it.isTls) } }
                    invalidUrl = parsed.isFailure
                    parsed.getOrNull()?.let { manualId = ClientSpeech.endpointId(it) }
                }, enabled = url.isNotBlank() && !state.busy) {
                    Text(stringResource(R.string.speech_load))
                }
            }
            if (endpoint != null && state.loaded) {
                Text(stringResource(R.string.speech_current_mode, labels[state.mode.ordinal]))
                Text(
                    stringResource(
                        if (state.mode ==
                            SpeechMode.SERVER
                        ) {
                            R.string.speech_server_ready
                        } else if (state.ready) {
                            R.string.speech_client_ready
                        } else {
                            R.string.speech_client_missing
                        }
                    )
                )
            }
            SettingsChoice(
                stringResource(R.string.speech_source),
                labels[mode.ordinal],
                labels,
                profileEnabled
            ) {
                mode = SpeechMode.entries[it]
            }
            SpeechInput(R.string.speech_profile_voice, voice, {
                voice = it.take(128)
            }, enabled = profileEnabled)
            if (mode == SpeechMode.PROFILE) {
                SpeechInput(
                    R.string.speech_profile_key,
                    profileKey,
                    {
                        profileKey = it.take(256)
                    },
                    secret = true,
                    enabled = profileEnabled
                )
            }
            Button({ session.saveProfile(mode, profileKey, voice) }, enabled = profileEnabled) {
                Text(stringResource(R.string.speech_save_profile))
            }
            TextButton({ session.checkQuota(false) }, enabled = profileEnabled) {
                Text(stringResource(R.string.speech_test_profile))
            }
            TextButton({ session.deleteProfile() }, enabled = profileEnabled) {
                Text(stringResource(R.string.speech_delete_profile))
            }
        }
        SettingsSection {
            Text(
                stringResource(R.string.speech_shared_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(
                    if (state.sharedReady) {
                        R.string.speech_shared_ready
                    } else {
                        R.string.speech_shared_missing
                    }
                )
            )
            SpeechInput(R.string.speech_shared_key, sharedKey, {
                sharedKey = it.take(256)
            }, secret = true, enabled = enabled)
            SpeechInput(R.string.speech_shared_voice, sharedVoice, {
                sharedVoice = it.take(128)
            }, enabled = enabled)
            Button({ session.saveShared(sharedKey, sharedVoice) }, enabled = enabled) {
                Text(stringResource(R.string.speech_save_shared))
            }
            TextButton({ session.checkQuota(true, sharedKey) }, enabled = enabled) {
                Text(stringResource(R.string.speech_test_shared))
            }
            TextButton({ session.deleteShared() }, enabled = enabled) {
                Text(stringResource(R.string.speech_delete_shared))
            }
        }
        TextButton(::leave) { Text(stringResource(R.string.speech_back)) }
    }
}

@Composable
private fun SpeechStatus(state: SpeechSettingsState) {
    val resource = when (state.notice) {
        SpeechNotice.NONE -> return
        SpeechNotice.LOADED -> R.string.speech_loaded
        SpeechNotice.SAVED -> R.string.speech_saved
        SpeechNotice.DELETED -> R.string.speech_deleted
        SpeechNotice.SYNC_PENDING -> R.string.speech_sync_pending
        SpeechNotice.FAILED -> R.string.speech_operation_failed
        SpeechNotice.MISSING_KEY -> R.string.speech_key_missing
        SpeechNotice.QUOTA -> if (state.remaining ==
            null
        ) {
            R.string.speech_quota_unknown
        } else {
            R.string.speech_quota
        }
    }
    Text(
        if (state.notice == SpeechNotice.QUOTA &&
            state.remaining != null
        ) {
            stringResource(resource, state.remaining)
        } else {
            stringResource(resource)
        }
    )
}

@Composable
private fun SpeechInput(
    label: Int,
    value: String,
    changed: (String) -> Unit,
    secret: Boolean = false,
    uri: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value,
        changed,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) {
                KeyboardType.Password
            } else if (uri) {
                KeyboardType.Uri
            } else {
                KeyboardType.Text
            }
        ),
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        }
    )
}
