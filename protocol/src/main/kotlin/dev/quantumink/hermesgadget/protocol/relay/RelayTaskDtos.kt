package dev.quantumink.hermesgadget.protocol.relay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A live prompt projection. Whether it may ever be ANSWERED is decided by [actionable] (from an immutable [source])
 * only: the full form is a prompt the server said can be answered; every other form — the `too_large` notice, an
 * expired or settled one, an event, a list item, an O5 copy — is display-only data with no path to a response.
 *
 * The constructor is Kotlin-`internal`: code outside this module cannot build a [Prompt] or choose its [source], so
 * provenance is assigned only by the reply decoders (`relay.task.get` / `relay.existing.prompt.get` -> fetched,
 * `relay.tasks.list` -> list, `relay.events.read` -> event). This is a compile-time boundary for ordinary Kotlin
 * consumers (proved by an outside-module compile in `tools/relay-external-probe`); `internal` members remain
 * JVM-public, so it is not a barrier against reflection or a deliberately forged server reply.
 */
class Prompt internal constructor(
    val promptId: PromptId,
    val taskId: TaskId,
    val kind: PromptKind,
    val text: String?,
    choices: List<String>,
    val generation: Long,
    val expiresAtMs: Long?,
    val deadline: PromptDeadline,
    val actionable: Boolean,
    val freeText: Boolean,
    val reason: PromptReason?,
    val source: PromptSource
) {
    val choices: List<String> = ownedBounded(choices, RelayLimits.PROMPT_CHOICES)

    init {
        require(generation >= 0 && (expiresAtMs ?: 0) >= 0)
        require(text == null || validText(text, RelayLimits.PROMPT_TEXT_BYTES, 0))
        require(this.choices.all { validText(it, RelayLimits.CHOICE_BYTES, 0) })
        // The server derives `actionable` from `reason is None`; a mixed pair is not a shape it emits.
        require(actionable == (reason == null))
        require(!freeText || (actionable && kind == PromptKind.CLARIFY))
        // `text` is absent only in the explicit `too_large` notice, which also carries no options.
        require(text != null || (reason == PromptReason.TOO_LARGE && this.choices.isEmpty()))
        require(deadline == PromptDeadline.BOUNDED || expiresAtMs == null)
    }

    /**
     * The only way to obtain an [ActionablePrompt]: a prompt FETCHED by `relay.task.get` or
     * `relay.existing.prompt.get` that the server marked actionable. Events, list items and O5 copies return null.
     */
    fun asActionable(): ActionablePrompt? = if (actionable &&
        (source == PromptSource.TASK_GET || source == PromptSource.EXISTING_PROMPT_GET)
    ) {
        ActionablePrompt(this)
    } else {
        null
    }

    override fun equals(other: Any?): Boolean =
        other is Prompt && other.source == source && other.toWire() == toWire()

    override fun hashCode(): Int = toWire().hashCode() * 31 + source.hashCode()

    override fun toString(): String =
        "Prompt(id=${promptId.value}, kind=${kind.wire}, actionable=$actionable, reason=${reason?.wire}, redacted)"

    fun toWire(): JsonObject = wireObject(
        "prompt_id" to JsonPrimitive(promptId.value),
        "task_id" to JsonPrimitive(taskId.value),
        "kind" to JsonPrimitive(kind.wire),
        "text" to wireString(text),
        "choices" to wireArray(choices) { JsonPrimitive(it) },
        "generation" to JsonPrimitive(generation),
        "expires_at_ms" to wireLong(expiresAtMs),
        "deadline" to JsonPrimitive(deadline.wire),
        "actionable" to wireBoolean(actionable),
        "free_text" to wireBoolean(freeText),
        "reason" to wireString(reason?.wire)
    )

    internal companion object {
        val KEYS = setOf(
            "prompt_id", "task_id", "kind", "text", "choices", "generation", "expires_at_ms",
            "deadline", "actionable", "free_text", "reason"
        )

        fun decode(reader: FieldReader, source: PromptSource): Prompt {
            val choices = reader.array("choices", RelayLimits.PROMPT_CHOICES).mapIndexed {
                    index,
                    element
                ->
                val value = element as? JsonPrimitive
                if (value == null || !value.isString ||
                    !validText(value.content, RelayLimits.CHOICE_BYTES, 0)
                ) {
                    reader.fail("choices[$index]")
                }
                value.content
            }
            return guarded(reader, "prompt") {
                Prompt(
                    reader.id("prompt_id", PromptId::of),
                    reader.id("task_id", TaskId::of),
                    reader.token("kind", PromptKind.entries.toTypedArray()),
                    reader.stringOrNull("text", RelayLimits.PROMPT_TEXT_BYTES, 0),
                    choices,
                    reader.long("generation"),
                    reader.longOrNull("expires_at_ms"),
                    reader.token("deadline", PromptDeadline.entries.toTypedArray()),
                    reader.boolean("actionable"),
                    reader.boolean("free_text"),
                    reader.tokenOrNull("reason", PromptReason.entries.toTypedArray()),
                    source
                )
            }
        }
    }
}

/**
 * A fetched, server-actionable prompt, carrying exactly what an answer needs. It is created only by
 * [Prompt.asActionable]; nothing recovered from a notification, history, event or copy can produce one.
 */
class ActionablePrompt internal constructor(prompt: Prompt) {
    val promptId: PromptId = prompt.promptId
    val taskId: TaskId = prompt.taskId
    val kind: PromptKind = prompt.kind
    val generation: Long = prompt.generation
    val freeText: Boolean = prompt.freeText
    val choices: List<String> = prompt.choices

    override fun toString(): String =
        "ActionablePrompt(id=${promptId.value}, kind=${kind.wire}, redacted)"
}

/** One answer: exactly one of an offered choice or free text (only where the prompt allows it). */
sealed class PromptAnswer {
    abstract val value: String

    class Choice(override val value: String) : PromptAnswer() {
        override fun toString(): String = "PromptAnswer.Choice(redacted)"
    }

    class Text(override val value: String) : PromptAnswer() {
        override fun toString(): String = "PromptAnswer.Text(redacted)"
    }

    internal fun acceptableFor(prompt: ActionablePrompt): Boolean = when (this) {
        is Choice -> value in prompt.choices && validAnswer(value, RelayLimits.CHOICE_BYTES)
        is Text -> prompt.freeText && validAnswer(value, RelayLimits.ANSWER_BYTES)
    }

    private fun validAnswer(text: String, maxBytes: Int) =
        validText(text, maxBytes) && '\u0000' !in text
}

private val RELAY_STATE_OUTCOMES: Map<TaskState, (TaskOutcome?) -> Boolean> = mapOf(
    TaskState.WORKING to { it == null },
    TaskState.NEEDS_INPUT to { it == null },
    TaskState.UNKNOWN to { it == null },
    TaskState.FINISHED to { it != null && it.finished },
    TaskState.FAILED to { it == TaskOutcome.FAILED || it == TaskOutcome.REJECTED }
)

private fun stateAllowsOutcome(state: TaskState, outcome: TaskOutcome?): Boolean =
    RELAY_STATE_OUTCOMES.getValue(state)(outcome)

/**
 * A Relay task. [runId] is null while queued and never null for `needs_input`; [outcome] is null unless
 * `finished`/`failed`. [prompt] appears only on `relay.tasks.list` / `relay.task.get`, never in an event or copy.
 */
data class Task(
    val taskId: TaskId,
    val conversationId: ConversationId,
    val admissionId: AdmissionId,
    val runId: RunId?,
    val state: TaskState,
    val outcome: TaskOutcome?,
    val updatedAtMs: Long,
    val prompt: Prompt? = null
) {
    init {
        require(updatedAtMs >= 0 && stateAllowsOutcome(state, outcome))
        require(state != TaskState.NEEDS_INPUT || runId != null)
        require(prompt == null || (prompt.taskId == taskId && state == TaskState.NEEDS_INPUT))
    }

    /** A task display that is a copy never carries an answerable prompt. */
    val withoutPrompt: Task get() = if (prompt == null) this else copy(prompt = null)

    fun toWire(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "task_id" to JsonPrimitive(taskId.value),
            "conversation_id" to JsonPrimitive(conversationId.value),
            "admission_id" to JsonPrimitive(admissionId.value),
            "run_id" to wireString(runId?.value),
            "state" to JsonPrimitive(state.wire),
            "outcome" to wireString(outcome?.wire),
            "updated_at_ms" to JsonPrimitive(updatedAtMs)
        )
        prompt?.let { members["prompt"] = it.toWire() }
        return JsonObject(members)
    }

    internal companion object {
        val REQUIRED =
            setOf(
                "task_id",
                "conversation_id",
                "admission_id",
                "run_id",
                "state",
                "outcome",
                "updated_at_ms"
            )

        fun decode(reader: FieldReader, promptSource: PromptSource?): Task {
            val prompt = if (reader.has("prompt")) {
                if (promptSource == null) reader.fail("prompt")
                Prompt.decode(reader.obj("prompt", Prompt.KEYS), promptSource)
            } else {
                null
            }
            return guarded(reader, "task") {
                Task(
                    reader.id("task_id", TaskId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.id("admission_id", AdmissionId::of),
                    reader.idOrNull("run_id", RunId::of),
                    reader.token("state", TaskState.entries.toTypedArray()),
                    reader.tokenOrNull("outcome", TaskOutcome.entries.toTypedArray()),
                    reader.long("updated_at_ms"),
                    prompt
                )
            }
        }

        fun decodeGet(result: JsonObject): Task =
            decode(FieldReader.of("task", result, REQUIRED, setOf("prompt")), PromptSource.TASK_GET)
    }
}

class TaskPage(items: List<Task>, val nextCursor: String?) {
    val items: List<Task> = ownedBounded(items, RelayLimits.PAGE_ITEMS)

    init {
        require(nextCursor == null || validCursor(nextCursor))
    }

    override fun equals(other: Any?): Boolean = other is TaskPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String = "TaskPage(items=${items.size}, more=${nextCursor != null})"

    fun toWire(): JsonObject = wireObject(
        "items" to wireArray(items) { it.toWire() },
        "next_cursor" to wireString(nextCursor)
    )

    internal companion object {
        fun decode(result: JsonObject): TaskPage {
            val reader = FieldReader.of("tasks", result, setOf("items", "next_cursor"))
            val items = reader.objects(
                "items",
                RelayLimits.PAGE_ITEMS,
                Task.REQUIRED,
                setOf("prompt")
            ) {
                Task.decode(it, PromptSource.TASK_LIST)
            }
            return guarded(reader, "tasks") {
                TaskPage(items, reader.stringOrNull("next_cursor", RelayLimits.CURSOR_BYTES))
            }
        }
    }
}

data class TaskProvenance(val board: String, val taskId: String, val runId: Long) {
    init {
        require(
            runId >= 0 && board.length <= RelayLimits.PROVENANCE_CHARS &&
                taskId.length <= RelayLimits.PROVENANCE_CHARS
        )
    }

    override fun toString(): String = "TaskProvenance(redacted)"

    fun toWire(): JsonObject = wireObject(
        "kind" to JsonPrimitive("kanban"),
        "board" to JsonPrimitive(board),
        "task_id" to JsonPrimitive(taskId),
        "run_id" to JsonPrimitive(runId)
    )

    internal companion object {
        fun decode(reader: FieldReader): TaskProvenance {
            if (reader.string("kind", 16) != "kanban") reader.fail("kind")
            return guarded(reader, "provenance") {
                TaskProvenance(
                    reader.string("board", RelayLimits.PROVENANCE_CHARS, 0),
                    reader.string("task_id", RelayLimits.PROVENANCE_CHARS, 0),
                    reader.long("run_id")
                )
            }
        }
    }
}

/** An existing-session task: never `needs_input` (no prompt is ever projected with it). */
data class ExistingTask(
    val taskId: TaskId,
    val conversationId: ConversationId,
    val admissionId: AdmissionId,
    val runId: RunId?,
    val executionGeneration: Long?,
    val state: TaskState,
    val outcome: TaskOutcome?,
    val source: String,
    val provenance: TaskProvenance?
) {
    init {
        require(state != TaskState.NEEDS_INPUT && stateAllowsOutcome(state, outcome))
        require((executionGeneration ?: 0) >= 0 && validSourceLabel(source))
    }

    override fun toString(): String =
        "ExistingTask(id=${taskId.value}, state=${state.wire}, redacted)"

    fun toWire(): JsonObject = wireObject(
        "task_id" to JsonPrimitive(taskId.value),
        "conversation_id" to JsonPrimitive(conversationId.value),
        "admission_id" to JsonPrimitive(admissionId.value),
        "run_id" to wireString(runId?.value),
        "execution_generation" to wireLong(executionGeneration),
        "state" to JsonPrimitive(state.wire),
        "outcome" to wireString(outcome?.wire),
        "origin" to JsonPrimitive(TaskOrigin.EXISTING.wire),
        "source" to JsonPrimitive(source),
        "provenance" to (provenance?.toWire() ?: kotlinx.serialization.json.JsonNull)
    )

    internal companion object {
        val KEYS = setOf(
            "task_id", "conversation_id", "admission_id", "run_id", "execution_generation",
            "state", "outcome", "origin", "source", "provenance"
        )

        fun decode(reader: FieldReader): ExistingTask {
            if (reader.token("origin", TaskOrigin.entries.toTypedArray()) !=
                TaskOrigin.EXISTING
            ) {
                reader.fail("origin")
            }
            val provenance = reader.objOrNull(
                "provenance",
                setOf("kind", "board", "task_id", "run_id")
            )
                ?.let(TaskProvenance::decode)
            return guarded(reader, "existing.task") {
                ExistingTask(
                    reader.id("task_id", TaskId::of),
                    reader.id("conversation_id", ConversationId::of),
                    reader.id("admission_id", AdmissionId::of),
                    reader.idOrNull("run_id", RunId::of),
                    reader.longOrNull("execution_generation"),
                    reader.token("state", TaskState.entries.toTypedArray()),
                    reader.tokenOrNull("outcome", TaskOutcome.entries.toTypedArray()),
                    reader.string("source", RelayLimits.SOURCE_CHARS),
                    provenance
                )
            }
        }
    }
}

class ExistingTaskPage(items: List<ExistingTask>, val nextCursor: String?) {
    val items: List<ExistingTask> = ownedBounded(items, RelayLimits.PAGE_ITEMS)

    init {
        require(nextCursor == null || validCursor(nextCursor))
    }

    override fun equals(other: Any?): Boolean =
        other is ExistingTaskPage && toWire() == other.toWire()

    override fun hashCode(): Int = toWire().hashCode()

    override fun toString(): String =
        "ExistingTaskPage(items=${items.size}, more=${nextCursor != null})"

    fun toWire(): JsonObject = wireObject(
        "items" to wireArray(items) { it.toWire() },
        "next_cursor" to wireString(nextCursor)
    )

    internal companion object {
        fun decode(result: JsonObject): ExistingTaskPage {
            val reader = FieldReader.of("existing.tasks", result, setOf("items", "next_cursor"))
            val items = reader.objects(
                "items",
                RelayLimits.PAGE_ITEMS,
                ExistingTask.KEYS,
                decode = ExistingTask::decode
            )
            return guarded(reader, "existing.tasks") {
                ExistingTaskPage(
                    items,
                    reader.stringOrNull("next_cursor", RelayLimits.CURSOR_BYTES)
                )
            }
        }
    }
}

/** `relay.existing.prompt.get`: the caller's own current prompt on an existing binding, or none. */
class ExistingPrompt(val prompt: Prompt?) {
    override fun equals(other: Any?): Boolean = other is ExistingPrompt && other.prompt == prompt

    override fun hashCode(): Int = prompt.hashCode()

    override fun toString(): String = "ExistingPrompt(present=${prompt != null})"

    fun toWire(): JsonObject =
        wireObject("prompt" to (prompt?.toWire() ?: kotlinx.serialization.json.JsonNull))

    internal companion object {
        fun decode(result: JsonObject): ExistingPrompt {
            val reader = FieldReader.of("existing.prompt", result, setOf("prompt"))
            return ExistingPrompt(
                reader.objOrNull("prompt", Prompt.KEYS)?.let {
                    Prompt.decode(it, PromptSource.EXISTING_PROMPT_GET)
                }
            )
        }
    }
}
