package dev.quantumink.hermesgadget.protocol.relay

/**
 * Every wire method of the accepted SDK v1 (`relay_conversations.METHODS`, `_FIELDS`, `relay_service._METHODS`,
 * `relay_prompts.METHOD`, `relay_cancel.METHOD`). A method may be called only if `relay.info` lists it.
 *
 * @property responseBytes the whole-reply byte bound the server applies to a SUCCESS (`relay_dto.bounded`)
 * @property responseDepth the whole-reply item-depth bound for a success
 */
enum class RelayMethod(
    val wire: String,
    val responseBytes: Int = RelayLimits.DTO_BYTES,
    val responseDepth: Int = RelayLimits.DTO_DEPTH
) {
    INFO("relay.info"),
    CONVERSATION_CREATE("relay.conversation.create"),
    CONVERSATION_ATTACH("relay.conversation.attach"),
    CONVERSATION_DETACH("relay.conversation.detach"),
    TURN_SUBMIT("relay.turn.submit"),
    TURN_RECEIPT("relay.turn.receipt"),
    HISTORY_PAGE("relay.history.page", RelayLimits.HISTORY_PAGE_BYTES),
    EVENTS_READ("relay.events.read"),
    TASKS_LIST("relay.tasks.list"),
    TASK_GET("relay.task.get"),
    CONVERSATIONS_LIST("relay.conversations.list"),
    CONVERSATION_UPDATE("relay.conversation.update"),
    CONVERSATION_DELETE("relay.conversation.delete"),
    PROMPT_RESPOND("relay.prompt.respond"),
    TURN_CANCEL("relay.turn.cancel"),
    EXISTING_LIST("relay.existing.list"),
    EXISTING_HISTORY_PAGE("relay.existing.history.page", RelayLimits.HISTORY_PAGE_BYTES),
    EXISTING_ATTACH("relay.existing.attach"),
    EXISTING_TASKS_LIST("relay.existing.tasks.list"),
    EXISTING_PROMPT_GET("relay.existing.prompt.get"),
    OUTBOX_READ("relay.outbox.read", RelayLimits.OUTBOX_READ_BYTES, RelayLimits.DEPTH),
    OUTBOX_ACK("relay.outbox.ack", RelayLimits.OUTBOX_READ_BYTES, RelayLimits.DEPTH),

    // Canonical conversation voice (`relay_voice_wire.BOUNDS`): info/start/discard/stop 8,192 bytes, finish/result/speak 32,768; depth is the shared 6.
    VOICE_INFO("relay.voice.info"),
    VOICE_CAPTURE_START("relay.voice.capture.start"),
    VOICE_CAPTURE_FINISH("relay.voice.capture.finish", RelayLimits.VOICE_LARGE_BYTES),
    VOICE_CAPTURE_RESULT("relay.voice.capture.result", RelayLimits.VOICE_LARGE_BYTES),
    VOICE_CAPTURE_DISCARD("relay.voice.capture.discard"),
    VOICE_SPEAK("relay.voice.speak", RelayLimits.VOICE_LARGE_BYTES),
    VOICE_SPEAK_STOP("relay.voice.speak.stop");

    /** O5 methods answer with the first nine stable error codes only. */
    val isOutbox: Boolean get() = this == OUTBOX_READ || this == OUTBOX_ACK

    /** The seven canonical-voice methods; a build that predates them keeps the name as an unknown method of `relay.info`. */
    val isVoice: Boolean get() = wire.startsWith("relay.voice.")

    companion object {
        private val byWire: Map<String, RelayMethod> = entries.associateBy { it.wire }

        fun fromWire(name: String): RelayMethod? = byWire[name]
    }
}
