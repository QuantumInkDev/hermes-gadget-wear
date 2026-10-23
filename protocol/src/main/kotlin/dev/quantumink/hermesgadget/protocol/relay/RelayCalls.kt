package dev.quantumink.hermesgadget.protocol.relay

import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCalls
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed requests with the exact method-specific parameter bounds of the accepted SDK (`Conversations._FIELDS`,
 * `Conversations.params`, `Outbox._string`, `relay_prompts.params`, `relay_cancel.params`, `relay_list.validate`).
 * Each builder refuses with [IllegalArgumentException] what the server refuses, before anything is built or sent,
 * so a caller can never transmit an over-size, wrong-typed or unknown member. Optional members are omitted, not
 * null. Nothing here sends, retries, persists or chooses the id: ids come from the caller (a durable [RequestId]
 * from the future ledger; a [CorrelationId] only at encode time) and a call is only valid if `relay.info` lists it.
 */
object RelayCalls {
    fun info(): RelayCall<Info> = RelayCall(RelayMethod.INFO, wireObject(), Info::decode)

    fun createConversation(
        requestId: RequestId,
        title: String? = null
    ): RelayCall<ConversationSummary> {
        require(
            title == null ||
                validTitle(
                    title,
                    RelayLimits.CREATE_TITLE_BYTES,
                    RelayLimits.CREATE_TITLE_CODE_POINTS
                )
        ) {
            "Invalid title."
        }
        val params =
            linkedMapOf<String, JsonElement>("request_id" to JsonPrimitive(requestId.value))
        title?.let { params["title"] = JsonPrimitive(it) }
        return RelayCall(
            RelayMethod.CONVERSATION_CREATE,
            JsonObject(params),
            ::decodeConversationSummary
        )
    }

    fun attach(conversationId: ConversationId): RelayCall<Binding> = RelayCall(
        RelayMethod.CONVERSATION_ATTACH,
        wireObject("conversation_id" to JsonPrimitive(conversationId.value)),
        Binding::decode
    )

    fun detach(bindingId: BindingId): RelayCall<Detached> = RelayCall(
        RelayMethod.CONVERSATION_DETACH,
        wireObject("binding_id" to JsonPrimitive(bindingId.value)),
        Detached::decode
    )

    /** [text] is immutable user content: 1..16,384 UTF-8 bytes and at most 4,000 code points. */
    fun submit(
        binding: BindingRef,
        requestId: RequestId,
        expiresAtMs: Long,
        text: String
    ): RelayCall<Receipt> {
        require(expiresAtMs >= 0 && validSubmitText(text)) { "Invalid turn." }
        return RelayCall(
            RelayMethod.TURN_SUBMIT,
            wireObject(
                "binding_id" to JsonPrimitive(binding.bindingId.value),
                "authority_epoch" to JsonPrimitive(binding.authorityEpoch),
                "request_id" to JsonPrimitive(requestId.value),
                "expires_at_ms" to JsonPrimitive(expiresAtMs),
                "text" to JsonPrimitive(text)
            ),
            Receipt::decode
        )
    }

    fun receipt(conversationId: ConversationId, requestId: RequestId): RelayCall<Receipt> =
        RelayCall(
            RelayMethod.TURN_RECEIPT,
            wireObject(
                "conversation_id" to JsonPrimitive(conversationId.value),
                "request_id" to JsonPrimitive(requestId.value)
            ),
            Receipt::decode
        )

    fun history(
        conversationId: ConversationId,
        cursor: String? = null,
        limit: Int? = null
    ): RelayCall<HistoryPage> = RelayCall(
        RelayMethod.HISTORY_PAGE,
        paged(
            linkedMapOf("conversation_id" to JsonPrimitive(conversationId.value)),
            cursor,
            limit
        ),
        HistoryPage::decode
    )

    fun events(
        binding: BindingRef,
        replayEpoch: String,
        afterSequence: Long,
        limit: Int? = null
    ): RelayCall<EventsPage> {
        require(afterSequence >= 0 && validText(replayEpoch, RelayLimits.REPLAY_EPOCH_CHARS)) {
            "Invalid cursor."
        }
        val params = linkedMapOf<String, JsonElement>(
            "binding_id" to JsonPrimitive(binding.bindingId.value),
            "replay_epoch" to JsonPrimitive(replayEpoch),
            "after_sequence" to JsonPrimitive(afterSequence)
        )
        limit?.let { params["limit"] = JsonPrimitive(checkedLimit(it)) }
        return RelayCall(RelayMethod.EVENTS_READ, JsonObject(params), EventsPage::decode)
    }

    fun tasks(
        cursor: String? = null,
        limit: Int? = null,
        state: TaskState? = null
    ): RelayCall<TaskPage> {
        val params = paged(linkedMapOf(), cursor, limit).toMutableMap()
        state?.let { params["state"] = JsonPrimitive(it.wire) }
        return RelayCall(RelayMethod.TASKS_LIST, JsonObject(params), TaskPage::decode)
    }

    fun task(taskId: TaskId): RelayCall<Task> = RelayCall(
        RelayMethod.TASK_GET,
        wireObject("task_id" to JsonPrimitive(taskId.value)),
        Task::decodeGet
    )

    fun conversations(
        cursor: String? = null,
        limit: Int? = null,
        query: String? = null,
        bookmarked: Boolean? = null
    ): RelayCall<ConversationPage> {
        require(
            query == null || (query.isNotBlank() && validText(query, RelayLimits.QUERY_BYTES))
        ) {
            "Invalid query."
        }
        val params = paged(linkedMapOf(), cursor, limit).toMutableMap()
        query?.let { params["query"] = JsonPrimitive(it) }
        bookmarked?.let { params["bookmarked"] = JsonPrimitive(it) }
        return RelayCall(
            RelayMethod.CONVERSATIONS_LIST,
            JsonObject(params),
            ConversationPage::decode
        )
    }

    /** At least one of [title] / [bookmarked]; a title is 1..1,024 characters and at most 4,096 bytes. */
    fun update(
        conversationId: ConversationId,
        requestId: RequestId,
        expectedRevision: Long,
        title: String? = null,
        bookmarked: Boolean? = null
    ): RelayCall<ConversationUpdate> {
        require(expectedRevision >= 1 && (title != null || bookmarked != null)) {
            "Invalid update."
        }
        require(
            title == null ||
                validTitle(title, RelayLimits.UPDATE_TITLE_BYTES, RelayLimits.UPDATE_TITLE_CHARS)
        ) {
            "Invalid title."
        }
        val params = linkedMapOf<String, JsonElement>(
            "conversation_id" to JsonPrimitive(conversationId.value),
            "request_id" to JsonPrimitive(requestId.value),
            "expected_revision" to JsonPrimitive(expectedRevision)
        )
        title?.let { params["title"] = JsonPrimitive(it) }
        bookmarked?.let { params["bookmarked"] = JsonPrimitive(it) }
        return RelayCall(
            RelayMethod.CONVERSATION_UPDATE,
            JsonObject(params),
            ConversationUpdate::decode
        )
    }

    /** Canonical delete: invalidates BOTH devices' cached lineage. Never implied by cache expiry or purge. */
    fun delete(
        conversationId: ConversationId,
        requestId: RequestId,
        expectedRevision: Long
    ): RelayCall<Deleted> {
        require(expectedRevision >= 1) { "Invalid revision." }
        return RelayCall(
            RelayMethod.CONVERSATION_DELETE,
            wireObject(
                "conversation_id" to JsonPrimitive(conversationId.value),
                "request_id" to JsonPrimitive(requestId.value),
                "expected_revision" to JsonPrimitive(expectedRevision),
                "scope" to JsonPrimitive("canonical")
            ),
            Deleted::decode
        )
    }

    /**
     * An explicit answer to a FETCHED actionable prompt only ([ActionablePrompt] cannot be made from an event, a
     * list item, a notification or an O5 copy). The answer must be one of its choices, or free text where allowed.
     */
    fun respond(
        binding: BindingRef,
        prompt: ActionablePrompt,
        answer: PromptAnswer,
        requestId: RequestId
    ): RelayCall<AnswerAccepted> {
        require(answer.acceptableFor(prompt)) { "Invalid answer." }
        return respondChecked(binding, prompt.promptId, prompt.generation, answer, requestId)
    }

    internal fun respondChecked(
        binding: BindingRef,
        promptId: PromptId,
        generation: Long,
        answer: PromptAnswer,
        requestId: RequestId
    ): RelayCall<AnswerAccepted> {
        require(generation >= 0) { "Invalid generation." }
        val response = when (answer) {
            is PromptAnswer.Choice -> wireObject("choice" to JsonPrimitive(answer.value))
            is PromptAnswer.Text -> wireObject("text" to JsonPrimitive(answer.value))
        }
        return RelayCall(
            RelayMethod.PROMPT_RESPOND,
            wireObject(
                "binding_id" to JsonPrimitive(binding.bindingId.value),
                "authority_epoch" to JsonPrimitive(binding.authorityEpoch),
                "generation" to JsonPrimitive(generation),
                "prompt_id" to JsonPrimitive(promptId.value),
                "response" to response,
                "request_id" to JsonPrimitive(requestId.value)
            ),
            AnswerAccepted::decode
        )
    }

    /** A queued turn (generation null) is cancelled; a running claim is stopped with exactly its generation. */
    fun cancel(
        binding: BindingRef,
        target: CancelTarget,
        requestId: RequestId
    ): RelayCall<Receipt> = RelayCall(
        RelayMethod.TURN_CANCEL,
        wireObject(
            "binding_id" to JsonPrimitive(binding.bindingId.value),
            "authority_epoch" to JsonPrimitive(binding.authorityEpoch),
            "admission_id" to JsonPrimitive(target.admissionId.value),
            "generation" to wireLong(target.generation),
            "request_id" to JsonPrimitive(requestId.value)
        ),
        Receipt::decode
    )

    fun existingList(
        cursor: String? = null,
        limit: Int? = null
    ): RelayCall<ExistingConversationPage> = RelayCall(
        RelayMethod.EXISTING_LIST,
        paged(linkedMapOf(), cursor, limit),
        ExistingConversationPage::decode
    )

    fun existingHistory(
        conversationId: ConversationId,
        cursor: String? = null,
        limit: Int? = null
    ): RelayCall<HistoryPage> = RelayCall(
        RelayMethod.EXISTING_HISTORY_PAGE,
        paged(
            linkedMapOf("conversation_id" to JsonPrimitive(conversationId.value)),
            cursor,
            limit
        ),
        HistoryPage::decode
    )

    fun existingAttach(conversationId: ConversationId): RelayCall<ExistingBinding> = RelayCall(
        RelayMethod.EXISTING_ATTACH,
        wireObject("conversation_id" to JsonPrimitive(conversationId.value)),
        ExistingBinding::decode
    )

    fun existingTasks(
        conversationId: ConversationId,
        cursor: String? = null,
        limit: Int? = null
    ): RelayCall<ExistingTaskPage> = RelayCall(
        RelayMethod.EXISTING_TASKS_LIST,
        paged(
            linkedMapOf("conversation_id" to JsonPrimitive(conversationId.value)),
            cursor,
            limit
        ),
        ExistingTaskPage::decode
    )

    fun existingPrompt(bindingId: BindingId): RelayCall<ExistingPrompt> = RelayCall(
        RelayMethod.EXISTING_PROMPT_GET,
        wireObject("binding_id" to JsonPrimitive(bindingId.value)),
        ExistingPrompt::decode
    )

    fun outboxRead(limit: Int? = null): RelayCall<OutboxPage> {
        val params = linkedMapOf<String, JsonElement>()
        limit?.let { params["limit"] = JsonPrimitive(checkedLimit(it)) }
        return RelayCall(RelayMethod.OUTBOX_READ, JsonObject(params), OutboxPage::decode)
    }

    /** Acknowledge only after the item is durably stored; 1..50 ids; a repeat for a lost response is safe. */
    fun outboxAck(deliveryIds: List<DeliveryId>): RelayCall<Acknowledged> {
        val owned = ownedBounded(deliveryIds, RelayLimits.ACK_IDS)
        require(owned.isNotEmpty()) { "Nothing to acknowledge." }
        return RelayCall(
            RelayMethod.OUTBOX_ACK,
            wireObject("delivery_ids" to JsonArray(owned.map { JsonPrimitive(it.value) })),
            Acknowledged::decode
        )
    }

    /** The result decoder of [method], independent of any request (test support: golden decoding of captures). */
    internal fun resultDecoder(method: RelayMethod): (JsonObject) -> Any = when (method) {
        RelayMethod.INFO -> Info::decode
        RelayMethod.CONVERSATION_CREATE -> ::decodeConversationSummary
        RelayMethod.CONVERSATION_ATTACH -> Binding::decode
        RelayMethod.CONVERSATION_DETACH -> Detached::decode
        RelayMethod.TURN_SUBMIT,
        RelayMethod.TURN_RECEIPT,
        RelayMethod.TURN_CANCEL -> Receipt::decode
        RelayMethod.HISTORY_PAGE, RelayMethod.EXISTING_HISTORY_PAGE -> HistoryPage::decode
        RelayMethod.EVENTS_READ -> EventsPage::decode
        RelayMethod.TASKS_LIST -> TaskPage::decode
        RelayMethod.TASK_GET -> Task::decodeGet
        RelayMethod.CONVERSATIONS_LIST -> ConversationPage::decode
        RelayMethod.CONVERSATION_UPDATE -> ConversationUpdate::decode
        RelayMethod.CONVERSATION_DELETE -> Deleted::decode
        RelayMethod.PROMPT_RESPOND -> AnswerAccepted::decode
        RelayMethod.EXISTING_LIST -> ExistingConversationPage::decode
        RelayMethod.EXISTING_ATTACH -> ExistingBinding::decode
        RelayMethod.EXISTING_TASKS_LIST -> ExistingTaskPage::decode
        RelayMethod.EXISTING_PROMPT_GET -> ExistingPrompt::decode
        RelayMethod.OUTBOX_READ -> OutboxPage::decode
        RelayMethod.OUTBOX_ACK -> Acknowledged::decode
        RelayMethod.VOICE_INFO,
        RelayMethod.VOICE_CAPTURE_START,
        RelayMethod.VOICE_CAPTURE_FINISH,
        RelayMethod.VOICE_CAPTURE_RESULT,
        RelayMethod.VOICE_CAPTURE_DISCARD,
        RelayMethod.VOICE_SPEAK,
        RelayMethod.VOICE_SPEAK_STOP -> VoiceCalls.resultDecoder(method)
    }

    // -- shared validation ------------------------------------------------------------------------------

    fun validSubmitText(text: String): Boolean = validText(text, RelayLimits.SUBMIT_TEXT_BYTES) &&
        text.codePointCount(0, text.length) <= RelayLimits.SUBMIT_TEXT_CODE_POINTS

    /** Non-blank (the core sanitizer turns blank into "no title" and the server refuses it), within the bounds. */
    private fun validTitle(title: String, maxBytes: Int, maxCodePoints: Int): Boolean =
        title.isNotBlank() && validText(title, maxBytes) &&
            title.codePointCount(0, title.length) <= maxCodePoints

    private fun checkedLimit(limit: Int): Int {
        require(limit in 1..RelayLimits.PAGE_ITEMS) { "Invalid limit." }
        return limit
    }

    private fun paged(
        base: LinkedHashMap<String, JsonElement>,
        cursor: String?,
        limit: Int?
    ): JsonObject {
        require(cursor == null || validCursor(cursor)) { "Invalid cursor." }
        cursor?.let { base["cursor"] = JsonPrimitive(it) }
        limit?.let { base["limit"] = JsonPrimitive(checkedLimit(it)) }
        return JsonObject(base)
    }

    /** Parse wire params back into the typed call (test support: the inverse of the builders above). */
    internal fun fromWire(method: RelayMethod, params: JsonObject): RelayCall<*>? = try {
        parse(method, params)
    } catch (_: DecodeFailure) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parse(method: RelayMethod, params: JsonObject): RelayCall<*> {
        fun reader(required: Set<String>, optional: Set<String> = emptySet()) =
            FieldReader.of("params", params, required, optional)

        // The server also accepts an explicit `cursor: null`; the builders simply omit the member.
        fun cursor(r: FieldReader): String? =
            r.stringOrNullIfPresent("cursor", RelayLimits.CURSOR_BYTES)

        fun limit(r: FieldReader): Int? =
            if (r.has("limit")) r.long("limit", 1, 50).toInt() else null

        return when (method) {
            RelayMethod.INFO -> {
                reader(emptySet())
                info()
            }
            RelayMethod.CONVERSATION_CREATE -> reader(setOf("request_id"), setOf("title")).let {
                createConversation(
                    it.id("request_id", RequestId::of),
                    if (it.has("title")) it.string("title", 4096) else null
                )
            }
            RelayMethod.CONVERSATION_ATTACH -> attach(
                reader(setOf("conversation_id")).id("conversation_id", ConversationId::of)
            )
            RelayMethod.CONVERSATION_DETACH -> detach(
                reader(setOf("binding_id")).id("binding_id", BindingId::of)
            )
            RelayMethod.TURN_SUBMIT -> reader(
                setOf("binding_id", "authority_epoch", "request_id", "expires_at_ms", "text"),
                setOf("attachment_ids")
            ).let {
                if (it.has("attachment_ids")) it.fail("attachment_ids")
                submit(
                    BindingRef(it.id("binding_id", BindingId::of), it.long("authority_epoch")),
                    it.id("request_id", RequestId::of),
                    it.long("expires_at_ms", Long.MIN_VALUE),
                    it.string("text", 1 shl 20)
                )
            }
            RelayMethod.TURN_RECEIPT -> reader(setOf("conversation_id", "request_id")).let {
                receipt(
                    it.id("conversation_id", ConversationId::of),
                    it.id("request_id", RequestId::of)
                )
            }
            RelayMethod.HISTORY_PAGE -> reader(
                setOf("conversation_id"),
                setOf("cursor", "limit")
            ).let {
                history(it.id("conversation_id", ConversationId::of), cursor(it), limit(it))
            }
            RelayMethod.EVENTS_READ -> reader(
                setOf("binding_id", "replay_epoch", "after_sequence"),
                setOf("limit")
            ).let {
                // `authority_epoch` is not an events.read member; the epoch the call carries is irrelevant here.
                events(
                    BindingRef(it.id("binding_id", BindingId::of), 0),
                    it.string("replay_epoch", 1 shl 10),
                    it.long("after_sequence"),
                    limit(it)
                )
            }
            RelayMethod.TASKS_LIST -> reader(emptySet(), setOf("cursor", "limit", "state")).let {
                tasks(
                    cursor(it),
                    limit(it),
                    it.tokenOrNullIfPresent("state", TaskState.entries.toTypedArray())
                )
            }
            RelayMethod.TASK_GET -> task(reader(setOf("task_id")).id("task_id", TaskId::of))
            RelayMethod.CONVERSATIONS_LIST -> reader(
                emptySet(),
                setOf("cursor", "limit", "query", "bookmarked")
            ).let {
                conversations(
                    cursor(it),
                    limit(it),
                    if (it.has("query")) it.string("query", 1 shl 12) else null,
                    if (it.has("bookmarked")) it.boolean("bookmarked") else null
                )
            }
            RelayMethod.CONVERSATION_UPDATE -> reader(
                setOf("conversation_id", "request_id", "expected_revision"),
                setOf("title", "bookmarked")
            ).let {
                update(
                    it.id("conversation_id", ConversationId::of),
                    it.id("request_id", RequestId::of),
                    it.long("expected_revision", 1),
                    if (it.has("title")) it.string("title", 1 shl 14) else null,
                    if (it.has("bookmarked")) it.boolean("bookmarked") else null
                )
            }
            RelayMethod.CONVERSATION_DELETE -> reader(
                setOf("conversation_id", "request_id", "expected_revision", "scope")
            ).let {
                if (it.string("scope", 16) != "canonical") it.fail("scope")
                delete(
                    it.id("conversation_id", ConversationId::of),
                    it.id("request_id", RequestId::of),
                    it.long("expected_revision", 1)
                )
            }
            RelayMethod.PROMPT_RESPOND -> reader(
                setOf(
                    "binding_id",
                    "authority_epoch",
                    "generation",
                    "prompt_id",
                    "response",
                    "request_id"
                )
            ).let {
                val response = it.obj("response", emptySet(), setOf("choice", "text"))
                if (response.has("choice") == response.has("text")) response.fail("response")
                val answer = if (response.has("choice")) {
                    PromptAnswer.Choice(response.string("choice", RelayLimits.CHOICE_BYTES))
                } else {
                    PromptAnswer.Text(response.string("text", RelayLimits.ANSWER_BYTES))
                }
                require('\u0000' !in answer.value)
                respondChecked(
                    BindingRef(it.id("binding_id", BindingId::of), it.long("authority_epoch")),
                    it.id("prompt_id", PromptId::of),
                    it.long("generation"),
                    answer,
                    it.id("request_id", RequestId::of)
                )
            }
            RelayMethod.TURN_CANCEL -> reader(
                setOf("binding_id", "authority_epoch", "admission_id", "generation", "request_id")
            ).let {
                cancel(
                    BindingRef(it.id("binding_id", BindingId::of), it.long("authority_epoch")),
                    CancelTarget(
                        it.id("admission_id", AdmissionId::of),
                        it.longOrNull("generation", 1)
                    ),
                    it.id("request_id", RequestId::of)
                )
            }
            RelayMethod.EXISTING_LIST -> reader(emptySet(), setOf("cursor", "limit")).let {
                existingList(cursor(it), limit(it))
            }
            RelayMethod.EXISTING_HISTORY_PAGE -> reader(
                setOf("conversation_id"),
                setOf("cursor", "limit")
            ).let {
                existingHistory(it.id("conversation_id", ConversationId::of), cursor(it), limit(it))
            }
            RelayMethod.EXISTING_ATTACH ->
                existingAttach(
                    reader(setOf("conversation_id")).id("conversation_id", ConversationId::of)
                )
            RelayMethod.EXISTING_TASKS_LIST -> reader(
                setOf("conversation_id"),
                setOf("cursor", "limit")
            ).let {
                existingTasks(it.id("conversation_id", ConversationId::of), cursor(it), limit(it))
            }
            RelayMethod.EXISTING_PROMPT_GET ->
                existingPrompt(reader(setOf("binding_id")).id("binding_id", BindingId::of))
            RelayMethod.VOICE_INFO,
            RelayMethod.VOICE_CAPTURE_START,
            RelayMethod.VOICE_CAPTURE_FINISH,
            RelayMethod.VOICE_CAPTURE_RESULT,
            RelayMethod.VOICE_CAPTURE_DISCARD,
            RelayMethod.VOICE_SPEAK,
            RelayMethod.VOICE_SPEAK_STOP -> VoiceCalls.fromWire(method, params)
                ?: throw DecodeFailure("params")
            RelayMethod.OUTBOX_READ -> reader(emptySet(), setOf("limit")).let {
                outboxRead(limit(it))
            }
            RelayMethod.OUTBOX_ACK -> reader(setOf("delivery_ids")).let { r ->
                outboxAck(
                    r.array("delivery_ids", RelayLimits.ACK_IDS).mapIndexed { index, element ->
                        (element as? JsonPrimitive)?.takeIf {
                            it.isString
                        }?.content?.let(DeliveryId::of)
                            ?: r.fail("delivery_ids[$index]")
                    }
                )
            }
        }
    }

    private fun FieldReader.stringOrNullIfPresent(name: String, maxBytes: Int): String? =
        if (has(name)) stringOrNull(name, maxBytes) else null

    private fun <E> FieldReader.tokenOrNullIfPresent(
        name: String,
        values: Array<E>
    ): E? where E : Enum<E>, E : WireToken = if (has(name)) token(name, values) else null
}

internal fun decodeConversationSummary(result: JsonObject): ConversationSummary =
    ConversationSummary.decode(
        FieldReader.of(
            "conversation",
            result,
            ConversationSummary.REQUIRED,
            setOf("metadata_state")
        )
    )
