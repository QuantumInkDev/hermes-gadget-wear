package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.TransportPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfilesPage(context: Context, back: () -> Unit) {
    val vault = remember { ProfileVault(context) }
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(emptyList<EndpointProfile>()) }
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf(TransportPreference.AUTO) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    val cleartext = url.trim().startsWith("ws:", true)
    fun work(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    action()
                    vault.profiles()
                }
            }
            result.fold({
                profiles = it
                notice = context.getString(R.string.profiles_saved)
            }, {
                notice = context.getString(R.string.profiles_failed)
            })
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { vault.profiles() } }
        result.fold({ profiles = it }, { notice = context.getString(R.string.profiles_failed) })
        busy = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.profiles_title))
        Text(stringResource(R.string.profile_sync_disclosure))
        profiles.forEach { profile ->
            Button(enabled = !busy, onClick = {
                url = profile.endpoint.url
                label = profile.label
                token = profile.accessToken
                local = profile.endpoint.privateNetwork
                accepted = profile.endpoint.cleartextAccepted
                transport = profile.transport
            }) { Text(profile.label) }
        }
        OutlinedTextField(
            label = { Text(stringResource(R.string.profile_name)) },
            value = label,
            onValueChange = { label = it.take(32) },
            enabled = !busy,
            singleLine = true
        )
        OutlinedTextField(
            label = { Text(stringResource(R.string.profile_url)) },
            value = url,
            onValueChange = {
                url = it.take(2048)
                accepted = false
            },
            enabled = !busy,
            singleLine = true
        )
        OutlinedTextField(
            label = { Text(stringResource(R.string.profile_token)) },
            value = token,
            onValueChange = { token = it.take(2048) },
            enabled = !busy,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Button(enabled = !busy, onClick = {
            local = !local
            accepted = false
        }) {
            Text(
                stringResource(if (local) R.string.profile_private_on else R.string.profile_private)
            )
        }
        if (cleartext) {
            Text(stringResource(R.string.profile_cleartext_warning))
            Button(enabled = !busy, onClick = { accepted = !accepted }) {
                Text(
                    stringResource(
                        if (accepted) R.string.profile_accepted else R.string.profile_accept
                    )
                )
            }
        }
        Button(enabled = !busy, onClick = {
            transport = TransportPreference.entries[(transport.ordinal + 1) % 3]
        }) { Text(transport.name) }
        Button(
            enabled = !busy && url.isNotBlank() && label.isNotBlank() &&
                (!cleartext || (local && accepted)) &&
                !(cleartext && transport == TransportPreference.RELAY),
            onClick = {
                val profile = runCatching {
                    EndpointProfile(
                        Endpoint.parse(url, local, accepted),
                        label.trim(),
                        token.trim(),
                        transport
                    )
                }.getOrNull()
                if (profile == null) {
                    notice = context.getString(R.string.profiles_failed)
                } else {
                    work {
                        vault.save(profile)
                        ProfileSync.publish(context, vault)
                    }
                }
            }
        ) { Text(stringResource(R.string.profile_save_sync)) }
        Button(enabled = !busy, onClick = { work { ProfileSync.publish(context, vault) } }) {
            Text(stringResource(R.string.profile_resync))
        }
        Button(enabled = !busy && profiles.any { it.endpoint.url == url }, onClick = {
            val deleted = profiles.single { it.endpoint.url == url }
            work {
                vault.remove(deleted.endpoint.url)
                val speech = TtsVault(context)
                speech.save(
                    ClientSpeech.endpointId(deleted.endpoint),
                    SpeechSettings(SpeechMode.SERVER, "", "")
                )
                PhoneSpeech.publish(context, speech)
                ProfileSync.publish(context, vault)
            }
            token = ""
        }) { Text(stringResource(R.string.profile_delete)) }
        if (notice.isNotEmpty()) Text(notice)
        Button(enabled = !busy, onClick = back) { Text(stringResource(R.string.speech_back)) }
    }
}
