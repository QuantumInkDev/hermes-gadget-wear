package dev.quantumink.hermesgadget.mobile.widgets

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.phoneColors
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus

@Composable
internal fun HomeWidgetPreview(
    config: WidgetConfig,
    pages: List<CatalogPage>,
    edit: (Int) -> Unit,
    portrait: @Composable (CatalogPage, Modifier) -> Unit
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when (config.appearance) {
        WidgetAppearance.SYSTEM -> if (Build.VERSION.SDK_INT >=
            31
        ) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            phoneColors(if (dark) Appearance.DARK else Appearance.LIGHT)
        }
        WidgetAppearance.LIGHT -> phoneColors(Appearance.LIGHT)
        WidgetAppearance.DARK -> phoneColors(Appearance.DARK)
        WidgetAppearance.MOONLIGHT -> phoneColors(Appearance.MOONLIGHT)
    }
    MaterialTheme(colorScheme = colors) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(config.preset.rows) { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(config.preset.columns) { column ->
                            val slot = row * config.preset.columns + column
                            val key = config.slots[slot]
                            val page = pages.singleOrNull {
                                it.key == key &&
                                    it.status != CatalogStatus.WITHDRAWN
                            }
                            Column(
                                Modifier.weight(
                                    1f
                                ).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                                    edit(slot)
                                }.semantics {
                                    contentDescription =
                                        "Edit widget slot ${slot + 1}"
                                }.heightIn(min = 88.dp).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val image = Modifier.size(
                                    if (config.preset.columns >=
                                        4
                                    ) {
                                        44.dp
                                    } else {
                                        64.dp
                                    }
                                ).clip(CircleShape)
                                if (page !=
                                    null
                                ) {
                                    portrait(page, image)
                                } else {
                                    Surface(
                                        image,
                                        shape = CircleShape,
                                        color = colors.surfaceContainerHighest
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                if (key ==
                                                    null
                                                ) {
                                                    "+"
                                                } else {
                                                    "?"
                                                },
                                                color = colors.primary,
                                                style = MaterialTheme.typography.headlineMedium
                                            )
                                        }
                                    }
                                }
                                Text(
                                    page?.profile?.name
                                        ?: if (key ==
                                            null
                                        ) {
                                            "Add bot"
                                        } else {
                                            "Unavailable"
                                        },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
