package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase

/** The conversation a handoff came from. Its page, and any page on its endpoint, is never offered as the target. */
internal data class HandoffSource(val key: String, val url: String, val label: String) {
    override fun toString(): String = "HandoffSource(key=$key)"
}

/** One message the conversation page shows, captured when the owner opens Hand off. */
internal class HandoffMessage(val role: HistoryRole, val text: String) {
    override fun toString(): String = "HandoffMessage(role=$role, content=redacted)"
}

/** Explicit handoff: only messages the owner picks, as an editable draft for another profile. No history moves. */
internal object AgentHandoff {
    /** What the page shows, in page order: the stock voice transcript and reply, then an open Relay conversation. */
    fun visible(state: PhoneConversationState): List<HandoffMessage> {
        val conversation = state.connection.conversation
        val relay = state.relay.takeIf {
            state.profile?.endpoint?.isTls == true &&
                it.phase in setOf(RelayPhase.ATTACHING, RelayPhase.SELECTED)
        }?.transcript.orEmpty()
        return buildList {
            if (conversation.transcript.isNotBlank()) {
                add(HandoffMessage(HistoryRole.USER, conversation.transcript))
            }
            if (conversation.reply.isNotBlank()) {
                add(HandoffMessage(HistoryRole.ASSISTANT, conversation.reply))
            }
            relay.filter { it.text.isNotBlank() }.forEach { add(HandoffMessage(it.role, it.text)) }
        }
    }

    /** The last question and the last reply; the owner adds anything else deliberately. */
    fun defaultSelection(messages: List<HandoffMessage>): Set<Int> = setOfNotNull(
        messages.indexOfLast { it.role == HistoryRole.USER }.takeIf { it >= 0 },
        messages.indexOfLast { it.role == HistoryRole.ASSISTANT }.takeIf { it >= 0 }
    )

    /** Provenance header, then each selected message in page order, labelled by role. Not clipped: the review bounds it. */
    fun text(source: String, messages: List<HandoffMessage>, selected: Set<Int>): String {
        val name = source.trim().replace(Regex("\\s+"), " ").ifEmpty { "another profile" }
        return messages.filterIndexed { index, _ -> index in selected }
            .joinToString("\n\n", "Handed off from $name:\n\n") {
                (if (it.role == HistoryRole.USER) "User" else "Assistant") + ":\n" + it.text.trim()
            }
    }

    fun distinct(source: HandoffSource, page: CatalogPage): Boolean =
        page.key != source.key && page.profile.gadgetUrl != source.url

    /** Ready recipients; a handoff excludes its source conversation by catalog key and by endpoint. */
    fun targets(pages: List<CatalogPage>, source: HandoffSource?): List<CatalogPage> =
        pages.filter {
            it.status != CatalogStatus.WITHDRAWN && it.profile.gadgetUrl != null &&
                (source == null || distinct(source, it))
        }
}
