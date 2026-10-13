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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
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
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val repository = remember(context.applicationContext) { CatalogRepository.get(context) }
    DirectoryContent(context, repository, onTalk, onBoxes, onManual, modifier, listState)
}

@Composable
internal fun DirectoryContent(
    context: Context,
    repository: CatalogRepository,
    onTalk: (CatalogPage) -> Unit,
    onBoxes: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val pages by repository.pages.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()
    val health by repository.health.collectAsStateWithLifecycle()
    val watchPictures by repository.watchPictures.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf(emptyList<CatalogSource>()) }
    var sourceOrder by remember { mutableStateOf(emptyList<String>()) }
    var profiles by remember { mutableStateOf(emptyMap<String, EndpointProfile?>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
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
        val snapshot = withContext(Dispatchers.IO) {
            Triple(
                pages.associate { it.key to runCatching { repository.profileFor(it) }.getOrNull() },
                repository.sources(),
                repository.sourceOrder()
            )
        }
        profiles = snapshot.first
        sources = snapshot.second
        sourceOrder = snapshot.third
    }

    val groups = CatalogOrder.group(pages).associateBy { it.first().sourceId }
    val labels = sources.associate { it.id to it.label }
    val ids = (sourceOrder + groups.keys).distinct().filter { id ->
        id != "manual" || groups[id].orEmpty().isNotEmpty() || reorder
    }
    LaunchedEffect(ids, busy) {
        if (!busy) collapsed = collapsed.filter { it in ids }
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
        state = listState,
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item(key = "directory-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your profiles", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "A conversation, one tap away.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more), "Directory options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = {
                                Text("Refresh")
                            }, enabled = !busy, onClick = {
                                menu = false
                                work { repository.refresh(true).get(20, TimeUnit.SECONDS) }
                            })
                            DropdownMenuItem(
                                text = { Text("Reorder") },
                                enabled =
                                !busy && query.isBlank(),
                                onClick = {
                                    menu = false
                                    reorder = true
                                }
                            )
                            DropdownMenuItem(text = {
                                Text("Manage boxes")
                            }, enabled = !busy, onClick = {
                                menu = false
                                onBoxes()
                            })
                            DropdownMenuItem(text = {
                                Text("Manual setup")
                            }, enabled = !busy, onClick = {
                                menu = false
                                onManual()
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(64) },
                    label = { Text("Search profiles or boxes") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reorder,
                    singleLine = true
                )
                if (query.isNotBlank()) {
                    Text(
                        "Matching boxes stay open while you search.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { query = "" }) { Text("Clear search") }
                }
                if (reorder) {
                    Text(
                        "Move boxes and profiles with the arrows. This order also appears on your watch."
                    )
                    TextButton(onClick = { reorder = false }) { Text("Done reordering") }
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
            val boxMatches = label.contains(query.trim(), ignoreCase = true)
            val shownProfiles = group.filter {
                query.isBlank() || boxMatches ||
                    it.profile.name.contains(query.trim(), ignoreCase = true)
            }
            item(key = "source:$id") {
                DirectoryBoxHeader(
                    label = label,
                    count = if (query.isNotBlank() && shownProfiles.size != group.size) {
                        "${shownProfiles.size} of ${group.size} profiles"
                    } else {
                        "${group.size} " + if (group.size == 1) "profile" else "profiles"
                    },
                    status = catalogStatus(
                        health[id] ?: group.firstOrNull()?.status,
                        id == "manual"
                    ),
                    expanded = !isCollapsed,
                    hasContents = !isCollapsed && shownProfiles.isNotEmpty(),
                    canToggle = !reorder && query.isBlank(),
                    onToggle = {
                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                    },
                    modifier = Modifier.padding(top = 16.dp),
                    orderControls = {
                        if (reorder) {
                            OrderArrow("Move $label earlier", "↑", !busy && sourceIndex > 0) {
                                work { repository.moveSource(id, -1) }
                            }
                            OrderArrow(
                                "Move $label later",
                                "↓",
                                !busy && sourceIndex < ids.lastIndex
                            ) { work { repository.moveSource(id, 1) } }
                        }
                    }
                )
            }
            if (!isCollapsed) {
                items(shownProfiles, key = { it.key }) { page ->
                    val index = group.indexOfFirst { it.key == page.key }
                    val last = page.key == shownProfiles.lastOrNull()?.key
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(
                            bottomStart = if (last) 16.dp else 0.dp,
                            bottomEnd = if (last) 16.dp else 0.dp
                        )
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(
                                start = 0.dp,
                                end = 0.dp,
                                bottom = if (last) 4.dp else 0.dp
                            )
                        ) {
                            Column {
                                ProfileRow(
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
                                    onResetPicture = {
                                        work { repository.useBoxPicture(page.key) }
                                    },
                                    onRetire = { retiringKey = page.key },
                                    onMove = { delta ->
                                        work { repository.movePage(page.key, delta) }
                                    }
                                )
                                if (!last) {
                                    HorizontalDivider(
                                        Modifier.padding(start = 72.dp, end = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = 0.35f
                                        )
                                    )
                                }
                            }
                        }
                    }
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
internal fun OrderArrow(description: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
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
