package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `[a-z_]{1,64}`: an outcome spelling the server's core chose; kept as text so an unknown one stays unknown. */
private fun validOutcomeToken(value: String): Boolean =
    value.length in 1..RelayLimits.ID_CHARS && value.all { it in 'a'..'z' || it == '_' }

/**
 * `relay.turn.submit` / `relay.turn.receipt` / `relay.turn.cancel`. [requestId] is the DURABLE client request id
 * (never the transport correlation id). A receipt is evidence of the server state at read time and nothing more.
 */
data class Receipt(
    val requestId: RequestId,
    val admissionId: AdmissionId,
    val conversationId: ConversationId,
    val status: ReceiptStatus,
    val outcome: String?,
    val authorityEpoch: Long,
    val executionGeneration: Long?
) {
    init {
        require(authorityEpoch >= 0 && (executionGeneration ?: 0) >= 0)
        require(outcome == null || validOutcomeToken(outcome))
    }

    /** The outcome when it is one this build knows; an unknown spelling stays null (never read as success). */
    val knownOutcome: TaskOutcome? get() = TaskOutcome.entries.firstOrNull { it.wire == outcome }

    /**
     * What an explicit cancel may name for this receipt: a queued turn (generation null) or exactly the running
     * claim of a started turn. Terminal and unknown receipts name nothing.
     */
    val cancelTarget: CancelTarget?
        get() = when (status) {
            ReceiptStatus.QUEUED -> CancelTarget(admissionId, null)
            ReceiptStatus.STARTED -> executionGeneration?.takeIf {
                it >= 1
            }?.let { CancelTarget(admissionId, it) }
            else -> null
        }

    fun toWire(): JsonObject = wireObject(
        "request_id" to JsonPrimitive(requestId.value),
        "admission_id" to JsonPrimitive(admissionId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "status" to JsonPrimitive(status.wire),
        "outcome" to wireString(outcome),
        "authority_epoch" to JsonPrimitive(authorityEpoch),
        "execution_generation" to wireLong(executionGeneration)
    )

    internal companion object {
        val KEYS = setOf(
            "request_id",
            "admission_id",
            "conversation_id",
            "status",
            "outcome",
            "authority_epoch",
            "execution_generation"
        )

        fun decode(result: JsonObject): Receipt {
            val reader = FieldReader.of("receipt", result, KEYS)
            return guarded(reader, "receipt") {
                Receipt(
                    reader.id("request_id", RequestId::of),
                    reader.id("admission_id", AdmissionId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.token("status", ReceiptStatus.entries.toTypedArray()),
                    reader.stringOrNull("outcome", RelayLimits.ID_CHARS),
                    reader.long("authority_epoch"),
                    reader.longOrNull("execution_generation")
                )
            }
        }
    }
}

/** A queued turn is cancelled with a null generation; a running claim is stopped with exactly its generation. */
data class CancelTarget(val admissionId: AdmissionId, val generation: Long?) {
    init {
        require(generation == null || generation >= 1)
    }
}

/** One history row or segment: `message_id` + `segment_index`/`segment_count` assemble a message exactly. */
data class HistoryMessage(
    val messageId: MessageId,
    val role: HistoryRole,
    val text: String,
    val createdAtMs: Long,
    val segmentIndex: Int,
    val segmentCount: Int
) {
    init {
        require(createdAtMs >= 0 && segmentCount >= 1 && segmentIndex in 0 until segmentCount)
        require(validText(text, RelayLimits.HISTORY_TEXT_BYTES, 0))
    }

    override fun toString(): String =
        "HistoryMessage(id=${messageId.value}, segment=$segmentIndex/$segmentCount, redacted)"

    fun toWire(): JsonObject = wireObject(
        "message_id" to JsonPrimitive(messageId.value),
        "role" to JsonPrimitive(role.wire),
        "text" to JsonPrimitive(text),
        "created_at_ms" to JsonPrimitive(createdAtMs),
        "attachment_ids" to wireArray(emptyList<String>()) { JsonPrimitive(it) },
        "segment_index" to JsonPrimitive(segmentIndex),
        "segment_count" to JsonPrimitive(segmentCount)
    )

    internal companion object {
        val KEYS = setOf(
            "message_id",
            "role",
            "text",
            "created_at_ms",
            "attachment_ids",
            "segment_index",
            "segment_count"
        )

        fun decode(reader: FieldReader): HistoryMessage {
            // This increment has no attachments: a non-empty list is not a shape the server emits.
            if (reader.array("attachment_ids", 0).isNotEmpty()) reader.fail("attachment_ids")
            return guarded(reader, "history") {
                HistoryMessage(
                    reader.id("message_id", MessageId::of),
                    reader.token("role", HistoryRole.entries.toTypedArray()),
                    reader.string("text", RelayLimits.HISTORY_TEXT_BYTES, 0),
                    reader.long("created_at_ms"),
                    reader.long("segment_index", 0, Int.MAX_VALUE.toLong()).toInt(),
                    reader.long("segment_count", 1, Int.MAX_VALUE.toLong()).toInt()
                )
            }
        }
    }
}

/** A page of history rows. An empty page with a cursor is valid progress; it is never "the end". */
class HistoryPage(items: List<HistoryMessage>, val nextCursor: String?) {
    val items: List<HistoryMessage> = ownedBounded(items, RelayLimits.HISTORY_PAGE_ROWS)

    init {
        require(nextCursor == null || validCursor(nextCursor))
    }

    override fun equals(other: Any?): Boolean = other is HistoryPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "HistoryPage(items=${items.size}, more=${nextCursor != null})"

    fun toWire(): JsonObject = wireObject(
        "items" to wireArray(items) { it.toWire() },
        "next_cursor" to wireString(nextCursor)
    )

    internal companion object {
        fun decode(result: JsonObject): HistoryPage {
            val reader = FieldReader.of("history", result, setOf("items", "next_cursor"))
            val items = reader.objects(
                "items",
                RelayLimits.HISTORY_PAGE_ROWS,
                HistoryMessage.KEYS,
                decode = HistoryMessage::decode
            )
            return guarded(reader, "history") {
                HistoryPage(items, reader.stringOrNull("next_cursor", RelayLimits.CURSOR_BYTES))
            }
        }
    }
}

/** `{detached: true}`. */
data object Detached {
    internal fun decode(result: JsonObject): Detached {
        FieldReader.of("detached", result, setOf("detached")).mustBeTrue("detached")
        return Detached
    }

    fun toWire(): JsonObject = wireObject("detached" to wireBoolean(true))
}

/** `{deleted: true}`: the canonical conversation was deleted. Invalidate BOTH devices' cached lineage. */
data object Deleted {
    internal fun decode(result: JsonObject): Deleted {
        FieldReader.of("deleted", result, setOf("deleted")).mustBeTrue("deleted")
        return Deleted
    }

    fun toWire(): JsonObject = wireObject("deleted" to wireBoolean(true))
}

/** `{accepted: true}`: THIS answer won the native first settlement. */
data object AnswerAccepted {
    internal fun decode(result: JsonObject): AnswerAccepted {
        FieldReader.of("accepted", result, setOf("accepted")).mustBeTrue("accepted")
        return AnswerAccepted
    }

    fun toWire(): JsonObject = wireObject("accepted" to wireBoolean(true))
}
