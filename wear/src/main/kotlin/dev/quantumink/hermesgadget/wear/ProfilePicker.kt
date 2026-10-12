package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch

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
                    if (pin != null && !locked) {
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

/** Local editing remains available without a phone; every edit shares the watch CAS revision. */
@Composable
fun ProfilePinEditor(pages: List<ProfilePage>, key: String, locked: Boolean, close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val store = androidx.compose.runtime.remember { TilePinStore(context) }
    var pins by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<dev.quantumink.hermesgadget.protocol.TilePins?>(
            null
        )
    }
    var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var saving by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.read() }
        }
            .onSuccess { pins = it }.onFailure { notice = "Tile settings need attention" }
    }
    fun edit(change: () -> dev.quantumink.hermesgadget.protocol.TilePins) {
        if (locked || saving || pages.none { it.key == key }) return
        saving = true
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    change()
                }
            }
                .onSuccess {
                    pins = it
                    notice = "Pins saved on watch"
                    TilePinSync.changed(context)
                }
                .onFailure { notice = "Could not save tile settings" }
            saving = false
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        RoundPage(Modifier.fillMaxSize()) {
            item { Heading("Pin ${pages.singleOrNull { it.key == key }?.label ?: "Unavailable"}") }
            val current = pins
            if (current != null) {
                item {
                    Secondary(if (current.focus == key) "Clear Focus" else "Use for Focus", {
                        edit { store.editFocus(if (current.focus == key) null else key) }
                    }, enabled = !locked && !saving)
                }
                current.grid.forEachIndexed { index, value ->
                    item {
                        val label =
                            pages.singleOrNull { it.key == value }?.label
                                ?: if (value == null) "Empty" else "Unavailable"
                        Secondary("Slot ${index + 1}: $label", {
                            edit { store.editSlot(index, if (value == key) null else key) }
                        }, enabled = !locked && !saving)
                    }
                }
                item {
                    Body(
                        "Tap an occupied slot to replace it. Tap this profile's slot to clear it.",
                        muted = true
                    )
                }
            }
            if (notice.isNotEmpty()) item { Body(notice, muted = true) }
            item { Secondary("Back to profiles", close) }
        }
    }
}
