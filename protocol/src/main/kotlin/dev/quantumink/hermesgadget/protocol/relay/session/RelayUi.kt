package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.MetadataState
import dev.quantumink.hermesgadget.protocol.relay.state.TurnStatus

/** The coarse state of Relay mode for the selected phone profile. */
enum class RelayPhase {
    /** Relay is not enabled for this profile: the stock phone chat is exactly as before. */
    OFF,

    /** The stored state is being read. */
    CHECKING,

    /** The stored state cannot be used (see [RelayUiState.storage]); its bytes are kept. */
    STORAGE_PROBLEM,

    /** Enabled; waiting for the connection, the negotiation or `relay.info`. */
    CONNECTING,

    /** The server or the grant does not offer what Relay mode needs (see [RelayUiState.reason]). */
    UNAVAILABLE,

    /** The profile or server identity changed: nothing is sent until the user reviews it. */
    REVIEW_REQUIRED,

    /** `permission_denied`: sending stopped; content eviction may still be pending. */
    STOPPED,

    /** Negotiated and ready: conversations can be listed, created and selected. */
    READY,

    /** A conversation was chosen and is being attached and refreshed. */
    ATTACHING,

    /** Attached to one canonical conversation. */
    SELECTED
}

enum class RelayReason {
    NONE,
    NOT_OFFERED,
    MALFORMED_WELCOME,
    NOT_PAIRED,
    MISSING_METHODS,
    NO_SERVER_TIME,
    INFO_FAILED,
    ACCESS_DENIED,
    CLOCK_UNAVAILABLE,
    TLS_REQUIRED,
    TOO_MANY_ENROLLMENTS
}

/** One plain, content-free line for the user. The UI chooses the words. */
enum class RelayNotice {
    NONE,
    STORAGE_FULL_PENDING_ITEMS,
    STORAGE_WRITE_FAILED,
    STORAGE_UNCERTAIN,
    OBSERVATION_UNSAVED,
    OBSERVATION_UNCERTAIN,
    SEND_REFUSED_STORAGE,
    CLOCK_UNAVAILABLE,
    LEDGER_FULL,
    TURN_REFUSED,
    CONVERSATION_NOT_CONTINUABLE,
    CREATE_PENDING,
    CREATE_REFUSED,
    CREATE_EXPIRED,
    CREATE_FAILED,
    NAME_INVALID,
    BUSY_TRY_AGAIN,
    RECONNECTING,
    HISTORY_INCOMPLETE,
    SNAPSHOT_INCOMPLETE,
    PROMPT_ELSEWHERE,
    PERMISSION_DENIED,
    PROFILE_CHANGED,
    SERVER_ERROR,
    ANOMALY,
    LINK_FAILED,
    CONVERSATION_DELETED,
    VOICE_UNAVAILABLE
}

data class ConversationItem(
    val id: String,
    val title: String?,
    val updatedAtMs: Long,
    val canContinue: Boolean,
    val revision: Long = 0,
    val bookmarked: Boolean = false,
    val metadataState: MetadataState? = null
) {
    override fun toString(): String = "ConversationItem(id=$id, redacted)"
}

data class TranscriptLine(
    val id: String,
    val role: HistoryRole,
    val text: String,
    val createdAtMs: Long
) {
    override fun toString(): String = "TranscriptLine(id=$id, redacted)"
}

/** What the user sees of one typed turn. The request id is the key the actions name. */
data class TurnView(
    val requestId: String,
    val status: TurnStatus,
    val preview: String,
    val expiresAtMs: Long,
    /** A read-only receipt lookup is allowed now. */
    val canCheck: Boolean,
    /** The SAME request (same id, text, expiry) may be sent again, deliberately. */
    val canResend: Boolean,
    /** The user may stop tracking this turn (it may still run on the server). */
    val canAbandon: Boolean,
    /** The turn belongs to an earlier profile epoch and can only be cleared. */
    val previousProfile: Boolean = false
) {
    override fun toString(): String = "TurnView($requestId, $status, redacted)"
}

data class PendingCreate(val title: String?) {
    override fun toString(): String = "PendingCreate(redacted)"
}

data class RelayUiState(
    val identity: EnrollmentIdentity? = null,
    val foregroundSession: Long = 0,
    val phase: RelayPhase = RelayPhase.OFF,
    val reason: RelayReason = RelayReason.NONE,
    val enabled: Boolean = false,
    val storage: StoreProblem? = null,
    /** Short, non-secret digests of the pinned and observed profile ids, for the review screen only. */
    val pinnedDigest: String? = null,
    val observedDigest: String? = null,
    val conversations: List<ConversationItem> = emptyList(),
    val hasMore: Boolean = false,
    val listing: Boolean = false,
    val onlineQuery: String = "",
    val bookmarkFilter: Boolean? = null,
    val listUnavailableCount: Int = 0,
    val metadataIntents: List<MetadataIntent> = emptyList(),
    val canEditMetadata: Boolean = false,
    val retained: List<RetainedThread> = emptyList(),
    val retainedSelected: RetainedThread? = null,
    val retainedProblem: HistoryProblem? = null,
    val retentionDays: Int? = null,
    val cacheReview: HistoryEraseReview? = null,
    val cacheReviewConfirmation: CacheReviewConfirmation? = null,
    val pendingCreate: PendingCreate? = null,
    val selected: ConversationItem? = null,
    val resumable: ConversationItem? = null,
    val transcript: List<TranscriptLine> = emptyList(),
    val historyIncomplete: Boolean = false,
    val turns: List<TurnView> = emptyList(),
    val tasksWorking: Int? = null,
    val tasksNeedInput: Int? = null,
    val refreshOwed: Boolean = false,
    val notice: RelayNotice = RelayNotice.NONE,
    /** In-memory changes exist that are not stored yet; sending is refused until the store recovers. */
    val unsaved: Boolean = false,
    /** Saved scope content still needs a confirmed eviction commit. */
    val contentEvictionPending: Boolean = false,
    val busy: Boolean = false,
    /** The typed composer may send into the selected conversation. */
    val canSend: Boolean = false,
    /** Canonical voice is not exposed in this stage: inside a selected Relay conversation the microphone stays off. */
    val micAvailable: Boolean = false
) {
    /** True when a selected Relay conversation owns the composer (stock chat input must not be used). */
    val relayOwnsComposer: Boolean get() = retainedSelected != null || selected != null ||
        phase == RelayPhase.ATTACHING

    override fun toString(): String = "RelayUiState($phase, content=redacted)"
}
