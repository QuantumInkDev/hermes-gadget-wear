package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nullability, unions and cross-field rules, exercised by editing REAL captured results one fact at a time. */
class RelaySchemaInvariantTest {
    private fun real(
        method: RelayMethod,
        pick: (RelayVectors.Case) -> Boolean = { true }
    ): JsonObject = RelayVectors.forMethod(method).first {
        it.isSuccess && pick(it)
    }.result

    private fun decodes(method: RelayMethod, result: JsonObject): Boolean = try {
        RelayCalls.resultDecoder(method)(result)
        true
    } catch (_: DecodeFailure) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private val needsInputTask = real(RelayMethod.TASK_GET) {
        (it.result["state"] as JsonPrimitive).content == "needs_input" &&
            (it.result["prompt"] as JsonObject).let { prompt ->
                (prompt["actionable"] as JsonPrimitive).content == "true" &&
                    (prompt["kind"] as JsonPrimitive).content == "clarify"
            }
    }
    private val finishedTask =
        real(RelayMethod.TASK_GET) {
            (it.result["state"] as JsonPrimitive).content ==
                "finished"
        }
    private val queuedFinished = RelayVectors.forMethod(RelayMethod.TASKS_LIST).filter {
        it.isSuccess
    }
        .flatMap { (it.result["items"] as JsonArray).map { item -> item as JsonObject } }
        .first { it["run_id"] is JsonNull }

    private fun JsonObject.with(
        vararg edits: Pair<String, kotlinx.serialization.json.JsonElement?>
    ) = JsonObject(
        LinkedHashMap(this).also { map ->
            edits.forEach { (k, v) ->
                if (v ==
                    null
                ) {
                    map.remove(k)
                } else {
                    map[k] = v
                }
            }
        }
    )

    @Test fun runIdIsNullableWhileQueuedButMissingOrWrongTypedIsInvalidAndNeverNullForNeedsInput() {
        val task = Task.decodeGet(queuedFinished.with("prompt" to null))
        assertNull(task.runId)
        assertTrue(decodes(RelayMethod.TASK_GET, queuedFinished))
        assertTrue(
            "missing run_id",
            !decodes(RelayMethod.TASK_GET, queuedFinished.with("run_id" to null))
        )
        assertTrue(
            "numeric run_id",
            !decodes(RelayMethod.TASK_GET, queuedFinished.with("run_id" to JsonPrimitive(1)))
        )
        assertNotNull(Task.decodeGet(needsInputTask).runId)
        assertTrue(
            "needs_input without a run",
            !decodes(RelayMethod.TASK_GET, needsInputTask.with("run_id" to JsonNull))
        )
        assertTrue(decodes(RelayMethod.TASK_GET, needsInputTask))
    }

    @Test fun stateAndOutcomeFormAClosedUnionWithUnknownStayingUnknown() {
        fun task(state: String, outcome: String?) = finishedTask.with(
            "state" to JsonPrimitive(state),
            "outcome" to (outcome?.let(::JsonPrimitive) ?: JsonNull),
            "prompt" to null
        )
        val valid = listOf(
            "working" to null,
            "unknown" to null,
            "finished" to "completed",
            "finished" to "cancelled",
            "finished" to "interrupted",
            "finished" to "steered",
            "failed" to "failed",
            "failed" to "rejected"
        )
        for ((state, outcome) in valid) {
            assertTrue(
                "$state/$outcome",
                decodes(RelayMethod.TASK_GET, task(state, outcome))
            )
        }
        val invalid = listOf(
            "working" to "completed", "unknown" to "failed", "finished" to null,
            "finished" to "failed",
            "finished" to "rejected", "failed" to null, "failed" to "completed",
            "failed" to "steered",
            "finished" to "success", "success" to null, "needs_input" to null
        )
        for ((state, outcome) in invalid) {
            assertTrue(
                "$state/$outcome",
                !decodes(RelayMethod.TASK_GET, task(state, outcome))
            )
        }
        assertEquals(TaskState.UNKNOWN, Task.decodeGet(task("unknown", null)).state)
    }

    @Test fun aPromptBelongsToExactlyItsOwnNeedsInputTask() {
        val prompt = needsInputTask["prompt"] as JsonObject
        assertTrue(decodes(RelayMethod.TASK_GET, needsInputTask))
        assertTrue(
            "prompt on a finished task",
            !decodes(RelayMethod.TASK_GET, finishedTask.with("prompt" to prompt))
        )
        val otherTask = prompt.with(
            "task_id" to JsonPrimitive("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")
        )
        assertTrue(
            "prompt for another task",
            !decodes(RelayMethod.TASK_GET, needsInputTask.with("prompt" to otherTask))
        )
        assertTrue(
            "null prompt member",
            !decodes(RelayMethod.TASK_GET, needsInputTask.with("prompt" to JsonNull))
        )
    }

    @Test fun promptFlagsAreCoherentOrNotAPrompt() {
        val prompt = needsInputTask["prompt"] as JsonObject
        fun check(vararg edits: Pair<String, kotlinx.serialization.json.JsonElement?>) =
            decodes(RelayMethod.TASK_GET, needsInputTask.with("prompt" to prompt.with(*edits)))
        assertTrue(Task.decodeGet(needsInputTask).prompt!!.actionable)
        assertTrue("actionable with a reason", !check("reason" to JsonPrimitive("expired")))
        assertTrue("not actionable without a reason", !check("actionable" to JsonPrimitive(false)))
        assertTrue(
            "free text on a non-actionable prompt",
            !check(
                "actionable" to JsonPrimitive(false),
                "reason" to JsonPrimitive("settled"),
                "free_text" to JsonPrimitive(true)
            )
        )
        assertTrue("no text outside a too_large notice", !check("text" to JsonNull))
        assertTrue("bounded needs an expiry shape", check("deadline" to JsonPrimitive("bounded")))
        assertTrue("unbounded with an expiry", !check("deadline" to JsonPrimitive("unlimited")))
        assertTrue(
            "approval with free text",
            !check(
                "kind" to JsonPrimitive("approval"),
                "free_text" to JsonPrimitive(true)
            )
        )
        assertTrue(
            "nine choices",
            !check(
                "choices" to JsonArray(
                    List(9) {
                        JsonPrimitive("c$it")
                    }
                )
            )
        )
        assertTrue(
            "choice over 256 bytes",
            !check("choices" to JsonArray(listOf(JsonPrimitive("x".repeat(257)))))
        )
        assertTrue(
            "choice of exactly 256 bytes",
            check("choices" to JsonArray(listOf(JsonPrimitive("x".repeat(256)))))
        )
        assertTrue("text over 4096 bytes", !check("text" to JsonPrimitive("x".repeat(4_097))))
        assertTrue("text of exactly 4096 bytes", check("text" to JsonPrimitive("x".repeat(4_096))))
        assertTrue(
            "multibyte text over the byte cap",
            !check("text" to JsonPrimitive("€".repeat(1_366)))
        )
        assertTrue(
            "multibyte text at the byte cap",
            check("text" to JsonPrimitive("€".repeat(1_365)))
        )
    }

    @Test fun nullableFieldsDistinguishMissingFromAllowedNull() {
        val receipt =
            real(RelayMethod.TURN_SUBMIT) {
                it.result["execution_generation"] is JsonNull &&
                    it.result["outcome"] is JsonNull
            }
        assertTrue(decodes(RelayMethod.TURN_SUBMIT, receipt))
        assertTrue(!decodes(RelayMethod.TURN_SUBMIT, receipt.with("execution_generation" to null)))
        assertTrue(!decodes(RelayMethod.TURN_SUBMIT, receipt.with("outcome" to null)))
        assertTrue(
            !decodes(
                RelayMethod.TURN_SUBMIT,
                receipt.with("execution_generation" to JsonPrimitive("1"))
            )
        )
        val conversation = real(RelayMethod.CONVERSATION_CREATE) { it.result["title"] is JsonNull }
        assertTrue(decodes(RelayMethod.CONVERSATION_CREATE, conversation))
        assertTrue(!decodes(RelayMethod.CONVERSATION_CREATE, conversation.with("title" to null)))
        assertTrue(
            !decodes(
                RelayMethod.CONVERSATION_CREATE,
                conversation.with("revision" to JsonPrimitive(0))
            )
        )
        assertTrue(
            !decodes(
                RelayMethod.CONVERSATION_CREATE,
                conversation.with("updated_at_ms" to JsonPrimitive(-1))
            )
        )
        val page = real(RelayMethod.HISTORY_PAGE)
        assertTrue(!decodes(RelayMethod.HISTORY_PAGE, page.with("next_cursor" to null)))
        assertTrue(
            !decodes(RelayMethod.HISTORY_PAGE, page.with("next_cursor" to JsonPrimitive("")))
        )
        assertTrue(
            !decodes(
                RelayMethod.HISTORY_PAGE,
                page.with("next_cursor" to JsonPrimitive("x".repeat(513)))
            )
        )
    }

    @Test fun relayAndExistingBindingsDifferExactlyWhereTheSourceDiffers() {
        assertNotNull(Binding.decode(real(RelayMethod.CONVERSATION_ATTACH)))
        assertTrue(
            !decodes(
                RelayMethod.CONVERSATION_ATTACH,
                real(RelayMethod.CONVERSATION_ATTACH).with("execution_generation" to JsonNull)
            )
        )
        val nulls =
            real(RelayMethod.EXISTING_ATTACH) {
                it.result["execution_generation"] is JsonNull &&
                    it.result["revision"] is JsonNull
            }
        assertEquals(null, ExistingBinding.decode(nulls).executionGeneration)
        assertTrue(
            !decodes(RelayMethod.EXISTING_ATTACH, nulls.with("execution_generation" to null))
        )
        val delegated =
            real(RelayMethod.EXISTING_ATTACH) { (it.result["controls"] as JsonArray).isNotEmpty() }
        assertEquals(
            listOf(ExistingControl.CANCEL_QUEUED),
            ExistingBinding.decode(delegated).controls
        )
        assertTrue(
            "read-only binding with a control",
            !decodes(
                RelayMethod.EXISTING_ATTACH,
                delegated.with(
                    "read_only" to JsonPrimitive(true),
                    "can_continue" to JsonPrimitive(false)
                )
            )
        )
        assertTrue(
            !decodes(
                RelayMethod.EXISTING_ATTACH,
                delegated.with("origin" to JsonPrimitive("relay"))
            )
        )
    }

    @Test fun eventsAreOrderedConsistentAndTypedByTheirKind() {
        val page = real(RelayMethod.EVENTS_READ) { (it.result["events"] as JsonArray).size >= 2 }
        assertTrue(decodes(RelayMethod.EVENTS_READ, page))
        val events = (page["events"] as JsonArray).map { it as JsonObject }
        assertTrue(
            "reordered",
            !decodes(RelayMethod.EVENTS_READ, page.with("events" to JsonArray(events.reversed())))
        )
        assertTrue(
            "stale last_sequence",
            !decodes(RelayMethod.EVENTS_READ, page.with("last_sequence" to JsonPrimitive(0)))
        )
        assertTrue(
            "foreign epoch",
            !decodes(
                RelayMethod.EVENTS_READ,
                page.with("replay_epoch" to JsonPrimitive("another-epoch"))
            )
        )
        val gap = events.firstOrNull { (it["kind"] as JsonPrimitive).content == "gap" }
        if (gap != null) {
            assertTrue(
                "a gap carries no content",
                !decodes(
                    RelayMethod.EVENTS_READ,
                    page.with(
                        "events" to
                            JsonArray(
                                listOf(
                                    gap.with(
                                        "payload" to
                                            JsonObject(
                                                mapOf(
                                                    "snapshot_required" to JsonPrimitive(true),
                                                    "text" to JsonPrimitive("x")
                                                )
                                            )
                                    )
                                )
                            )
                    )
                )
            )
            assertTrue(
                "sequence zero",
                !decodes(
                    RelayMethod.EVENTS_READ,
                    page.with(
                        "events" to JsonArray(listOf(gap.with("sequence" to JsonPrimitive(0)))),
                        "last_sequence" to JsonPrimitive(0)
                    )
                )
            )
        }
        val kinds = RelayVectors.forMethod(RelayMethod.EVENTS_READ).filter { it.isSuccess }
            .flatMap { EventsPage.decode(it.result).events }.map { it.payload.kind }.toSet()
        assertTrue(
            kinds.containsAll(
                setOf(
                    EventKind.TASK,
                    EventKind.PROMPT,
                    EventKind.GAP,
                    EventKind.MESSAGE,
                    EventKind.CONVERSATION,
                    EventKind.CHANGED
                )
            )
        )
        val tombstones = RelayVectors.forMethod(RelayMethod.EVENTS_READ).filter { it.isSuccess }
            .flatMap {
                EventsPage.decode(it.result).events
            }.map {
                it.payload
            }.filterIsInstance<EventPayload.ConversationChanged>().filter { it.deleted }
        assertTrue(tombstones.all { it.conversation.title == null && !it.conversation.canContinue })
    }

    @Test fun historySegmentsAssembleByIndexAndCountOnly() {
        val message = HistoryMessage.decode(
            FieldReader.of(
                "history",
                (
                    real(RelayMethod.HISTORY_PAGE) {
                        it.result["items"].toString().length >
                            10
                    }["items"] as JsonArray
                    )[0],
                HistoryMessage.KEYS
            )
        )
        val wire = message.toWire()
        fun decode(vararg edits: Pair<String, kotlinx.serialization.json.JsonElement?>) = try {
            HistoryMessage.decode(FieldReader.of("history", wire.with(*edits), HistoryMessage.KEYS))
            true
        } catch (_: DecodeFailure) {
            false
        }
        assertTrue(decode())
        assertTrue(
            "index equals count",
            !decode("segment_index" to JsonPrimitive(message.segmentCount))
        )
        assertTrue(
            "zero count",
            !decode(
                "segment_count" to JsonPrimitive(0),
                "segment_index" to JsonPrimitive(0)
            )
        )
        assertTrue(
            "attachments are not part of this increment",
            !decode("attachment_ids" to JsonArray(listOf(JsonPrimitive("a"))))
        )
        assertTrue(
            "text at the 32 KiB byte cap",
            decode("text" to JsonPrimitive("x".repeat(32_768)))
        )
        assertTrue(
            "text over the 32 KiB byte cap",
            !decode("text" to JsonPrimitive("x".repeat(32_769)))
        )
        assertTrue(
            "multibyte text over the cap",
            !decode("text" to JsonPrimitive("€".repeat(10_923)))
        )
        assertTrue("multibyte text at the cap", decode("text" to JsonPrimitive("€".repeat(10_922))))
        assertTrue("unknown role", !decode("role" to JsonPrimitive("system")))
    }
}
