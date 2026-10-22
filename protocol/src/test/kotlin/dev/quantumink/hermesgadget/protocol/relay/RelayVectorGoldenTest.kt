package dev.quantumink.hermesgadget.protocol.relay

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden behaviour against frames recorded from the ACCEPTED SDK on the real registered adapter, authority and
 * SQLite: the DTO layer must decode what the server really sent and re-encode it unchanged, and the request side
 * must express what the server really accepted. Nothing here mirrors a hand-written DTO.
 */
class RelayVectorGoldenTest {
    private val cases = RelayVectors.all

    @Test fun everyCapturedFrameIsTheExactWireTheServerSent() {
        assertTrue(cases.size > 200)
        for (case in cases) {
            assertEquals(case.id, case.wireBytes, case.wireText.toByteArray(Charsets.UTF_8).size)
            assertTrue(case.id, case.wireBytes <= RelayLimits.FRAME_BYTES)
        }
    }

    @Test fun everyCapturedSuccessDecodesToItsTypedDtoAndEncodesBackExactly() {
        var checked = 0
        for (case in cases.filter { it.isSuccess && it.discrepancy == null }) {
            val method = RelayMethod.fromWire(case.method) ?: continue
            val frame = RelayEnvelope.decodeFrame(case.wireText)
            assertTrue("${case.id}: $frame", frame is RelayFrame.Result)
            val id = CorrelationId.of(case.responseId!!)!!
            val decoded = RelayCalls.resultDecoder(method)(case.result)
            assertEquals(case.id, case.result, wireOf(decoded))
            // Through the public path too: id check, whole-reply byte/depth bound, typed decoding.
            val call = RelayCall(method, JsonObject(emptyMap()), RelayCalls.resultDecoder(method))
            val outcome = call.interpret(id, frame)
            assertTrue("${case.id}: $outcome", outcome is RelayOutcome.Success<*>)
            assertEquals(case.id, case.result, wireOf((outcome as RelayOutcome.Success<*>).value))
            checked++
        }
        assertTrue(checked >= 110)
    }

    @Test fun fixtureArtifactsThatBreakTheIdContractAreRefusedNeverDecodedAsSuccess() {
        val odd = cases.filter { it.discrepancy != null }
        assertTrue(odd.isNotEmpty())
        for (case in odd) {
            val call =
                RelayCall(
                    RelayMethod.OUTBOX_READ,
                    JsonObject(emptyMap()),
                    RelayCalls.resultDecoder(RelayMethod.OUTBOX_READ)
                )
            val outcome = call.interpret(
                CorrelationId.of(case.responseId!!)!!,
                RelayEnvelope.decodeFrame(case.wireText)
            )
            assertTrue(
                "${case.id}: $outcome",
                outcome is RelayOutcome.Invalid && outcome.problem == RelayProblem.INVALID_RESULT
            )
        }
    }

    @Test fun everyCapturedErrorIsAFailureAndNeverSuccess() {
        var checked = 0
        for (case in cases.filter { !it.isSuccess }) {
            val method = RelayMethod.fromWire(case.method) ?: RelayMethod.INFO
            val frame = RelayEnvelope.decodeFrame(case.wireText)
            assertTrue(case.id, frame is RelayFrame.Failure)
            val call = RelayCall(method, JsonObject(emptyMap()), RelayCalls.resultDecoder(method))
            val id = CorrelationId.of(case.responseId ?: "none")!!
            val outcome = call.interpret(id, frame)
            if (case.responseId == null) {
                assertTrue(case.id, outcome is RelayOutcome.Uncorrelated)
            } else {
                assertTrue("${case.id}: $outcome", outcome is RelayOutcome.Failed)
                val error = (outcome as RelayOutcome.Failed).error
                assertEquals(case.error["code"]!!.jsonPrimitive.content, error.code.wire)
                assertEquals(case.error.containsKey("snapshot_required"), error.snapshotRequired)
                assertNotNull(case.id, error.action)
            }
            checked++
        }
        assertTrue(checked > 100)
    }

    @Test fun everyAcceptedCapturedRequestIsExpressedExactlyByTheTypedBuilders() {
        var checked = 0
        for (case in cases) {
            val method = RelayMethod.fromWire(case.method) ?: continue
            if (case.isSuccess) {
                val call = RelayCalls.fromWire(method, case.params)
                assertNotNull("${case.id}: ${case.params}", call)
                // The server also accepts an explicit `cursor: null`; the builders omit it (same meaning).
                val canonical = JsonObject(
                    case.params.filterNot {
                        it.key == "cursor" &&
                            it.value == kotlinx.serialization.json.JsonNull
                    }
                )
                assertEquals(case.id, canonical, call!!.params)
                val encoded = RelayEnvelope.encodeRequest(
                    CorrelationId.of(case.request["id"]!!.jsonPrimitive.content)!!,
                    call
                )
                assertTrue(case.id, encoded is RelayEncoded.Frame)
                checked++
            }
        }
        assertTrue(checked > 120)
    }

    @Test fun serverRefusedParametersAreRefusedByTheBuildersToo() {
        val refused = mutableListOf<String>()
        var confirmed = 0
        for (case in cases.filter {
            !it.isSuccess &&
                it.error["code"]!!.jsonPrimitive.content == "invalid_request" &&
                !it.id.startsWith("malformed-envelope")
        }) {
            val method = RelayMethod.fromWire(case.method) ?: continue
            if (RelayCalls.fromWire(method, case.params) ==
                null
            ) {
                confirmed++
            } else {
                refused.add(case.id)
            }
        }
        // A server `invalid_request` can also come from state (never from a shape the builders reject). Those are
        // the only allowed exceptions and each is named: nothing else may slip through.
        assertEquals(STATE_DEPENDENT_INVALID_REQUESTS, refused.toSet())
        assertTrue(confirmed >= 8)
    }

    @Test fun capturedO5CopiesCoverBothPayloadKindsBothOriginsAndBothReferences() {
        val items = RelayVectors.forMethod(RelayMethod.OUTBOX_READ).filter {
            it.isSuccess &&
                it.discrepancy == null
        }
            .flatMap { OutboxPage.decode(it.result).items }
        val finals = items.map { it.payload }.filterIsInstance<OutboxPayload.FinalText>()
        assertTrue(finals.any { it.messageId == null })
        assertTrue(finals.any { it.messageId != null })
        val changes = items.map { it.payload }.filterIsInstance<OutboxPayload.TaskChange>()
        for (origin in TaskOrigin.entries) {
            for (reference in OutboxReference.entries) {
                assertTrue(
                    "$origin/$reference",
                    changes.any {
                        it.origin == origin &&
                            it.reference == reference
                    }
                )
            }
        }
        val states = changes.filterIsInstance<OutboxPayload.TaskChange.Relay>().map {
            it.task.state
        }.toSet()
        assertTrue(
            states.containsAll(setOf(TaskState.WORKING, TaskState.NEEDS_INPUT, TaskState.FINISHED))
        )
        val gaps = RelayVectors.forMethod(RelayMethod.OUTBOX_READ).filter {
            it.isSuccess &&
                it.discrepancy == null
        }
            .map { it.result["gap_generation"]!!.jsonPrimitive.int }
        assertTrue(gaps.any { it == 0 } && gaps.any { it > 0 } && gaps.any { it >= 2 })
    }

    @Test fun nullableRunIdAndRequiredNeedsInputRunIdMatchRealTasks() {
        val tasks = (
            RelayVectors.forMethod(RelayMethod.TASKS_LIST) +
                RelayVectors.forMethod(RelayMethod.TASK_GET)
            )
            .filter { it.isSuccess }
            .flatMap {
                if (it.method ==
                    RelayMethod.TASK_GET.wire
                ) {
                    listOf(Task.decodeGet(it.result))
                } else {
                    TaskPage.decode(it.result).items
                }
            }
        assertTrue(tasks.any { it.runId == null && it.state == TaskState.FINISHED })
        assertTrue(tasks.filter { it.state == TaskState.NEEDS_INPUT }.all { it.runId != null })
        assertTrue(tasks.any { it.state == TaskState.NEEDS_INPUT })
    }

    @Test fun realPromptFormsAreOnlyActionableWhenFetchedAndFull() {
        val gets = RelayVectors.forMethod(RelayMethod.TASK_GET).filter {
            it.isSuccess
        }.map { Task.decodeGet(it.result) }
        val prompts = gets.mapNotNull { it.prompt }
        assertTrue(prompts.any { it.actionable && it.asActionable() != null })
        assertTrue(prompts.any { !it.actionable && it.asActionable() == null })
        assertTrue(
            prompts.any {
                it.reason == PromptReason.TOO_LARGE && it.text == null &&
                    it.choices.isEmpty()
            }
        )
        val listed = RelayVectors.forMethod(RelayMethod.TASKS_LIST).filter { it.isSuccess }
            .flatMap { TaskPage.decode(it.result).items }.mapNotNull { it.prompt }
        assertTrue(listed.isNotEmpty() && listed.all { it.asActionable() == null })
        val events = RelayVectors.forMethod(RelayMethod.EVENTS_READ).filter { it.isSuccess }
            .flatMap { EventsPage.decode(it.result).events }
            .mapNotNull { (it.payload as? EventPayload.PromptShown)?.prompt }
        assertTrue(events.isNotEmpty() && events.all { it.asActionable() == null })
        val existing = RelayVectors.forMethod(RelayMethod.EXISTING_PROMPT_GET).filter {
            it.isSuccess
        }
            .mapNotNull { ExistingPrompt.decode(it.result).prompt }
        assertTrue(existing.any { it.asActionable() != null })
    }

    @Test fun hubLevelRefusalsAreReproducedByteForByteAndAgreeWithTheParser() {
        val hub = RelayVectors.json("hub-level.json")["cases"]!!.jsonArray
        assertEquals(
            setOf("oversize-65537", "edge-65536-multibyte", "depth-8-inside", "depth-9-refused"),
            hub.map { it.jsonObject["case"]!!.jsonPrimitive.content }.toSet()
        )
        for (entry in hub.map { it.jsonObject }) {
            val case = entry["case"]!!.jsonPrimitive.content
            val recipe = entry["recipe"]!!.jsonObject
            val text = when (recipe["kind"]!!.jsonPrimitive.content) {
                "literal" -> recipe["text"]!!.jsonPrimitive.content
                else -> {
                    val method = recipe["method"]!!.jsonObject
                    val name = method["prefix"]!!.jsonPrimitive.content +
                        method["repeat"]!!.jsonPrimitive.content.repeat(
                            method["count"]!!.jsonPrimitive.int
                        ) +
                        method["suffix"]!!.jsonPrimitive.content
                    val requestId = recipe["id"]!!.jsonPrimitive.content
                    "{\"type\":\"relay.request\",\"v\":1,\"id\":\"$requestId\"," +
                        "\"method\":\"$name\",\"params\":{}}"
                }
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertEquals(case, entry["request_utf8_bytes"]!!.jsonPrimitive.int, bytes.size)
            assertEquals(case, entry["request_sha256"]!!.jsonPrimitive.content, sha256(bytes))
            val parsed = RelayJson.parse(text)
            // The server refuses (bare invalid_request) exactly what our outer bound refuses.
            val serverRefused = entry["responses"]!!.jsonArray.single().jsonObject.let {
                "id" !in it
            }
            assertEquals(case, serverRefused, parsed is RelayParse.Rejected)
            if (parsed is RelayParse.Rejected) {
                assertEquals(
                    case,
                    if (case.startsWith(
                            "oversize"
                        )
                    ) {
                        RelayRejection.OVERSIZE
                    } else {
                        RelayRejection.DEPTH
                    },
                    parsed.reason
                )
            }
            val reply = RelayEnvelope.decodeFrame(
                entry["responses"]!!.jsonArray.single().jsonObject.toString()
            )
            assertTrue(case, reply is RelayFrame.Failure)
            assertEquals(case, serverRefused, (reply as RelayFrame.Failure).id == null)
        }
    }

    @Test fun manifestPinsTheSourceAndCoversEveryAdvertisedMethodWithSuccessAndError() {
        val manifest = RelayVectors.manifest
        assertEquals(
            "286e13482f12a71b1eb81e66c43470df09da2f05",
            manifest["sdk"]!!.jsonObject["head"]!!.jsonPrimitive.content
        )
        assertEquals(
            "7c17e6d72a3b9ec2974b3566f02dab1ed4b94eae",
            manifest["core"]!!.jsonObject["head"]!!.jsonPrimitive.content
        )
        val methods = RelayMethod.entries.filter { m ->
            cases.any {
                it.method == m.wire &&
                    it.isSuccess
            }
        }
        assertEquals(
            "success vector missing for ${RelayMethod.entries - methods.toSet()}",
            RelayMethod.entries.toSet(),
            methods.toSet()
        )
        // Methods with no real captured error vector are named; every other one has at least one.
        val withError = RelayMethod.entries.filter { m ->
            cases.any {
                it.method == m.wire &&
                    !it.isSuccess
            }
        }.toSet()
        assertEquals(NO_CAPTURED_ERROR, RelayMethod.entries.toSet() - withError)
        // Per-file digests in the manifest are the files as they are now (nothing edited after the capture).
        for (entry in manifest["coverage"]!!.jsonArray.map { it.jsonObject }) {
            val file = entry["file"]!!.jsonPrimitive.content
            assertEquals(
                file,
                entry["sha256"]!!.jsonPrimitive.content,
                sha256(RelayVectors.text(file).toByteArray())
            )
        }
        assertFalse(manifest.toString().contains("/Users/"))
    }

    private fun wireOf(value: Any): JsonObject = when (value) {
        is Info -> value.toWire()
        is ConversationSummary -> value.toWire()
        is Binding -> value.toWire()
        is Detached -> value.toWire()
        is Receipt -> value.toWire()
        is HistoryPage -> value.toWire()
        is EventsPage -> value.toWire()
        is TaskPage -> value.toWire()
        is Task -> value.toWire()
        is ConversationPage -> value.toWire()
        is ConversationUpdate -> value.toWire()
        is Deleted -> value.toWire()
        is AnswerAccepted -> value.toWire()
        is ExistingConversationPage -> value.toWire()
        is ExistingBinding -> value.toWire()
        is ExistingTaskPage -> value.toWire()
        is ExistingPrompt -> value.toWire()
        is OutboxPage -> value.toWire()
        is Acknowledged -> value.toWire()
        else -> fail("no encoder for ${value::class}").let { JsonObject(emptyMap()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val STATE_DEPENDENT_INVALID_REQUESTS = emptySet<String>()
        val NO_CAPTURED_ERROR = emptySet<RelayMethod>()
    }
}
