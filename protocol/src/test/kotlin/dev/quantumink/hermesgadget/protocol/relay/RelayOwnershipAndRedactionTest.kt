package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A caller collection may return its own backing array from toArray() (Android 10 libcore then keeps it inside an
 * ArrayList). Every list a DTO holds must be an independent unmodifiable copy built by bounded iteration, and no
 * diagnostic may print user text.
 */
class RelayOwnershipAndRedactionTest {
    /** Fails the test if anything bulk-copies it, and would alias [items] if anything did. */
    private class ExposingList<T>(private val items: Array<Any?>) : java.util.AbstractList<T>() {
        override val size: Int get() = items.size

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): T = items[index] as T

        override fun toArray(): Array<Any?> = throw AssertionError("caller toArray() was used")
    }

    private fun <T> exposing(vararg items: T): ExposingList<T> = ExposingList(arrayOf(*items))

    private val receipt = Receipt.decode(
        RelayVectors.forMethod(RelayMethod.TURN_SUBMIT).first {
            it.isSuccess
        }.result
    )
    private val page = ConversationPage.decode(
        RelayVectors.forMethod(RelayMethod.CONVERSATIONS_LIST).first {
            it.isSuccess &&
                it.result["items"].toString().length > 10
        }.result
    )
    private val history = HistoryPage.decode(
        RelayVectors.forMethod(RelayMethod.HISTORY_PAGE).first {
            it.isSuccess &&
                it.result["items"].toString().length > 10
        }.result
    )
    private val tasks = TaskPage.decode(
        RelayVectors.forMethod(RelayMethod.TASKS_LIST).first {
            it.isSuccess &&
                it.result["items"].toString().length > 10
        }.result
    )
    private val outbox = OutboxPage.decode(
        RelayVectors.forMethod(RelayMethod.OUTBOX_READ).first {
            it.isSuccess &&
                it.discrepancy == null &&
                it.result["items"].toString().length > 10
        }.result
    )
    private val events = EventsPage.decode(
        RelayVectors.forMethod(RelayMethod.EVENTS_READ).first {
            it.isSuccess &&
                it.result["events"].toString().length > 10
        }.result
    )
    private val info = Info.decode(
        RelayVectors.forMethod(RelayMethod.INFO).first {
            it.isSuccess &&
                "server_time_ms" in it.result
        }.result
    )

    @Test fun everyListConstructorCopiesWithoutBulkConversionAndSurvivesCallerMutation() {
        val items = Array<Any?>(page.items.size) { page.items[it] }
        val owned = ConversationPage(ExposingList(items), null, emptyList(), null)
        items.fill(null)
        assertEquals(page.items, owned.items)

        val rows = Array<Any?>(history.items.size) { history.items[it] }
        val ownedHistory = HistoryPage(ExposingList(rows), null)
        rows.fill(null)
        assertEquals(history.items, ownedHistory.items)

        assertEquals(tasks.items, TaskPage(exposing(*tasks.items.toTypedArray()), null).items)
        assertEquals(outbox.items, OutboxPage(exposing(*outbox.items.toTypedArray()), 0).items)
        assertEquals(
            events.events,
            EventsPage(
                exposing(*events.events.toTypedArray()),
                events.replayEpoch,
                events.lastSequence,
                false
            ).events
        )
        val ids = exposing(DeliveryId.of("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")!!)
        assertEquals(1, Acknowledged(ids).acknowledged.size)
        assertEquals(
            1,
            RelayCalls.outboxAck(ids).params["delivery_ids"].toString().count { it == '"' } / 2
        )
        assertEquals(
            info.methods,
            Info(
                info.profileId,
                info.grantGeneration,
                info.expiresAtMs,
                info.serverTimeMs,
                exposing(*info.methods.toTypedArray()),
                info.limits
            ).methods
        )
        assertEquals(
            listOf(OutboxPayloadKind.FINAL_TEXT),
            InfoLimits(
                false,
                25,
                16_384,
                null,
                null,
                null,
                null,
                exposing(OutboxPayloadKind.FINAL_TEXT)
            ).outboxPayloads
        )
        val prompt = RelayVectors.forMethod(RelayMethod.TASK_GET).filter {
            it.isSuccess
        }.mapNotNull { Task.decodeGet(it.result).prompt }.first { it.choices.isNotEmpty() }
        val backing = Array<Any?>(prompt.choices.size) { prompt.choices[it] }
        val copy =
            Prompt(
                prompt.promptId, prompt.taskId, prompt.kind, prompt.text,
                ExposingList(
                    backing
                ),
                prompt.generation,
                prompt.expiresAtMs,
                prompt.deadline,
                prompt.actionable,
                prompt.freeText,
                prompt.reason,
                prompt.source
            )
        backing.fill("tampered")
        assertEquals(prompt.choices, copy.choices)
    }

    @Test fun copiesAreUnmodifiableEvenThroughAMutableCast() {
        @Suppress("UNCHECKED_CAST")
        for (list in listOf<List<Any>>(
            page.items,
            history.items,
            tasks.items,
            outbox.items,
            events.events,
            info.methods
        )) {
            try {
                (list as MutableList<Any>).add(list.first())
                throw AssertionError("a DTO list was mutable")
            } catch (_: UnsupportedOperationException) {
            }
        }
    }

    @Test fun anOversizedOrHostileSourceIsRefusedAfterAtMostMaximumPlusOneElements() {
        var touched = 0
        val endless = Iterable {
            object : Iterator<Int> {
                override fun hasNext(): Boolean = true

                override fun next(): Int = ++touched
            }
        }
        try {
            ownedBounded(endless, 50)
            throw AssertionError("an endless source was accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(51, touched)
        assertEquals(50, ownedBounded(List(50) { it }, 50).size)
    }

    @Test fun diagnosticsNeverContainUserTextTitlesPromptsOrQueries() {
        val secrets = mutableSetOf<String>()
        for (message in history.items) secrets += message.text
        for (conversation in page.items) conversation.title?.let { secrets += it }
        for (item in outbox.items) {
            (item.payload as? OutboxPayload.FinalText)?.let {
                secrets +=
                    it.text
            }
        }
        for (case in RelayVectors.forMethod(RelayMethod.TASK_GET).filter { it.isSuccess }) {
            Task.decodeGet(case.result).prompt?.let { prompt ->
                prompt.text?.let { secrets += it }
                secrets +=
                    prompt.choices
            }
        }
        secrets.removeAll { it.length < 4 }
        assertTrue(secrets.size > 3)
        val rendered = StringBuilder()
        rendered.append(
            history
        ).append(history.items).append(page).append(page.items).append(outbox).append(outbox.items)
        rendered.append(tasks).append(tasks.items).append(events).append(events.events).append(info)
        for (case in RelayVectors.forMethod(RelayMethod.TASK_GET).filter { it.isSuccess }) {
            val task = Task.decodeGet(case.result)
            rendered.append(task).append(task.prompt)
            rendered.append(
                RelayEnvelope.decodeFrame(case.wireText)
            ).append(task.prompt?.asActionable())
        }
        for (case in RelayVectors.forMethod(RelayMethod.EXISTING_LIST).filter { it.isSuccess }) {
            rendered.append(
                ExistingConversationPage.decode(case.result)
            ).append(ExistingConversationPage.decode(case.result).items)
        }
        rendered.append(
            PromptAnswer.Text("answer-secret-xyz")
        ).append(PromptAnswer.Choice("choice-secret-xyz"))
        rendered.append(
            RelayCalls.createConversation(
                RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!,
                "title-secret-xyz"
            )
        )
        rendered.append(RelayCalls.conversations(query = "query-secret-xyz"))
        for (secret in secrets) assertFalse("leaked: $secret", rendered.contains(secret))
        for (marker in listOf(
            "answer-secret-xyz",
            "choice-secret-xyz",
            "title-secret-xyz",
            "query-secret-xyz"
        )) {
            assertFalse(marker, rendered.contains(marker))
        }
    }

    @Test fun failuresNameOnlyAFieldPathNeverAValue() {
        val case = RelayVectors.forMethod(RelayMethod.HISTORY_PAGE).first {
            it.isSuccess &&
                it.result["items"].toString().length > 10
        }
        val items = (case.result["items"] as kotlinx.serialization.json.JsonArray).toMutableList()
        val first = items[0] as JsonObject
        items[0] = JsonObject(first + ("role" to JsonPrimitive("secret-bogus-role")))
        val mutated =
            JsonObject(case.result + ("items" to kotlinx.serialization.json.JsonArray(items)))
        val reply = JsonObject(
            mapOf(
                "type" to JsonPrimitive("relay.response"),
                "v" to JsonPrimitive(1),
                "id" to JsonPrimitive("r1"),
                "result" to mutated
            )
        )
        val call =
            RelayCall(
                RelayMethod.HISTORY_PAGE,
                JsonObject(emptyMap()),
                RelayCalls.resultDecoder(RelayMethod.HISTORY_PAGE)
            )
        val outcome = call.interpret(
            CorrelationId.of("r1")!!,
            RelayEnvelope.decodeFrame(RelayVectors.asciiJson(reply))
        ) as RelayOutcome.Invalid
        assertEquals("history.items[0].role", outcome.detail)
        assertFalse(outcome.toString().contains("secret"))
        assertFalse(first["text"].toString().let { outcome.toString().contains(it) })
    }
}
