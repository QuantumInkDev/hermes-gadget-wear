package dev.quantumink.hermesgadget.protocol

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {
    private fun action(key: String? = "one:bot", template: String = "Explain {{issue}}") =
        QuickAction(UUID.randomUUID().toString(), key, "Help", template)
    private fun page(source: String, status: CatalogStatus) = CatalogPage(
        source,
        "Same box",
        CatalogProfile("bot", "Same name", "wss://$source.example.invalid/gadget", null),
        status,
        0
    )

    @Test fun templatesRequireProfileAndInputsAndSubstituteOnlyOnce() {
        val unbound = action(null)
        assertTrue(runCatching { QuickActions.expand(unbound, mapOf("issue" to "test")) }.isFailure)
        val bound = action(template = "Explain {{issue}} and {{issue}} for {{context}}")
        assertEquals(listOf("issue", "context"), QuickActions.inputs(bound.template))
        assertTrue(runCatching { QuickActions.expand(bound, mapOf("issue" to "x")) }.isFailure)
        assertTrue(
            runCatching {
                QuickActions.expand(bound, mapOf("issue" to " ", "context" to "c"))
            }.isFailure
        )
        assertEquals(
            "Explain $1\\{{context}} and $1\\{{context}} for c",
            QuickActions.expand(bound, mapOf("issue" to "$1\\{{context}}", "context" to "c"))
        )
        assertTrue(runCatching { action(template = "Bad {{missing") }.isFailure)
        assertTrue(runCatching { action(template = "Bad {{a-b}}") }.isFailure)
        assertTrue(
            runCatching {
                action(template = (1..9).joinToString { "{{input_$it}}" })
            }.isFailure
        )
        assertTrue(
            runCatching {
                QuickActions.expand(action(template = "{{x}}{{x}}"), mapOf("x" to "x".repeat(4097)))
            }.isFailure
        )
    }

    @Test fun codecEnforcesSchemaBoundsAndUniqueOpaqueIds() {
        val actions = listOf(action(), action(null, "Hello"))
        val decoded = QuickActions.decode(QuickActions.encode(actions))
        assertEquals(actions, decoded)
        assertTrue(runCatching { QuickActions.encode(listOf(actions[0], actions[0])) }.isFailure)
        assertTrue(runCatching { QuickActions.encode((1..33).map { action() }) }.isFailure)
        assertTrue(
            runCatching {
                QuickActions.decode(" ".repeat(QuickActions.MAX_BYTES + 1))
            }.isFailure
        )
        assertTrue(runCatching { QuickAction("../bad", "one:bot", "Title", "Hello") }.isFailure)
        assertTrue(runCatching { action("wss://secret") }.isFailure)
        assertTrue(runCatching { action(template = "x".repeat(4097)) }.isFailure)
        assertTrue(
            runCatching {
                QuickActions.decode(
                    QuickActions.encode(actions).replace("\"version\":1", "\"version\":2")
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                QuickActions.decode(
                    QuickActions.encode(
                        actions
                    ).replace("\"title\":\"Help\"", "\"title\":\"Help\",\"token\":\"forbidden\"")
                )
            }.isFailure
        )
        assertEquals("QuickAction(content=redacted)", actions[0].toString())
    }

    @Test fun exactKeySurvivesDuplicateNamesAndOfflineButNeverSubstitutesDeletedOrWithdrawn() {
        val one = page("one", CatalogStatus.OFFLINE)
        val two = page("two", CatalogStatus.AVAILABLE)
        assertSame(one, QuickActions.resolve(action(), listOf(two, one)))
        assertNull(QuickActions.resolve(action(), listOf(two)))
        assertNull(
            QuickActions.resolve(action(), listOf(page("one", CatalogStatus.WITHDRAWN), two))
        )
        assertNull(QuickActions.resolve(action(), listOf(one, one)))
        assertNull(QuickActions.resolve(action(null), listOf(one)))
    }

    @Test fun seedsAreUsefulEditableAndUnbound() {
        val seeds = QuickActions.seeds()
        assertEquals(
            listOf(
                "Morning briefing",
                "Check builds",
                "Capture a thought",
                "Summarize shared content",
                "Troubleshoot an issue",
                "Follow up a task"
            ),
            seeds.map {
                it.title
            }
        )
        assertEquals(6, seeds.map { it.id }.distinct().size)
        seeds.forEach {
            assertNull(it.profileKey)
            assertTrue(QuickActions.inputs(it.template).isNotEmpty())
            assertTrue(runCatching { QuickActions.expand(it, emptyMap()) }.isFailure)
        }
    }
}
