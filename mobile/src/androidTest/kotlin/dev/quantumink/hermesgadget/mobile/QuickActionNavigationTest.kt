package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.Prompt
import dev.quantumink.hermesgadget.protocol.QuickAction
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickActionNavigationTest {
    private val action =
        QuickAction(UUID.randomUUID().toString(), "one:bot", "Review", "Explain {{issue}}")
    private fun page(source: String = "one", status: CatalogStatus = CatalogStatus.AVAILABLE) =
        CatalogPage(
            source,
            "Same box",
            CatalogProfile("bot", "Same name", "wss://$source.example.invalid/gadget", null),
            status,
            0
        )

    @Test fun latestActionAndExactCurrentProfileRequiredBeforeTransfer() {
        val one = page(status = CatalogStatus.OFFLINE)
        val profile = EndpointProfile(Endpoint.parse(one.profile.gadgetUrl!!), "Same name", "")
        val state = PhoneConversationState()
        assertEquals(
            "Explain error",
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                listOf(action),
                one to profile,
                state
            )
        )
        assertNull(
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                emptyList(),
                one to profile,
                state
            )
        )
        assertNull(
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                listOf(action.copy(template = "Changed")),
                one to profile,
                state
            )
        )
        assertNull(
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                listOf(action),
                page("two") to profile,
                state
            )
        )
        assertNull(
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                listOf(action),
                page(status = CatalogStatus.WITHDRAWN) to profile,
                state
            )
        )
        assertNull(
            QuickActionNavigation.prompt(
                action,
                mapOf("issue" to "error"),
                listOf(action),
                null,
                state
            )
        )
    }

    @Test fun busyApprovalsPairingRecordingAndPlaybackAllBlockEvenSameProfile() {
        val one = page()
        val profile = EndpointProfile(Endpoint.parse(one.profile.gadgetUrl!!), "Same name", "")
        val base = PhoneConversationState(visible = true, page = one, profile = profile)
        val states = listOf(
            base.copy(sending = true),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(
                        prompt = Prompt("approval", "Approval", "Keep", 0, null)
                    )
                )
            ),
            base.copy(speaking = true),
            base.copy(connection = ConnectionState(status = ConnectionStatus.PAIRING)),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(mode = ConversationMode.THINKING)
                )
            ),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(mode = ConversationMode.LISTENING)
                )
            )
        )
        states.forEach {
            assertNull(
                QuickActionNavigation.prompt(
                    action,
                    mapOf("issue" to "error"),
                    listOf(action),
                    one to profile,
                    it
                )
            )
        }
    }
}
