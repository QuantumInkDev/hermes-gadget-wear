package dev.quantumink.hermesgadget.protocol.relay

import java.lang.reflect.Modifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O5 copies, notice forms, events and list items are display-only: no decoder produces an actionable prompt from
 * them and no public API accepts them where a response, cancel, submit or attach is built.
 */
class RelayActionFreeCopiesTest {
    private val recovered: List<OutboxItem> = RelayVectors.forMethod(RelayMethod.OUTBOX_READ)
        .filter {
            it.isSuccess && it.discrepancy == null
        }.flatMap { OutboxPage.decode(it.result).items }

    private fun decodeItem(item: JsonObject): RelayOutcome<Any> {
        val page = JsonObject(
            mapOf(
                "items" to JsonArray(listOf(item)),
                "next_cursor" to JsonNull,
                "gap_generation" to JsonPrimitive(0)
            )
        )
        val reply = JsonObject(
            mapOf(
                "type" to JsonPrimitive("relay.response"),
                "v" to JsonPrimitive(1),
                "id" to JsonPrimitive("r1"),
                "result" to page
            )
        )
        val call =
            RelayCall(
                RelayMethod.OUTBOX_READ,
                JsonObject(emptyMap()),
                RelayCalls.resultDecoder(RelayMethod.OUTBOX_READ)
            )
        return call.interpret(
            CorrelationId.of("r1")!!,
            RelayEnvelope.decodeFrame(RelayVectors.asciiJson(reply))
        )
    }

    private fun taskChangeItem(): JsonObject =
        RelayVectors.forMethod(RelayMethod.OUTBOX_READ).filter {
            it.isSuccess &&
                it.discrepancy == null
        }
            .flatMap { (it.result["items"] as JsonArray).map { item -> item as JsonObject } }
            .first {
                ((it["payload"] as JsonObject)["origin"] as? JsonPrimitive)?.content == "relay"
            }

    @Test fun aRecoveredTaskCopyNeverCarriesAPromptAndAWantedInputCopyIsStillOnlyAFact() {
        val changes = recovered.map {
            it.payload
        }.filterIsInstance<OutboxPayload.TaskChange.Relay>()
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it.task.prompt == null && it.task.withoutPrompt === it.task })
        val wanted = changes.first { it.task.state == TaskState.NEEDS_INPUT }
        assertNotNull(wanted.task.runId) // needs_input is never null-run, even in a copy
        // "Wanted input then": fetch relay.task.get before showing any prompt.
        assertNull(wanted.task.prompt)
        assertEquals(TaskOrigin.RELAY, wanted.origin)
        val copies: List<DisplayOnlyCopy> = recovered.map { it.payload }
        assertEquals(recovered.size, copies.size)
        assertTrue(DisplayOnlyCopy::class.java.isAssignableFrom(OutboxPayload::class.java))
    }

    @Test fun aCopyCarryingAnyActionableMemberIsNotACopyAtAll() {
        val base = taskChangeItem()
        val payload = base["payload"] as JsonObject
        val task = payload["task"] as JsonObject
        val samplePrompt = RelayVectors.forMethod(RelayMethod.TASK_GET).filter {
            it.isSuccess
        }.mapNotNull { (it.result["prompt"] as? JsonObject) }.first()
        val mutations = mapOf(
            "prompt inside the task" to
                JsonObject(
                    base +
                        (
                            "payload" to
                                JsonObject(
                                    payload +
                                        ("task" to JsonObject(task + ("prompt" to samplePrompt)))
                                )
                            )
                ),
            "prompt beside the task" to
                JsonObject(base + ("payload" to JsonObject(payload + ("prompt" to samplePrompt)))),
            "actionable flag" to
                JsonObject(
                    base +
                        ("payload" to JsonObject(payload + ("actionable" to JsonPrimitive(true))))
                ),
            "binding" to
                JsonObject(
                    base +
                        (
                            "payload" to
                                JsonObject(
                                    payload +
                                        (
                                            "binding_id" to
                                                JsonPrimitive(
                                                    "0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11"
                                                )
                                            )
                                )
                            )
                ),
            "controls" to
                JsonObject(
                    base +
                        (
                            "payload" to
                                JsonObject(
                                    payload +
                                        (
                                            "controls" to JsonArray(
                                                listOf(JsonPrimitive("cancel_queued"))
                                            )
                                            )
                                )
                            )
                ),
            "unknown kind" to
                JsonObject(
                    base + ("payload" to JsonObject(payload + ("kind" to JsonPrimitive("prompt"))))
                ),
            "audio" to
                JsonObject(
                    base +
                        (
                            "payload" to
                                JsonObject(
                                    mapOf(
                                        "kind" to JsonPrimitive("final_audio"),
                                        "text" to JsonPrimitive("x")
                                    )
                                )
                            )
                )
        )
        assertTrue(decodeItem(base) is RelayOutcome.Success<*>)
        for ((name, item) in mutations) {
            val outcome = decodeItem(item)
            assertTrue("$name -> $outcome", outcome is RelayOutcome.Invalid)
        }
    }

    @Test fun finalTextCopiesAreTextOnlyWithNoActionMembers() {
        val finals = recovered.map { it.payload }.filterIsInstance<OutboxPayload.FinalText>()
        assertTrue(finals.isNotEmpty())
        val item = RelayVectors.forMethod(RelayMethod.OUTBOX_READ).filter {
            it.isSuccess &&
                it.discrepancy == null
        }
            .flatMap { (it.result["items"] as JsonArray).map { entry -> entry as JsonObject } }
            .first {
                ((it["payload"] as JsonObject)["kind"] as JsonPrimitive).content ==
                    "final_text"
            }
        val payload = item["payload"] as JsonObject
        for (extra in listOf("choices", "prompt", "speak", "audio", "actionable")) {
            val outcome =
                decodeItem(
                    JsonObject(
                        item + ("payload" to JsonObject(payload + (extra to JsonPrimitive(1))))
                    )
                )
            assertTrue(extra, outcome is RelayOutcome.Invalid)
        }
    }

    @Test fun noNoticeOrExpiredOrSettledOrFormEverBecomesActionable() {
        val gets = RelayVectors.forMethod(RelayMethod.TASK_GET).filter {
            it.isSuccess
        }.mapNotNull { Task.decodeGet(it.result).prompt }
        val notices = gets.filter { it.reason == PromptReason.TOO_LARGE }
        assertTrue(notices.isNotEmpty())
        for (prompt in gets.filter { !it.actionable }) assertNull(prompt.asActionable())
        // A server notice that claims to be actionable is a contradiction and not a prompt.
        val noticeJson = RelayVectors.forMethod(RelayMethod.TASK_GET).filter { it.isSuccess }
            .mapNotNull { it.result["prompt"] as? JsonObject }.first {
                (it["reason"] as? JsonPrimitive)?.content ==
                    "too_large"
            }
        for (change in listOf(
            "actionable" to JsonPrimitive(true),
            "reason" to JsonNull,
            "text" to JsonPrimitive("full"),
            "free_text" to JsonPrimitive(true)
        )) {
            val mutated = JsonObject(noticeJson + change)
            val ok = try {
                Prompt.decode(FieldReader.of("prompt", mutated, Prompt.KEYS), PromptSource.TASK_GET)
                true
            } catch (_: DecodeFailure) {
                false
            }
            // The only single change that stays a coherent prompt is none of these: a notice keeps no text/options.
            assertFalse(change.first, ok && change.first != "text")
        }
        val eventPrompts = RelayVectors.forMethod(RelayMethod.EVENTS_READ).filter { it.isSuccess }
            .flatMap {
                EventsPage.decode(it.result).events
            }.mapNotNull { (it.payload as? EventPayload.PromptShown)?.prompt }
        assertTrue(eventPrompts.any { it.actionable })
        assertTrue(eventPrompts.all { it.asActionable() == null })
    }

    @Test fun noPublicBuilderAcceptsACopyAnEventOrAListItemWhereAnActionIsBuilt() {
        val forbidden = setOf(
            OutboxPayload::class.java, OutboxItem::class.java, DisplayOnlyCopy::class.java,
            Prompt::class.java, Task::class.java, TaskPage::class.java, RelayEvent::class.java,
            EventPayload::class.java, ExistingTask::class.java, Receipt::class.java,
            EventsPage::class.java, OutboxPage::class.java
        )
        for (method in RelayCalls::class.java.methods.filter { Modifier.isPublic(it.modifiers) }) {
            for (type in method.parameterTypes) {
                assertFalse(
                    "${method.name} takes ${type.simpleName}",
                    forbidden.any {
                        it.isAssignableFrom(type)
                    }
                )
            }
        }
        // Responding takes an ActionablePrompt only; its constructor is Kotlin-`internal` (module-private at compile
        // time), so the sole public way to obtain one is Prompt.asActionable, covered above.
        val respond = RelayCalls::class.java.methods.single {
            it.name.substringBefore("-") ==
                "respond"
        }
        assertTrue(respond.parameterTypes.contains(ActionablePrompt::class.java))
        // Cancelling takes a CancelTarget, obtainable from a Receipt only for a queued or started turn.
        assertTrue(
            RelayCalls::class.java.methods.single {
                it.name.substringBefore("-") == "cancel"
            }.parameterTypes.contains(CancelTarget::class.java)
        )
    }

    @Test fun nothingOnACopyYieldsAPromptABindingARequestOrACall() {
        val banned =
            listOf(
                Prompt::class.java,
                ActionablePrompt::class.java,
                RelayCall::class.java,
                Binding::class.java,
                BindingRef::class.java,
                ExistingBinding::class.java,
                RequestId::class.java
            )
        for (type in listOf(
            OutboxPayload.FinalText::class.java,
            OutboxPayload.TaskChange.Relay::class.java,
            OutboxPayload.TaskChange.Existing::class.java,
            OutboxItem::class.java
        )) {
            for (method in type.methods.filter { Modifier.isPublic(it.modifiers) }) {
                assertFalse(
                    "${type.simpleName}.${method.name}",
                    banned.any {
                        it.isAssignableFrom(method.returnType)
                    }
                )
            }
        }
    }

    @Test fun anExistingTaskCopyKeepsItsSourceAndNeverNeedsInput() {
        val existing = recovered.map {
            it.payload
        }.filterIsInstance<OutboxPayload.TaskChange.Existing>()
        assertTrue(existing.isNotEmpty())
        assertTrue(
            existing.all {
                it.task.state != TaskState.NEEDS_INPUT &&
                    it.origin == TaskOrigin.EXISTING
            }
        )
        val base = RelayVectors.forMethod(RelayMethod.OUTBOX_READ).filter {
            it.isSuccess &&
                it.discrepancy == null
        }
            .flatMap { (it.result["items"] as JsonArray).map { item -> item as JsonObject } }
            .first {
                ((it["payload"] as JsonObject)["origin"] as? JsonPrimitive)?.content ==
                    "existing"
            }
        val payload = base["payload"] as JsonObject
        val task = payload["task"] as JsonObject
        val needsInput = JsonObject(
            base +
                (
                    "payload" to
                        JsonObject(
                            payload +
                                (
                                    "task" to
                                        JsonObject(task + ("state" to JsonPrimitive("needs_input")))
                                    )
                        )
                    )
        )
        assertTrue(decodeItem(needsInput) is RelayOutcome.Invalid)
        val relayOrigin =
            JsonObject(
                base + ("payload" to JsonObject(payload + ("origin" to JsonPrimitive("relay"))))
            )
        assertTrue(
            "a relay-origin copy must carry a relay task shape",
            decodeItem(relayOrigin) is RelayOutcome.Invalid
        )
    }
}
