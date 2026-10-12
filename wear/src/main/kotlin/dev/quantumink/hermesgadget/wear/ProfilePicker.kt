package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text

/** Separate window owns Back and rotary focus while the conversation stays mounted below it. */
@Composable
fun ProfilePicker(
    pages: List<ProfilePage>,
    selectedKey: String,
    locked: Boolean,
    select: (String) -> Unit,
    close: () -> Unit,
    pin: ((String) -> Unit)? = null
) {
    val density = LocalDensity.current
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            RoundPage(Modifier.fillMaxSize().selectableGroup()) {
                item { Heading("Choose a profile") }
                if (locked) {
                    item {
                        Body("Finish the current turn to change profiles", muted = true)
                    }
                }
                pages.forEach { page ->
                    item(key = page.key) {
                        RadioButton(
                            selected = selectedKey == page.key,
                            onSelect = { select(page.key) },
                            enabled = !locked,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(page.label) },
                            secondaryLabel = { Text(page.source) }
                        )
                    }
                    if (pin != null) {
                        item(key = "pin:${page.key}") {
                            Secondary("Pin ${page.label}", { pin(page.key) }, enabled = !locked)
                        }
                    }
                }
                item { Secondary("Close", close) }
            }
        }
    }
}
