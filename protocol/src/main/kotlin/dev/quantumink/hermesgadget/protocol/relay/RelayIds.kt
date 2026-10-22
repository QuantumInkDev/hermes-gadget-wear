package dev.quantumink.hermesgadget.protocol.relay

/** `relay.request.id`: the transport correlation id, 1–64 of `[A-Za-z0-9_-]`. Never a durable request id. */
@JvmInline
value class CorrelationId private constructor(val value: String) {
    companion object {
        fun of(value: String): CorrelationId? = value.takeIf { valid(it) }?.let(::CorrelationId)

        internal fun valid(value: String): Boolean = value.length in 1..RelayLimits.ID_CHARS &&
            value.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-'
            }
    }

    override fun toString(): String = "CorrelationId($value)"
}

/**
 * The durable client-generated `request_id`: a canonical lowercase UUID with the version-7 form. Only the form is
 * checked here; its age against the server clock and its reuse for a retry belong to the pure ledger (Task2).
 */
@JvmInline
value class RequestId private constructor(val value: String) {
    companion object {
        fun of(value: String): RequestId? = value.takeIf {
            isCanonicalUuid(it) && it[14] == '7' && it[19] in "89ab"
        }?.let(::RequestId)
    }

    override fun toString(): String = "RequestId($value)"
}

internal fun isCanonicalUuid(value: String): Boolean {
    if (value.length != 36) return false
    for (index in value.indices) {
        val c = value[index]
        val dash = index == 8 || index == 13 || index == 18 || index == 23
        if (dash) {
            if (c != '-') return false
        } else if (c !in '0'..'9' && c !in 'a'..'f') {
            return false
        }
    }
    return true
}

/*
 * Server-issued identifiers are opaque, lowercase-hyphenated UUID text. The only operation is equality: nothing
 * here parses a timestamp, version or ordering out of them, and each role is a separate type so a binding id can
 * never be handed to a call that wants a conversation id.
 */
@JvmInline
value class ConversationId private constructor(val value: String) {
    companion object {
        fun of(value: String): ConversationId? =
            value.takeIf(::isCanonicalUuid)?.let(::ConversationId)
    }
    override fun toString(): String = "ConversationId($value)"
}

@JvmInline
value class BindingId private constructor(val value: String) {
    companion object {
        fun of(value: String): BindingId? = value.takeIf(::isCanonicalUuid)?.let(::BindingId)
    }
    override fun toString(): String = "BindingId($value)"
}

@JvmInline
value class TaskId private constructor(val value: String) {
    companion object {
        fun of(value: String): TaskId? = value.takeIf(::isCanonicalUuid)?.let(::TaskId)
    }
    override fun toString(): String = "TaskId($value)"
}

@JvmInline
value class AdmissionId private constructor(val value: String) {
    companion object {
        fun of(value: String): AdmissionId? = value.takeIf(::isCanonicalUuid)?.let(::AdmissionId)
    }
    override fun toString(): String = "AdmissionId($value)"
}

@JvmInline
value class RunId private constructor(val value: String) {
    companion object {
        fun of(value: String): RunId? = value.takeIf(::isCanonicalUuid)?.let(::RunId)
    }
    override fun toString(): String = "RunId($value)"
}

@JvmInline
value class MessageId private constructor(val value: String) {
    companion object {
        fun of(value: String): MessageId? = value.takeIf(::isCanonicalUuid)?.let(::MessageId)
    }
    override fun toString(): String = "MessageId($value)"
}

@JvmInline
value class EventId private constructor(val value: String) {
    companion object {
        fun of(value: String): EventId? = value.takeIf(::isCanonicalUuid)?.let(::EventId)
    }
    override fun toString(): String = "EventId($value)"
}

@JvmInline
value class DeliveryId private constructor(val value: String) {
    companion object {
        fun of(value: String): DeliveryId? = value.takeIf(::isCanonicalUuid)?.let(::DeliveryId)
    }
    override fun toString(): String = "DeliveryId($value)"
}

@JvmInline
value class PromptId private constructor(val value: String) {
    companion object {
        fun of(value: String): PromptId? = value.takeIf(::isCanonicalUuid)?.let(::PromptId)
    }
    override fun toString(): String = "PromptId($value)"
}

/** The opaque `profile_id` a grant names (the server's UUID); equality only. */
@JvmInline
value class ProfileId private constructor(val value: String) {
    companion object {
        fun of(value: String): ProfileId? = value.takeIf(::isCanonicalUuid)?.let(::ProfileId)
    }
    override fun toString(): String = "ProfileId($value)"
}

/*
 * O5 FINAL-TEXT identifiers are NOT canonical UUIDs by contract. The accepted `Outbox.put` stores any non-empty
 * UTF-8 string of at most 256 bytes (event), 128 bytes (conversation) and 36 bytes (optional message id), and
 * `Outbox.read` returns them untouched; an accepted test asserts the real adapter delivers `stock-chat` /
 * `stock-final`. (Production capture happens to derive UUIDv5 values; that does not narrow the wire.) These
 * types therefore carry the exact text: no trimming, case folding, normalization or parsing, equality by exact
 * value, a distinct type per role, and no conversion to the canonical-UUID types that scope task-change copies,
 * bindings and conversations.
 */
@JvmInline
value class OpaqueConversationRef private constructor(val value: String) {
    companion object {
        const val MAX_BYTES = 128

        fun of(value: String): OpaqueConversationRef? =
            value.takeIf { validText(it, MAX_BYTES) }?.let(::OpaqueConversationRef)
    }

    override fun toString(): String = "OpaqueConversationRef(redacted)"
}

@JvmInline
value class OpaqueEventRef private constructor(val value: String) {
    companion object {
        const val MAX_BYTES = 256

        fun of(value: String): OpaqueEventRef? =
            value.takeIf { validText(it, MAX_BYTES) }?.let(::OpaqueEventRef)
    }

    override fun toString(): String = "OpaqueEventRef(redacted)"
}

/** The optional `message_id` of a shared-conversation final: at most 36 bytes, otherwise opaque. */
@JvmInline
value class OpaqueMessageRef private constructor(val value: String) {
    /** The same text as a canonical [MessageId] only when it already is one, exactly; never normalized. */
    val asCanonicalOrNull: MessageId? get() = MessageId.of(value)

    companion object {
        const val MAX_BYTES = 36

        fun of(value: String): OpaqueMessageRef? =
            value.takeIf { validText(it, MAX_BYTES) }?.let(::OpaqueMessageRef)
    }

    override fun toString(): String = "OpaqueMessageRef(redacted)"
}
