package dev.quantumink.hermesgadget.protocol.relay

/**
 * Every bound comes from the accepted SDK v1 source (`relay_service._bounded`, `relay_dto.bounded`,
 * `Conversations.params`, `Outbox._string`, `relay_prompts`); none is a client preference.
 */
object RelayLimits {
    /** Outer frame, in UTF-8 bytes of the text frame. */
    const val FRAME_BYTES = 65_536

    /** Outer parser depth: root is 1 and scalars count as an item, so anything at depth 9 is refused. */
    const val DEPTH = 8
    const val ITEMS = 65_536

    /** Conversation DTO replies: bytes and depth of the whole `relay.response` (history pages are larger). */
    const val DTO_BYTES = 8_192
    const val DTO_DEPTH = 6
    const val HISTORY_PAGE_BYTES = 65_536
    const val OUTBOX_READ_BYTES = 65_536

    /** `relay_voice_wire.BOUNDS` for `capture.finish`, `capture.result` and `speak` (the other voice methods keep [DTO_BYTES]). */
    const val VOICE_LARGE_BYTES = 32_768

    const val PAGE_ITEMS = 50

    /**
     * Rows of a history page: at most 50 messages, each possibly split into segments (an existing-session page
     * is segmented per row), within 65,536 bytes. A generous structural bound; never a truncation.
     */
    const val HISTORY_PAGE_ROWS = 400
    const val DEFAULT_PAGE_ITEMS = 25
    const val CURSOR_BYTES = 512
    const val QUERY_BYTES = 256
    const val ID_CHARS = 64
    const val REPLAY_EPOCH_CHARS = 64
    const val ACK_IDS = 50
    const val METHODS = 64

    /** `relay.turn.submit` text: 1..16,384 UTF-8 bytes AND at most 4,000 code points. */
    const val SUBMIT_TEXT_BYTES = 16_384
    const val SUBMIT_TEXT_CODE_POINTS = 4_000
    const val HISTORY_TEXT_BYTES = 32_768
    const val OUTBOX_TEXT_BYTES = 16_384

    /** `relay.conversation.create` title: 1..512 bytes and at most 120 code points. */
    const val CREATE_TITLE_BYTES = 512
    const val CREATE_TITLE_CODE_POINTS = 120

    /** `relay.conversation.update` title: at most 1,024 characters and 4,096 bytes. */
    const val UPDATE_TITLE_CHARS = 1_024
    const val UPDATE_TITLE_BYTES = 4_096

    const val PROMPT_TEXT_BYTES = 4_096
    const val PROMPT_CHOICES = 8
    const val CHOICE_BYTES = 256
    const val ANSWER_BYTES = 4_000
    const val SOURCE_CHARS = 32
    const val PROVENANCE_CHARS = 64
    const val INT64_MAX = Long.MAX_VALUE
}
