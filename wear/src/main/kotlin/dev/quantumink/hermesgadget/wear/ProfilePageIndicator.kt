package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

/** A moving six-page window keeps a large roster quiet without hiding its exact position. */
@Composable
fun ProfilePageIndicator(index: Int, count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val position = index.coerceIn(0, count - 1)
    val marks = minOf(6, count)
    val start = (position - marks / 2).coerceIn(0, count - marks)
    Row(
        modifier.background(MaterialTheme.colorScheme.background, CircleShape)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Profile ${position + 1} of $count"
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(marks) { mark ->
            Box(
                Modifier.size(4.dp).background(
                    if (start + mark ==
                        position
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    CircleShape
                )
            )
        }
    }
}
