package dev.quantumink.hermesgadget.protocol.relay

import java.util.Collections
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which transport-independent facts one `relay.info` answer established. */
enum class OutboxPayloadKind(override val wire: String) : WireToken {
    FINAL_TEXT("final_text"),
    TASK_CHANGE("task_change")
}

class InfoLimits(
    val outboxCursor: Boolean,
    val pageItems: Int,
    val textBytes: Int,
    val dtoBytes: Int?,
    val dtoDepth: Int?,
    val historyPageBytes: Int?,
    val historyTextBytes: Int?,
    outboxPayloads: List<OutboxPayloadKind>?
) {
    val outboxPayloads: List<OutboxPayloadKind>? = outboxPayloads?.let {
        ownedBounded(it, OutboxPayloadKind.entries.size)
    }

    init {
        require(
            pageItems in 1..RelayLimits.PAGE_ITEMS && textBytes in 1..RelayLimits.SUBMIT_TEXT_BYTES
        )
        val conversation = listOf(dtoBytes, dtoDepth, historyPageBytes, historyTextBytes)
        require(conversation.all { it == null } || conversation.none { it == null })
        require(
            (dtoBytes ?: 1) in 1..RelayLimits.DTO_BYTES &&
                (dtoDepth ?: 1) in 1..RelayLimits.DTO_DEPTH
        )
        require((historyPageBytes ?: 1) in 1..RelayLimits.HISTORY_PAGE_BYTES)
        require((historyTextBytes ?: 1) in 1..RelayLimits.HISTORY_TEXT_BYTES)
        require(this.outboxPayloads?.let { it.isNotEmpty() && it.toSet().size == it.size } != false)
    }

    /** The conversation-bridge form carries the DTO bounds; the O5-only service form does not. */
    val hasConversationBounds: Boolean get() = dtoBytes != null

    override fun equals(other: Any?): Boolean = other is InfoLimits && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "InfoLimits(${toWire()})"

    fun toWire(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "outbox_cursor" to wireBoolean(outboxCursor),
            "page_items" to JsonPrimitive(pageItems),
            "text_bytes" to JsonPrimitive(textBytes)
        )
        if (dtoBytes != null) {
            members["dto_bytes"] = JsonPrimitive(dtoBytes)
            members["dto_depth"] = JsonPrimitive(dtoDepth)
            members["history_page_bytes"] = JsonPrimitive(historyPageBytes)
            members["history_text_bytes"] = JsonPrimitive(historyTextBytes)
        }
        outboxPayloads?.let {
            members["outbox_payloads"] =
                wireArray(it) { kind -> JsonPrimitive(kind.wire) }
        }
        return JsonObject(members)
    }

    internal companion object {
        fun decode(reader: FieldReader): InfoLimits {
            val payloads = if (reader.has("outbox_payloads")) {
                reader.array("outbox_payloads", OutboxPayloadKind.entries.size).mapIndexed {
                        index,
                        element
                    ->
                    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: reader.fail("outbox_payloads[$index]")
                    OutboxPayloadKind.entries.firstOrNull { it.wire == text }
                        ?: reader.fail("outbox_payloads[$index]")
                }
            } else {
                null
            }
            return guarded(reader, "limits") {
                InfoLimits(
                    reader.boolean("outbox_cursor"),
                    reader.long("page_items", 1, RelayLimits.PAGE_ITEMS.toLong()).toInt(),
                    reader.long("text_bytes", 1, RelayLimits.SUBMIT_TEXT_BYTES.toLong()).toInt(),
                    if (reader.has(
                            "dto_bytes"
                        )
                    ) {
                        reader.long(
                            "dto_bytes",
                            1,
                            RelayLimits.DTO_BYTES.toLong()
                        ).toInt()
                    } else {
                        null
                    },
                    if (reader.has(
                            "dto_depth"
                        )
                    ) {
                        reader.long(
                            "dto_depth",
                            1,
                            RelayLimits.DTO_DEPTH.toLong()
                        ).toInt()
                    } else {
                        null
                    },
                    if (reader.has("history_page_bytes")) {
                        reader.long(
                            "history_page_bytes",
                            1,
                            RelayLimits.HISTORY_PAGE_BYTES.toLong()
                        ).toInt()
                    } else {
                        null
                    },
                    if (reader.has("history_text_bytes")) {
                        reader.long(
                            "history_text_bytes",
                            1,
                            RelayLimits.HISTORY_TEXT_BYTES.toLong()
                        ).toInt()
                    } else {
                        null
                    },
                    payloads
                )
            }
        }
    }
}

/** Runs a DTO constructor, turning its own invariant failure into a decode failure at [reader]'s path. */
internal inline fun <T> guarded(reader: FieldReader, field: String, build: () -> T): T = try {
    build()
} catch (_: IllegalArgumentException) {
    reader.fail(field)
}

/**
 * `relay.info`: the first call; use ONLY the listed methods. [serverTimeMs] is present in the conversation bridge form
 * (and, additively, in the O5 form while remote wake is enabled) and is the sole clock for deadlines (never the
 * device wall clock). Scope (endpoint, profile) is
 * carried by the caller in a `RelayScope`; nothing in this DTO proves authority.
 */
class Info(
    val profileId: ProfileId,
    val grantGeneration: Long,
    val expiresAtMs: Long,
    val serverTimeMs: Long?,
    methods: List<String>,
    val limits: InfoLimits
) {
    val methods: List<String> = ownedBounded(methods, RelayLimits.METHODS)

    init {
        require(grantGeneration >= 0 && expiresAtMs >= 0 && (serverTimeMs ?: 0) >= 0)
        require(this.methods.firstOrNull() == RelayMethod.INFO.wire)
        require(this.methods.toSet().size == this.methods.size)
        require(
            this.methods.all {
                it.length in 1..RelayLimits.ID_CHARS &&
                    it.all { c -> c in 'a'..'z' || c == '.' || c == '_' }
            }
        )
        // The conversation form always carries the clock; the O5 form carries it only while remote wake is enabled.
        require(serverTimeMs != null || !limits.hasConversationBounds)
    }

    private val known: Set<RelayMethod> = this.methods.mapNotNull(RelayMethod::fromWire).toSet()

    /** Methods this build understands AND the server lists. */
    val supported: Set<RelayMethod> get() = Collections.unmodifiableSet(known)

    /** Listed names this build does not know: never callable from here. */
    val unknownMethods: List<String> get() = methods.filter { RelayMethod.fromWire(it) == null }

    fun advertises(method: RelayMethod): Boolean = method in known

    override fun equals(other: Any?): Boolean = other is Info && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "Info(profile=${profileId.value}, methods=${methods.size})"

    fun toWire(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "profile_id" to JsonPrimitive(profileId.value),
            "grant_generation" to JsonPrimitive(grantGeneration),
            "expires_at_ms" to JsonPrimitive(expiresAtMs)
        )
        serverTimeMs?.let { members["server_time_ms"] = JsonPrimitive(it) }
        members["methods"] = wireArray(methods) { JsonPrimitive(it) }
        members["limits"] = limits.toWire()
        return JsonObject(members)
    }

    internal companion object {
        fun decode(result: JsonObject): Info {
            val reader = FieldReader.of(
                "info",
                result,
                setOf("profile_id", "grant_generation", "expires_at_ms", "methods", "limits"),
                setOf("server_time_ms")
            )
            val names = reader.array("methods", RelayLimits.METHODS).mapIndexed { index, element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: reader.fail("methods[$index]")
            }
            val limits = InfoLimits.decode(
                reader.obj(
                    "limits",
                    setOf("outbox_cursor", "page_items", "text_bytes"),
                    setOf(
                        "dto_bytes",
                        "dto_depth",
                        "history_page_bytes",
                        "history_text_bytes",
                        "outbox_payloads"
                    )
                )
            )
            return guarded(reader, "info") {
                Info(
                    reader.id("profile_id", ProfileId::of),
                    reader.long("grant_generation"),
                    reader.long("expires_at_ms"),
                    if (reader.has("server_time_ms")) reader.long("server_time_ms") else null,
                    names,
                    limits
                )
            }
        }
    }
}

/** A conversation as `relay.conversations.list`, create, update and the `conversation` event show it. */
data class ConversationSummary(
    val conversationId: ConversationId,
    val title: String?,
    val revision: Long,
    val bookmarked: Boolean,
    val updatedAtMs: Long,
    val canContinue: Boolean,
    /** A fresh view can never cure `unavailable`; writes are refused. */
    val metadataState: MetadataState? = null
) {
    init {
        require(revision >= 1 && updatedAtMs >= 0)
        require(title == null || validText(title, RelayLimits.UPDATE_TITLE_BYTES))
    }

    override fun toString(): String =
        "ConversationSummary(id=${conversationId.value}, revision=$revision, redacted)"

    fun toWire(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "conversation_id" to JsonPrimitive(conversationId.value),
            "title" to wireString(title),
            "revision" to JsonPrimitive(revision),
            "bookmarked" to wireBoolean(bookmarked),
            "updated_at_ms" to JsonPrimitive(updatedAtMs),
            "can_continue" to wireBoolean(canContinue)
        )
        metadataState?.let { members["metadata_state"] = JsonPrimitive(it.wire) }
        return JsonObject(members)
    }

    internal companion object {
        val REQUIRED =
            setOf(
                "conversation_id",
                "title",
                "revision",
                "bookmarked",
                "updated_at_ms",
                "can_continue"
            )

        fun decode(reader: FieldReader): ConversationSummary = guarded(reader, "conversation") {
            ConversationSummary(
                reader.id("conversation_id", ConversationId::of),
                reader.stringOrNull("title", RelayLimits.UPDATE_TITLE_BYTES),
                reader.long("revision", 1),
                reader.boolean("bookmarked"),
                reader.long("updated_at_ms"),
                reader.boolean("can_continue"),
                if (reader.has(
                        "metadata_state"
                    )
                ) {
                    reader.token(
                        "metadata_state",
                        MetadataState.entries.toTypedArray()
                    )
                } else {
                    null
                }
            )
        }
    }
}

/** `relay.conversation.update`: the summary plus the terminal outcome of the journaled intent. */
data class ConversationUpdate(
    val conversation: ConversationSummary,
    val mutationOutcome: MutationOutcome,
    val coreCommitted: Boolean
) {
    fun toWire(): JsonObject = JsonObject(
        conversation.toWire() + mapOf(
            "mutation_outcome" to JsonPrimitive(mutationOutcome.wire),
            "core_committed" to wireBoolean(coreCommitted)
        )
    )

    internal companion object {
        fun decode(result: JsonObject): ConversationUpdate {
            val reader = FieldReader.of(
                "update",
                result,
                ConversationSummary.REQUIRED + setOf("mutation_outcome", "core_committed"),
                setOf("metadata_state")
            )
            return ConversationUpdate(
                ConversationSummary.decode(reader),
                reader.token("mutation_outcome", MutationOutcome.entries.toTypedArray()),
                reader.boolean("core_committed")
            )
        }
    }
}

data class UnavailableConversation(
    val conversationId: ConversationId,
    val metadataState: MetadataState
)

class ConversationPage(
    items: List<ConversationSummary>,
    val nextCursor: String?,
    unavailable: List<UnavailableConversation>,
    val unavailableCount: Int?
) {
    val items: List<ConversationSummary> = ownedBounded(items, RelayLimits.PAGE_ITEMS)
    val unavailable: List<UnavailableConversation> = ownedBounded(unavailable, MAX_UNAVAILABLE)

    init {
        require(nextCursor == null || validCursor(nextCursor))
        require(unavailableCount != null || this.unavailable.isEmpty())
        require(unavailableCount == null || unavailableCount >= this.unavailable.size)
    }

    override fun equals(other: Any?): Boolean =
        other is ConversationPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "ConversationPage(items=${items.size}, more=${nextCursor != null})"

    fun toWire(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "items" to wireArray(items) { it.toWire() },
            "next_cursor" to wireString(nextCursor)
        )
        if (unavailableCount != null) {
            members["unavailable"] = wireArray(unavailable) {
                wireObject(
                    "conversation_id" to JsonPrimitive(it.conversationId.value),
                    "metadata_state" to JsonPrimitive(it.metadataState.wire)
                )
            }
            members["unavailable_count"] = JsonPrimitive(unavailableCount)
        }
        return JsonObject(members)
    }

    internal companion object {
        const val MAX_UNAVAILABLE = 16

        fun decode(result: JsonObject): ConversationPage {
            val reader = FieldReader.of(
                "conversations",
                result,
                setOf("items", "next_cursor"),
                setOf("unavailable", "unavailable_count")
            )
            if (reader.has("unavailable") !=
                reader.has("unavailable_count")
            ) {
                reader.fail("unavailable")
            }
            val items = reader.objects(
                "items",
                RelayLimits.PAGE_ITEMS,
                ConversationSummary.REQUIRED,
                setOf("metadata_state"),
                ConversationSummary::decode
            )
            val unavailable = if (reader.has("unavailable")) {
                reader.objects(
                    "unavailable",
                    MAX_UNAVAILABLE,
                    setOf("conversation_id", "metadata_state")
                ) {
                    UnavailableConversation(
                        it.id("conversation_id", ConversationId::of),
                        it.token("metadata_state", MetadataState.entries.toTypedArray())
                    )
                }
            } else {
                emptyList()
            }
            return guarded(reader, "conversations") {
                ConversationPage(
                    items,
                    reader.stringOrNull("next_cursor", RelayLimits.CURSOR_BYTES),
                    unavailable,
                    if (reader.has(
                            "unavailable_count"
                        )
                    ) {
                        reader.long(
                            "unavailable_count",
                            1,
                            Int.MAX_VALUE.toLong()
                        ).toInt()
                    } else {
                        null
                    }
                )
            }
        }
    }
}

/**
 * A transport binding: valid only for the socket that attached, and retired by the next attach. It is deliberately
 * not serializable and not convertible to a persisted identity — persist the conversation identity, never this.
 */
class Binding(
    val bindingId: BindingId,
    val conversationId: ConversationId,
    val authorityEpoch: Long,
    val executionGeneration: Long,
    val revision: Long,
    val replayEpoch: String,
    val lastSequence: Long,
    val executionState: ExecutionState
) {
    init {
        require(
            authorityEpoch >= 0 && executionGeneration >= 0 && revision >= 0 && lastSequence >= 0
        )
        require(validText(replayEpoch, RelayLimits.REPLAY_EPOCH_CHARS))
    }

    val ref: BindingRef get() = BindingRef(bindingId, authorityEpoch)

    override fun equals(other: Any?): Boolean = other is Binding && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "Binding(id=${bindingId.value}, epoch=$authorityEpoch)"

    fun toWire(): JsonObject = wireObject(
        "binding_id" to JsonPrimitive(bindingId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "authority_epoch" to JsonPrimitive(authorityEpoch),
        "execution_generation" to JsonPrimitive(executionGeneration),
        "revision" to JsonPrimitive(revision),
        "replay_epoch" to JsonPrimitive(replayEpoch),
        "last_sequence" to JsonPrimitive(lastSequence),
        "execution_state" to JsonPrimitive(executionState.wire)
    )

    internal companion object {
        fun decode(result: JsonObject): Binding {
            val reader = FieldReader.of(
                "binding",
                result,
                setOf(
                    "binding_id",
                    "conversation_id",
                    "authority_epoch",
                    "execution_generation",
                    "revision",
                    "replay_epoch",
                    "last_sequence",
                    "execution_state"
                )
            )
            return guarded(reader, "binding") {
                Binding(
                    reader.id("binding_id", BindingId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.long("authority_epoch"),
                    reader.long("execution_generation"),
                    reader.long("revision"),
                    reader.string("replay_epoch", RelayLimits.REPLAY_EPOCH_CHARS),
                    reader.long("last_sequence"),
                    reader.token("execution_state", ExecutionState.entries.toTypedArray())
                )
            }
        }
    }
}

/** The binding id and authority epoch a request must carry. Not persistable. */
data class BindingRef(val bindingId: BindingId, val authorityEpoch: Long) {
    init {
        require(authorityEpoch >= 0)
    }
}

/** `relay.existing.attach`: read-only unless the owner's live delegation lets this device continue it. */
class ExistingBinding(
    val bindingId: BindingId,
    val conversationId: ConversationId,
    val authorityEpoch: Long,
    val executionGeneration: Long?,
    val revision: Long?,
    val replayEpoch: String,
    val lastSequence: Long,
    val executionState: ExecutionState,
    val source: String,
    val readOnly: Boolean,
    val canContinue: Boolean,
    controls: List<ExistingControl>
) {
    val controls: List<ExistingControl> = ownedBounded(controls, ExistingControl.entries.size)

    init {
        require(
            authorityEpoch >= 0 && (executionGeneration ?: 0) >= 0 && (revision ?: 0) >= 0 &&
                lastSequence >= 0
        )
        require(validText(replayEpoch, RelayLimits.REPLAY_EPOCH_CHARS) && validSourceLabel(source))
        require(readOnly == !canContinue)
        require(
            this.controls.toSet().size == this.controls.size &&
                (readOnly == false || this.controls.isEmpty())
        )
    }

    val ref: BindingRef get() = BindingRef(bindingId, authorityEpoch)

    override fun equals(other: Any?): Boolean =
        other is ExistingBinding && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "ExistingBinding(id=${bindingId.value}, readOnly=$readOnly)"

    fun toWire(): JsonObject = wireObject(
        "binding_id" to JsonPrimitive(bindingId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "authority_epoch" to JsonPrimitive(authorityEpoch),
        "execution_generation" to wireLong(executionGeneration),
        "revision" to wireLong(revision),
        "replay_epoch" to JsonPrimitive(replayEpoch),
        "last_sequence" to JsonPrimitive(lastSequence),
        "execution_state" to JsonPrimitive(executionState.wire),
        "origin" to JsonPrimitive(TaskOrigin.EXISTING.wire),
        "source" to JsonPrimitive(source),
        "read_only" to wireBoolean(readOnly),
        "can_continue" to wireBoolean(canContinue),
        "controls" to wireArray(controls) { JsonPrimitive(it.wire) }
    )

    internal companion object {
        fun decode(result: JsonObject): ExistingBinding {
            val reader = FieldReader.of(
                "existing.binding",
                result,
                setOf(
                    "binding_id", "conversation_id", "authority_epoch", "execution_generation",
                    "revision", "replay_epoch", "last_sequence", "execution_state", "origin",
                    "source", "read_only", "can_continue", "controls"
                )
            )
            if (reader.token("origin", TaskOrigin.entries.toTypedArray()) !=
                TaskOrigin.EXISTING
            ) {
                reader.fail("origin")
            }
            val controls = reader.array("controls", ExistingControl.entries.size).mapIndexed {
                    index,
                    element
                ->
                val text =
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: reader.fail("controls[$index]")
                ExistingControl.entries.firstOrNull { it.wire == text }
                    ?: reader.fail("controls[$index]")
            }
            return guarded(reader, "existing.binding") {
                ExistingBinding(
                    reader.id("binding_id", BindingId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.long("authority_epoch"),
                    reader.longOrNull("execution_generation"),
                    reader.longOrNull("revision"),
                    reader.string("replay_epoch", RelayLimits.REPLAY_EPOCH_CHARS),
                    reader.long("last_sequence"),
                    reader.token("execution_state", ExecutionState.entries.toTypedArray()),
                    reader.string("source", RelayLimits.SOURCE_CHARS),
                    reader.boolean("read_only"),
                    reader.boolean("can_continue"),
                    controls
                )
            }
        }
    }
}

/** An existing (non-Relay) session as `relay.existing.list` shows it: read-only unless the owner delegates. */
data class ExistingConversation(
    val conversationId: ConversationId,
    val title: String?,
    val updatedAtMs: Long,
    val source: String,
    val canContinue: Boolean
) {
    init {
        require(updatedAtMs >= 0 && validSourceLabel(source))
        require(title == null || validText(title, RelayLimits.UPDATE_TITLE_BYTES))
    }

    override fun toString(): String = "ExistingConversation(id=${conversationId.value}, redacted)"

    fun toWire(): JsonObject = wireObject(
        "conversation_id" to JsonPrimitive(conversationId.value),
        "title" to wireString(title),
        "updated_at_ms" to JsonPrimitive(updatedAtMs),
        "origin" to JsonPrimitive(TaskOrigin.EXISTING.wire),
        "source" to JsonPrimitive(source),
        "can_continue" to wireBoolean(canContinue)
    )

    internal companion object {
        val KEYS =
            setOf("conversation_id", "title", "updated_at_ms", "origin", "source", "can_continue")

        fun decode(reader: FieldReader): ExistingConversation {
            if (reader.token("origin", TaskOrigin.entries.toTypedArray()) !=
                TaskOrigin.EXISTING
            ) {
                reader.fail("origin")
            }
            return guarded(reader, "existing.conversation") {
                ExistingConversation(
                    reader.id("conversation_id", ConversationId::of),
                    reader.stringOrNull("title", RelayLimits.UPDATE_TITLE_BYTES),
                    reader.long("updated_at_ms"),
                    reader.string("source", RelayLimits.SOURCE_CHARS),
                    reader.boolean("can_continue")
                )
            }
        }
    }
}

class ExistingConversationPage(items: List<ExistingConversation>, val nextCursor: String?) {
    val items: List<ExistingConversation> = ownedBounded(items, RelayLimits.PAGE_ITEMS)

    init {
        require(nextCursor == null || validCursor(nextCursor))
    }

    override fun equals(other: Any?): Boolean =
        other is ExistingConversationPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "ExistingConversationPage(items=${items.size}, more=${nextCursor != null})"

    fun toWire(): JsonObject = wireObject(
        "items" to wireArray(items) { it.toWire() },
        "next_cursor" to wireString(nextCursor)
    )

    internal companion object {
        fun decode(result: JsonObject): ExistingConversationPage {
            val reader = FieldReader.of("existing.list", result, setOf("items", "next_cursor"))
            val items = reader.objects(
                "items",
                RelayLimits.PAGE_ITEMS,
                ExistingConversation.KEYS,
                decode = ExistingConversation::decode
            )
            return guarded(reader, "existing.list") {
                ExistingConversationPage(
                    items,
                    reader.stringOrNull("next_cursor", RelayLimits.CURSOR_BYTES)
                )
            }
        }
    }
}
