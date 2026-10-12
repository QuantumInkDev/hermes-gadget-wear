package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Shared phone shell, including screens outside a Material card or surface. */
@Composable
internal fun PhoneScaffold(
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFF11170F),
        contentColor = MaterialTheme.colorScheme.onSurface,
        bottomBar = bottomBar,
        content = content
    )
}
