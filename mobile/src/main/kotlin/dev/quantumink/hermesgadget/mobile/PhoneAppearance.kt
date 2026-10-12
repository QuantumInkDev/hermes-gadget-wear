package dev.quantumink.hermesgadget.mobile

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.appearanceTokens

@Composable
fun PhoneAppearance(mode: Appearance, content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = mode == Appearance.LIGHT
                isAppearanceLightNavigationBars = mode == Appearance.LIGHT
            }
        }
    }
    MaterialTheme(colorScheme = phoneColors(mode), content = content)
}

private fun phoneColors(mode: Appearance): ColorScheme {
    val t = appearanceTokens(mode)
    return lightColorScheme(
        primary = Color(t.accent),
        onPrimary = Color(t.onAccent),
        primaryContainer = Color(t.accentContainer),
        onPrimaryContainer = Color(t.onAccentContainer),
        inversePrimary = Color(t.inverseAccent),
        secondary = Color(t.supportingAccent),
        onSecondary = Color(t.onSupportingAccent),
        secondaryContainer = Color(t.accentContainer),
        onSecondaryContainer = Color(t.onAccentContainer),
        tertiary = Color(t.supportingAccent),
        onTertiary = Color(t.onSupportingAccent),
        tertiaryContainer = Color(t.accentContainer),
        onTertiaryContainer = Color(t.onAccentContainer),
        background = Color(t.background),
        onBackground = Color(t.text),
        surface = Color(t.surface),
        onSurface = Color(t.text),
        surfaceVariant = Color(t.surfaceHigh),
        onSurfaceVariant = Color(t.secondaryText),
        surfaceTint = Color(t.accent),
        inverseSurface = Color(t.inverseSurface),
        inverseOnSurface = Color(t.inverseText),
        error = Color(t.error),
        onError = Color(t.onError),
        errorContainer = Color(t.errorContainer),
        onErrorContainer = Color(t.onErrorContainer),
        outline = Color(t.outline),
        outlineVariant = Color(t.outline),
        surfaceBright = Color(t.surfaceHigh),
        surfaceDim = Color(t.surfaceLow),
        surfaceContainer = Color(t.surface),
        surfaceContainerHigh = Color(t.surfaceHigh),
        surfaceContainerHighest = Color(t.surfaceHighest),
        surfaceContainerLow = Color(t.surfaceLow),
        surfaceContainerLowest = Color(t.background),
        primaryFixed = Color(t.accentContainer),
        primaryFixedDim = Color(t.accentContainer),
        onPrimaryFixed = Color(t.onAccentContainer),
        onPrimaryFixedVariant = Color(t.onAccentContainer),
        secondaryFixed = Color(t.accentContainer),
        secondaryFixedDim = Color(t.accentContainer),
        onSecondaryFixed = Color(t.onAccentContainer),
        onSecondaryFixedVariant = Color(t.onAccentContainer),
        tertiaryFixed = Color(t.accentContainer),
        tertiaryFixedDim = Color(t.accentContainer),
        onTertiaryFixed = Color(t.onAccentContainer),
        onTertiaryFixedVariant = Color(t.onAccentContainer),
        scrim = Color.Black
    )
}
