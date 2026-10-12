package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var catalogs by remember { mutableStateOf(false) }
    if (catalogs) {
        CatalogPage(context) { catalogs = false }
        return
    }
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
    val photo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val selected = profiles.singleOrNull { it.endpoint.url == url }
        if (uri != null && selected != null) {
            work {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 8 * 1024 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Photo unavailable")
                CatalogRepository.get(context).selectPhoto(selected, bytes)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(enabled = !busy, onClick = { catalogs = true }) { Text("Boxes and discovery") }
        Text("Manual watch setup", style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.profile_sync_disclosure))
        if (profiles.isNotEmpty()) {
            SettingsSection {
                Text("Saved endpoints", style = MaterialTheme.typography.titleMedium)
                profiles.forEach { profile ->
                    TextButton(enabled = !busy, onClick = {
                        url = profile.endpoint.url
                        label = profile.label
                        token = profile.accessToken
                        local = profile.endpoint.privateNetwork
                        accepted = profile.endpoint.cleartextAccepted
                        transport = profile.transport
                    }) { Text(profile.label) }
                }
            }
        }
        SettingsSection {
            Text("Endpoint details", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_name)) },
                value = label,
                onValueChange = { label = it.take(32) },
                enabled = !busy,
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_token)) },
                value = token,
                onValueChange = { token = it.take(2048) },
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            SettingsToggle("Private network", local, !busy) {
                local = it
                accepted = false
            }
            if (cleartext) {
                Text(stringResource(R.string.profile_cleartext_warning))
                SettingsToggle(stringResource(R.string.profile_accept), accepted, !busy) {
                    accepted =
                        it
                }
            }
            SettingsChoice(
                "Transport",
                transport.name,
                TransportPreference.entries.map {
                    it.name
                },
                !busy
            ) {
                transport = TransportPreference.entries[it]
            }
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
        }
        SettingsSection {
            Text("Saved setup", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy && profiles.any { it.endpoint.url == url }, onClick = {
                photo.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }) { Text("Choose watch picture") }
            TextButton(enabled = !busy, onClick = {
                work { ProfileSync.publish(context, vault) }
            }) {
                Text(stringResource(R.string.profile_resync))
            }
            TextButton(enabled = !busy && profiles.any { it.endpoint.url == url }, onClick = {
                val deleted = profiles.single { it.endpoint.url == url }
                work {
                    vault.remove(deleted.endpoint.url)
                    CatalogRepository.get(context).setupRemoved()
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
        }
        if (notice.isNotEmpty()) Text(notice)
        TextButton(enabled = !busy, onClick = back) { Text(stringResource(R.string.speech_back)) }
    }
}
