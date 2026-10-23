package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact optional request shape and existing bounded Receipt/error decoding, from new actual captures. */
class RelayOwnReceiptCallsTest {
    private fun session(name: String): JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/relay-own-native-v1/$name.json")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    ).jsonObject

    private fun step(name: String): JsonObject = session(name)["steps"]!!.jsonArray.first {
        it.jsonObject["request"]!!.jsonObject["method"]!!.jsonPrimitive.content ==
            "relay.turn.receipt.own"
    }.jsonObject

    @Test fun exactBuilderAndRequestDecoderRejectAuthorityFieldsAndWrongScalars() {
        val request = step("found")["request"]!!.jsonObject
        val params = request["params"]!!.jsonObject
        val call = RelayCalls.ownReceipt(
            ConversationId.of(params["conversation_id"]!!.jsonPrimitive.content)!!,
            RequestId.of(params["request_id"]!!.jsonPrimitive.content)!!
        )
        assertEquals("relay.turn.receipt.own", call.method.wire)
        assertEquals(RelayMethod.TURN_RECEIPT_OWN, RelayMethod.fromWire(call.method.wire))
        assertEquals(setOf("conversation_id", "request_id"), call.params.keys)
        assertEquals(params, call.params)
        assertEquals(params, RelayCalls.fromWire(call.method, params)!!.params)
        for (field in listOf(
            "device_id",
            "key_generation",
            "authority_epoch",
            "permissions",
            "profile_id"
        )) {
            assertNull(
                field,
                RelayCalls.fromWire(
                    call.method,
                    JsonObject(params + (field to JsonPrimitive("caller")))
                )
            )
        }
        for (field in params.keys) {
            assertNull(RelayCalls.fromWire(call.method, JsonObject(params - field)))
            for (wrong in listOf(
                JsonPrimitive(false),
                JsonPrimitive(5),
                JsonPrimitive("x".repeat(1025))
            )) {
                assertNull(RelayCalls.fromWire(call.method, JsonObject(params + (field to wrong))))
            }
        }
    }

    @Test fun actualFoundUsesTheSameReceiptDecoderAndBoundsAsLegacy() {
        val captured = step("found")
        val result = captured["response"]!!.jsonObject["result"]!!.jsonObject
        val decoded = RelayCalls.resultDecoder(RelayMethod.TURN_RECEIPT_OWN)(result) as Receipt
        assertEquals(result, decoded.toWire())
        assertEquals(
            (RelayCalls.resultDecoder(RelayMethod.TURN_RECEIPT)(result) as Receipt).toWire(),
            decoded.toWire()
        )
        assertEquals(
            RelayMethod.TURN_RECEIPT.responseBytes,
            RelayMethod.TURN_RECEIPT_OWN.responseBytes
        )
        assertEquals(
            RelayMethod.TURN_RECEIPT.responseDepth,
            RelayMethod.TURN_RECEIPT_OWN.responseDepth
        )
        val call = RelayCalls.fromWire(
            RelayMethod.TURN_RECEIPT_OWN,
            captured["request"]!!.jsonObject["params"]!!.jsonObject
        )!!
        val response = captured["response"]!!.jsonObject
        val id = CorrelationId.of(response["id"]!!.jsonPrimitive.content)!!
        assertTrue(
            call.interpret(
                id,
                RelayEnvelope.decodeFrame(response.toString())
            ) is RelayOutcome.Success
        )
        val oversized = JsonObject(
            response +
                ("result" to JsonObject(result + ("outcome" to JsonPrimitive("x".repeat(8193)))))
        )
        assertTrue(
            call.interpret(
                id,
                RelayEnvelope.decodeFrame(oversized.toString())
            ) is RelayOutcome.Invalid
        )
    }

    @Test fun actualOwnErrorsRemainFailuresAndNeverAbsenceByConversion() {
        for (name in listOf(
            "absent",
            "missing-sdk",
            "proof-unavailable",
            "denied",
            "old-id",
            "expired-intent"
        )) {
            val captured = step(name)
            val request = captured["request"]!!.jsonObject
            val response = captured["response"]!!.jsonObject
            val call = RelayCalls.fromWire(
                RelayMethod.TURN_RECEIPT_OWN,
                request["params"]!!.jsonObject
            )!!
            val id = CorrelationId.of(response["id"]!!.jsonPrimitive.content)!!
            val outcome = call.interpret(id, RelayEnvelope.decodeFrame(response.toString()))
            assertTrue(name, outcome is RelayOutcome.Failed)
            assertEquals(
                response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
                (outcome as RelayOutcome.Failed).error.code.wire
            )
            assertTrue(
                call.interpret(
                    CorrelationId.of("foreign")!!,
                    RelayEnvelope.decodeFrame(response.toString())
                ) is RelayOutcome.Invalid
            )
            assertTrue(
                call.interpret(
                    id,
                    RelayEnvelope.decodeFrame(JsonObject(response - "id").toString())
                ) is RelayOutcome.Uncorrelated
            )
        }
        assertNotNull(RelayMethod.fromWire("relay.turn.receipt"))
        assertNull(RelayMethod.fromWire("relay.turn.receipt.own.extra"))
    }
}
