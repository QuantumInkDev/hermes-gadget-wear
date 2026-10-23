package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelope codec: frames in and out, id correlation, error classes and the per-method reply bounds. */
class RelayEnvelopeTest {
    private val id = CorrelationId.of("r1")!!

    private fun result(
        vararg members: Pair<String, String>,
        id: String? = "r1",
        extra: String = ""
    ): String =
        "{\"type\":\"relay.response\",\"v\":1" + (id?.let { ",\"id\":\"$it\"" } ?: "") + extra +
            ",\"result\":{" + members.joinToString(",") { "\"${it.first}\":${it.second}" } + "}}"

    private fun error(code: String, id: String? = "r1", extra: String = ""): String =
        "{\"type\":\"relay.response\",\"v\":1" + (id?.let { ",\"id\":\"$it\"" } ?: "") +
            ",\"error\":{\"code\":\"$code\"$extra}}"

    private fun head(version: String) =
        "{\"type\":\"relay.response\",\"v\":$version,\"id\":\"r1\",\"result\":{}}"

    private fun probe(method: RelayMethod) = RelayCall(method, JsonObject(emptyMap())) { 1 }

    @Test fun aRequestIsTheExactEnvelopeWithTheCorrelationIdAndNotTheDurableId() {
        val request = RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!
        val call = RelayCalls.createConversation(request)
        val frame = RelayEnvelope.encodeRequest(id, call) as RelayEncoded.Frame
        assertEquals(
            "{\"type\":\"relay.request\",\"v\":1,\"id\":\"r1\"," +
                "\"method\":\"relay.conversation.create\"," +
                "\"params\":{\"request_id\":\"01a14d67-a300-7000-b9f6-5ea067c5b9f5\"}}",
            frame.text
        )
        assertEquals(frame.text.toByteArray().size, frame.bytes)
        // Same durable id, any number of correlation ids: the retry is the same request.
        val retry = RelayEnvelope.encodeRequest(
            CorrelationId.of("r2")!!,
            call
        ) as RelayEncoded.Frame
        assertNotEquals(frame.text, retry.text)
        assertTrue(retry.text.contains(request.value))
    }

    @Test fun correlationIdsAreOneToSixtyFourSafeCharsAndDurableIdsAreVersionSevenUuids() {
        assertEquals("a-B_9", CorrelationId.of("a-B_9")!!.value)
        assertNull(CorrelationId.of(""))
        assertNull(CorrelationId.of("a b"))
        assertNull(CorrelationId.of("é"))
        assertNull(CorrelationId.of("x".repeat(65)))
        assertEquals(64, CorrelationId.of("x".repeat(64))!!.value.length)
        assertEquals(
            "01a14d67-a300-7000-b9f6-5ea067c5b9f5",
            RequestId.of("01a14d67-a300-7000-b9f6-5ea067c5b9f5")!!.value
        )
        assertNull(RequestId.of("01A14D67-A300-7000-B9F6-5EA067C5B9F5")) // canonical lowercase only
        assertNull(RequestId.of("01a14d67-a300-4000-b9f6-5ea067c5b9f5")) // version 4
        assertNull(RequestId.of("01a14d67-a300-7000-09f6-5ea067c5b9f5")) // wrong variant
        assertNull(RequestId.of("r1"))
    }

    @Test fun anEncodedRequestIsBoundedByTheSameOuterByteAndDepthRulesAsTheServer() {
        fun frameWith(params: JsonObject) =
            RelayEnvelope.encodeRequest(id, RelayCall(RelayMethod.INFO, params) { 1 })
        val skeleton = (
            frameWith(
                JsonObject(mapOf("a" to JsonPrimitive("")))
            ) as RelayEncoded.Frame
            ).bytes
        for (filler in listOf("x", "é", "€", "😀")) {
            val unit = filler.toByteArray().size
            val units = (RelayLimits.FRAME_BYTES - skeleton) / unit
            val pad = RelayLimits.FRAME_BYTES - skeleton - units * unit
            val exact =
                frameWith(
                    JsonObject(mapOf("a" to JsonPrimitive(filler.repeat(units) + "x".repeat(pad))))
                )
            assertEquals(filler, RelayLimits.FRAME_BYTES, (exact as RelayEncoded.Frame).bytes)
            val over =
                frameWith(
                    JsonObject(
                        mapOf("a" to JsonPrimitive(filler.repeat(units) + "x".repeat(pad + 1)))
                    )
                )
            assertEquals(filler, RelayRejection.OVERSIZE, (over as RelayEncoded.Refused).reason)
        }
        // The frame is depth 1 and `params` depth 2, so a scalar under six more objects is at depth 8 (inclusive).
        fun nest(objects: Int): JsonObject =
            (1 until objects).fold(JsonObject(mapOf("k" to JsonPrimitive(1)))) { inner, _ ->
                JsonObject(mapOf("k" to inner))
            }
        assertTrue(frameWith(nest(6)) is RelayEncoded.Frame)
        assertEquals(RelayRejection.DEPTH, (frameWith(nest(7)) as RelayEncoded.Refused).reason)
        assertEquals(
            RelayRejection.NOT_UTF8,
            (
                frameWith(
                    JsonObject(mapOf("a" to JsonPrimitive("\uD800")))
                ) as RelayEncoded.Refused
                ).reason
        )
    }

    @Test fun aWellFormedResultAndErrorFrameDecode() {
        val ok = RelayEnvelope.decodeFrame(result("detached" to "true")) as RelayFrame.Result
        assertEquals("r1", ok.id.value)
        val failure = RelayEnvelope.decodeFrame(error("busy")) as RelayFrame.Failure
        assertEquals(RelayErrorCode.BUSY, failure.error.code)
        assertEquals("r1", failure.id!!.value)
        val bare = RelayEnvelope.decodeFrame(
            error("invalid_request", id = null)
        ) as RelayFrame.Failure
        assertNull(bare.id)
    }

    @Test fun anythingBeyondTheExactEnvelopeIsNotAFrame() {
        val invalid = mapOf(
            "result and error" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":{},\"error\":{\"code\":\"busy\"}}",
            "neither" to "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\"}",
            "request type" to "{\"type\":\"relay.request\",\"v\":1,\"id\":\"r1\",\"result\":{}}",
            "other type" to "{\"type\":\"pong\",\"v\":1,\"id\":\"r1\",\"result\":{}}",
            "version 2" to "{\"type\":\"relay.response\",\"v\":2,\"id\":\"r1\",\"result\":{}}",
            "version text" to
                "{\"type\":\"relay.response\",\"v\":\"1\",\"id\":\"r1\",\"result\":{}}",
            "version float" to head("1.0"),
            "version bool" to head("true"),
            "missing version" to "{\"type\":\"relay.response\",\"id\":\"r1\",\"result\":{}}",
            "success without id" to "{\"type\":\"relay.response\",\"v\":1,\"result\":{}}",
            "bad id" to "{\"type\":\"relay.response\",\"v\":1,\"id\":\"a b\",\"result\":{}}",
            "numeric id" to "{\"type\":\"relay.response\",\"v\":1,\"id\":5,\"result\":{}}",
            "result array" to "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":[]}",
            "result null" to "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":null}",
            "result string" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":\"ok\"}",
            "unknown top member" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":{},\"extra\":1}",
            "error string" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"error\":\"busy\"}",
            "error without code" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"error\":{}}",
            "error code number" to
                "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"error\":{\"code\":4}}",
            "error code upper" to error("BUSY"),
            "error code space" to error("not found"),
            "error extra member" to error("busy", extra = ",\"detail\":\"x\""),
            "snapshot false" to error("conflict", extra = ",\"snapshot_required\":false"),
            "snapshot text" to error("conflict", extra = ",\"snapshot_required\":\"true\"")
        )
        for ((name, text) in invalid) {
            val frame = RelayEnvelope.decodeFrame(text)
            assertTrue("$name -> $frame", frame is RelayFrame.Invalid)
            val outcome = probe(RelayMethod.INFO).interpret(id, frame)
            assertTrue("$name -> $outcome", outcome is RelayOutcome.Invalid)
        }
    }

    @Test fun anUnknownErrorCodeIsStillAnErrorAndNeverSuccess() {
        val frame = RelayEnvelope.decodeFrame(error("brand_new_code"))
        val outcome = probe(RelayMethod.INFO).interpret(id, frame)
        assertEquals(RelayErrorCode.UNKNOWN, (outcome as RelayOutcome.Failed).error.code)
        assertEquals(RelayErrorAction.FAILED, outcome.error.action)
    }

    @Test fun theSnapshotFlagAlongsideAnotherCodeDemandsAFreshViewAndIsNeverDropped() {
        val frame = RelayEnvelope.decodeFrame(
            error("conflict", extra = ",\"snapshot_required\":true")
        )
        val failed = probe(
            RelayMethod.CONVERSATION_UPDATE
        ).interpret(id, frame) as RelayOutcome.Failed
        assertEquals(RelayErrorCode.CONFLICT, failed.error.code)
        assertTrue(failed.error.snapshotRequired && failed.error.needsFreshView)
        assertEquals(RelayErrorAction.REATTACH_AND_REFRESH, failed.error.action)
    }

    @Test fun aReplyIsOnlyAttributedToTheRequestItAnswersAndAnIdlessErrorIsUncorrelated() {
        val other = RelayEnvelope.decodeFrame(result("detached" to "true", id = "r9"))
        assertEquals(
            RelayProblem.ID_MISMATCH,
            (
                probe(
                    RelayMethod.CONVERSATION_DETACH
                ).interpret(id, other) as RelayOutcome.Invalid
                ).problem
        )
        val mismatchedError = RelayEnvelope.decodeFrame(error("busy", id = "r9"))
        assertEquals(
            RelayProblem.ID_MISMATCH,
            (probe(RelayMethod.INFO).interpret(id, mismatchedError) as RelayOutcome.Invalid).problem
        )
        val bare = RelayEnvelope.decodeFrame(error("invalid_request", id = null))
        val uncorrelated = probe(RelayMethod.INFO).interpret(id, bare)
        assertTrue(uncorrelated is RelayOutcome.Uncorrelated)
    }

    @Test fun outboxMethodsAnswerWithTheFirstNineCodesOnly() {
        for (code in RelayErrorCode.entries.filter { it != RelayErrorCode.UNKNOWN }) {
            val frame = RelayEnvelope.decodeFrame(error(code.wire))
            val read = probe(RelayMethod.OUTBOX_READ).interpret(id, frame)
            val conversation = probe(RelayMethod.TASK_GET).interpret(id, frame)
            assertTrue("conversation method keeps $code", conversation is RelayOutcome.Failed)
            if (code.isOutboxCode) {
                assertTrue("$code", read is RelayOutcome.Failed)
            } else {
                assertEquals(
                    RelayProblem.UNEXPECTED_ERROR_CODE,
                    (read as RelayOutcome.Invalid).problem
                )
            }
        }
        assertEquals(9, RelayErrorCode.entries.count { it.isOutboxCode })
    }

    @Test fun eachMethodAppliesItsOwnReplyByteAndDepthBound() {
        fun padded(bytes: Int) = result("pad" to "\"" + "x".repeat(bytes) + "\"")
        val small = RelayEnvelope.decodeFrame(padded(100))
        assertTrue(probe(RelayMethod.TASKS_LIST).interpret(id, small) is RelayOutcome.Success<*>)
        val skeleton = padded(0).toByteArray().size
        val atBound = RelayEnvelope.decodeFrame(padded(RelayLimits.DTO_BYTES - skeleton))
        val over = RelayEnvelope.decodeFrame(padded(RelayLimits.DTO_BYTES - skeleton + 1))
        assertTrue(probe(RelayMethod.TASKS_LIST).interpret(id, atBound) is RelayOutcome.Success<*>)
        assertEquals(
            RelayProblem.RESPONSE_TOO_LARGE,
            (probe(RelayMethod.TASKS_LIST).interpret(id, over) as RelayOutcome.Invalid).problem
        )
        // History pages and O5 reads are the two larger bounds.
        assertTrue(probe(RelayMethod.HISTORY_PAGE).interpret(id, over) is RelayOutcome.Success<*>)
        assertTrue(probe(RelayMethod.OUTBOX_READ).interpret(id, over) is RelayOutcome.Success<*>)
        // The scalar inside `[1]` is at depth 7.
        val deep7 = "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\"," +
            "\"result\":{\"a\":{\"b\":{\"c\":{\"d\":[1]}}}}}"
        assertEquals(
            RelayProblem.RESPONSE_TOO_DEEP,
            (
                probe(
                    RelayMethod.TASK_GET
                ).interpret(id, RelayEnvelope.decodeFrame(deep7)) as RelayOutcome.Invalid
                ).problem
        )
        assertTrue(
            probe(
                RelayMethod.OUTBOX_ACK
            ).interpret(id, RelayEnvelope.decodeFrame(deep7)) is RelayOutcome.Success<*>
        )
    }

    @Test fun bytesAndOversizeFramesAreRefusedBeforeAnyInterpretation() {
        val invalid = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())
        assertTrue(RelayEnvelope.decodeFrame(invalid) is RelayFrame.Invalid)
        assertTrue(
            RelayEnvelope.decodeFrame(
                ByteArray(RelayLimits.FRAME_BYTES + 1) {
                    ' '.code.toByte()
                }
            ) is RelayFrame.Invalid
        )
        val deep =
            "{\"type\":\"relay.response\",\"v\":1,\"id\":\"r1\",\"result\":" + "{\"a\":".repeat(7) +
                "1" +
                "}".repeat(7) +
                "}"
        assertEquals(
            RelayProblem.FRAME_REFUSED,
            (RelayEnvelope.decodeFrame(deep) as RelayFrame.Invalid).problem
        )
    }

    @Test fun methodNamesAndLimitsComeFromTheAcceptedSdk() {
        // The frozen 22 Task1 + seven voice methods remain; own receipt is separately optional.
        val predecessor = RelayMethod.entries - RelayMethod.TURN_RECEIPT_OWN
        assertEquals(22, predecessor.count { !it.isVoice })
        assertEquals(7, predecessor.count { it.isVoice })
        assertEquals(29, predecessor.size)
        assertEquals(RelayMethod.TURN_RECEIPT_OWN, RelayMethod.fromWire("relay.turn.receipt.own"))
        assertEquals(RelayMethod.OUTBOX_ACK, RelayMethod.fromWire("relay.outbox.ack"))
        assertNull(RelayMethod.fromWire("relay.bogus"))
        assertEquals(
            setOf(RelayMethod.OUTBOX_READ, RelayMethod.OUTBOX_ACK),
            RelayMethod.entries.filter {
                it.isOutbox
            }.toSet()
        )
        assertEquals(65_536, RelayMethod.HISTORY_PAGE.responseBytes)
        assertEquals(8_192, RelayMethod.TURN_SUBMIT.responseBytes)
        assertEquals(6, RelayMethod.EVENTS_READ.responseDepth)
        assertEquals(8, RelayMethod.OUTBOX_READ.responseDepth)
    }
}
