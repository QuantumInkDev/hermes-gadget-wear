package dev.quantumink.hermesgadget.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.client.ReplyPlatformActions
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.ReplyBlock
import dev.quantumink.hermesgadget.protocol.ReplyPresentation

@Composable
internal fun StructuredReply(
    conversation: ConversationState,
    profileKey: String,
    enabled: Boolean,
    actions: ReplyPlatformActions = ReplyPlatformActions(LocalContext.current)
) {
    // No saveable/server state: remount, reconnect, turn or publication clears local checks.
    key(profileKey, conversation.replyRevision) {
        val parsed = remember(conversation.reply) { ReplyPresentation.parse(conversation.reply) }
        var plain by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        val checks = remember { mutableStateMapOf<Pair<Int, Int>, Boolean>() }
        val active = enabled && !conversation.busy && !conversation.replyChanging &&
            conversation.prompt == null
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ plain = !plain }) { Text(if (plain) "Show cards" else "Full text") }
            TextButton({
                notice = if (actions.copyResponse(conversation.reply)) {
                    "Copied response"
                } else {
                    "Clipboard unavailable. The response is still readable."
                }
            }, enabled = active) { Text("Copy full response") }
            if (notice.isNotEmpty()) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (plain || parsed !is ReplyPresentation.Accepted) {
                SelectableReplyText(conversation.reply)
            } else {
                parsed.blocks.forEach { block ->
                    if (block is ReplyBlock.Text) {
                        SelectableReplyText(block.source)
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when (block) {
                                    is ReplyBlock.Code -> {
                                        Text("Code" + block.language?.let { " · $it" }.orEmpty())
                                        SelectableReplyText(block.code, code = true)
                                        TextButton({
                                            notice =
                                                if (actions.copyCode(block.action)) {
                                                    "Copied code"
                                                } else {
                                                    "Clipboard unavailable. Code is still readable."
                                                }
                                        }, enabled = active) { Text("Copy code") }
                                    }
                                    is ReplyBlock.Link -> {
                                        SelectableReplyText(block.label)
                                        SelectableReplyText("HTTPS host: " + block.action.host)
                                        SelectableReplyText(block.action.url)
                                        TextButton({
                                            notice = if (actions.open(block.action)) {
                                                "Choose an app"
                                            } else {
                                                "No app can open this link. Copy the full response."
                                            }
                                        }, enabled = active) { Text("Open link") }
                                    }
                                    is ReplyBlock.Address -> {
                                        Text("Address")
                                        SelectableReplyText(block.address)
                                        TextButton({
                                            notice = if (actions.open(block.action)) {
                                                "Choose an app"
                                            } else {
                                                "No app can open this address. Copy the full response."
                                            }
                                        }, enabled = active) { Text("Open address") }
                                    }
                                    is ReplyBlock.Checklist -> {
                                        Text("Checklist · on this screen only")
                                        block.items.forEach { item ->
                                            val position = item.blockIndex to item.itemIndex
                                            val checked = checks[position] ?: item.initiallyChecked
                                            Row(
                                                Modifier.fillMaxWidth().toggleable(
                                                    checked,
                                                    enabled = active,
                                                    role = Role.Checkbox,
                                                    onValueChange = { checks[position] = it }
                                                ).semantics {
                                                    contentDescription = "Local check: " + item.text
                                                }.padding(vertical = 8.dp)
                                            ) {
                                                Checkbox(checked, onCheckedChange = null)
                                                SelectableReplyText(
                                                    item.text,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                    is ReplyBlock.Text -> Unit
                                }
                            }
                        }
                    }
                }
            }
            if (!active) Text("Reply actions are paused while the conversation is changing.")
        }
    }
}

@Composable
private fun SelectableReplyText(
    value: String,
    modifier: Modifier = Modifier,
    code: Boolean = false
) {
    SelectionContainer(modifier) {
        Text(value, fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
    }
}
