package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.MaterialTheme
import dev.quantumink.hermesgadget.client.ReplyPlatformActions
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.ReplyBlock
import dev.quantumink.hermesgadget.protocol.ReplyPresentation

/** State lives above lazy items, so scrolling a checkbox off screen does not forget it. */
internal class WatchReplyState(text: String) {
    val parsed = ReplyPresentation.parse(text)
    var plain by mutableStateOf(false)
    var expanded by mutableStateOf(-1)
    var notice by mutableStateOf("")
    val checks = mutableStateMapOf<Pair<Int, Int>, Boolean>()
}

@Composable
internal fun rememberWatchReply(
    conversation: ConversationState,
    profileKey: String
): WatchReplyState =
    remember(profileKey, conversation.replyRevision) { WatchReplyState(conversation.reply) }

/** Each chunk is a separate lazy item; even a maximum-length plain response remains scrollable. */
internal fun TransformingLazyColumnScope.structuredReply(
    conversation: ConversationState,
    local: WatchReplyState,
    enabled: Boolean,
    platformActions: ReplyPlatformActions? = null
) {
    val active = enabled && !conversation.busy && !conversation.replyChanging &&
        conversation.prompt == null
    item {
        Secondary(if (local.plain) "Show cards" else "Full text", { local.plain = !local.plain })
    }
    item {
        val actions = platformActions ?: ReplyPlatformActions(LocalContext.current)
        Secondary("Copy full response", {
            local.notice = if (actions.copyResponse(conversation.reply)) {
                "Copied response"
            } else {
                "Clipboard unavailable. Response remains readable."
            }
        }, enabled = active)
    }
    if (local.notice.isNotEmpty()) item { Body(local.notice) }
    val parsed = local.parsed
    if (local.plain || parsed !is ReplyPresentation.Accepted) {
        replyText(conversation.reply)
    } else {
        parsed.blocks.forEachIndexed { index, block ->
            when (block) {
                is ReplyBlock.Text -> replyText(block.source)
                else -> {
                    val heading = when (block) {
                        is ReplyBlock.Code -> "Code" + block.language?.let { " · $it" }.orEmpty()
                        is ReplyBlock.Link -> "Link · " + block.action.host
                        is ReplyBlock.Address -> "Address"
                        is ReplyBlock.Checklist -> "Checklist · ${block.items.size} items"
                        is ReplyBlock.Text -> "Response"
                    }
                    item {
                        ReplyCard {
                            Body(heading)
                            if (block is ReplyBlock.Link) Body(block.label)
                            Secondary(
                                if (local.expanded ==
                                    index
                                ) {
                                    "Collapse $heading"
                                } else {
                                    "Expand $heading"
                                },
                                { local.expanded = if (local.expanded == index) -1 else index }
                            )
                        }
                    }
                    if (local.expanded == index) {
                        when (block) {
                            is ReplyBlock.Code -> {
                                replyText(block.code)
                                item {
                                    val actions =
                                        platformActions
                                            ?: ReplyPlatformActions(LocalContext.current)
                                    Secondary("Copy code", {
                                        local.notice =
                                            if (actions.copyCode(block.action)) {
                                                "Copied code"
                                            } else {
                                                "Clipboard unavailable. Code remains readable."
                                            }
                                    }, active)
                                }
                            }
                            is ReplyBlock.Link -> {
                                replyText(
                                    "HTTPS host: " + block.action.host + "\n" + block.action.url
                                )
                                item {
                                    val actions =
                                        platformActions
                                            ?: ReplyPlatformActions(LocalContext.current)
                                    Secondary("Open link", {
                                        local.notice =
                                            if (actions.open(block.action)) {
                                                "Choose an app"
                                            } else {
                                                "No app can open this link. Copy the full response."
                                            }
                                    }, active)
                                }
                            }
                            is ReplyBlock.Address -> {
                                replyText(block.address)
                                item {
                                    val actions =
                                        platformActions
                                            ?: ReplyPlatformActions(LocalContext.current)
                                    Secondary("Open address", {
                                        local.notice =
                                            if (actions.open(block.action)) {
                                                "Choose an app"
                                            } else {
                                                "No app can open this address. " +
                                                    "Copy the full response."
                                            }
                                    }, active)
                                }
                            }
                            is ReplyBlock.Checklist -> {
                                item { Body("Checks stay on this screen only.", muted = true) }
                                block.items.forEach { check ->
                                    item {
                                        val id = check.blockIndex to check.itemIndex
                                        val checked = local.checks[id] ?: check.initiallyChecked
                                        androidx.wear.compose.material3.FilledTonalButton(
                                            onClick = { local.checks[id] = !checked },
                                            modifier = Modifier.fillMaxWidth().semantics {
                                                role = Role.Checkbox
                                                contentDescription = "Local check: " + check.text
                                                toggleableState =
                                                    if (checked) {
                                                        ToggleableState.On
                                                    } else {
                                                        ToggleableState.Off
                                                    }
                                                stateDescription =
                                                    if (checked) "Checked" else "Not checked"
                                            },
                                            enabled = active
                                        ) { Body((if (checked) "✓ " else "○ ") + check.text) }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
    if (!active) item { Body("Reply actions paused while conversation changes.", muted = true) }
    item { Body("Open on phone is unavailable on this connection.", muted = true) }
}

private fun TransformingLazyColumnScope.replyText(value: String) {
    // Preserve every character without splitting a Unicode surrogate pair across native Text nodes.
    var start = 0
    while (start < value.length) {
        var end = (start + 400).coerceAtMost(value.length)
        if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) {
            end--
        }
        val chunk = value.substring(start, end)
        item { SelectionContainer { Body(chunk) } }
        start = end
    }
}

@Composable
private fun ReplyCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.surfaceContainer,
            RoundedCornerShape(16.dp)
        ).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}
