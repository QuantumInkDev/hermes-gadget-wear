package dev.quantumink.hermesgadget.protocol.relay

enum class TaskState(override val wire: String) : WireToken {
    WORKING("working"),
    NEEDS_INPUT("needs_input"),
    FINISHED("finished"),
    FAILED("failed"),

    /** Stays unknown: never rendered as success. */
    UNKNOWN("unknown")
}

enum class TaskOutcome(override val wire: String) : WireToken {
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    INTERRUPTED("interrupted"),
    STEERED("steered"),
    FAILED("failed"),
    REJECTED("rejected");

    internal val finished: Boolean get() = ordinal <= STEERED.ordinal
}

enum class ReceiptStatus(override val wire: String) : WireToken {
    QUEUED("queued"),
    STARTED("started"),
    UNKNOWN("unknown"),
    TERMINAL("terminal")
}

enum class ExecutionState(override val wire: String) : WireToken {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    UNKNOWN("unknown"),
    TERMINAL("terminal")
}

enum class HistoryRole(override val wire: String) : WireToken {
    USER("user"),
    ASSISTANT("assistant")
}

enum class PromptKind(override val wire: String) : WireToken {
    APPROVAL("approval"),
    CLARIFY("clarify")
}

enum class PromptDeadline(override val wire: String) : WireToken {
    BOUNDED("bounded"),
    UNLIMITED("unlimited"),
    UNKNOWN("unknown")
}

enum class PromptReason(override val wire: String) : WireToken {
    EXPIRED("expired"),
    DEADLINE_UNKNOWN("deadline_unknown"),
    TOO_LARGE("too_large"),
    EDIT("edit"),
    MULTI_SELECT("multi_select"),
    NATIVE_ONLY("native_only"),
    SETTLED("settled")
}

enum class MutationOutcome(override val wire: String) : WireToken {
    ACCEPTED("accepted"),
    SUPERSEDED("superseded"),
    RETIRED("retired"),
    REFUSED("refused"),
    ABANDONED("abandoned")
}

enum class MetadataState(override val wire: String) : WireToken {
    UNAVAILABLE("unavailable"),
    PENDING("pending")
}

enum class OutboxReference(override val wire: String) : WireToken {
    CURRENT("current"),

    /** An older truthful snapshot: show the newer one or refresh. */
    SUPERSEDED("superseded")
}

enum class TaskOrigin(override val wire: String) : WireToken {
    RELAY("relay"),
    EXISTING("existing")
}

enum class ExistingControl(override val wire: String) : WireToken {
    CANCEL_QUEUED("cancel_queued")
}

enum class EventKind(override val wire: String) : WireToken {
    MESSAGE("message"),
    TASK("task"),
    CONVERSATION("conversation"),
    GAP("gap"),
    PROMPT("prompt"),
    CHANGED("changed")
}

/** Where a decoded [Prompt] came from. Only a freshly FETCHED prompt can ever become actionable. */
enum class PromptSource {
    /** `relay.task.get`: the whole prompt (the contract's fetch). */
    TASK_GET,

    /** `relay.existing.prompt.get`: the caller's own current prompt on an existing binding. */
    EXISTING_PROMPT_GET,

    /** A `relay.tasks.list` item: the server shows options only as a notice there. */
    TASK_LIST,

    /** An event in `relay.events.read`: history, revalidated server-side but never a fetch. */
    EVENT
}
