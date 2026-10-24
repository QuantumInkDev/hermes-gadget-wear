package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit recipient and content review for one share. Using it fills a draft; Send stays in Talk/Chat. */
@Composable
internal fun ShareReviewPage(
    content: SharedContent?,
    notice: String,
    locked: Boolean,
    transfer: (String, String, () -> Boolean) -> Unit,
    cancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { CatalogRepository.get(context) }
    val pages by repository.pages.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { repository.savedPages() } }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.share_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall
        )
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.error)
        when (content) {
            null -> {
                Text(stringResource(R.string.share_unavailable))
                TextButton(cancel) { Text(stringResource(R.string.share_close)) }
            }
            is SharedContent.Rejected -> {
                Text(
                    when (content.reason) {
                        ShareRejection.EMPTY -> stringResource(R.string.share_rejected_empty)
                        ShareRejection.TOO_LONG -> stringResource(
                            R.string.share_rejected_too_long,
                            ShareIntake.MAX_BYTES
                        )
                        ShareRejection.UNSUPPORTED ->
                            stringResource(R.string.share_rejected_unsupported)
                        ShareRejection.UNREADABLE ->
                            stringResource(R.string.share_rejected_unreadable)
                    }
                )
                TextButton(cancel) { Text(stringResource(R.string.share_close)) }
            }
            is SharedContent.Text -> {
                var text by remember(content) { mutableStateOf(content.text) }
                var selectedKey by rememberSaveable(content) { mutableStateOf<String?>(null) }
                val options = pages.filter {
                    it.status != CatalogStatus.WITHDRAWN && it.profile.gadgetUrl != null
                }
                val selected = options.singleOrNull { it.key == selectedKey }
                // A catalog reload or edit invalidates any lookup already in flight.
                val generation = remember(
                    content,
                    selected?.key,
                    selected?.profile?.gadgetUrl,
                    selected?.status
                ) { QuickActionReviewGeneration() }
                DisposableEffect(generation) { onDispose { generation.close() } }
                Text(stringResource(R.string.share_intro))
                Text(
                    stringResource(R.string.share_profiles),
                    style = MaterialTheme.typography.titleMedium
                )
                if (options.isEmpty()) Text(stringResource(R.string.share_no_profiles))
                Column(
                    Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    options.forEach { page ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = page.key == selectedKey,
                                role = Role.RadioButton,
                                onClick = {
                                    generation.invalidate()
                                    selectedKey = page.key
                                }
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(selected = page.key == selectedKey, onClick = null)
                            DirectoryPortrait(repository, page, Modifier.size(40.dp))
                            Text(quickActionProfileLabel(page), Modifier.weight(1f))
                        }
                    }
                }
                if (selectedKey != null && selected == null) {
                    Text(stringResource(R.string.share_profile_missing))
                }
                val bytes = text.toByteArray(Charsets.UTF_8).size
                val withinBound = ShareIntake.withinBound(text)
                OutlinedTextField(
                    text,
                    {
                        generation.invalidate()
                        text = it
                    },
                    label = { Text(stringResource(R.string.share_message)) },
                    supportingText = {
                        Text(stringResource(R.string.share_size, bytes, ShareIntake.MAX_BYTES))
                    },
                    isError = !withinBound,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    !withinBound -> Text(
                        stringResource(R.string.share_too_long_edit, ShareIntake.MAX_BYTES),
                        color = MaterialTheme.colorScheme.error
                    )
                    text.isBlank() -> Text(stringResource(R.string.share_empty_edit))
                    selected == null && options.isNotEmpty() ->
                        Text(stringResource(R.string.share_choose_profile))
                }
                Text(
                    stringResource(R.string.share_draft_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (locked) Text(stringResource(R.string.share_locked))
                Button(
                    {
                        val key = selected?.key
                        val reviewed = text
                        if (key != null && ShareIntake.sendable(reviewed)) {
                            val current = generation.capture()
                            transfer(key, reviewed) { current() && text == reviewed }
                        }
                    },
                    enabled = !locked && selected != null && ShareIntake.sendable(text)
                ) { Text(stringResource(R.string.share_use)) }
                TextButton(cancel) { Text(stringResource(R.string.share_cancel)) }
            }
        }
    }
}
