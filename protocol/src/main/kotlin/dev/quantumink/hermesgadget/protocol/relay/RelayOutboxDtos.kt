package dev.quantumink.hermesgadget.protocol.relay

import java.util.Collections
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * O5 recovered content: DISPLAY ONLY. A copy never speaks, vibrates with content, opens a binding, submits, or
 * answers: this type has no member that yields a prompt, a request or a binding, and the payload decoders refuse
 * a prompt, control or action member outright. Fetch fresh state (`relay.task.get`, `relay.existing.tasks.list`)
 * before showing anything actionable.
 */
sealed interface DisplayOnlyCopy {
    val kind: OutboxPayloadKind
    fun toWire(): JsonObject
}

sealed interface OutboxPayload : DisplayOnlyCopy {
    /**
     * Final assistant text (a stock final, or a shared-conversation final carrying its [messageRef]). The message
     * reference is opaque text of at most 36 bytes exactly as the server stored it.
     */
    data class FinalText(val text: String, val messageRef: OpaqueMessageRef?) : OutboxPayload {
        init {
            require(validText(text, RelayLimits.OUTBOX_TEXT_BYTES))
        }

        override val kind: OutboxPayloadKind get() = OutboxPayloadKind.FINAL_TEXT

        override fun toString(): String = "OutboxPayload.FinalText(redacted)"

        override fun toWire(): JsonObject {
            val members = linkedMapOf<String, JsonElement>(
                "kind" to JsonPrimitive(kind.wire),
                "text" to JsonPrimitive(text)
            )
            messageRef?.let { members["message_id"] = JsonPrimitive(it.value) }
            return JsonObject(members)
        }
    }

    /** A truthful task snapshot. `needs_input` here means "wanted input then": fetch before showing a prompt. */
    sealed interface TaskChange : OutboxPayload {
        val origin: TaskOrigin
        val reference: OutboxReference

        override val kind: OutboxPayloadKind get() = OutboxPayloadKind.TASK_CHANGE

        data class Relay(val task: Task, override val reference: OutboxReference) : TaskChange {
            init {
                require(task.prompt == null)
            }

            override val origin: TaskOrigin get() = TaskOrigin.RELAY

            override fun toWire(): JsonObject = taskChangeWire(this, task.toWire())
        }

        data class Existing(val task: ExistingTask, override val reference: OutboxReference) :
            TaskChange {
            override val origin: TaskOrigin get() = TaskOrigin.EXISTING

            override fun toWire(): JsonObject = taskChangeWire(this, task.toWire())
        }
    }
}

private fun taskChangeWire(change: OutboxPayload.TaskChange, task: JsonObject): JsonObject =
    wireObject(
        "kind" to JsonPrimitive(change.kind.wire),
        "origin" to JsonPrimitive(change.origin.wire),
        "task" to task,
        "reference" to JsonPrimitive(change.reference.wire)
    )

/**
 * One O5 delivery. The delivery id is always a server-issued canonical UUID. The conversation/event identifiers
 * depend on the payload: a final-text copy keeps them as exact opaque text ([Final]); a task-change copy requires
 * canonical UUIDs because the source validates and scopes them ([Change]).
 */
sealed class OutboxItem(val deliveryId: DeliveryId, val createdAtMs: Long, val expiresAtMs: Long) {
    abstract val payload: OutboxPayload

    /** The server's dedup key (`event_id`) as exact text: equal only when byte-for-byte equal. */
    abstract val eventKey: String

    init {
        require(createdAtMs >= 0 && expiresAtMs >= 0)
    }

    abstract fun toWire(): JsonObject

    class Final(
        deliveryId: DeliveryId,
        val conversationRef: OpaqueConversationRef,
        val eventRef: OpaqueEventRef,
        createdAtMs: Long,
        expiresAtMs: Long,
        override val payload: OutboxPayload.FinalText
    ) : OutboxItem(deliveryId, createdAtMs, expiresAtMs) {
        override val eventKey: String get() = eventRef.value

        override fun toWire(): JsonObject = wireObject(
            "delivery_id" to JsonPrimitive(deliveryId.value),
            "conversation_id" to JsonPrimitive(conversationRef.value),
            "event_id" to JsonPrimitive(eventRef.value),
            "created_at_ms" to JsonPrimitive(createdAtMs),
            "expires_at_ms" to JsonPrimitive(expiresAtMs),
            "payload" to payload.toWire()
        )
    }

    class Change(
        deliveryId: DeliveryId,
        val conversationId: ConversationId,
        val eventId: EventId,
        createdAtMs: Long,
        expiresAtMs: Long,
        override val payload: OutboxPayload.TaskChange
    ) : OutboxItem(deliveryId, createdAtMs, expiresAtMs) {
        init {
            val task = (payload as? OutboxPayload.TaskChange.Relay)?.task?.conversationId
                ?: (payload as? OutboxPayload.TaskChange.Existing)?.task?.conversationId
            require(task == null || task == conversationId)
        }

        override val eventKey: String get() = eventId.value

        override fun toWire(): JsonObject = wireObject(
            "delivery_id" to JsonPrimitive(deliveryId.value),
            "conversation_id" to JsonPrimitive(conversationId.value),
            "event_id" to JsonPrimitive(eventId.value),
            "created_at_ms" to JsonPrimitive(createdAtMs),
            "expires_at_ms" to JsonPrimitive(expiresAtMs),
            "payload" to payload.toWire()
        )
    }

    override fun equals(other: Any?): Boolean = other is OutboxItem && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "OutboxItem(delivery=${deliveryId.value}, kind=${payload.kind.wire})"

    internal companion object {
        val KEYS =
            setOf(
                "delivery_id",
                "conversation_id",
                "event_id",
                "created_at_ms",
                "expires_at_ms",
                "payload"
            )

        fun decode(reader: FieldReader): OutboxItem {
            val body = reader.rawObject("payload")
            val kind = (body["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val delivery = reader.id("delivery_id", DeliveryId::of)
            val created = reader.long("created_at_ms")
            val expires = reader.long("expires_at_ms")
            return when (kind) {
                OutboxPayloadKind.FINAL_TEXT.wire -> {
                    val fields = reader.obj("payload", setOf("kind", "text"), setOf("message_id"))
                    val payload = guarded(fields, "final_text") {
                        OutboxPayload.FinalText(
                            fields.string("text", RelayLimits.OUTBOX_TEXT_BYTES),
                            if (fields.has("message_id")) {
                                OpaqueMessageRef.of(
                                    fields.string("message_id", OpaqueMessageRef.MAX_BYTES)
                                )
                            } else {
                                null
                            }
                        )
                    }
                    guarded(reader, "outbox.item") {
                        Final(
                            delivery,
                            OpaqueConversationRef.of(
                                reader.string("conversation_id", OpaqueConversationRef.MAX_BYTES)
                            ) ?: reader.fail("conversation_id"),
                            OpaqueEventRef.of(
                                reader.string("event_id", OpaqueEventRef.MAX_BYTES)
                            ) ?: reader.fail("event_id"),
                            created,
                            expires,
                            payload
                        )
                    }
                }
                OutboxPayloadKind.TASK_CHANGE.wire -> {
                    val fields = reader.obj("payload", setOf("kind", "origin", "task", "reference"))
                    val reference = fields.token(
                        "reference",
                        OutboxReference.entries.toTypedArray()
                    )
                    val payload = when (fields.token("origin", TaskOrigin.entries.toTypedArray())) {
                        TaskOrigin.RELAY -> OutboxPayload.TaskChange.Relay(
                            Task.decode(fields.obj("task", Task.REQUIRED), null),
                            reference
                        )
                        TaskOrigin.EXISTING -> OutboxPayload.TaskChange.Existing(
                            ExistingTask.decode(fields.obj("task", ExistingTask.KEYS)),
                            reference
                        )
                    }
                    // Task-change copies are validated by the source as canonical UUIDs and scoped by them.
                    guarded(reader, "outbox.item") {
                        Change(
                            delivery,
                            reader.id("conversation_id", ConversationId::of),
                            reader.id("event_id", EventId::of),
                            created,
                            expires,
                            payload
                        )
                    }
                }
                else -> reader.fail("payload")
            }
        }
    }
}

/**
 * `relay.outbox.read`: oldest unacknowledged first, no cursor. A larger [gapGeneration] than last seen means
 * "refresh the task list and history"; it is not an error and carries no content.
 */
class OutboxPage(items: List<OutboxItem>, val gapGeneration: Long) {
    val items: List<OutboxItem> = ownedBounded(items, RelayLimits.PAGE_ITEMS)

    init {
        require(gapGeneration >= 0)
        require(this.items.map { it.deliveryId }.toSet().size == this.items.size)
    }

    override fun equals(other: Any?): Boolean = other is OutboxPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "OutboxPage(items=${items.size}, gap=$gapGeneration)"

    fun toWire(): JsonObject = wireObject(
        "items" to wireArray(items) { it.toWire() },
        "next_cursor" to wireString(null),
        "gap_generation" to JsonPrimitive(gapGeneration)
    )

    internal companion object {
        fun decode(result: JsonObject): OutboxPage {
            val reader = FieldReader.of(
                "outbox",
                result,
                setOf("items", "next_cursor", "gap_generation")
            )
            // limits.outbox_cursor is false: a cursor here is not a shape the server emits.
            if (!reader.isNull("next_cursor")) reader.fail("next_cursor")
            val items = reader.objects(
                "items",
                RelayLimits.PAGE_ITEMS,
                OutboxItem.KEYS,
                decode = OutboxItem::decode
            )
            return guarded(reader, "outbox") { OutboxPage(items, reader.long("gap_generation")) }
        }
    }
}

/** `relay.outbox.ack`: the delivery ids now acknowledged. Repeating an ACK for a lost response is safe. */
class Acknowledged(acknowledged: List<DeliveryId>) {
    val acknowledged: List<DeliveryId> = ownedBounded(acknowledged, RelayLimits.ACK_IDS)

    init {
        require(this.acknowledged.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is Acknowledged && other.acknowledged == acknowledged

    override fun hashCode(): Int = acknowledged.hashCode()

    override fun toString(): String = "Acknowledged(${acknowledged.size})"

    fun toWire(): JsonObject = wireObject(
        "acknowledged" to wireArray(acknowledged) {
            JsonPrimitive(it.value)
        }
    )

    internal companion object {
        fun decode(result: JsonObject): Acknowledged {
            val reader = FieldReader.of("ack", result, setOf("acknowledged"))
            val ids = reader.array("acknowledged", RelayLimits.ACK_IDS).mapIndexed {
                    index,
                    element
                ->
                val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                text?.let(DeliveryId::of) ?: reader.fail("acknowledged[$index]")
            }
            return guarded(reader, "ack") { Acknowledged(Collections.unmodifiableList(ids)) }
        }
    }
}
