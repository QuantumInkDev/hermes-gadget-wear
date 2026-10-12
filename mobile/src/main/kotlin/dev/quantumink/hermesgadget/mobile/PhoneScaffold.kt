package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import dev.quantumink.hermesgadget.client.R as ClientR

/** Shared phone shell, including screens outside a Material card or surface. */
@Composable
internal fun PhoneScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = topBar,
        bottomBar = bottomBar,
        content = content
    )
}

/** Activity owns the destination; this surface only emits explicit navigation choices. */
@Composable
internal fun PhoneNavigationBar(destination: String, onNavigate: (String) -> Unit) {
    androidx.compose.material3.NavigationBar {
        listOf("profiles" to "Profiles", "watch" to "Watch", "settings" to "Settings")
            .forEach { (route, label) ->
                NavigationBarItem(
                    selected = destination == route,
                    onClick = { onNavigate(route) },
                    icon = {
                        val icon = when (route) {
                            "profiles" -> ClientR.drawable.ic_nav_profiles
                            "watch" -> ClientR.drawable.ic_nav_watch
                            else -> ClientR.drawable.ic_nav_settings
                        }
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(icon),
                            null
                        )
                    },
                    label = { androidx.compose.material3.Text(label) }
                )
            }
    }
}
