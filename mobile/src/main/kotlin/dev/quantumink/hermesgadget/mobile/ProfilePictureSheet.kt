package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.CatalogPage

/** One native chooser for directory and catalog, with Cancel outside the scrolling body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilePictureSheet(
    repository: CatalogRepository,
    page: CatalogPage,
    busy: Boolean,
    dismiss: () -> Unit,
    choose: () -> Unit,
    restore: () -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(page.key) {
        focus.clearFocus()
        keyboard?.hide()
    }
    val window = LocalWindowInfo.current.containerSize
    val height = with(LocalDensity.current) { window.height.toDp() * .8f }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = height)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Picture for ${page.profile.name}",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DirectoryPortrait(repository, page)
                    Column {
                        Text(page.profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Shown on phone, watch and widgets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column {
                    PictureAction(
                        "Choose picture",
                        "Pick a photo or GIF from your phone",
                        R.drawable.ic_picture,
                        !busy,
                        choose
                    )
                    PictureAction(
                        if (page.sourceId == "manual") "Use default picture" else "Use box picture",
                        "Restore the imported picture",
                        R.drawable.ic_picture_restore,
                        !busy,
                        restore
                    )
                }
            }
            OutlinedButton(
                dismiss,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun PictureAction(
    title: String,
    description: String,
    icon: Int,
    enabled: Boolean,
    action: () -> Unit
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    TextButton(
        action,
        Modifier.fillMaxWidth().heightIn(min = 68.dp),
        enabled = enabled,
        contentPadding = PaddingValues(vertical = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
