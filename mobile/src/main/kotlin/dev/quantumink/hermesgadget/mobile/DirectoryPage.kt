package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogOrder
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground directory. The activity owns navigation and endpoint review before conversation. */
@Composable
fun DirectoryPage(
    context: Context,
    onTalk: (CatalogPage) -> Unit,
    onBoxes: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = remember(context.applicationContext) { CatalogRepository.get(context) }
    val pages by repository.pages.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()
    val health by repository.health.collectAsStateWithLifecycle()
    val watchPictures by repository.watchPictures.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf(emptyList<CatalogSource>()) }
    var sourceOrder by remember { mutableStateOf(emptyList<String>()) }
    var profiles by remember { mutableStateOf(emptyMap<String, EndpointProfile?>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pictureKey by rememberSaveable { mutableStateOf<String?>(null) }
    var photoKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var retiringKey by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun work(action: () -> Unit) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    action()
                    repository.sources() to repository.sourceOrder()
                }
            }
            result.fold({ (configured, order) ->
                sources = configured
                sourceOrder = order
                error = ""
            }, { error = "Could not complete this change. Saved profiles were kept. Try again." })
            busy = false
        }
    }

    val photo = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        val key = photoKey
        photoKey = null
        if (uri != null && key != null) {
            work {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) {
                            "Picture is too large."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Picture unavailable")
                repository.selectWatchPhoto(key, bytes)
            }
        }
    }
    LaunchedEffect(repository) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                repository.savedPages()
                repository.sources() to repository.sourceOrder()
            }
        }
        result.fold({ (configured, order) ->
            sources = configured
            sourceOrder = order
        }, { error = "Saved profiles could not be read. Open Settings to review setup." })
        busy = false
    }
    LaunchedEffect(pages) {
        profiles = withContext(Dispatchers.IO) {
            pages.associate { it.key to runCatching { repository.profileFor(it) }.getOrNull() }
        }
    }

    val groups = CatalogOrder.group(pages).associateBy { it.first().sourceId }
    val labels = sources.associate { it.id to it.label }
    val ids = (sourceOrder + groups.keys).distinct().filter { id ->
        id != "manual" || groups[id].orEmpty().isNotEmpty() || reorder
    }
    val visible = ids.filter { id ->
        val label = if (id == "manual") {
            "Manual endpoints"
        } else {
            labels[id] ?: groups[id]?.firstOrNull()?.sourceLabel ?: "Box"
        }
        query.isBlank() || label.contains(query.trim(), ignoreCase = true) ||
            groups[id].orEmpty().any { it.profile.name.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "directory-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your profiles", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a profile to talk or chat. Phone pairing is separate from your watch.")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(64) },
                    label = { Text("Search profiles or boxes") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reorder,
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        work { repository.refresh(true).get(20, TimeUnit.SECONDS) }
                    }) { Text("Refresh") }
                    OutlinedButton(enabled = !busy && query.isBlank(), onClick = {
                        reorder = !reorder
                    }) { Text(if (reorder) "Done reordering" else "Reorder") }
                }
                if (query.isNotBlank()) Text("Clear search to reorder your directory.")
                if (reorder) {
                    Text(
                        "Move boxes and profiles with the arrows. This order also appears on your watch."
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBoxes, enabled = !busy) { Text("Manage boxes") }
                    TextButton(onClick = onManual, enabled = !busy) { Text("Manual setup") }
                }
                if (busy) Text("Updating profiles…")
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visible.isEmpty() && !busy) {
            item(key = "empty-directory") {
                Text(
                    if (query.isNotBlank()) {
                        "No matching profiles or boxes."
                    } else {
                        "Add a box or a manual endpoint to begin."
                    }
                )
            }
        }
        visible.forEach { id ->
            val group = groups[id].orEmpty()
            val label = if (id ==
                "manual"
            ) {
                "Manual endpoints"
            } else {
                labels[id] ?: group.firstOrNull()?.sourceLabel
                    ?: "Box"
            }
            val isCollapsed = id in collapsed && !reorder && query.isBlank()
            val sourceIndex = ids.indexOf(id)
            item(key = "source:$id") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription =
                                        "${if (isCollapsed) "Expand" else "Collapse"} $label, " +
                                        "${group.size} profiles"
                                },
                                enabled = !reorder,
                                onClick = {
                                    collapsed =
                                        if (id in collapsed) collapsed - id else collapsed + id
                                }
                            ) { Text("${if (isCollapsed) "▸" else "▾"} $label · ${group.size}") }
                            if (reorder) {
                                OrderArrow("Move $label earlier", "↑", !busy && sourceIndex > 0) {
                                    work { repository.moveSource(id, -1) }
                                }
                                OrderArrow(
                                    "Move $label later",
                                    "↓",
                                    !busy && sourceIndex < ids.lastIndex
                                ) {
                                    work { repository.moveSource(id, 1) }
                                }
                            }
                        }
                        if (group.isEmpty()) {
                            Text(
                                catalogStatus(health[id], manual = id == "manual"),
                                Modifier.padding(8.dp)
                            )
                            Text("No cached profiles yet.", Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
            if (!isCollapsed) {
                val boxMatches = label.contains(query.trim(), ignoreCase = true)
                items(
                    group.filter {
                        query.isBlank() || boxMatches ||
                            it.profile.name.contains(query.trim(), ignoreCase = true)
                    },
                    key = { it.key }
                ) { page ->
                    val index = group.indexOfFirst { it.key == page.key }
                    DirectoryCard(
                        page = page,
                        repository = repository,
                        chosenPicture = page.key in watchPictures,
                        profile = profiles[page.key],
                        busy = busy,
                        reorder = reorder,
                        canMoveUp = index > 0,
                        canMoveDown = index < group.lastIndex,
                        onTalk = { onTalk(page) },
                        onPicture = { pictureKey = page.key },
                        onDetails = { detailKey = page.key },
                        onMove = { delta -> work { repository.movePage(page.key, delta) } }
                    )
                }
            }
        }
    }
    pages.singleOrNull { it.key == pictureKey }?.let { page ->
        AlertDialog(
            onDismissRequest = { pictureKey = null },
            title = { Text("Picture for ${page.profile.name}") },
            text = {
                Text(
                    "Choose the picture shown for this profile on your watch and phone. Your choice survives a box refresh."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    photoKey = page.key
                    pictureKey = null
                    photo.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                }) { Text("Choose picture") }
            },
            dismissButton = {
                Column {
                    TextButton(enabled = !busy, onClick = {
                        pictureKey = null
                        work { repository.useBoxPicture(page.key) }
                    }) {
                        Text(
                            if (page.sourceId ==
                                "manual"
                            ) {
                                "Use default picture"
                            } else {
                                "Use box picture"
                            }
                        )
                    }
                    TextButton(onClick = { pictureKey = null }) { Text("Cancel") }
                }
            }
        )
    }
    pages.singleOrNull { it.key == detailKey }?.let { page ->
        AlertDialog(
            onDismissRequest = { detailKey = null },
            title = { Text(page.profile.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(page.sourceLabel)
                    Text(catalogStatus(page.status, page.sourceId == "manual"))
                    Text(
                        profiles[page.key]?.endpoint?.url
                            ?: "No conversation endpoint is configured."
                    )
                    Text(
                        "Catalog availability does not confirm a live Gadget connection " +
                            "or phone/watch pairing."
                    )
                    if (page.status == CatalogStatus.WITHDRAWN) {
                        TextButton(enabled = !busy, onClick = {
                            detailKey = null
                            retiringKey = page.key
                        }) { Text("Forget withdrawn card") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailKey = null }) { Text("Close") } }
        )
    }
    pages.singleOrNull { it.key == retiringKey }?.let { page ->
        AlertDialog(
            onDismissRequest = { retiringKey = null },
            title = { Text("Forget withdrawn profile?") },
            text = {
                Text(
                    "Remove ${page.profile.name} from this catalog. Existing phone setup can " +
                        "still appear as a manual endpoint. Saved watch setup and enrollment remain."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    retiringKey = null
                    work { repository.retirePage(page.key) }
                }) { Text("Forget card") }
            },
            dismissButton = { TextButton(onClick = { retiringKey = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DirectoryCard(
    page: CatalogPage,
    repository: CatalogRepository,
    chosenPicture: Boolean,
    profile: EndpointProfile?,
    busy: Boolean,
    reorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onTalk: () -> Unit,
    onPicture: () -> Unit,
    onDetails: () -> Unit,
    onMove: (Int) -> Unit
) {
    val reason = when {
        profile == null -> "Talk/Chat needs a configured TLS Gadget endpoint."
        !profile.endpoint.isTls ->
            "Talk/Chat requires a wss TLS endpoint. " +
                "Review this legacy endpoint in Settings."
        else -> null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DirectoryPortrait(repository, page)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(page.profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(page.sourceLabel, style = MaterialTheme.typography.bodySmall)
                    Text(
                        catalogStatus(page.status, page.sourceId == "manual"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (chosenPicture) {
                            "Using watch picture"
                        } else if (page.sourceId ==
                            "manual"
                        ) {
                            "Phone picture or default"
                        } else if (page.profile.portrait ==
                            null
                        ) {
                            "Default picture"
                        } else {
                            "Using box picture"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (reorder) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Order within this box", Modifier.weight(1f))
                    OrderArrow(
                        "Move ${page.profile.name} earlier in ${page.sourceLabel}",
                        "↑",
                        !busy && canMoveUp
                    ) { onMove(-1) }
                    OrderArrow(
                        "Move ${page.profile.name} later in ${page.sourceLabel}",
                        "↓",
                        !busy && canMoveDown
                    ) { onMove(1) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && reason == null, onClick = onTalk) {
                        Text("Talk/Chat")
                    }
                    OutlinedButton(enabled = !busy, onClick = onPicture) { Text("Picture") }
                }
                if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDetails, enabled = !busy) { Text("Endpoint details") }
            }
        }
    }
}

@Composable
internal fun DirectoryPortrait(
    repository: CatalogRepository,
    page: CatalogPage,
    modifier: Modifier = Modifier
) {
    val hash = page.profile.portrait?.sha256
    var image by remember(hash) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(hash) {
        image = withContext(Dispatchers.IO) {
            hash?.let {
                runCatching {
                    repository.portraitFile(it)?.let { file ->
                        BitmapFactory.decodeFile(file.path)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    Surface(
        modifier.size(64.dp).clip(CircleShape),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        val portrait = image
        if (portrait != null) {
            Image(
                portrait,
                "Watch picture for ${page.profile.name}",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(page.profile.name.take(1), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun OrderArrow(description: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription =
                description
        }
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}

private fun catalogStatus(status: CatalogStatus?, manual: Boolean): String = if (manual) {
    "Saved on phone · availability unverified"
} else {
    when (status) {
        CatalogStatus.AVAILABLE -> "Catalog available"
        CatalogStatus.OFFLINE -> "Catalog offline · cached profile"
        CatalogStatus.ATTENTION -> "Catalog needs attention · cached profile"
        CatalogStatus.WITHDRAWN -> "Withdrawn · saved setup retained"
        null -> "Catalog has not been refreshed"
    }
}
