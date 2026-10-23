package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property over every REAL captured success: any single deviation from the observed schema — an unknown member, a
 * missing required member, a wrong-typed value, an unknown enum spelling, a malformed identifier — must decode as
 * [RelayOutcome.Invalid] and never as a success, and must never throw. Allowed nulls and optional members are the
 * only deviations that may stay valid, and they are named.
 */
class RelayDtoMutationTest {
    /** Members that are optional in the accepted wire: removing them is valid (nullable ones keep their null). */
    private val optionalMembers = setOf(
        "prompt", "metadata_state", "message_id", "outbox_payloads", "unavailable",
        "unavailable_count", "deleted", "server_time_ms", "dto_bytes", "dto_depth",
        "history_page_bytes", "history_text_bytes"
    )

    private val enumMembers = setOf(
        "state", "kind", "status", "role", "deadline", "reason", "origin", "reference",
        "execution_state", "mutation_outcome", "metadata_state"
    )

    private fun isOpaqueFinalTextMember(node: JsonObject, key: String): Boolean {
        val payload = node["payload"] as? JsonObject
        val itemKind = (payload?.get("kind") as? JsonPrimitive)?.content
        val ownKind = (node["kind"] as? JsonPrimitive)?.content
        return (itemKind == "final_text" && key in setOf("conversation_id", "event_id")) ||
            (ownKind == "final_text" && key == "message_id")
    }

    private data class Mutation(
        val description: String,
        val result: JsonObject,
        val mayStayValid: Boolean
    )

    private fun outcomeOf(method: RelayMethod, result: JsonObject): RelayOutcome<Any> {
        val call = RelayCall(method, JsonObject(emptyMap()), RelayCalls.resultDecoder(method))
        val reply = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("relay.response"),
                "v" to JsonPrimitive(1),
                "id" to JsonPrimitive("m1"),
                "result" to result
            )
        )
        return call.interpret(
            CorrelationId.of("m1")!!,
            RelayEnvelope.decodeFrame(RelayVectors.asciiJson(reply))
        )
    }

    private fun mutations(method: RelayMethod, root: JsonObject): List<Mutation> {
        val out = ArrayList<Mutation>()
        fun walk(node: JsonElement, path: String, replace: (JsonElement) -> JsonObject) {
            when (node) {
                is JsonObject -> {
                    out.add(
                        Mutation(
                            "$path +unknown",
                            replace(JsonObject(node + ("zz_unknown" to JsonPrimitive(1)))),
                            false
                        )
                    )
                    for ((key, value) in node) {
                        val here = "$path.$key"
                        fun swap(newValue: JsonElement?): JsonObject = replace(
                            JsonObject(
                                LinkedHashMap(node).apply {
                                    if (newValue ==
                                        null
                                    ) {
                                        remove(key)
                                    } else {
                                        put(key, newValue)
                                    }
                                }
                            )
                        )
                        out.add(Mutation("$here -missing", swap(null), key in optionalMembers))
                        val flipped: JsonElement? = when {
                            value is JsonNull -> null
                            value is JsonObject || value is JsonArray -> JsonPrimitive("x")
                            value is JsonPrimitive && value.isString -> JsonPrimitive(7)
                            else -> JsonPrimitive("7")
                        }
                        if (flipped !=
                            null
                        ) {
                            out.add(Mutation("$here -wrongtype", swap(flipped), false))
                        }
                        if (value is JsonPrimitive && value.isString && key in enumMembers) {
                            out.add(Mutation("$here -enum", swap(JsonPrimitive("bogus")), false))
                        }
                        if (value is JsonPrimitive && value.isString && key.endsWith("_id") &&
                            "provenance" !in path
                        ) {
                            // Opaque by contract only inside a final-text copy: see RelayOpaqueFinalTextTest.
                            val opaque = isOpaqueFinalTextMember(node, key)
                            out.add(
                                Mutation("$here -id", swap(JsonPrimitive("NOT-A-UUID")), opaque)
                            )
                        }
                        walk(value, here) { replacement -> swap(replacement) }
                    }
                }
                is JsonArray -> node.forEachIndexed { index, element ->
                    walk(element, "$path[$index]") { replacement ->
                        replace(JsonArray(node.toMutableList().also { it[index] = replacement }))
                    }
                }
                else -> Unit
            }
        }
        walk(root, method.wire) { it as JsonObject }
        return out
    }

    @Test fun noSingleDeviationFromACapturedSuccessEverDecodesAsSuccessOrThrows() {
        var executed = 0
        var stayedValid = 0
        val methodsSeen = mutableSetOf<RelayMethod>()
        for (case in RelayVectors.all.filter { it.isSuccess }) {
            val method = RelayMethod.fromWire(case.method) ?: continue
            methodsSeen += method
            assertTrue(
                "${case.id} baseline",
                outcomeOf(method, case.result) is RelayOutcome.Success<*>
            )
            for (mutation in mutations(method, case.result)) {
                val outcome = outcomeOf(method, mutation.result)
                executed++
                if (outcome is RelayOutcome.Success<*>) {
                    stayedValid++
                    assertTrue(
                        "${case.id} ${mutation.description} decoded as success",
                        mutation.mayStayValid
                    )
                } else {
                    assertTrue(
                        "${case.id} ${mutation.description}: $outcome",
                        outcome is RelayOutcome.Invalid
                    )
                    assertEquals(
                        RelayProblem.INVALID_RESULT,
                        (outcome as RelayOutcome.Invalid).problem
                    )
                }
            }
        }
        // The voice methods have their own mutation property over their own vectors (VoiceDtoStrictnessTest).
        assertEquals(RelayMethod.entries.filter { !it.isVoice }.toSet(), methodsSeen)
        assertTrue("only $executed mutations", executed > 5000)
        // Optional-member removals are a small minority; if they dominated, the property would be vacuous.
        assertTrue(stayedValid < executed / 10)
    }

    @Test fun anOutcomeTokenOfAReceiptStaysFreeTextWhileATaskOutcomeIsClosed() {
        val receipt = RelayVectors.forMethod(RelayMethod.TURN_SUBMIT).first { it.isSuccess }.result
        val withUnknownOutcome =
            JsonObject(receipt + ("outcome" to JsonPrimitive("never_heard_of_it")))
        val outcome = outcomeOf(RelayMethod.TURN_SUBMIT, withUnknownOutcome)
        assertTrue(outcome is RelayOutcome.Success<*>)
        val decoded = (outcome as RelayOutcome.Success<*>).value as Receipt
        assertEquals("never_heard_of_it", decoded.outcome)
        assertEquals(null, decoded.knownOutcome) // unknown stays unknown, never read as a success
        val task = RelayVectors.forMethod(RelayMethod.TASK_GET).first { it.isSuccess }.result
        assertTrue(
            outcomeOf(
                RelayMethod.TASK_GET,
                JsonObject(task + ("outcome" to JsonPrimitive("never_heard_of_it")))
            ) is RelayOutcome.Invalid
        )
    }
}
