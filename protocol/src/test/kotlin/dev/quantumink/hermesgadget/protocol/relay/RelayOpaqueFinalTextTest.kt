package dev.quantumink.hermesgadget.protocol.relay

import java.lang.reflect.Modifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O5 final-text identifiers are opaque exact text by the accepted source (`Outbox.put`: event <= 256 B,
 * conversation <= 128 B, message id <= 36 B, no UUID rule), while task-change copies keep canonical UUIDs.
 */
class RelayOpaqueFinalTextTest {
    private fun items(): List<JsonObject> = RelayVectors.forMethod(RelayMethod.OUTBOX_READ)
        .filter { it.isSuccess }
        .flatMap { (it.result["items"] as JsonArray).map { item -> item as JsonObject } }

    private fun kind(item: JsonObject) =
        ((item["payload"] as JsonObject)["kind"] as JsonPrimitive).content

    private val stockFinal = items().first {
        kind(it) == "final_text" &&
            "stock-final" in it.toString()
    }
    private val sharedFinal = items().first {
        kind(it) == "final_text" && (it["payload"] as JsonObject).containsKey("message_id") &&
            "stock-final" !in it.toString()
    }
    private val relayChange = items().first {
        kind(it) == "task_change" &&
            ((it["payload"] as JsonObject)["origin"] as JsonPrimitive).content == "relay"
    }
    private val existingChange = items().first {
        kind(it) == "task_change" &&
            ((it["payload"] as JsonObject)["origin"] as JsonPrimitive).content == "existing"
    }

    private fun JsonObject.with(
        vararg edits: Pair<String, kotlinx.serialization.json.JsonElement>
    ) = JsonObject(LinkedHashMap(this).also { it.putAll(edits) })

    private fun JsonObject.payloadWith(
        vararg edits: Pair<String, kotlinx.serialization.json.JsonElement>
    ) = with(
        "payload" to
            JsonObject(LinkedHashMap(this["payload"] as JsonObject).also { it.putAll(edits) })
    )

    private fun decode(item: JsonObject): RelayOutcome<Any> {
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

    private fun accepted(item: JsonObject): OutboxItem {
        val outcome = decode(item)
        assertTrue("$outcome", outcome is RelayOutcome.Success<*>)
        val page = (outcome as RelayOutcome.Success<*>).value as OutboxPage
        // Exact wire round trip, nothing normalized.
        assertEquals(item, page.items.single().toWire())
        return page.items.single()
    }

    private fun refused(item: JsonObject) {
        val outcome = decode(item)
        assertTrue("$outcome", outcome is RelayOutcome.Invalid)
    }

    @Test fun theRecordedStockFinalIsASuccessKeepingItsExactOpaqueValues() {
        val item = accepted(stockFinal) as OutboxItem.Final
        assertEquals("stock-chat", item.conversationRef.value)
        assertEquals("stock-final", item.eventRef.value)
        assertEquals("stock-final", item.eventKey)
        assertNull(item.payload.messageRef)
        // The delivery id stays server-issued canonical.
        assertTrue(item.deliveryId.value.length == 36)
    }

    @Test fun aRecordedSharedFinalKeepsItsMessageReferenceAndCanonicalFormIsOnlyReported() {
        val item = accepted(sharedFinal) as OutboxItem.Final
        val ref = item.payload.messageRef!!
        assertEquals(MessageId.of(ref.value), ref.asCanonicalOrNull)
        assertNull(OpaqueMessageRef.of("not-a-uuid-at-all")!!.asCanonicalOrNull)
        // An uppercase spelling is reported as non-canonical, never lowercased.
        val upper = OpaqueMessageRef.of("0F6A3C43-1B0B-4A6D-8F7E-0D4D3D6A7C11")!!
        assertNull(upper.asCanonicalOrNull)
    }

    @Test fun opaqueTextIsNeverTrimmedFoldedParsedOrNormalized() {
        val samples = listOf(
            "A", "a", " ", " lead", "trail ", "stock final", "É", "é", "😀",
            "0F6A3C43-1B0B-4A6D-8F7E-0D4D3D6A7C11",
            "x".repeat(10), "a\tb", "line\nbreak", "\u0000"
        )
        for (value in samples) {
            val item = stockFinal.with(
                "conversation_id" to JsonPrimitive(value),
                "event_id" to JsonPrimitive(value)
            )
                .payloadWith("message_id" to JsonPrimitive(value.take(8)))
            val decoded = accepted(item) as OutboxItem.Final
            assertEquals(value, decoded.conversationRef.value)
            assertEquals(value, decoded.eventKey)
        }
        assertNotEquals(OpaqueEventRef.of("A"), OpaqueEventRef.of("a"))
        assertNotEquals(OpaqueEventRef.of("a"), OpaqueEventRef.of("a "))
        assertNotEquals(OpaqueConversationRef.of("é"), OpaqueConversationRef.of("é")) // NFD != NFC
    }

    @Test fun finalTextIdentifierBoundsAreTheSourcesExactByteCaps() {
        fun at(field: String, size: Int, filler: String): JsonObject {
            val unit = filler.toByteArray(Charsets.UTF_8).size
            val value = filler.repeat(size / unit) + "x".repeat(size % unit)
            return if (field == "message_id") {
                stockFinal.payloadWith("message_id" to JsonPrimitive(value))
            } else {
                stockFinal.with(field to JsonPrimitive(value))
            }
        }
        val caps = mapOf("conversation_id" to 128, "event_id" to 256, "message_id" to 36)
        for ((field, cap) in caps) {
            for (filler in listOf("x", "é", "€", "😀")) {
                accepted(at(field, cap, filler))
                refused(at(field, cap + 1, filler))
            }
            refused(at(field, 0, "x")) // empty
            val oneOver = when (field) {
                "message_id" -> stockFinal.payloadWith(field to JsonPrimitive(1))
                else -> stockFinal.with(field to JsonPrimitive(1))
            }
            refused(oneOver) // wrong type
            refused(
                if (field ==
                    "message_id"
                ) {
                    stockFinal.payloadWith(field to JsonNull)
                } else {
                    stockFinal.with(field to JsonNull)
                }
            )
            refused(
                if (field == "message_id") {
                    stockFinal.payloadWith(field to JsonPrimitive("\uD800"))
                } else {
                    stockFinal.with(field to JsonPrimitive("\uD800"))
                }
            )
        }
        assertNull(OpaqueConversationRef.of(""))
        assertNull(OpaqueEventRef.of("x".repeat(257)))
        assertNull(OpaqueMessageRef.of("x".repeat(37)))
    }

    @Test fun missingOrExtraFinalTextMembersAreStillInvalid() {
        for (key in listOf(
            "conversation_id",
            "event_id",
            "delivery_id",
            "created_at_ms",
            "expires_at_ms",
            "payload"
        )) {
            refused(JsonObject(LinkedHashMap(stockFinal).also { it.remove(key) }))
        }
        refused(stockFinal.with("extra" to JsonPrimitive(1)))
        refused(stockFinal.payloadWith("choices" to JsonArray(emptyList())))
        refused(stockFinal.payloadWith("prompt" to JsonObject(emptyMap())))
        // The delivery id is server-issued and canonical for every payload kind.
        refused(stockFinal.with("delivery_id" to JsonPrimitive("stock-delivery")))
        refused(
            stockFinal.with("delivery_id" to JsonPrimitive("0F6A3C43-1B0B-4A6D-8F7E-0D4D3D6A7C11"))
        )
    }

    @Test fun taskChangeCopiesKeepCanonicalUuidScopeAndAreNotWeakened() {
        for (change in listOf(relayChange, existingChange)) {
            assertTrue(accepted(change) is OutboxItem.Change)
            refused(change.with("event_id" to JsonPrimitive("stock-final")))
            refused(change.with("conversation_id" to JsonPrimitive("stock-chat")))
            refused(change.with("delivery_id" to JsonPrimitive("stock-delivery")))
            refused(
                change.with(
                    "event_id" to
                        JsonPrimitive(((change["event_id"] as JsonPrimitive).content).uppercase())
                )
            )
            refused(
                change.with(
                    "conversation_id" to JsonPrimitive("0f6a3c43-1b0b-4a6d-8f7e-0d4d3d6a7c11")
                )
            ) // not the task's
        }
    }

    @Test fun aMixedPageDecodesEachItemUnderItsOwnRuleAndDedupsByExactEventText() {
        val page = JsonObject(
            mapOf(
                "items" to JsonArray(listOf(stockFinal, relayChange, sharedFinal, existingChange)),
                "next_cursor" to JsonNull,
                "gap_generation" to JsonPrimitive(1)
            )
        )
        val decoded = OutboxPage.decode(page)
        assertEquals(page, decoded.toWire())
        assertEquals(
            listOf(true, false, true, false),
            decoded.items.map { it is OutboxItem.Final }
        )
        assertEquals(4, decoded.items.map { it.eventKey }.toSet().size)
        assertFalse(decoded.items.map { it.eventKey }.contains("STOCK-FINAL"))
    }

    @Test fun opaqueFinalTextRemainsActionFreeAndNeverConvertsToCanonicalScopeTypes() {
        val item = accepted(stockFinal) as OutboxItem.Final
        val copy: DisplayOnlyCopy = item.payload
        assertEquals(OutboxPayloadKind.FINAL_TEXT, copy.kind)
        val banned = listOf(
            ConversationId::class.java, EventId::class.java, BindingId::class.java,
            BindingRef::class.java, Binding::class.java, Prompt::class.java,
            ActionablePrompt::class.java, RelayCall::class.java,
            RequestId::class.java, TaskId::class.java
        )
        for (type in listOf(
            OutboxItem.Final::class.java,
            OpaqueConversationRef::class.java,
            OpaqueEventRef::class.java,
            OpaqueMessageRef::class.java,
            OutboxPayload.FinalText::class.java
        )) {
            for (method in type.methods.filter { Modifier.isPublic(it.modifiers) }) {
                val returns = method.returnType
                val leaks = banned.any { it.isAssignableFrom(returns) }
                // The one allowed conversion is the exact-canonical report on a message reference.
                val allowed =
                    type == OpaqueMessageRef::class.java && returns == MessageId::class.java ||
                        type == OpaqueMessageRef::class.java &&
                        method.name.contains("asCanonicalOrNull")
                assertFalse("${type.simpleName}.${method.name}", leaks && !allowed)
            }
        }
        assertFalse(
            item.toString().contains("stock-chat") || item.toString().contains("stock-final")
        )
        assertFalse(item.conversationRef.toString().contains("stock"))
        // No builder accepts an opaque reference where a canonical id or a request is built.
        for (method in RelayCalls::class.java.methods.filter { Modifier.isPublic(it.modifiers) }) {
            for (type in method.parameterTypes) {
                assertFalse(
                    method.name,
                    type == OpaqueConversationRef::class.java || type == OpaqueEventRef::class.java
                )
            }
        }
    }
}
