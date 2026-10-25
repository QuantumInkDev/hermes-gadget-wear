package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHandoffTest {
    private val source = HandoffSource("box:one", url("one"), "Atlas")

    private fun url(id: String) = "wss://$id.example.invalid/gadget"

    private fun user(text: String) = HandoffMessage(HistoryRole.USER, text)

    private fun assistant(text: String) = HandoffMessage(HistoryRole.ASSISTANT, text)

    private fun lines(messages: List<HandoffMessage>) = messages.map { it.role to it.text }

    @Test fun onlyMessagesThePageShowsAreOffered() {
        val stock = PhoneConversationState(
            profile = endpoint("one"),
            connection = ConnectionState(
                conversation = ConversationState(transcript = "Question", reply = "Answer")
            )
        )
        assertEquals(
            listOf(HistoryRole.USER to "Question", HistoryRole.ASSISTANT to "Answer"),
            lines(AgentHandoff.visible(stock))
        )
        assertTrue(
            AgentHandoff.visible(PhoneConversationState(profile = endpoint("one"))).isEmpty()
        )
        val transcript = listOf(
            TranscriptLine("1", HistoryRole.USER, "Earlier", 1),
            TranscriptLine("2", HistoryRole.ASSISTANT, " ", 2),
            TranscriptLine("3", HistoryRole.ASSISTANT, "Later", 3)
        )
        val relay = stock.copy(
            relay = RelayUiState(phase = RelayPhase.SELECTED, transcript = transcript)
        )
        assertEquals(
            listOf(
                HistoryRole.USER to "Question",
                HistoryRole.ASSISTANT to "Answer",
                HistoryRole.USER to "Earlier",
                HistoryRole.ASSISTANT to "Later"
            ),
            lines(AgentHandoff.visible(relay))
        )
        // A Relay transcript that is not on screen is never carried.
        val hidden = relay.copy(relay = relay.relay.copy(phase = RelayPhase.READY))
        assertEquals(2, AgentHandoff.visible(hidden).size)
    }

    @Test fun defaultIsTheLastQuestionAndTheLastReplyOnly() {
        val messages = listOf(user("a"), assistant("b"), user("c"), assistant("d"), assistant("e"))
        assertEquals(setOf(2, 4), AgentHandoff.defaultSelection(messages))
        assertEquals(setOf(0), AgentHandoff.defaultSelection(listOf(user("only"))))
        assertEquals(emptySet<Int>(), AgentHandoff.defaultSelection(emptyList()))
    }

    @Test fun textStartsWithProvenanceAndLabelsEachSelectedMessage() {
        val messages = listOf(user("old"), user(" Why?\n"), assistant("Because."))
        assertEquals(
            "Handed off from Atlas:\n\nUser:\nWhy?\n\nAssistant:\nBecause.",
            AgentHandoff.text("Atlas", messages, setOf(2, 1))
        )
        assertEquals(
            "Handed off from Two words:\n\nUser:\nold",
            AgentHandoff.text(" Two\n words ", messages, setOf(0))
        )
        assertTrue(
            AgentHandoff.text("", messages, setOf(0)).startsWith("Handed off from another profile:")
        )
        assertFalse(AgentHandoff.text("Atlas", messages, setOf(1)).contains("old"))
    }

    @Test fun reviewBoundStillAppliesToHandoffText() {
        val long = listOf(assistant("a".repeat(ShareIntake.MAX_BYTES)))
        assertFalse(ShareIntake.sendable(AgentHandoff.text("Atlas", long, setOf(0))))
        assertTrue(ShareIntake.sendable(AgentHandoff.text("Atlas", listOf(user("hi")), setOf(0))))
    }

    @Test fun targetsExcludeTheSourceByKeyAndEndpoint() {
        val one = page("one", "one", CatalogStatus.AVAILABLE)
        val two = page("two", "two", CatalogStatus.AVAILABLE)
        val sameEndpoint = page("alias", "one", CatalogStatus.AVAILABLE)
        val withdrawn = page("three", "three", CatalogStatus.WITHDRAWN)
        val pages = listOf(one, two, sameEndpoint, withdrawn)
        assertEquals(listOf(two), AgentHandoff.targets(pages, source))
        assertEquals(listOf(one, two, sameEndpoint), AgentHandoff.targets(pages, null))
    }

    @Test fun finalGateRefusesTheSourceConversation() {
        val idle = PhoneConversationState()
        val one = page("one", "one", CatalogStatus.AVAILABLE)
        val two = page("two", "two", CatalogStatus.AVAILABLE)
        assertNull(ShareIntake.accept(one.key, "hi", one to endpoint("one"), idle, source))
        val alias = page("alias", "one", CatalogStatus.AVAILABLE)
        assertNull(ShareIntake.accept(alias.key, "hi", alias to endpoint("one"), idle, source))
        assertSame(
            two,
            ShareIntake.accept(two.key, "hi", two to endpoint("two"), idle, source)?.first
        )
    }

    @Test fun handoffContentIsRedactedFromLogs() {
        assertFalse(user("secret").toString().contains("secret"))
        assertFalse(SharedContent.Text("secret", source).toString().contains("secret"))
        assertFalse(source.toString().contains("Atlas"))
    }

    private fun endpoint(id: String) = EndpointProfile(Endpoint.parse(url(id)), id, "")

    private fun page(id: String, host: String, status: CatalogStatus) = CatalogPage(
        "box",
        "Box",
        CatalogProfile(id, "One", url(host), null),
        status,
        0
    )
}
