package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource

/** Secondary profile operations stay distinct from the row's conversation action. */
@Composable
internal fun ProfileActions(
    name: String,
    enabled: Boolean,
    resetLabel: String?,
    withdrawn: Boolean,
    onPicture: () -> Unit,
    onResetPicture: () -> Unit,
    onDetails: () -> Unit,
    onRetire: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = enabled, onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more), "Options for $name")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Watch picture") }, onClick = {
                expanded = false
                onPicture()
            })
            if (resetLabel != null) {
                DropdownMenuItem(text = { Text(resetLabel) }, onClick = {
                    expanded = false
                    onResetPicture()
                })
            }
            DropdownMenuItem(text = { Text("Endpoint details") }, onClick = {
                expanded = false
                onDetails()
            })
            if (withdrawn) {
                DropdownMenuItem(text = { Text("Forget withdrawn card") }, onClick = {
                    expanded = false
                    onRetire()
                })
            }
        }
    }
}
