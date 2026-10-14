package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full, scalable one-line name. Large fonts can scroll without losing the complete title. */
@Composable
fun PhoneBrandHeader(
    title: String = "Quantum Relay for Hermes Agents",
    actions: @Composable () -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val size = when {
            maxWidth >= 380.dp -> 22.sp
            maxWidth >= 328.dp -> 20.sp
            else -> 18.sp
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = size,
                    lineHeight = size * 1.35f,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                softWrap = false
            )
            actions()
        }
    }
}
