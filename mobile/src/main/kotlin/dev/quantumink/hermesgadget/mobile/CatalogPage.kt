package dev.quantumink.hermesgadget.mobile

import android.content.Context
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CatalogPage(context: Context, back: () -> Unit) {
    val repository = remember { CatalogRepository.get(context) }
    val pages by repository.pages.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()
    val health by repository.health.collectAsStateWithLifecycle()
    val watchPictures by repository.watchPictures.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf(emptyList<CatalogSource>()) }
    var id by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var retiring by remember {
        mutableStateOf<dev.quantumink.hermesgadget.protocol.CatalogPage?>(null)
    }
    var error by remember { mutableStateOf("") }
    var picturePage by remember {
        mutableStateOf<dev.quantumink.hermesgadget.protocol.CatalogPage?>(null)
    }
    var photoKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun work(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        action()
                        repository.sources()
                    }
                }
            result.fold({
                sources = it
                error = ""
            }, {
                error =
                    "Catalog needs attention. Check HTTPS, credentials and profile limits."
            })
            busy = false
        }
    }
    val photo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val key = photoKey
        photoKey = null
        if (uri != null && key != null) {
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
                repository.selectWatchPhoto(key, bytes)
            }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                sources = repository.sources()
                repository.savedPages()
            }
                .onFailure { error = "Saved catalog could not be read." }
        }
        busy = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Boxes and discovery")
        Text(
            "Add up to three read-only HTTPS catalogs. " +
                "Each profile keeps its own watch connection and pairing. " +
                "New URLs need review on the watch."
        )
        sources.forEach { source ->
            Button(enabled = !busy, onClick = {
                id = source.id
                label = source.label
                url = source.url
                credential =
                    source.credential
            }) { Text(source.label) }
            if (health[source.id] == CatalogStatus.ATTENTION) Text("Connection needs attention")
            if (health[source.id] ==
                CatalogStatus.OFFLINE
            ) {
                Text("Offline · cached catalog retained")
            }
        }
        OutlinedTextField(value = label, onValueChange = {
            label = it.take(32)
        }, label = { Text("Box name") }, enabled = !busy, singleLine = true)
        OutlinedTextField(value = url, onValueChange = {
            url = it.take(2048)
        }, label = { Text("HTTPS catalog URL") }, enabled = !busy, singleLine = true)
        OutlinedTextField(
            value = credential,
            onValueChange = {
                credential = it.take(2048)
            },
            label = {
                Text("Catalog-only credential")
            },
            enabled = !busy,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Button(
            enabled = !busy && label.isNotBlank() && url.isNotBlank() && credential.isNotBlank(),
            onClick = {
                work {
                    val source =
                        CatalogSource(
                            id ?: java.util.UUID.randomUUID().toString(),
                            label.trim(),
                            url.trim(),
                            credential.trim()
                        )
                    repository.saveSource(source)
                    id = source.id
                    repository.refresh(true).get(20, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        ) { Text("Save box and refresh") }
        Button(enabled = !busy && sources.size < 3, onClick = {
            id = null
            label = ""
            url = ""
            credential =
                ""
        }) { Text("Add another box") }
        Button(enabled = !busy, onClick = {
            work { repository.refresh(true).get(20, java.util.concurrent.TimeUnit.SECONDS) }
        }) { Text("Refresh profiles") }
        retiring?.let { page ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { retiring = null },
                title = { Text("Forget withdrawn profile?") },
                text = {
                    Text(
                        "Remove ${page.profile.name} from this phone's catalog. Saved watch setup and pairing keys remain. Existing phone setup can still appear as a manual profile."
                    )
                },
                confirmButton = {
                    Button(enabled = !busy, onClick = {
                        retiring = null
                        work { repository.retirePage(page.key) }
                    }) { Text("Forget catalog card") }
                },
                dismissButton = { Button(onClick = { retiring = null }) { Text("Cancel") } }
            )
        }
        picturePage?.let { page ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { picturePage = null },
                title = { Text("Picture for ${page.profile.name}") },
                text = {
                    Text(
                        "Keep the imported box picture or choose one " +
                            "just for this watch profile. " +
                            "A watch picture stays selected when the box refreshes. " +
                            "The box's original is unchanged."
                    )
                },
                confirmButton = {
                    Button(enabled = !busy, onClick = {
                        picturePage = null
                        photoKey = page.key
                        photo.launch(
                            PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                        )
                    }) { Text("Choose watch picture") }
                },
                dismissButton = {
                    Column {
                        Button(enabled = !busy, onClick = {
                            picturePage = null
                            work { repository.useBoxPicture(page.key) }
                        }) { Text("Use box picture") }
                        Button(onClick = { picturePage = null }) { Text("Cancel") }
                    }
                }
            )
        }
        pages.forEach { page ->
            Text("${page.profile.name} · ${page.sourceLabel}")
            Text(if (page.key in watchPictures) "Using watch picture" else "Using box picture")
            Button(enabled = !busy, onClick = { picturePage = page }) { Text("Picture options") }
            if (page.status == CatalogStatus.WITHDRAWN) {
                Button(enabled = !busy, onClick = {
                    retiring = page
                }) { Text("Forget withdrawn card") }
            }
            Button(enabled = !busy && pages.firstOrNull()?.key != page.key, onClick = {
                work { repository.movePage(page.key, -1) }
            }) { Text("Move earlier") }
            Button(enabled = !busy && pages.lastOrNull()?.key != page.key, onClick = {
                work { repository.movePage(page.key, 1) }
            }) { Text("Move later") }
            Text(
                when (page.status) {
                    CatalogStatus.AVAILABLE -> if (page.profile.gadgetUrl ==
                        null
                    ) {
                        "No conversation endpoint. Set up this profile on its Hermes box, then refresh."
                    } else {
                        "Catalog available"
                    }
                    CatalogStatus.OFFLINE -> "Offline · cached profile"
                    CatalogStatus.ATTENTION -> "Connection needs attention"
                    CatalogStatus.WITHDRAWN -> "Withdrawn · saved watch setup retained"
                }
            )
        }
        if (error.isNotEmpty()) Text(error)
        if (notice.isNotEmpty()) Text(notice)
        Button(enabled = !busy, onClick = back) { Text("Back") }
    }
}
