package dev.quantumink.hermesgadget.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A box section header; the whole header is the expand/collapse target. */
@Composable
internal fun DirectoryBoxHeader(
    label: String,
    count: String,
    status: String,
    expanded: Boolean,
    hasContents: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    orderControls: @Composable () -> Unit = {}
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "box chevron")
    val corner = if (hasContents) 0.dp else 16.dp
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp, 16.dp, corner, corner)
    ) {
        Row(
            Modifier.fillMaxWidth().then(
                if (canToggle) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "${if (expanded) "Collapse" else "Expand"} $label",
                        onClick = onToggle
                    )
                } else {
                    Modifier
                }
            ).semantics(mergeDescendants = canToggle) {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painterResource(R.drawable.ic_box),
                    null,
                    Modifier.padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$count · " +
                        if (count == "0 profiles") "No cached profiles yet. $status" else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canToggle) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    null,
                    Modifier.size(24.dp).rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            orderControls()
        }
    }
}
