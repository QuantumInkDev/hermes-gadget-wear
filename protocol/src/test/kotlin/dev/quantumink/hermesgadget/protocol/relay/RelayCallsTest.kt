package dev.quantumink.hermesgadget.protocol.relay

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

/** Exact method-specific request limits, strict scalars and the prompt/cancel preconditions. */
class RelayCallsTest {
    private val request = RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!
    private val conversation = ConversationId.of("4b327fa7-d0ea-46fa-a917-3fdc62d1c349")!!
    private val binding = BindingRef(BindingId.of("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")!!, 3)

    private fun refused(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected the builder to refuse")
    }

    private fun submit(text: String) = RelayCalls.submit(binding, request, 1_792_300_030_000, text)

    @Test fun submitTextIsOneToSixteenThousandBytesAndAtMostFourThousandCodePoints() {
        assertNotNull(submit("x"))
        assertNotNull(submit("x".repeat(4_000)))
        refused { submit("x".repeat(4_001)) }
        refused { submit("") }
        // 4,000 four-byte code points are 16,000 bytes: the code-point cap binds before the byte cap can.
        assertNotNull(submit("😀".repeat(4_000)))
        refused { submit("😀".repeat(4_001)) }
        assertNotNull(submit("é".repeat(4_000)))
        refused { submit("\uD800") } // not encodable as UTF-8
        refused { RelayCalls.submit(binding, request, -1, "x") }
        assertTrue(RelayCalls.validSubmitText("a\u0000b")) // the server only bounds size
        assertFalse(RelayCalls.validSubmitText("a\uDC00"))
    }

    @Test fun submitCarriesTheBindingEpochAndTheDurableIdAndNeverAttachments() {
        val call = submit("hello")
        assertEquals(
            setOf("binding_id", "authority_epoch", "request_id", "expires_at_ms", "text"),
            call.params.keys
        )
        assertEquals(JsonPrimitive(3), call.params["authority_epoch"])
        assertEquals(JsonPrimitive(request.value), call.params["request_id"])
        assertFalse(call.params.containsKey("attachment_ids"))
    }

    @Test fun createTitleIsNonBlankAtMostOneTwentyCodePoints() {
        assertNull(RelayCalls.createConversation(request).params["title"])
        assertEquals(
            JsonPrimitive("t"),
            RelayCalls.createConversation(request, "t").params["title"]
        )
        assertNotNull(RelayCalls.createConversation(request, "x".repeat(120)))
        refused { RelayCalls.createConversation(request, "x".repeat(121)) }
        refused { RelayCalls.createConversation(request, "") }
        refused {
            RelayCalls.createConversation(request, "   ")
        } // the server refused exactly this in the capture
        assertNotNull(RelayCalls.createConversation(request, "😀".repeat(120)))
        refused { RelayCalls.createConversation(request, "😀".repeat(121)) }
    }

    @Test fun updateNeedsAChangeARevisionAndABoundedTitle() {
        refused { RelayCalls.update(conversation, request, 1) }
        refused { RelayCalls.update(conversation, request, 0, bookmarked = true) }
        assertEquals(
            JsonPrimitive(false),
            RelayCalls.update(conversation, request, 1, bookmarked = false).params["bookmarked"]
        )
        assertNotNull(RelayCalls.update(conversation, request, 2, title = "x".repeat(1_024)))
        refused { RelayCalls.update(conversation, request, 2, title = "x".repeat(1_025)) }
        // 1,024 four-byte code points are exactly 4,096 bytes.
        assertNotNull(RelayCalls.update(conversation, request, 2, title = "😀".repeat(1_024)))
        refused { RelayCalls.update(conversation, request, 2, title = "😀".repeat(1_025)) }
        refused { RelayCalls.update(conversation, request, 2, title = " ") }
        assertEquals(
            JsonPrimitive("canonical"),
            RelayCalls.delete(conversation, request, 2).params["scope"]
        )
        refused { RelayCalls.delete(conversation, request, 0) }
    }

    @Test fun pagingCursorsLimitsAndQueriesHaveTheirExactBounds() {
        for (limit in listOf(0, -1, 51, Int.MAX_VALUE)) {
            refused { RelayCalls.tasks(limit = limit) }
            refused { RelayCalls.history(conversation, limit = limit) }
            refused { RelayCalls.outboxRead(limit) }
        }
        assertEquals(JsonPrimitive(1), RelayCalls.tasks(limit = 1).params["limit"])
        assertEquals(JsonPrimitive(50), RelayCalls.tasks(limit = 50).params["limit"])
        assertNotNull(RelayCalls.history(conversation, "A".repeat(512)))
        refused { RelayCalls.history(conversation, "A".repeat(513)) }
        refused { RelayCalls.history(conversation, "") }
        refused { RelayCalls.history(conversation, "é") }
        assertNotNull(RelayCalls.conversations(query = "x".repeat(256)))
        refused { RelayCalls.conversations(query = "x".repeat(257)) }
        assertNotNull(RelayCalls.conversations(query = "€".repeat(85))) // 255 bytes
        refused { RelayCalls.conversations(query = "€".repeat(86)) } // 258 bytes
        refused { RelayCalls.conversations(query = "   ") }
        assertEquals(
            JsonPrimitive(true),
            RelayCalls.conversations(bookmarked = true).params["bookmarked"]
        )
        assertEquals(setOf("state"), RelayCalls.tasks(state = TaskState.NEEDS_INPUT).params.keys)
        assertEquals(
            JsonPrimitive("needs_input"),
            RelayCalls.tasks(state = TaskState.NEEDS_INPUT).params["state"]
        )
        refused { RelayCalls.events(binding, "e", -1) }
        refused { RelayCalls.events(binding, "x".repeat(65), 0) }
        assertEquals(
            setOf("binding_id", "replay_epoch", "after_sequence"),
            RelayCalls.events(binding, "e", 0).params.keys
        )
    }

    @Test fun acknowledgementTakesOneToFiftyOwnedIds() {
        val id = DeliveryId.of("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")!!
        refused { RelayCalls.outboxAck(emptyList()) }
        refused { RelayCalls.outboxAck(List(51) { id }) }
        val fifty = RelayCalls.outboxAck(List(50) { id })
        assertEquals(50, (fifty.params["delivery_ids"] as JsonArray).size)
    }

    private fun fetchedPrompts(): List<Prompt> =
        RelayVectors.forMethod(RelayMethod.TASK_GET).filter {
            it.isSuccess
        }.mapNotNull { Task.decodeGet(it.result).prompt }

    private fun actionable(freeText: Boolean): ActionablePrompt = fetchedPrompts().first {
        it.asActionable() != null && it.freeText == freeText
    }.asActionable()!!

    @Test fun anAnswerIsAnOfferedChoiceOrFreeTextOnlyWhereThePromptAllowsIt() {
        val clarify = actionable(freeText = true)
        val text = RelayCalls.respond(binding, clarify, PromptAnswer.Text("because"), request)
        assertEquals(JsonObject(mapOf("text" to JsonPrimitive("because"))), text.params["response"])
        assertEquals(JsonPrimitive(clarify.generation), text.params["generation"])
        assertEquals(JsonPrimitive(clarify.promptId.value), text.params["prompt_id"])
        refused { RelayCalls.respond(binding, clarify, PromptAnswer.Text(""), request) }
        refused { RelayCalls.respond(binding, clarify, PromptAnswer.Text("a\u0000b"), request) }
        refused {
            RelayCalls.respond(binding, clarify, PromptAnswer.Text("x".repeat(4_001)), request)
        }
        assertNotNull(
            RelayCalls.respond(binding, clarify, PromptAnswer.Text("x".repeat(4_000)), request)
        )
        refused {
            RelayCalls.respond(binding, clarify, PromptAnswer.Choice("not offered"), request)
        }
        val choice = fetchedPrompts().first {
            it.asActionable() != null && it.choices.isNotEmpty()
        }.asActionable()!!
        val pick = RelayCalls.respond(
            binding,
            choice,
            PromptAnswer.Choice(choice.choices.first()),
            request
        )
        assertEquals(
            JsonObject(mapOf("choice" to JsonPrimitive(choice.choices.first()))),
            pick.params["response"]
        )
        if (!choice.freeText) {
            refused {
                RelayCalls.respond(binding, choice, PromptAnswer.Text("free"), request)
            }
        }
    }

    @Test fun cancelNamesAQueuedTurnWithNullOrExactlyTheRunningGeneration() {
        val receipts = (
            RelayVectors.forMethod(RelayMethod.TURN_SUBMIT) +
                RelayVectors.forMethod(RelayMethod.TURN_RECEIPT)
            )
            .filter { it.isSuccess }.map { Receipt.decode(it.result) }
        val queued = receipts.first { it.status == ReceiptStatus.QUEUED }
        val started = receipts.first { it.status == ReceiptStatus.STARTED }
        val terminal = receipts.first { it.status == ReceiptStatus.TERMINAL }
        assertEquals(CancelTarget(queued.admissionId, null), queued.cancelTarget)
        assertEquals(
            CancelTarget(started.admissionId, started.executionGeneration),
            started.cancelTarget
        )
        assertNull(terminal.cancelTarget)
        assertNull(queued.copy(status = ReceiptStatus.UNKNOWN).cancelTarget)
        assertNull(started.copy(executionGeneration = null).cancelTarget)
        val call = RelayCalls.cancel(binding, queued.cancelTarget!!, request)
        assertEquals(JsonNull, call.params["generation"]) // present and null: a queued turn
        assertEquals(
            JsonPrimitive(started.executionGeneration),
            RelayCalls.cancel(binding, started.cancelTarget!!, request).params["generation"]
        )
        refused { CancelTarget(queued.admissionId, 0) }
    }

    @Test fun everyBuilderRefusalIsAConstantMessageThatCannotLeakTheRejectedValue() {
        val secret = "super-secret-user-text-" + "x".repeat(5_000)
        try {
            submit(secret)
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertFalse(submit("private words").toString().contains("private"))
    }
}
