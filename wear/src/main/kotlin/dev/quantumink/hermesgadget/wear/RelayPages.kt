package dev.quantumink.hermesgadget.wear

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.session.RelayNotice
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayReason
import dev.quantumink.hermesgadget.protocol.relay.session.TranscriptLine

/*
 * Relay history and inbox on the watch: secondary pages behind one entry on the conversation page, so the portrait
 * page and the horizontal profile swipes stay exactly as they are. Every control is a deliberate user action.
 */

private val LIVE = setOf(RelayPhase.READY, RelayPhase.ATTACHING, RelayPhase.SELECTED)

private fun offline(watch: WatchState) =
    watch.connection.status in setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)

private fun stamp(at: Long): String = if (at <= 0) {
    ""
} else {
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()
}

/** Two-line list row (title and a muted detail) with one spoken label. */
@Composable
private fun Row2(title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick,
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = if (detail.isBlank()) title else "$title, $detail"
        },
        enabled = enabled
    ) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text(title, maxLines = 2, style = MaterialTheme.typography.labelMedium)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun RelayHistoryPage(
    watch: WatchState,
    service: GadgetService,
    connect: () -> Unit,
    open: () -> Unit,
    inbox: () -> Unit,
    back: () -> Unit
) {
    val ui = watch.relay
    val tls = watch.profile?.endpoint?.isTls == true
    RoundPage {
        item { Heading("Conversations") }
        watch.profile?.let { item { Body(it.label, muted = true) } }
        if (!tls) {
            item { Body(WatchRelayModel.reasonText(RelayReason.TLS_REQUIRED)) }
        } else {
            item {
                Toggle("Relay for this profile", ui.enabled) { service.relayEnable(!ui.enabled) }
            }
        }
        WatchRelayModel.phaseText(ui).takeIf {
            tls && it.isNotBlank()
        }?.let { item { Body(it, muted = true) } }
        if (ui.notice != RelayNotice.NONE) item { Body(WatchRelayModel.noticeText(ui.notice)) }
        when (ui.phase) {
            RelayPhase.STORAGE_PROBLEM -> item {
                Secondary("Retry saved state", { service.relay(ui) { it.retryStorage() } })
            }
            RelayPhase.REVIEW_REQUIRED -> {
                item {
                    Body(
                        "Saved ${ui.pinnedDigest.orEmpty()} · now ${ui.observedDigest ?: "?"}",
                        muted = true
                    )
                }
                item {
                    Primary("Review and continue", enabled = ui.observedDigest != null) {
                        service.relay(ui) { it.acceptReview() }
                    }
                }
            }
            in LIVE -> {
                ui.selected?.let {
                    item { Primary("Open " + (it.title ?: "conversation"), onClick = open) }
                }
                ui.resumable?.let { last ->
                    item {
                        Primary("Resume " + (last.title ?: "last conversation")) {
                            service.relay(ui) { it.select(last.id) }
                            open()
                        }
                    }
                }
                if (ui.pendingCreate != null) {
                    item {
                        Body(WatchRelayModel.noticeText(RelayNotice.CREATE_PENDING), muted = true)
                    }
                    item { Secondary("Discard new", { service.relay(ui) { it.discardCreate() } }) }
                } else {
                    item {
                        Secondary("New conversation", {
                            service.relay(ui) { it.create(null) }
                            open()
                        })
                    }
                }
                ui.conversations.forEach { conversation ->
                    item {
                        Row2(
                            conversation.title ?: "Untitled",
                            if (conversation.canContinue) {
                                stamp(
                                    conversation.updatedAtMs
                                )
                            } else {
                                "Read only"
                            },
                            enabled = conversation.canContinue && !ui.busy
                        ) {
                            service.relay(ui) { it.select(conversation.id) }
                            open()
                        }
                    }
                }
                if (ui.listing) {
                    item { Body("Loading…", muted = true) }
                } else if (ui.conversations.isEmpty()) {
                    item { Body("No conversations yet.", muted = true) }
                }
                if (ui.hasMore) {
                    item {
                        Secondary("More", { service.relay(ui) { it.loadMoreConversations() } })
                    }
                }
                item {
                    Secondary("Refresh list", {
                        service.relay(ui) { it.refreshConversations() }
                    }, !ui.listing)
                }
            }
            else -> if (ui.enabled && offline(watch)) {
                item { Primary("Connect", onClick = connect) }
            }
        }
        item { Secondary("Inbox", inbox) }
        item { Secondary("Back", back) }
    }
}

@Composable
private fun Message(line: TranscriptLine) {
    val user = line.role == HistoryRole.USER
    Text(
        (if (user) "You: " else "") + line.text.take(4000),
        color = if (user) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = (if (user) "You said: " else "Hermes: ") + line.text.take(4000)
        }
    )
}

@Composable
internal fun RelayThreadPage(
    watch: WatchState,
    service: GadgetService,
    connect: () -> Unit,
    reply: () -> Unit,
    back: () -> Unit
) {
    val ui = watch.relay
    val lines = ui.transcript.takeLast(WatchRelayModel.MAX_TRANSCRIPT)
    val open = ui.turns.filterNot { WatchRelayModel.settled(it.status) }
    val status = listOfNotNull(
        WatchRelayModel.phaseText(ui).takeIf { it.isNotBlank() && ui.phase != RelayPhase.READY },
        if (ui.pendingCreate != null) "Creating the conversation…" else null,
        WatchRelayModel.noticeText(ui.notice).takeIf { it.isNotBlank() },
        if (ui.historyIncomplete) {
            WatchRelayModel.noticeText(
                RelayNotice.HISTORY_INCOMPLETE
            )
        } else {
            null
        }
    )
    val state = rememberTransformingLazyColumnState()
    // Newest reply in view as it arrives: header items come first, then the bounded transcript.
    LaunchedEffect(lines.lastOrNull()?.id) {
        if (lines.isNotEmpty()) state.animateScrollToItem(1 + status.size + lines.size - 1)
    }
    RoundPage(state = state) {
        item { Heading(ui.selected?.title ?: "Conversation") }
        status.forEach { item { Body(it, muted = true) } }
        lines.forEach { line -> item { Message(line) } }
        open.forEach { turn ->
            item {
                Body(
                    "You: ${turn.preview} · ${WatchRelayModel.turnText(turn.status)}",
                    muted = true
                )
            }
            if (turn.canCheck) {
                item { Secondary("Check", { service.relay(ui) { it.check(turn.requestId) } }) }
            }
            if (turn.canResend) {
                item {
                    Secondary("Send again", { service.relay(ui) { it.resend(turn.requestId) } })
                }
            }
        }
        if (ui.turns.size > open.size) {
            item { Secondary("Clear finished", { service.relay(ui) { it.clearSettled() } }) }
        }
        if (offline(watch)) item { Primary("Connect", onClick = connect) }
        item { Primary("Continue", enabled = ui.canSend, onClick = reply) }
        item {
            Secondary("Leave conversation", {
                service.relay(ui) { it.leave() }
                back()
            })
        }
        item { Secondary("Back", back) }
    }
}

@Composable
internal fun RelayReplyPage(
    watch: WatchState,
    service: GadgetService,
    draft: String,
    changed: (String) -> Unit,
    back: () -> Unit
) {
    val ui = watch.relay
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    RoundPage {
        item { Heading(ui.selected?.title ?: "Continue") }
        // The system keyboard offers dictation; the text goes to this Relay conversation only.
        item { Input("Message", draft, { changed(it.take(8192)) }, enabled = !pending) }
        if (!ui.canSend) {
            item {
                Body(
                    WatchRelayModel.phaseText(ui).ifBlank {
                        "Open the conversation first."
                    },
                    muted = true
                )
            }
        }
        if (failed) {
            item {
                Body(
                    WatchRelayModel.noticeText(ui.notice).ifBlank {
                        "Message was not sent. Draft kept."
                    }
                )
            }
        }
        item {
            Primary(
                if (pending) "Sending" else "Send",
                enabled = ui.canSend && !pending && draft.isNotBlank()
            ) {
                pending = true
                failed = false
                service.relaySend(ui, draft) { accepted ->
                    pending = false
                    failed = !accepted
                    if (accepted) {
                        changed("")
                        back()
                    }
                }
            }
        }
        item { Secondary("Back", back, enabled = !pending) }
    }
}

@Composable
internal fun RelayInboxPage(
    watch: WatchState,
    service: GadgetService,
    selected: String?,
    select: (String?) -> Unit,
    opened: () -> Unit,
    back: () -> Unit
) {
    LaunchedEffect(Unit) { service.loadInbox() }
    val lines = WatchRelayModel.lines(watch.inbox)
    var notice by remember(selected) { mutableStateOf("") }
    RoundPage {
        if (selected != null) {
            val line = lines.firstOrNull { it.handle == selected }
            if (line == null) {
                item { Heading("Inbox") }
                item {
                    Body(
                        if (watch.inbox ==
                            null
                        ) {
                            "Loading…"
                        } else {
                            "This item is no longer here."
                        }
                    )
                }
            } else {
                item { Heading(WatchRelayModel.stateText(line.state)) }
                item { Body(line.profile, muted = true) }
                item { Body(stamp(line.atMs), muted = true) }
                if (line.canOpen) {
                    item {
                        Primary("Open conversation") {
                            service.openInbox(line.handle) { ok ->
                                if (ok) {
                                    opened()
                                } else {
                                    notice =
                                        "Unavailable. Refresh or open its profile."
                                }
                            }
                        }
                    }
                } else {
                    item { Body("Read only on this watch.", muted = true) }
                }
            }
            if (notice.isNotBlank()) item { Body(notice) }
            item { Secondary("Back", { select(null) }) }
        } else {
            item { Heading("Inbox") }
            item { Body(watch.inboxStatus.ifBlank { "Retained on this watch." }, muted = true) }
            lines.forEach { line ->
                item {
                    Row2(
                        (if (line.unread) "• " else "") + WatchRelayModel.stateText(line.state),
                        listOf(line.profile, stamp(line.atMs)).filter {
                            it.isNotBlank()
                        }.joinToString(" · ")
                    ) { select(line.handle) }
                }
            }
            if (lines.isEmpty()) {
                item {
                    Body("No tasks yet. Turn on Relay for a profile and connect.", muted = true)
                }
            }
            item { Secondary("Refresh", service::refreshInbox) }
            watch.inbox?.profiles?.forEach { profile ->
                val on = profile.completionAlerts && profile.inputAlerts
                item {
                    Toggle("Notify · ${profile.label}", on) { service.inboxAlerts(profile, !on) }
                }
            }
            item { Secondary("Back", back) }
        }
    }
}
