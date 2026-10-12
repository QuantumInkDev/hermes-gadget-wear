package dev.quantumink.hermesgadget.wear

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.client.appearanceTokens

@Composable
fun WatchAppearance(mode: Appearance, content: @Composable () -> Unit) {
    val t = appearanceTokens(mode, watch = true)
    MaterialTheme(
        colorScheme = ColorScheme(
            primary = Color(t.accent),
            primaryDim = Color(t.accent),
            primaryContainer = Color(t.accentContainer),
            onPrimary = Color(t.onAccent),
            onPrimaryContainer = Color(t.onAccentContainer),
            secondary = Color(t.supportingAccent),
            secondaryDim = Color(t.supportingAccent),
            secondaryContainer = Color(t.accentContainer),
            onSecondary = Color(t.onSupportingAccent),
            onSecondaryContainer = Color(t.onAccentContainer),
            tertiary = Color(t.supportingAccent),
            tertiaryDim = Color(t.supportingAccent),
            tertiaryContainer = Color(t.accentContainer),
            onTertiary = Color(t.onSupportingAccent),
            onTertiaryContainer = Color(t.onAccentContainer),
            surfaceContainerLow = Color(t.surfaceLow),
            surfaceContainer = Color(t.surface),
            surfaceContainerHigh = Color(t.surfaceHigh),
            onSurface = Color(t.text),
            onSurfaceVariant = Color(t.secondaryText),
            outline = Color(t.outline),
            outlineVariant = Color(t.outline),
            background = Color(t.background),
            onBackground = Color(t.text),
            error = Color(t.error),
            errorDim = Color(t.error),
            errorContainer = Color(t.errorContainer),
            onError = Color(t.onError),
            onErrorContainer = Color(t.onErrorContainer)
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides Color(t.text), content = content)
    }
}

@Composable
internal fun WatchAppearancePage(context: Context, back: () -> Unit) {
    val preferences = remember(context) { AppearancePreferences(context) }
    val mode by preferences.mode.collectAsStateWithLifecycle()
    RoundPage {
        item { Heading("Appearance") }
        Appearance.entries.forEach { option ->
            val label = when (option) {
                Appearance.LIGHT -> "Light"
                Appearance.DARK -> "Dark"
                Appearance.MOONLIGHT -> "Electronic Moonlight"
            }
            item {
                Secondary((if (mode == option) "✓ " else "○ ") + label, {
                    preferences.set(option)
                })
            }
        }
        item { Secondary("Back", back) }
    }
}
