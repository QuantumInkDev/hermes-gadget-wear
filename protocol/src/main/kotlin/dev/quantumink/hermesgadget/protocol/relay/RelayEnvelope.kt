package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a frame or result is not usable. Carries a field path at most, never a value. */
enum class RelayProblem {
    FRAME_REFUSED,
    NOT_A_RESPONSE,
    WRONG_VERSION,
    MISSING_ID,
    ID_MISMATCH,
    RESULT_AND_ERROR,
    NEITHER_RESULT_NOR_ERROR,
    RESULT_NOT_AN_OBJECT,
    INVALID_ERROR,
    UNEXPECTED_ERROR_CODE,
    RESPONSE_TOO_LARGE,
    RESPONSE_TOO_DEEP,
    INVALID_RESULT
}

sealed interface RelayFrame {
    /** A `relay.response` carrying a result. [wireBytes] and [depth] are measured on the received frame. */
    class Result internal constructor(
        val id: CorrelationId,
        internal val result: JsonObject,
        val wireBytes: Int,
        val depth: Int
    ) : RelayFrame {
        override fun toString(): String =
            "RelayFrame.Result(id=${id.value}, bytes=$wireBytes, redacted)"
    }

    /**
     * A `relay.response` carrying an error. The id is absent when the server could not trust one (a frame it
     * refused before parsing it, or an over-size reply it replaced), so that failure cannot be attributed.
     */
    class Failure internal constructor(val id: CorrelationId?, val error: RelayError) : RelayFrame {
        override fun toString(): String = "RelayFrame.Failure(id=${id?.value}, $error)"
    }

    class Invalid internal constructor(val problem: RelayProblem, val detail: String? = null) :
        RelayFrame {
        override fun toString(): String = "RelayFrame.Invalid($problem${detail?.let {
            ", $it"
        } ?: ""})"
    }
}

/** The typed outcome of one request. Only [Success] is a success: errors, uncorrelated errors and junk are not. */
sealed interface RelayOutcome<out R : Any> {
    class Success<R : Any> internal constructor(val value: R) : RelayOutcome<R> {
        override fun toString(): String = "RelayOutcome.Success(redacted)"
    }

    class Failed internal constructor(val error: RelayError) : RelayOutcome<Nothing> {
        override fun toString(): String = "RelayOutcome.Failed($error)"
    }

    /** An error frame with no id: it must not be attributed to this request, only treated as a failed link. */
    class Uncorrelated internal constructor(val error: RelayError) : RelayOutcome<Nothing> {
        override fun toString(): String = "RelayOutcome.Uncorrelated($error)"
    }

    class Invalid internal constructor(val problem: RelayProblem, val detail: String? = null) :
        RelayOutcome<Nothing> {
        override fun toString(): String = "RelayOutcome.Invalid($problem${detail?.let {
            ", $it"
        } ?: ""})"
    }
}

sealed interface RelayEncoded {
    class Frame internal constructor(val text: String, val bytes: Int) : RelayEncoded {
        override fun toString(): String = "RelayEncoded.Frame(bytes=$bytes, redacted)"
    }

    class Refused internal constructor(val reason: RelayRejection) : RelayEncoded {
        override fun toString(): String = "RelayEncoded.Refused($reason)"
    }
}

/**
 * Envelope codec: `{type:"relay.request", v:1, id, method, params}` and `{type:"relay.response", v:1, id?, result|error}`.
 * It never sends, retries, correlates across requests or touches a socket.
 */
object RelayEnvelope {
    const val REQUEST_TYPE = "relay.request"
    const val RESPONSE_TYPE = "relay.response"
    const val VERSION = 1L

    fun encodeRequest(id: CorrelationId, call: RelayCall<*>): RelayEncoded {
        val frame = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive(REQUEST_TYPE),
                "v" to JsonPrimitive(VERSION),
                "id" to JsonPrimitive(id.value),
                "method" to JsonPrimitive(call.method.wire),
                "params" to call.params
            )
        )
        if (!RelayJson.withinDepth(
                frame,
                RelayLimits.DEPTH
            )
        ) {
            return RelayEncoded.Refused(RelayRejection.DEPTH)
        }
        val text = frame.toString()
        val bytes = RelayJson.utf8Length(text, RelayLimits.FRAME_BYTES)
        return when {
            bytes == RelayJson.LONE_SURROGATE -> RelayEncoded.Refused(RelayRejection.NOT_UTF8)
            bytes > RelayLimits.FRAME_BYTES -> RelayEncoded.Refused(RelayRejection.OVERSIZE)
            else -> RelayEncoded.Frame(text, bytes)
        }
    }

    fun decodeFrame(text: String): RelayFrame =
        interpret(RelayJson.parse(text), RelayJson.utf8Length(text))

    fun decodeFrame(bytes: ByteArray): RelayFrame = interpret(RelayJson.parse(bytes), bytes.size)

    private fun interpret(parsed: RelayParse, wireBytes: Int): RelayFrame {
        if (parsed !is RelayParse.Parsed) return RelayFrame.Invalid(RelayProblem.FRAME_REFUSED)
        val root = parsed.root
        return try {
            val reader = FieldReader.of(
                "frame",
                root,
                setOf("type", "v"),
                setOf("id", "result", "error")
            )
            if (reader.string("type", 32) !=
                RESPONSE_TYPE
            ) {
                return RelayFrame.Invalid(RelayProblem.NOT_A_RESPONSE)
            }
            if (reader.long("v", Long.MIN_VALUE) !=
                VERSION
            ) {
                return RelayFrame.Invalid(RelayProblem.WRONG_VERSION)
            }
            val id = if (reader.has("id")) reader.id("id", CorrelationId::of) else null
            val hasResult = reader.has("result")
            val hasError = reader.has("error")
            when {
                hasResult && hasError -> RelayFrame.Invalid(RelayProblem.RESULT_AND_ERROR)
                hasError -> {
                    val error = RelayError.decode(
                        reader.obj("error", setOf("code"), setOf("snapshot_required"))
                    )
                    RelayFrame.Failure(id, error)
                }
                hasResult -> {
                    if (id == null) return RelayFrame.Invalid(RelayProblem.MISSING_ID)
                    val result = root["result"] as? JsonObject
                        ?: return RelayFrame.Invalid(RelayProblem.RESULT_NOT_AN_OBJECT)
                    RelayFrame.Result(id, result, wireBytes, depthOf(root))
                }
                else -> RelayFrame.Invalid(RelayProblem.NEITHER_RESULT_NOR_ERROR)
            }
        } catch (failure: DecodeFailure) {
            RelayFrame.Invalid(
                if (failure.path.startsWith(
                        "frame.error"
                    )
                ) {
                    RelayProblem.INVALID_ERROR
                } else {
                    RelayProblem.NOT_A_RESPONSE
                },
                failure.path
            )
        }
    }

    /** Greatest item depth (root 1, scalars count). The parser already bounded it to [RelayLimits.DEPTH]. */
    private fun depthOf(root: JsonObject): Int {
        var depth = 1
        while (depth < RelayLimits.DEPTH && !RelayJson.withinDepth(root, depth)) depth++
        return depth
    }

    /** Pairs a frame with the request it answers and the method's own decoder and bounds. */
    internal fun <R : Any> outcome(
        call: RelayCall<R>,
        expected: CorrelationId,
        frame: RelayFrame
    ): RelayOutcome<R> = when (frame) {
        is RelayFrame.Invalid -> RelayOutcome.Invalid(frame.problem, frame.detail)
        is RelayFrame.Failure -> failure(call, expected, frame)
        is RelayFrame.Result -> when {
            frame.id != expected -> RelayOutcome.Invalid(RelayProblem.ID_MISMATCH)
            frame.wireBytes > call.method.responseBytes -> RelayOutcome.Invalid(
                RelayProblem.RESPONSE_TOO_LARGE
            )
            frame.depth > call.method.responseDepth -> RelayOutcome.Invalid(
                RelayProblem.RESPONSE_TOO_DEEP
            )
            else -> try {
                RelayOutcome.Success(call.decoder(frame.result))
            } catch (failure: DecodeFailure) {
                RelayOutcome.Invalid(RelayProblem.INVALID_RESULT, failure.path)
            } catch (_: IllegalArgumentException) {
                // A DTO invariant refused a combination the field-level reader could not see.
                RelayOutcome.Invalid(RelayProblem.INVALID_RESULT)
            }
        }
    }

    private fun <R : Any> failure(
        call: RelayCall<R>,
        expected: CorrelationId,
        frame: RelayFrame.Failure
    ) = when {
        (call.method.isOutbox || call.method.isWake) && !frame.error.code.isOutboxCode ->
            RelayOutcome.Invalid(RelayProblem.UNEXPECTED_ERROR_CODE)
        frame.id == null -> RelayOutcome.Uncorrelated(frame.error)
        frame.id != expected -> RelayOutcome.Invalid(RelayProblem.ID_MISMATCH)
        else -> RelayOutcome.Failed(frame.error)
    }
}

/**
 * One typed request: the method, its already-validated params and the decoder for exactly its result shape. Built
 * only by [RelayCalls]; it carries no transport, no id and no state.
 */
class RelayCall<out R : Any> internal constructor(
    val method: RelayMethod,
    internal val params: JsonObject,
    internal val decoder: (JsonObject) -> R
) {
    /** Interprets [frame] as the answer to the request sent with [expected]. */
    fun interpret(expected: CorrelationId, frame: RelayFrame): RelayOutcome<R> =
        RelayEnvelope.outcome(this, expected, frame)

    override fun toString(): String = "RelayCall(${method.wire}, params=redacted)"
}
