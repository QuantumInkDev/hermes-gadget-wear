package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SpeechSettingsPage(context: Context, back: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var sharedKey by remember { mutableStateOf("") }
    var sharedVoice by remember { mutableStateOf("") }
    var profileKey by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SpeechMode.SERVER) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { runCatching { TtsVault(context).read("shared") } }
        saved.fold({ sharedVoice = it.voice }, {
            status =
                context.getString(R.string.speech_operation_failed)
        })
        busy = false
    }
    fun endpointId(): String {
        val endpoint = Endpoint.parse(url.trim())
        require(endpoint.isTls)
        return ClientSpeech.endpointId(endpoint)
    }
    fun work(clear: () -> Unit = {}, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            status = result.getOrElse { context.getString(R.string.speech_operation_failed) }
            if (result.isSuccess) clear()
            busy = false
        }
    }
    fun quota(key: String): String {
        if (key.isEmpty()) return context.getString(R.string.speech_key_missing)
        return ElevenLabsApi().use { api ->
            try {
                val remaining = api.quota(key).remaining
                if (remaining ==
                    null
                ) {
                    context.getString(R.string.speech_quota_unknown)
                } else {
                    context.getString(R.string.speech_quota, remaining)
                }
            } catch (failure: SpeechFailure) {
                failure.message.orEmpty()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.speech_title))
        Text(stringResource(R.string.speech_disclosure))
        SpeechInput(R.string.speech_shared_key, sharedKey, {
            sharedKey = it.take(256)
        }, secret = true, enabled = !busy)
        SpeechInput(R.string.speech_shared_voice, sharedVoice, {
            sharedVoice = it.take(128)
        }, enabled = !busy)
        Button({
            work({ sharedKey = "" }) {
                val vault = TtsVault(context)
                val old = vault.read("shared")
                vault.save(
                    "shared",
                    SpeechSettings(
                        SpeechMode.SHARED,
                        sharedKey.trim().ifEmpty {
                            old.key
                        },
                        sharedVoice.trim()
                    )
                )
                PhoneSpeech.publish(context, vault)
                context.getString(R.string.speech_saved)
            }
        }, enabled = !busy) { Text(stringResource(R.string.speech_save_shared)) }
        Button({
            work { quota(sharedKey.trim().ifEmpty { TtsVault(context).read("shared").key }) }
        }, enabled = !busy) { Text(stringResource(R.string.speech_test_shared)) }
        Button({
            work {
                val vault = TtsVault(context)
                vault.save(
                    "shared",
                    SpeechSettings(SpeechMode.SHARED, "", vault.read("shared").voice)
                )
                PhoneSpeech.publish(context, vault)
                context.getString(R.string.speech_deleted)
            }
        }, enabled = !busy) { Text(stringResource(R.string.speech_delete_shared)) }
        SpeechInput(R.string.speech_endpoint, url, {
            url = it.take(2048)
        }, uri = true, enabled = !busy)
        Button({
            if (!busy) {
                mode = SpeechMode.entries[(mode.ordinal + 1) % SpeechMode.entries.size]
            }
        }, enabled = !busy) {
            Text(
                stringResource(
                    when (mode) {
                        SpeechMode.SERVER -> R.string.speech_mode_server
                        SpeechMode.SHARED -> R.string.speech_mode_shared
                        SpeechMode.PROFILE -> R.string.speech_mode_profile
                    }
                )
            )
        }
        SpeechInput(R.string.speech_profile_voice, voice, { voice = it.take(128) }, enabled = !busy)
        if (mode ==
            SpeechMode.PROFILE
        ) {
            SpeechInput(R.string.speech_profile_key, profileKey, {
                profileKey =
                    it.take(256)
            }, secret = true, enabled = !busy)
        }
        Button({
            if (!busy) {
                busy = true
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching { TtsVault(context).read(endpointId()) }
                        }
                    result.fold({
                        mode = it.mode
                        voice = it.voice
                        profileKey = ""
                        status =
                            context.getString(R.string.speech_loaded)
                    }, {
                        status =
                            context.getString(R.string.speech_operation_failed)
                    })
                    busy = false
                }
            }
        }, enabled = !busy && url.isNotBlank()) { Text(stringResource(R.string.speech_load)) }
        Button({
            work({ profileKey = "" }) {
                val id = endpointId()
                val vault = TtsVault(context)
                val old = vault.read(id)
                vault.save(
                    id,
                    SpeechSettings(
                        mode,
                        profileKey.trim().ifEmpty {
                            old.key
                        },
                        voice.trim()
                    )
                )
                PhoneSpeech.publish(context, vault)
                context.getString(R.string.speech_saved)
            }
        }, enabled = !busy && url.isNotBlank()) {
            Text(stringResource(R.string.speech_save_profile))
        }
        Button(
            { work { quota(TtsVault(context).selected(endpointId()).key) } },
            enabled =
            !busy && url.isNotBlank()
        ) { Text(stringResource(R.string.speech_test_profile)) }
        Button({
            work {
                val id = endpointId()
                val vault = TtsVault(context)
                val old = vault.read(id)
                vault.save(id, SpeechSettings(old.mode, "", old.voice))
                PhoneSpeech.publish(context, vault)
                context.getString(R.string.speech_deleted)
            }
        }, enabled = !busy && url.isNotBlank()) {
            Text(stringResource(R.string.speech_delete_profile))
        }
        if (status.isNotEmpty()) Text(status)
        Button(back, enabled = !busy) { Text(stringResource(R.string.speech_back)) }
    }
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
        label = {
            Text(stringResource(label))
        },
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
