package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.EndpointProfile

/** Stable-keyed portrait row; overflow and ordering never trigger conversation entry. */
@Composable
internal fun ProfileRow(
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
    onResetPicture: () -> Unit,
    onDetails: () -> Unit,
    onRetire: () -> Unit,
    onMove: (Int) -> Unit
) {
    val reason = when {
        profile == null -> "Set up a Gadget endpoint in Settings to talk."
        !profile.endpoint.isTls -> "Review the TLS endpoint in Settings to talk."
        else -> null
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(
                enabled = !busy && !reorder && reason == null,
                role = Role.Button,
                onClickLabel = "Talk and Chat",
                onClick = onTalk
            ).semantics { contentDescription = "Open ${page.profile.name} Talk and Chat" }
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DirectoryPortrait(repository, page, Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(page.profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    reason ?: "Talk & chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reason ==
                        null
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (page.status != CatalogStatus.AVAILABLE) {
                    Text(
                        when (page.status) {
                            CatalogStatus.OFFLINE -> "Cached · catalog offline"
                            CatalogStatus.ATTENTION -> "Cached · catalog needs attention"
                            CatalogStatus.WITHDRAWN -> "Withdrawn · setup retained"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!reorder) {
                ProfileActions(
                    page.profile.name,
                    !busy,
                    if (chosenPicture) {
                        if (page.sourceId == "manual") "Use default picture" else "Use box picture"
                    } else {
                        null
                    },
                    page.status == CatalogStatus.WITHDRAWN,
                    onPicture,
                    onResetPicture,
                    onDetails,
                    onRetire
                )
            }
        }
        if (reorder) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Profile order",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium
                )
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
        }
    }
}
