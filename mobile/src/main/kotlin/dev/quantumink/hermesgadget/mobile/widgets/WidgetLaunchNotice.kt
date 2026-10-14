package dev.quantumink.hermesgadget.mobile.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun WidgetLaunchNotice(
    pending: Boolean,
    unavailable: Boolean,
    locked: Boolean,
    canEdit: Boolean,
    open: () -> Unit,
    edit: () -> Unit,
    dismiss: () -> Unit
) {
    if (!pending && !unavailable) {
        return
    }
    Surface(
        Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (pending) "Bot switch pending" else "Bot shortcut unavailable",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (pending) {
                    "Finish or cancel this turn, then open the requested bot."
                } else {
                    "The widget or its bot changed. Edit the widget to choose a current profile."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                if (pending) TextButton(open, enabled = !locked) { Text("Open requested bot") }
                if (unavailable && canEdit) TextButton(edit) { Text("Edit widget") }
                TextButton(dismiss) { Text("Dismiss") }
            }
        }
    }
}
