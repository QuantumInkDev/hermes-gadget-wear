package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The payload of one event, closed over the kinds the accepted SDK emits. */
sealed interface EventPayload {
    val kind: EventKind
    fun toWire(): JsonObject

    /** A live assistant/user message (`kind: message`): a whole row, never segmented, with no attachment. */
    data class Message(
        val messageId: MessageId,
        val role: HistoryRole,
        val text: String,
        val createdAtMs: Long
    ) : EventPayload {
        init {
            require(createdAtMs >= 0 && validText(text, RelayLimits.HISTORY_TEXT_BYTES, 0))
        }

        override val kind: EventKind get() = EventKind.MESSAGE

        override fun toString(): String = "EventPayload.Message(id=${messageId.value}, redacted)"

        override fun toWire(): JsonObject = wireObject(
            "message_id" to JsonPrimitive(messageId.value),
            "role" to JsonPrimitive(role.wire),
            "text" to JsonPrimitive(text),
            "created_at_ms" to JsonPrimitive(createdAtMs),
            "attachment_ids" to wireArray(emptyList<String>()) { JsonPrimitive(it) }
        )
    }

    /** `kind: task`: a Relay task transition; never carries a prompt. */
    data class TaskChanged(val task: Task) : EventPayload {
        init {
            require(task.prompt == null)
        }

        override val kind: EventKind get() = EventKind.TASK

        override fun toWire(): JsonObject = task.toWire()
    }

    /** `kind: conversation`: new metadata, or the terminal tombstone after a canonical delete. */
    data class ConversationChanged(val conversation: ConversationSummary, val deleted: Boolean) :
        EventPayload {
        init {
            require(!deleted || (conversation.title == null && !conversation.canContinue))
        }

        override val kind: EventKind get() = EventKind.CONVERSATION

        override fun toWire(): JsonObject = if (deleted) {
            JsonObject(
                conversation.toWire() + ("deleted" to JsonPrimitive(true))
            )
        } else {
            conversation.toWire()
        }
    }

    /** `kind: gap`: refresh history and the task snapshot; no content and never an automatic submit. */
    data object Gap : EventPayload {
        override val kind: EventKind get() = EventKind.GAP

        override fun toWire(): JsonObject = wireObject("snapshot_required" to wireBoolean(true))
    }

    /** `kind: prompt`: history of a prompt; display-only until a FETCH proves it is still current. */
    data class PromptShown(val prompt: Prompt) : EventPayload {
        init {
            require(prompt.source == PromptSource.EVENT)
        }

        override val kind: EventKind get() = EventKind.PROMPT

        override fun toWire(): JsonObject = prompt.toWire()
    }

    /** `kind: changed` (existing bindings): something changed, refresh. */
    data object Changed : EventPayload {
        override val kind: EventKind get() = EventKind.CHANGED

        override fun toWire(): JsonObject = wireObject("refresh" to wireBoolean(true))
    }
}

class RelayEvent(
    val eventId: EventId,
    val conversationId: ConversationId,
    val bindingId: BindingId,
    val sequence: Long,
    val replayEpoch: String,
    val payload: EventPayload
) {
    init {
        require(sequence >= 1 && validText(replayEpoch, RelayLimits.REPLAY_EPOCH_CHARS))
    }

    override fun equals(other: Any?): Boolean = other is RelayEvent && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "RelayEvent(seq=$sequence, kind=${payload.kind.wire})"

    fun toWire(): JsonObject = wireObject(
        "event_id" to JsonPrimitive(eventId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "binding_id" to JsonPrimitive(bindingId.value),
        "sequence" to JsonPrimitive(sequence),
        "replay_epoch" to JsonPrimitive(replayEpoch),
        "kind" to JsonPrimitive(payload.kind.wire),
        "payload" to payload.toWire()
    )

    internal companion object {
        val KEYS =
            setOf(
                "event_id",
                "conversation_id",
                "binding_id",
                "sequence",
                "replay_epoch",
                "kind",
                "payload"
            )

        fun decode(reader: FieldReader): RelayEvent {
            val kind = reader.token("kind", EventKind.entries.toTypedArray())
            val payload = when (kind) {
                EventKind.MESSAGE -> {
                    val body = reader.obj(
                        "payload",
                        setOf("message_id", "role", "text", "created_at_ms", "attachment_ids")
                    )
                    if (body.array("attachment_ids", 0).isNotEmpty()) body.fail("attachment_ids")
                    guarded(body, "message") {
                        EventPayload.Message(
                            body.id("message_id", MessageId::of),
                            body.token("role", HistoryRole.entries.toTypedArray()),
                            body.string("text", RelayLimits.HISTORY_TEXT_BYTES, 0),
                            body.long("created_at_ms")
                        )
                    }
                }
                EventKind.TASK -> guarded(reader, "payload") {
                    EventPayload.TaskChanged(
                        Task.decode(reader.obj("payload", Task.REQUIRED), null)
                    )
                }
                EventKind.CONVERSATION -> {
                    val body = reader.obj(
                        "payload",
                        ConversationSummary.REQUIRED,
                        setOf("metadata_state", "deleted")
                    )
                    val deleted = if (body.has("deleted")) {
                        body.mustBeTrue("deleted")
                        true
                    } else {
                        false
                    }
                    val summary = ConversationSummary.decode(body)
                    guarded(body, "payload") { EventPayload.ConversationChanged(summary, deleted) }
                }
                EventKind.GAP -> {
                    reader.obj(
                        "payload",
                        setOf("snapshot_required")
                    ).mustBeTrue("snapshot_required")
                    EventPayload.Gap
                }
                EventKind.PROMPT -> EventPayload.PromptShown(
                    Prompt.decode(reader.obj("payload", Prompt.KEYS), PromptSource.EVENT)
                )
                EventKind.CHANGED -> {
                    reader.obj("payload", setOf("refresh")).mustBeTrue("refresh")
                    EventPayload.Changed
                }
            }
            return guarded(reader, "event") {
                RelayEvent(
                    reader.id("event_id", EventId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.id("binding_id", BindingId::of),
                    reader.long("sequence", 1),
                    reader.string("replay_epoch", RelayLimits.REPLAY_EPOCH_CHARS),
                    payload
                )
            }
        }
    }
}

/** `relay.events.read`: pull only. [snapshotRequired] means refresh/re-attach, not an error. */
class EventsPage(
    events: List<RelayEvent>,
    val replayEpoch: String,
    val lastSequence: Long,
    val snapshotRequired: Boolean
) {
    val events: List<RelayEvent> = ownedBounded(events, RelayLimits.PAGE_ITEMS)

    init {
        require(lastSequence >= 0 && validText(replayEpoch, RelayLimits.REPLAY_EPOCH_CHARS))
        require(this.events.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(this.events.all { it.replayEpoch == replayEpoch })
        require(this.events.isEmpty() || this.events.last().sequence == lastSequence)
    }

    override fun equals(other: Any?): Boolean = other is EventsPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "EventsPage(events=${events.size}, last=$lastSequence, snapshotRequired=$snapshotRequired)"

    fun toWire(): JsonObject = wireObject(
        "events" to wireArray(events) { it.toWire() },
        "replay_epoch" to JsonPrimitive(replayEpoch),
        "last_sequence" to JsonPrimitive(lastSequence),
        "snapshot_required" to wireBoolean(snapshotRequired)
    )

    internal companion object {
        fun decode(result: JsonObject): EventsPage {
            val reader = FieldReader.of(
                "events",
                result,
                setOf("events", "replay_epoch", "last_sequence", "snapshot_required")
            )
            val events = reader.objects(
                "events",
                RelayLimits.PAGE_ITEMS,
                RelayEvent.KEYS,
                decode = RelayEvent::decode
            )
            return guarded(reader, "events") {
                EventsPage(
                    events,
                    reader.string("replay_epoch", RelayLimits.REPLAY_EPOCH_CHARS),
                    reader.long("last_sequence"),
                    reader.boolean("snapshot_required")
                )
            }
        }
    }
}
