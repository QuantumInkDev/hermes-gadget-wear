package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.MetadataState
import dev.quantumink.hermesgadget.protocol.relay.MutationOutcome
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.Receipt
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCallResult
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayChannelState
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayFailure
import dev.quantumink.hermesgadget.protocol.relay.RelayInvalid
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.RelayLimits
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
import dev.quantumink.hermesgadget.protocol.relay.state.CallKind
import dev.quantumink.hermesgadget.protocol.relay.state.CallRef
import dev.quantumink.hermesgadget.protocol.relay.state.DeliveryEffect
import dev.quantumink.hermesgadget.protocol.relay.state.DiscardMode
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.EventCommit
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryAssembly
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryInvalidation
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryScope
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerStep
import dev.quantumink.hermesgadget.protocol.relay.state.MintResult
import dev.quantumink.hermesgadget.protocol.relay.state.PageOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PlanRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.PrepareOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.Reactivation
import dev.quantumink.hermesgadget.protocol.relay.state.ReadPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.ReceiptLookup
import dev.quantumink.hermesgadget.protocol.relay.state.RefreshKind
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ReplyOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIdEntropy
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeStatus
import dev.quantumink.hermesgadget.protocol.relay.state.ServerClock
import dev.quantumink.hermesgadget.protocol.relay.state.ServerInstant
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.StageOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnDraft
import dev.quantumink.hermesgadget.protocol.relay.state.TurnEffect
import dev.quantumink.hermesgadget.protocol.relay.state.TurnEntry
import dev.quantumink.hermesgadget.protocol.relay.state.TurnKey
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The enrollment a phone session runs under: its canonical endpoint and THIS phone's own public enrollment label. */
class EnrollmentIdentity(val endpointUrl: String, val enrollmentLabel: String) {
    init {
        require(endpointUrl.length in 1..EnrollmentRecord.MAX_URL_CHARS)
        require(enrollmentLabel.isNotEmpty() && enrollmentLabel.none { it.isISOControl() })
    }

    val endpointKey: String get() = EnrollmentRecord.endpointKey(endpointUrl)
    val tls: Boolean get() = endpointUrl.startsWith("wss:")

    override fun equals(other: Any?): Boolean = other is EnrollmentIdentity &&
        other.endpointUrl == endpointUrl && other.enrollmentLabel == enrollmentLabel

    override fun hashCode(): Int = endpointUrl.hashCode() * 31 + enrollmentLabel.hashCode()

    override fun toString(): String = "EnrollmentIdentity(redacted)"
}

fun interface Cancelable {
    fun cancel()
}

/** The controller's single thread. Every state change runs on it; tests substitute a manual scheduler. */
interface RelayScheduler {
    fun post(task: Runnable)
    fun postDelayed(delayMs: Long, task: Runnable): Cancelable
}

/** Local pacing policy. Every value is a client choice; none is a server rule. */
class RelayTiming(
    val requestTimeoutMs: Long = 15_000,
    val pollActiveMs: Long = 1_500,
    val pollIdleMs: Long = 8_000,
    val backoffBaseMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    val maxRetries: Int = 6,
    val maxHistoryPages: Int = 24,
    val maxHistoryRequests: Int = 64,
    val maxTaskPages: Int = 24,
    val historyPageItems: Int = 50,
    val listPageItems: Int = 25,
    val maxResubmits: Int = 3,
    /** A create older than this (server time) is not retried by itself: the server would answer `expired`. */
    val createRetryWindowMs: Long = StateLimits.ID_RETENTION_MS - 3_600_000L,
    /** Refresh `relay.info` this long before the grant expires. */
    val infoRefreshLeadMs: Long = 60_000,
    /** Jitter factor source (nominally 0.8..1.2); fixed in tests. */
    val jitter: () -> Double = { 1.0 }
)

/**
 * The phone's typed Relay controller: explicit opt-in, own enrollment/profile pinning, deliberate create/attach, the
 * receipt-first reconcile of typed turns, and the encrypted durable ledger, over the connection's single raw router.
 *
 * Threading: every public method posts to [scheduler] and returns; all state lives on that one thread. Transport
 * callbacks arrive on the connection thread, are tagged with the controller's session serial and posted here; a callback
 * of an older session, flow or socket generation is dropped before it can change or send anything.
 *
 * Persistence order (the contract of this class): derive the next immutable runtime, build the COMPLETE aggregate with the
 * captures passed explicitly, prove that the state layer can restore it, encrypt and commit, and only THEN write the
 * frame. A refused or failed commit refuses the send and the previous runtime stays. State advanced by a server answer is
 * applied in memory even when its commit fails (it is evidence): the controller is then `unsaved` and a later commit
 * (which contains everything) recovers it. After a failed commit whose outcome is uncertain nothing more is claimed: the
 * next successful commit overwrites whatever is on disk with the in-memory truth, which is never behind the disk.
 *
 * Not proven here: native Keystore/AtomicFile durability, any device or live host. Voice is not part of this stage.
 */
class RelayController(
    private val store: RelayStore,
    private val scheduler: RelayScheduler,
    private val monotonicNanos: () -> Long,
    private val entropy: RequestIdEntropy,
    private val timing: RelayTiming = RelayTiming(),
    private val retainedHistory: RetainedHistory? = null,
    private val listener: (RelayUiState) -> Unit
) {
    // -- state (scheduler thread only) -------------------------------------------------------------------------

    private sealed interface Stored {
        data object NotLoaded : Stored
        data object Empty : Stored
        class Doc(val document: RelayDocument) : Stored
        class Problem(val problem: StoreProblem) : Stored
    }

    private var identity: EnrollmentIdentity? = null
    private var foregroundSession = 0L
    private var stored: Stored = Stored.NotLoaded
    private var storeSequence = 0L
    private var runtime: RelayRuntime? = null
    private var unsaved = false

    private var transport: RelayTransport? = null
    private var channel: RelayChannelState = RelayChannelState.Closed
    private var info: Info? = null
    private var clock: ServerClock? = null
    private var infoDigest: String? = null
    private var readyGeneration = -1L

    /** The socket generation of the newest channel report. */
    private var socketGeneration = 0L

    /** Bumped by everything that invalidates in-flight callbacks: socket change, session end, hide, profile review. */
    private var serial = 0L

    /** Bumped by every attach flow and every selection change. */
    private var flow = 0L
    private var token: BindingToken? = null
    private var attaching = false
    private var attachedOnce = false
    private var unavailable: RelayReason? = null

    @Volatile private var ui = RelayUiState()
    private val conversations = ArrayList<ConversationItem>()
    private var listCursor: String? = null
    private var listOperation = 0L
    private var onlineQuery: String? = null
    private var bookmarkFilter: Boolean? = null
    private var listPages = 0
    private var listRestarts = 0
    private var listRequests = 0
    private val listCursors = HashSet<String>()
    private var listUnavailable = 0
    private var selectedSummary: ConversationItem? = null
    private var retained = emptyList<RetainedThread>()
    private var retainedSelected: RetainedThread? = null
    private var retainedProblem: HistoryProblem? = null
    private var retentionDays: Int? = null
    private var localQuery = ""
    private var cacheReview: HistoryEraseReview? = null
    private var historyAnchor: HistoryAnchor = HistoryAnchor.Beginning
    private var historyChainStarted = false
    private var historyListing = ""
    private var metadataSending: RequestId? = null
    private var listing = false
    private val transcript = Transcript()
    private var historyIncomplete = false
    private var historyComplete = false
    private var tasksComplete = false
    private var historyRequests = 0
    private val tasks = LinkedHashMap<String, TaskState>()
    private var notice = RelayNotice.NONE
    private var pollTimer: Cancelable? = null
    private var retryTimer: Cancelable? = null
    private var pollInFlight = false
    private var progressCursor = 0

    /** Turns written in THIS process may be written again by themselves (same id) after a fresh attach and `not_found`. */
    private val resubmits = HashMap<TurnKey, Int>()
    private var createOperation = 0L
    private var createTimer: Cancelable? = null
    private var createHalted = false
    private var createAttempt = 0
    private var infoRefreshing = false
    private var attachAttempt = 0

    /** Automatic fresh attaches after an uncertain submit, per turn (bounded; then it waits for the user). */
    private val uncertainAttempts = HashMap<TurnKey, Int>()

    // -- public API (thread-safe) ------------------------------------------------------------------------------

    fun prepare(identity: EnrollmentIdentity, foregroundSession: Long = 0) = post {
        if (this.foregroundSession != foregroundSession) {
            endSession(attachAgain = false)
            this.foregroundSession = foregroundSession
        }
        if (this.identity != identity) {
            endSession(attachAgain = false)
            this.identity = identity
            clock = null
            info = null
            infoDigest = null
            runtime = null
            stored = Stored.NotLoaded
            conversations.clear()
            transcript.clear()
            selectedSummary = null
            retained = emptyList()
            retainedSelected = null
            retainedProblem = null
            cacheReview = null
            onlineQuery = null
            bookmarkFilter = null
            unsaved = false
        }
        if (stored is Stored.NotLoaded) load()
        loadRetained()
        publish()
    }

    /** Blocks the CALLER (never the controller thread): true when Relay is enabled and usable for the prepared profile. */
    fun enabledBlocking(timeoutMs: Long = 5_000): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        post {
            result = ui.enabled && ui.storage == null
            latch.countDown()
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result
    }

    fun setEnabled(on: Boolean) = post {
        val who = identity ?: return@post
        if (stored is Stored.NotLoaded) load()
        if (stored is Stored.Problem) return@post publish()
        if (on && !who.tls) {
            unavailable = RelayReason.TLS_REQUIRED
            return@post publish()
        }
        val section = baseSection()
        val existing = section.find(who.endpointKey, who.enrollmentLabel)
        if (existing == null && section.enrollments.size >= StateLimits.MAX_ENROLLMENTS) {
            unavailable = RelayReason.TOO_MANY_ENROLLMENTS
            return@post publish()
        }
        val record = (
            existing ?: EnrollmentRecord.fresh(
                who.endpointUrl,
                who.enrollmentLabel
            )
            ).copy(
            enabled = on,
            selectedConversationId = if (on) existing?.selectedConversationId else null
        )
        if (!commitSection(section.with(record))) return@post publish()
        if (!on) {
            endSession(attachAgain = false)
            transcript.clear()
            conversations.clear()
            retained = emptyList()
            retainedSelected = null
            selectedSummary = null
            cacheReview = null
        }
        unavailable = null
        notice = RelayNotice.NONE
        publish()
    }

    /** The connection just built (or closed: null) for the prepared profile. */
    fun attachTransport(transport: RelayTransport?) = post {
        if (this.transport === transport) return@post
        endSession(attachAgain = false)
        this.transport = transport
        channel = RelayChannelState.Closed
        unavailable = null
        readyGeneration = -1
        publish()
    }

    /** From the connection's [dev.quantumink.hermesgadget.protocol.relay.RelayListener]. */
    fun onChannel(state: RelayChannelState, generation: Long) = post {
        if (transport == null) return@post
        onChannelState(state, generation)
    }

    fun refreshConversations() = post {
        if (!canList()) return@post
        refreshList()
    }

    fun loadMoreConversations() = post {
        val cursor = listCursor ?: return@post
        if (canList() && !listing) listPage(cursor, 0)
    }

    fun create(title: String?) = post { createConversation(title) }

    /** The user drops an unfinished create. (The conversation may already exist on the server; nothing is deleted.) */
    fun discardCreate() = post {
        val record = record() ?: return@post
        if (record.createIntent == null) return@post
        if (commitSection(withRecord(record.copy(createIntent = null)))) {
            createOperation++
            transport?.invalidatePending()
            createTimer?.cancel()
            createTimer = null
            createHalted = false
            notice = RelayNotice.NONE
        }
        publish()
    }

    fun select(conversationId: String) = post {
        val id = ConversationId.of(conversationId) ?: return@post
        selectConversation(id)
    }

    fun leave() = post { leaveConversation() }

    /** [accepted] runs on the controller thread: true only when the turn was durably stored and then written. */
    fun send(text: String, accepted: (Boolean) -> Unit = {}) = post { accepted(sendTyped(text)) }

    fun check(requestId: String) = post { turnKey(requestId)?.let { lookup(it) } }

    fun resend(requestId: String) = post {
        turnKey(requestId)?.let { resubmit(it, deliberate = true) }
    }

    fun abandon(requestId: String) = post { turnKey(requestId)?.let { forgetTurn(it) } }

    fun clearSettled() = post { clearSettledTurns() }

    fun acceptReview() = post { reviewAccepted() }

    fun reauthorize() = post { reauthorized() }

    /** Writes the current state again after a failed commit, or reads the store again after a failed read. */
    fun retryStorage() = post {
        val rt = runtime
        if (rt != null && unsaved) {
            if (tryCommit(rt)) unsaved = false
            evictOwed()
        } else if (stored is Stored.Problem && runtime == null) {
            stored = Stored.NotLoaded
            load()
        }
        publish()
    }

    /** The screen is hidden or replaced: transient work stops; the stored state stays; nothing re-attaches by itself. */
    fun hide() = post {
        endSession(attachAgain = false)
        transport = null
        channel = RelayChannelState.Closed
        readyGeneration = -1
        transcript.clear()
        conversations.clear()
        tasks.clear()
        retained = emptyList()
        retainedSelected = null
        cacheReview = null
        selectedSummary = null
        notice = RelayNotice.NONE
        publish()
    }

    /** The newest state, for tests and for a first paint. */
    val currentState: RelayUiState get() = ui

    private fun namespace(): HistoryNamespace? = record()?.let(HistoryNamespace::from)

    private fun itemView(summary: ConversationSummary) = ConversationItem(
        summary.conversationId.value,
        summary.title,
        summary.updatedAtMs,
        summary.canContinue,
        summary.revision,
        summary.bookmarked,
        summary.metadataState
    )

    fun searchOnline(query: String, bookmarked: Boolean?) = post {
        val clean = query.trim().takeIf { it.isNotEmpty() }
        try {
            RelayCalls.conversations(
                limit = timing.listPageItems,
                query = clean,
                bookmarked = bookmarked
            )
        } catch (
            _: IllegalArgumentException
        ) {
            notice = RelayNotice.NAME_INVALID
            return@post publish()
        }
        onlineQuery = clean
        bookmarkFilter = bookmarked
        conversations.clear()
        listOperation++
        listCursor = null
        listing = false
        if (canList()) refreshList()
        publish()
    }

    fun editMetadata(conversationId: String, title: String?, bookmarked: Boolean?) = post {
        val namespace = namespace() ?: return@post
        if (!isReady() || !supports(RelayMethod.CONVERSATION_UPDATE) || unsaved ||
            metadataSending != null
        ) {
            return@post
        }
        val item =
            conversations.firstOrNull { it.id == conversationId }
                ?: selectedSummary?.takeIf { it.id == conversationId }
        if (item == null || !item.canContinue || item.metadataState != null ||
            item.revision < 1
        ) {
            return@post
        }
        if (baseSection().intents.any {
                it.namespace == namespace &&
                    it.conversationId.value == conversationId
            }
        ) {
            return@post
        }
        val at = now() ?: return@post
        val id = (RequestIds.mint(at, entropy) as? MintResult.Minted)?.id ?: return@post
        val intent = try {
            MetadataIntent(
                namespace,
                requireNotNull(ConversationId.of(conversationId)),
                id,
                item.revision,
                title,
                bookmarked,
                at.serverMs
            )
        } catch (
            _: IllegalArgumentException
        ) {
            notice = RelayNotice.NAME_INVALID
            return@post publish()
        }
        if (!commitSection(baseSection().withIntent(intent))) return@post publish()
        sendMetadata(intent)
        publish()
    }

    fun retryMetadata(requestId: String) = post {
        val intent =
            baseSection().intents.firstOrNull {
                it.namespace == namespace() &&
                    it.requestId.value == requestId
            }
                ?: return@post
        if (intent.retryable) sendMetadata(intent)
        publish()
    }

    fun abandonMetadata(requestId: String) = post {
        val intent =
            baseSection().intents.firstOrNull {
                it.namespace == namespace() &&
                    it.requestId.value == requestId
            }
                ?: return@post
        transport?.invalidatePending()
        metadataSending = null
        commitSection(baseSection().withoutIntent(intent))
        publish()
    }

    private fun sendMetadata(intent: MetadataIntent) {
        if (!isReady() || unsaved || !supports(RelayMethod.CONVERSATION_UPDATE) ||
            intent.namespace != namespace() ||
            metadataSending != null
        ) {
            return
        }
        val item =
            conversations.firstOrNull { it.id == intent.conversationId.value }
                ?: selectedSummary?.takeIf { it.id == intent.conversationId.value }
        if (item == null || !item.canContinue || item.metadataState != null) return
        // Deliberate retry keeps the ORIGINAL revision/body. A refresh never rebases it.
        val rt = runtime ?: return
        val selectedScope = record()?.scope()
        val next = rt.copy(
            section = baseSection().withIntent(intent),
            histories = rt.histories.filterNot {
                it.scope.conversation.enrollment == selectedScope &&
                    it.scope.conversation.conversationId == intent.conversationId
            }
        )
        if (!adopt(next)) return
        // A possibly executed mutation invalidates its old cursors, even if its reply is lost.
        flow++
        listOperation++
        listCursor = null
        listing = false
        historyComplete = false
        historyIncomplete = true
        historyChainStarted = false
        historyAnchor = HistoryAnchor.Beginning
        metadataSending = intent.requestId
        request(intent.call()) { result ->
            if (baseSection().intents.none {
                    it.namespace == intent.namespace &&
                        it.requestId == intent.requestId
                }
            ) {
                return@request
            }
            metadataSending = null
            var status = MetadataStatus.UNKNOWN
            var committed: Boolean? = null
            var fresh = false
            when (val outcome = (result as? RelayCallResult.Outcome)?.outcome) {
                is RelayOutcome.Success -> {
                    if (outcome.value.conversation.conversationId != intent.conversationId) {
                        notice = RelayNotice.ANOMALY
                    } else {
                        committed = outcome.value.coreCommitted
                        status = when (outcome.value.mutationOutcome) {
                            MutationOutcome.ACCEPTED -> MetadataStatus.ACCEPTED
                            MutationOutcome.SUPERSEDED -> MetadataStatus.SUPERSEDED
                            MutationOutcome.RETIRED -> MetadataStatus.RETIRED
                            MutationOutcome.REFUSED, MutationOutcome.ABANDONED ->
                                MetadataStatus.REFUSED
                        }
                        updateTitle(outcome.value.conversation)
                        fresh = true
                    }
                }
                is RelayOutcome.Failed -> {
                    val error = outcome.error
                    status = when {
                        error.code == RelayErrorCode.CONFLICT && error.needsFreshView ->
                            MetadataStatus.STALE_SNAPSHOT
                        error.code == RelayErrorCode.CONFLICT -> MetadataStatus.UNAVAILABLE
                        error.code == RelayErrorCode.EXPIRED -> MetadataStatus.EXPIRED
                        error.code == RelayErrorCode.NOT_FOUND -> MetadataStatus.RETIRED
                        error.action == RelayErrorAction.RETRY_SAME_REQUEST ->
                            MetadataStatus.TRANSIENT
                        error.code == RelayErrorCode.INVALID_REQUEST ||
                            error.code == RelayErrorCode.UNSUPPORTED -> MetadataStatus.REFUSED
                        else -> MetadataStatus.UNKNOWN
                    }
                    if (error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE ||
                        error.action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
                    ) {
                        commonError(error, null, 0)
                        return@request publish()
                    }
                    fresh = error.needsFreshView
                }
                else -> Unit
            }
            val next = baseSection().withIntent(
                intent.copy(status = status, coreCommitted = committed)
            )
            runtime?.let { advance(it.copy(section = next)) }
            // Any mutation outcome invalidates old public list/history cursor chains.
            listOperation++
            listCursor = null
            cacheReview = null
            if (status == MetadataStatus.RETIRED &&
                record()?.selectedConversationId == intent.conversationId
            ) {
                conversationDeleted()
            } else if (fresh && isReady()) {
                val selected = record()?.selectedConversationId
                if (selected != null &&
                    token != null
                ) {
                    attach(selected, refreshListBeforeAttach = true)
                } else {
                    refreshList()
                }
            }
            publish()
        }
    }

    private fun loadRetained() {
        val owner = namespace()
        if (owner == null || record()?.enabled != true || record()?.standing != Standing.ACTIVE) {
            retained = emptyList()
            retainedSelected = null
            return
        }
        try {
            val snapshot = retainedHistory?.read(owner) ?: return
            retentionDays = snapshot.retentionDays
            val needle = localQuery.lowercase(java.util.Locale.ROOT)
            retained =
                snapshot.threads.filter {
                    it.title.lowercase(java.util.Locale.ROOT).contains(needle) ||
                        it.messages.any { message ->
                            message.text.lowercase(java.util.Locale.ROOT).contains(needle)
                        }
                }.take(50)
            retainedProblem = null
        } catch (failure: HistoryFailure) {
            retainedProblem = failure.problem
            retained =
                emptyList()
            retainedSelected = null
        }
    }

    fun searchRetained(query: String) = post {
        if (RelayJson.utf8Length(query, 256) !in 0..256) return@post
        localQuery = query
        retainedSelected = null
        cacheReview = null
        loadRetained()
        publish()
    }

    fun openRetained(conversationId: String) = post {
        loadRetained()
        retainedSelected = retained.firstOrNull { it.id == conversationId }
        cacheReview = null
        publish()
    }

    fun closeRetained() = post {
        retainedSelected = null
        publish()
    }

    fun reviewCache(conversationId: String?) = post {
        val owner = namespace() ?: return@post
        try {
            cacheReview = retainedHistory?.review(owner, conversationId)
        } catch (
            failure: HistoryFailure
        ) {
            retainedProblem = failure.problem
        }
        publish()
    }

    fun cancelCacheReview() = post {
        cacheReview = null
        publish()
    }

    fun confirmCacheReview() = post {
        val review = cacheReview ?: return@post
        val owner = namespace() ?: return@post
        val cache = retainedHistory ?: return@post
        // Exact reviewed scope is durable before deletion. Unreadable bytes affect ALL profiles.
        var section = baseSection()
        val owners = if (review.allProfiles) {
            section.enrollments.mapNotNull(
                HistoryNamespace::from
            )
        } else {
            listOf(owner)
        }
        for (namespace in owners) {
            if (review.retentionDays ==
                null
            ) {
                section = section.owing(
                    HistoryEviction(
                        namespace,
                        if (review.allProfiles) null else review.conversationId,
                        EvictionKind.CACHE_ONLY
                    )
                )
            }
        }
        if (!commitSection(section)) return@post publish()
        transport?.invalidatePending()
        flow++
        token = null
        attaching = false
        try {
            cache.erase(review, owner)
            cacheReview = null
            retainedSelected = null
            retained =
                emptyList()
            evictOwed()
        } catch (
            failure: HistoryFailure
        ) {
            retainedProblem = failure.problem
            cacheReview = null
            return@post publish()
        }
        loadRetained()
        // Refetch is a deliberate rebuild, scoped to the still-selected authenticated conversation.
        record()?.selectedConversationId?.let {
            if (isReady()) attach(it, refreshListBeforeAttach = true)
        }
        publish()
    }

    fun setHistoryRetention(days: Int) = post {
        val owner = namespace() ?: return@post
        if (days !in 0..365) return@post
        cacheReview = null
        try {
            cacheReview = retainedHistory?.reviewRetention(owner, days)
        } catch (
            failure: HistoryFailure
        ) {
            retainedProblem = failure.problem
        }
        publish()
    }

    // -- scheduling ----------------------------------------------------------------------------------------------

    private fun post(task: () -> Unit) = scheduler.post(Runnable { task() })

    private fun later(delayMs: Long, task: () -> Unit): Cancelable {
        val expected = serial
        return scheduler.postDelayed(
            delayMs,
            Runnable {
                if (expected == serial) task()
            }
        )
    }

    private fun backoff(attempt: Int): Long {
        val base = (timing.backoffBaseMs shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT))
            .coerceAtMost(timing.backoffMaxMs)
        return (base * timing.jitter()).toLong().coerceAtLeast(1)
    }

    // -- store -----------------------------------------------------------------------------------------------

    private fun load() {
        stored = when (val result = store.load()) {
            StoreLoad.Empty -> Stored.Empty
            is StoreLoad.Loaded -> {
                storeSequence = result.sequence
                Stored.Doc(result.document)
            }
            is StoreLoad.Unreadable -> Stored.Problem(result.problem)
        }
    }

    private fun baseSection(): ControllerSection = runtime?.section ?: when (val s = stored) {
        is Stored.Doc -> s.document.section
        else -> ControllerSection.EMPTY
    }

    private fun record(): EnrollmentRecord? {
        val who = identity ?: return null
        return baseSection().find(who.endpointKey, who.enrollmentLabel)
    }

    private fun withRecord(record: EnrollmentRecord): ControllerSection = baseSection().with(record)

    private fun scope(): EnrollmentScope? = record()?.scope()

    private fun now(): ServerInstant? {
        val fixed = clock ?: return null
        return (fixed.read(monotonicNanos()) as? InstantRead.At)?.instant
    }

    private fun emptySnapshot(): RelayStateSnapshot = (
        RelayStateSnapshot.of(
            TurnLedger.empty(),
            emptyList(),
            emptyList(),
            VoiceCaptureLedger.empty()
        ) as SnapshotBuild.Built
        ).snapshot

    /**
     * After an UNCERTAIN failure of our own write the new record may be in place: when the sequence on disk is exactly
     * the next one, it is ours and the base moves on (the next commit rewrites it with the in-memory truth, which is
     * never behind the disk). Any other sequence — a different writer — is never adopted.
     */
    private fun noticeFor(result: StoreCommit) {
        if (result is StoreCommit.Failed && result.uncertain &&
            store.currentSequence() == storeSequence + 1
        ) {
            storeSequence += 1
        }
        notice = when (result) {
            is StoreCommit.Refused ->
                if (result.reason == EncodeRefusal.TOO_MANY) {
                    RelayNotice.STORAGE_FULL_PENDING_ITEMS
                } else {
                    RelayNotice.STORAGE_WRITE_FAILED
                }
            is StoreCommit.Failed ->
                if (result.uncertain) {
                    RelayNotice.STORAGE_UNCERTAIN
                } else {
                    RelayNotice.STORAGE_WRITE_FAILED
                }
            StoreCommit.Stale -> RelayNotice.STORAGE_WRITE_FAILED
            is StoreCommit.Committed -> RelayNotice.NONE
        }
    }

    /**
     * A section-only change (enable, disable, selection, create intent, pin, standing). Before the runtime exists the
     * stored aggregate is carried over UNCHANGED (never replaced by an empty one); an empty store gets the first document.
     */
    private fun commitSection(section: ControllerSection): Boolean {
        val rt = runtime
        if (rt != null) return adopt(rt.copy(section = section))
        val snapshot = (stored as? Stored.Doc)?.document?.snapshot ?: emptySnapshot()
        val document = RelayDocument(section, snapshot)
        val result = store.commit(document, storeSequence)
        if (result !is StoreCommit.Committed) {
            noticeFor(result)
            return false
        }
        storeSequence = result.sequence
        stored = Stored.Doc(document)
        return true
    }

    /** Stores [next]; only when that succeeds does it become the runtime. The send that needed it is refused otherwise. */
    private fun adopt(next: RelayRuntime): Boolean {
        if (!tryCommit(next)) return false
        runtime = next
        unsaved = false
        return true
    }

    private fun tryCommit(next: RelayRuntime): Boolean {
        val at = now()
        if (at == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            return false
        }
        val document = when (val built = next.build(at)) {
            is DocumentBuild.Built -> built.document
            is DocumentBuild.TooMany -> {
                notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
                return false
            }
            is DocumentBuild.Unrestorable -> {
                notice = RelayNotice.STORAGE_WRITE_FAILED
                return false
            }
        }
        val result = store.commit(document, storeSequence)
        if (result !is StoreCommit.Committed) {
            noticeFor(result)
            return false
        }
        storeSequence = result.sequence
        stored = Stored.Doc(document)
        return true
    }

    /** A server answer or a retired binding changed the state: it is applied; a failed commit only marks it unsaved. */
    private fun advance(next: RelayRuntime) {
        runtime = next
        unsaved = !tryCommit(next)
        if (unsaved) {
            notice = when (notice) {
                RelayNotice.STORAGE_UNCERTAIN -> RelayNotice.OBSERVATION_UNCERTAIN
                RelayNotice.STORAGE_WRITE_FAILED -> RelayNotice.OBSERVATION_UNSAVED
                // Keep typed capacity/clock reasons; their text makes no zero-send claim.
                else -> notice
            }
        }
    }

    // -- the transport session ---------------------------------------------------------------------------------

    private fun onChannelState(state: RelayChannelState, generation: Long) {
        channel = state
        socketGeneration = generation
        when (state) {
            RelayChannelState.NotRequested, RelayChannelState.AwaitingWelcome -> Unit
            RelayChannelState.Closed -> endSession(attachAgain = true)
            RelayChannelState.NotOffered -> unavailable(RelayReason.NOT_OFFERED)
            is RelayChannelState.Invalid -> unavailable(
                if (state.reason == RelayInvalid.MALFORMED_WELCOME) {
                    RelayReason.MALFORMED_WELCOME
                } else {
                    RelayReason.NOT_PAIRED
                }
            )
            RelayChannelState.LoadingInfo -> {
                serial++
                unavailable = null
            }
            is RelayChannelState.Failed -> channelFailed(state)
            is RelayChannelState.Ready -> negotiated(state, generation)
        }
        publish()
    }

    /** `relay.info` itself failed. A refusal that carries an authority verdict is handled like any other (stop, review). */
    private fun channelFailed(state: RelayChannelState.Failed) {
        val error = state.error
        val action = error?.action
        if (runtime != null && action == RelayErrorAction.STOP_AND_EVICT_SCOPE) return stopScope()
        if (runtime != null && action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW) {
            return requireReview()
        }
        unavailable(
            when {
                action == RelayErrorAction.STOP_AND_EVICT_SCOPE -> RelayReason.ACCESS_DENIED
                state.reason == RelayFailure.NO_SERVER_TIME -> RelayReason.NO_SERVER_TIME
                state.reason == RelayFailure.CLOCK_REFUSED -> RelayReason.CLOCK_UNAVAILABLE
                else -> RelayReason.INFO_FAILED
            }
        )
    }

    private fun unavailable(reason: RelayReason) {
        endSession(attachAgain = true)
        unavailable = reason
    }

    /**
     * Everything transient of the socket generation ends. Stored state, the selection and unsent drafts stay.
     * [attachAgain]: a continuing foreground session may re-attach to its selection by itself after a reconnect; hide,
     * disable and a changed profile never do.
     */
    private fun endSession(attachAgain: Boolean) {
        transport?.invalidatePending()
        serial++
        flow++
        pollTimer?.cancel()
        retryTimer?.cancel()
        createTimer?.cancel()
        createTimer = null
        pollTimer = null
        retryTimer = null
        pollInFlight = false
        attaching = false
        listing = false
        listOperation++
        listCursor = null
        metadataSending = null
        cacheReview = null
        infoRefreshing = false
        val scope = scope()
        val rt = runtime
        if (rt != null && scope != null) {
            val lost = rt.ledger.bindingLost(scope)
            var next = rt.copy(ledger = lost.ledger, authority = rt.authority.retireScope(scope))
            rt.delivery(scope)?.bindingLost()?.let { next = next.withDelivery(it.delivery) }
            runtime = next
        }
        token = null
        attachedOnce = attachedOnce && attachAgain
    }

    private fun digestOf(info: Info): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((info.grantGeneration.toString() + "|" + info.profileId.value).toByteArray())
        for (method in info.methods) digest.update(("|" + method).toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun negotiated(state: RelayChannelState.Ready, generation: Long) {
        val digest = digestOf(state.info)
        val refresh = generation == readyGeneration && digest == infoDigest && token != null
        info = state.info
        clock = state.clock
        infoRefreshing = false
        if (refresh) return // a plain refresh of an unchanged grant disturbs nothing
        serial++
        flow++
        readyGeneration = generation
        unavailable = null
        if (stored is Stored.Problem) return
        val granted = infoDigest != null && infoDigest != digest
        infoDigest = digest
        if (!ensureRuntime()) return
        var record = record() ?: return
        if (!record.enabled) return
        val offered = state.info.profileId
        var rt = requireNotNull(runtime)
        // The pin: first use after the user's explicit enable; every later negotiation must match it.
        if (record.pinnedProfileId == null) {
            record = record.copy(pinnedProfileId = offered, observedProfileId = null)
            if (!adopt(rt.copy(section = withRecord(record)))) return
            rt = requireNotNull(runtime)
        } else if (record.pinnedProfileId != offered && record.standing == Standing.ACTIVE) {
            token = null
            retained = emptyList()
            retainedSelected = null
            cacheReview = null
            transcript.clear()
            selectedSummary = null
            advance(
                rt.copy(
                    section = withRecord(
                        record.copy(
                            standing = Standing.REVIEW_REQUIRED,
                            observedProfileId = offered
                        )
                    )
                )
            )
            notice = RelayNotice.PROFILE_CHANGED
            return
        } else if (record.standing == Standing.REVIEW_REQUIRED) {
            if (record.observedProfileId != offered) {
                advance(rt.copy(section = withRecord(record.copy(observedProfileId = offered))))
            }
            return
        }
        val current = requireNotNull(record.scope())
        if (record.standing == Standing.STOPPED) {
            evictOwed()
            return
        }
        if (rt.ledger.scopeStatus(current) != ScopeStatus.ACTIVE) {
            val standing = if (rt.ledger.scopeStatus(current) == ScopeStatus.STOPPED) {
                Standing.STOPPED
            } else {
                Standing.REVIEW_REQUIRED
            }
            advance(rt.copy(section = withRecord(record.copy(standing = standing))))
            if (standing == Standing.STOPPED) evictOwed()
            return
        }
        if (REQUIRED.any { !state.info.advertises(it) }) {
            unavailable = RelayReason.MISSING_METHODS
            return
        }
        // Grant or method changes refresh authority: a binding made under another view of the grant is never reused.
        if (granted) advance(rt.copy(authority = rt.authority.retireScope(current)))
        continueAfterReady(record)
    }

    /**
     * Restores the stored state with the FRESH server time of this `relay.info` and a fresh authority. Unreadable or
     * unrestorable bytes stay exactly as they are and block every mutation; an empty store starts an empty runtime.
     */
    private fun ensureRuntime(): Boolean {
        if (runtime != null) return true
        val at = now() ?: return false
        runtime = when (val s = stored) {
            is Stored.Problem -> return false
            Stored.NotLoaded, Stored.Empty -> RelayRuntime.empty(baseSection())
            is Stored.Doc -> {
                when (
                    val result = RelayStateRestore.restore(
                        s.document.snapshot,
                        at,
                        RelayAuthority.fresh()
                    )
                ) {
                    is RestoreResult.Restored -> RelayRuntime(
                        s.document.section,
                        result.value.ledger,
                        result.value.captures,
                        result.value.histories,
                        result.value.deliveries,
                        RelayAuthority.fresh()
                    )
                    is RestoreResult.Refused -> {
                        stored = Stored.Problem(StoreProblem.UNRESTORABLE)
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun continueAfterReady(record: EnrollmentRecord) {
        val intent = record.createIntent
        val selected = record.selectedConversationId
        if (intent == null && selected != null && attachedOnce) {
            attach(selected, refreshListBeforeAttach = true)
            return
        }
        refreshList()
        if (intent != null) sendCreate(intent)
    }

    private fun supports(method: RelayMethod): Boolean = method in (info?.supported ?: emptySet())

    private fun refreshList() {
        if (supports(RelayMethod.CONVERSATIONS_LIST)) {
            listOperation++
            listPages = 0
            listRestarts = 0
            listRequests = 0
            listCursors.clear()
            listCursor = null
            listPage(null, 0)
        }
    }

    // -- request plumbing --------------------------------------------------------------------------------------

    private fun <R : Any> request(
        call: RelayCall<R>,
        correlation: CorrelationId? = null,
        done: (RelayCallResult<R>) -> Unit
    ) {
        val link = transport
        if (link == null || channel !is RelayChannelState.Ready) {
            done(RelayCallResult.Refused(RelayRefusal.NOT_READY))
            return
        }
        val expected = serial
        val id = correlation ?: link.correlation()
        link.call(id, call, timing.requestTimeoutMs) { result ->
            post { if (expected == serial) done(result) }
        }
    }

    private fun canList(): Boolean = isReady() && supports(RelayMethod.CONVERSATIONS_LIST)

    private fun isReady(): Boolean = channel is RelayChannelState.Ready && runtime != null &&
        record()?.let { it.enabled && it.standing == Standing.ACTIVE } == true

    /** A local refusal that only means "not now": the same step is tried again later, never given up as a link failure. */
    private fun busyLocally(result: RelayCallResult<*>): Boolean =
        result is RelayCallResult.Refused &&
            (
                result.reason == RelayRefusal.TOO_MANY_OUTSTANDING ||
                    result.reason == RelayRefusal.QUEUE_FULL ||
                    result.reason == RelayRefusal.SOCKET_BACKPRESSURE
                )

    // -- shared error rules ------------------------------------------------------------------------------------

    /** The client rules every call shares. True when the error was dealt with. */
    private fun commonError(error: RelayError, retry: (() -> Unit)?, attempt: Int): Boolean =
        when (error.action) {
            RelayErrorAction.RETRY_SAME_REQUEST -> {
                notice = RelayNotice.BUSY_TRY_AGAIN
                if (retry != null && attempt < timing.maxRetries) {
                    retryTimer = later(backoff(attempt)) { retry() }
                }
                true
            }
            RelayErrorAction.REATTACH_AND_REFRESH -> {
                reattach()
                true
            }
            RelayErrorAction.STOP_AND_EVICT_SCOPE -> {
                stopScope()
                true
            }
            RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> {
                requireReview()
                true
            }
            RelayErrorAction.FAILED -> false
        }

    /**
     * `stale_binding`, `stale_epoch`, `snapshot_required`, a gap: the binding is retired, `relay.info` is read again (the
     * grant may have changed) and the continuing session attaches again. Nothing is resubmitted by this.
     */
    private fun reattach() {
        transport?.invalidatePending()
        val scope = scope()
        val rt = runtime
        token = null
        if (rt != null &&
            scope != null
        ) {
            runtime = rt.copy(authority = rt.authority.retireScope(scope))
        }
        val link = transport
        flow++
        if (link == null || infoRefreshing || !(attachedOnce || attaching)) return
        attaching = true
        infoRefreshing = true
        val expected = serial
        link.refreshInfo { result ->
            post {
                if (expected == serial) {
                    infoRefreshing = false
                    // A failed refresh moves the channel itself (the listener reports Failed); nothing else to do here.
                    if (result is RelayCallResult.Outcome &&
                        result.outcome !is RelayOutcome.Success
                    ) {
                        val failure = result.outcome as? RelayOutcome.Failed
                        if (failure != null) commonError(failure.error, null, 0)
                    }
                }
            }
        }
    }

    private fun stopScope() {
        transport?.invalidatePending()
        val record = record() ?: return
        val rt = runtime ?: return
        val scope = record.scope()
        val namespace = HistoryNamespace.from(record)
        var section = rt.section.with(record.copy(standing = Standing.STOPPED))
        if (namespace != null) section = section.owing(HistoryEviction(namespace, null))
        var stopped = rt.copy(section = section)
        if (scope != null) stopped = stopped.copy(authority = stopped.authority.retireScope(scope))
        transcript.clear()
        conversations.clear()
        tasks.clear()
        retained = emptyList()
        retainedSelected = null
        cacheReview = null
        token = null
        serial++
        flow++
        attaching = false
        advance(stopped)
        notice = RelayNotice.PERMISSION_DENIED
        evictOwed()
        publish()
    }

    /**
     * The content-eviction obligation of a stopped scope: the ledger's turn texts, the delivery state and the history
     * resume points of the scope. It stays owed — in memory and on disk — until the eviction itself is STORED.
     */
    private fun evictOwed() {
        var rt = runtime ?: return
        val record = record() ?: return
        val namespace = HistoryNamespace.from(record)
        if (record.standing == Standing.STOPPED && namespace != null &&
            rt.section.evictions.none { it.namespace == namespace && it.conversationId == null }
        ) {
            val next = rt.copy(section = rt.section.owing(HistoryEviction(namespace, null)))
            if (!adopt(next)) {
                runtime = next
                unsaved = true
                return
            }
            rt = requireNotNull(runtime)
        }
        // No deletion before the obligation itself is CONFIRMED durable.
        if (unsaved && !tryCommit(rt)) return
        unsaved = false
        for (obligation in rt.section.evictions) {
            try {
                if (obligation.kind == EvictionKind.CACHE_ONLY) {
                    // A stale/failed reviewed erase never becomes permission to erase changed bytes on restart.
                    if (retainedHistory?.absent(obligation.namespace, obligation.conversationId) ==
                        false
                    ) {
                        return
                    }
                } else {
                    retainedHistory?.evict(obligation.namespace, obligation.conversationId)
                }
            } catch (failure: HistoryFailure) {
                retainedProblem = failure.problem
                return
            }
            val owner = EnrollmentScope(
                RelayScope(
                    obligation.namespace.endpointKey,
                    requireNotNull(ProfileId.of(obligation.namespace.profileId))
                ),
                obligation.namespace.enrollmentLabel,
                obligation.namespace.reviewEpoch
            )
            var next = rt.copy(
                section = rt.section.cleared(obligation),
                histories = rt.histories.filterNot {
                    it.scope.conversation.enrollment == owner &&
                        (
                            obligation.conversationId == null ||
                                it.scope.conversation.conversationId.value ==
                                obligation.conversationId
                            )
                }
            )
            if (obligation.kind != EvictionKind.CACHE_ONLY) {
                next = next.copy(
                    ledger = next.ledger.evictContent(
                        owner,
                        obligation.conversationId?.let(ConversationId::of)
                    ).ledger
                )
            }
            if (obligation.kind == EvictionKind.REVOKED) {
                rt.delivery(owner)?.evictScopeContent()?.let {
                    next = next.withDelivery(it.delivery)
                }
            }
            if (!adopt(next)) {
                unsaved = true
                return
            }
            rt = requireNotNull(runtime)
        }
    }

    private fun requireReview() {
        transport?.invalidatePending()
        val record = record() ?: return
        val rt = runtime ?: return
        val scope = record.scope()
        var next = rt
        if (scope != null) next = next.copy(authority = next.authority.retireScope(scope))
        token = null
        serial++
        flow++
        attaching = false
        advance(
            next.copy(section = next.section.with(record.copy(standing = Standing.REVIEW_REQUIRED)))
        )
        notice = RelayNotice.PROFILE_CHANGED
        retained = emptyList()
        retainedSelected = null
        cacheReview = null
        publish()
        // The profile now in force is read again so that the review can show what changed.
        val link = transport
        if (link != null && !infoRefreshing && channel is RelayChannelState.Ready) {
            infoRefreshing = true
            val expected = serial
            link.refreshInfo { post { if (expected == serial) infoRefreshing = false } }
        }
    }

    // -- review and re-authorisation ----------------------------------------------------------------------------

    private fun reviewAccepted() {
        val record = record() ?: return
        val rt = runtime ?: return
        val offered = info?.profileId
        val state = channel
        // Only a fresh negotiation on THIS socket, whose profile is the one the user was shown, can be accepted.
        if (record.standing != Standing.REVIEW_REQUIRED || offered == null ||
            offered != record.observedProfileId || state !is RelayChannelState.Ready
        ) {
            return publish()
        }
        val old = record.scope()
        var next = rt
        if (old != null) {
            next = next.copy(authority = next.authority.retireScope(old))
            if (next.ledger.scopeRecord(old) != null) {
                next =
                    next.copy(
                        ledger = next.ledger.reactivate(old, Reactivation.USER_REVIEWED).ledger
                    )
            }
            next = next.withoutDeliveries { it != old }
            next =
                next.copy(
                    histories = next.histories.filter {
                        it.scope.conversation.enrollment !=
                            old
                    }
                )
        }
        val reviewed = record.copy(
            pinnedProfileId = offered,
            observedProfileId = null,
            reviewEpoch = record.reviewEpoch + 1,
            standing = Standing.ACTIVE,
            selectedConversationId = null,
            createIntent = null
        )
        val reviewedSection = next.section.with(reviewed)
        if (!adopt(next.copy(section = reviewedSection))) return publish()
        evictOwed()
        retained = emptyList()
        retainedSelected = null
        cacheReview = null
        endSession(attachAgain = false)
        transcript.clear()
        conversations.clear()
        notice = RelayNotice.NONE
        readyGeneration = -1
        negotiated(state, socketGeneration)
        publish()
    }

    private fun reauthorized() {
        val record = record() ?: return
        val rt = runtime ?: return
        if (record.standing != Standing.STOPPED) return publish()
        val scope = record.scope()
        var next = rt
        if (scope != null && next.ledger.scopeRecord(scope)?.status == ScopeStatus.STOPPED) {
            next =
                next.copy(
                    ledger = next.ledger.reactivate(scope, Reactivation.USER_REAUTHORIZED).ledger
                )
        }
        if (!adopt(
                next.copy(section = next.section.with(record.copy(standing = Standing.ACTIVE)))
            )
        ) {
            return publish()
        }
        notice = RelayNotice.NONE
        val state = channel
        if (state is RelayChannelState.Ready) {
            readyGeneration = -1
            negotiated(state, socketGeneration)
        }
        publish()
    }

    // -- listing -----------------------------------------------------------------------------------------------

    private fun listPage(cursor: String?, attempt: Int, operation: Long = listOperation) {
        if (operation != listOperation || !canList() || attempt > timing.maxRetries ||
            listPages >= timing.maxHistoryPages ||
            listRequests >= timing.maxHistoryRequests
        ) {
            return
        }
        listRequests++
        listing = true
        val call = try {
            RelayCalls.conversations(cursor, timing.listPageItems, onlineQuery, bookmarkFilter)
        } catch (_: IllegalArgumentException) {
            listing = false
            return
        }
        request(call) { result ->
            if (operation != listOperation) return@request
            listing = false
            when (result) {
                is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
                    is RelayOutcome.Success -> {
                        listPages++
                        onListed(outcome.value, cursor == null)
                        if (outcome.value.items.isEmpty() && listCursor != null &&
                            listPages < timing.maxHistoryPages
                        ) {
                            listPage(listCursor, 0, operation)
                        }
                    }
                    is RelayOutcome.Failed -> {
                        if (outcome.error.needsFreshView && cursor != null &&
                            attempt < timing.maxRetries &&
                            listRestarts < MAX_HISTORY_RESTARTS
                        ) {
                            listRestarts++
                            listCursors.clear()
                            listPages = 0
                            listPage(null, attempt + 1, operation)
                        } else if (!commonError(outcome.error, {
                                listPage(cursor, attempt + 1, operation)
                            }, attempt)
                        ) {
                            notice = RelayNotice.SERVER_ERROR
                        }
                    }
                    is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid ->
                        notice =
                            RelayNotice.LINK_FAILED
                }
                RelayCallResult.TimedOut -> {
                    notice = RelayNotice.BUSY_TRY_AGAIN
                    if (attempt < timing.maxRetries) {
                        retryTimer =
                            later(backoff(attempt)) { listPage(cursor, attempt + 1, operation) }
                    }
                }
                RelayCallResult.Closed -> Unit
                is RelayCallResult.Refused -> if (busyLocally(result) &&
                    attempt < timing.maxRetries
                ) {
                    retryTimer =
                        later(backoff(attempt)) { listPage(cursor, attempt + 1, operation) }
                } else {
                    notice = RelayNotice.LINK_FAILED
                }
            }
            publish()
        }
        publish()
    }

    private fun onListed(page: ConversationPage, first: Boolean) {
        if (first) conversations.clear()
        for (item in page.items) {
            if (conversations.size >= MAX_LISTED) break
            val view = itemView(item)
            conversations.removeAll { it.id == view.id }
            conversations.add(view)
            if (record()?.selectedConversationId?.value == view.id) selectedSummary = view
        }
        listUnavailable = page.unavailableCount ?: 0
        val next = page.nextCursor
        listCursor =
            next?.takeIf {
                conversations.size < MAX_LISTED && listPages < timing.maxHistoryPages &&
                    listCursors.add(it)
            }
        if (next != null && listCursor == null) notice = RelayNotice.HISTORY_INCOMPLETE
        if (notice == RelayNotice.BUSY_TRY_AGAIN) notice = RelayNotice.NONE
    }

    // -- create ------------------------------------------------------------------------------------------------

    private fun createConversation(title: String?) {
        if (!isReady() || !supports(RelayMethod.CONVERSATION_CREATE)) return publish()
        val record = record() ?: return
        if (record.createIntent != null) {
            notice = RelayNotice.CREATE_PENDING
            return publish()
        }
        val clean = title?.trim()?.takeIf { it.isNotEmpty() }
        val invalid = (title != null && clean == null) || (
            clean != null && (
                clean.codePointCount(0, clean.length) > RelayLimits.CREATE_TITLE_CODE_POINTS ||
                    RelayJson.utf8Length(clean, RelayLimits.CREATE_TITLE_BYTES) >
                    RelayLimits.CREATE_TITLE_BYTES
                )
            )
        if (invalid) {
            notice = RelayNotice.NAME_INVALID
            return publish()
        }
        val at = now()
        if (at == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            return publish()
        }
        val minted = RequestIds.mint(at, entropy)
        val id = (minted as? MintResult.Minted)?.id
        if (id == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            return publish()
        }
        val intent = CreateIntent(id, clean, at.serverMs)
        // The identity and the immutable title are durable BEFORE the frame is written.
        if (!commitSection(withRecord(record.copy(createIntent = intent)))) return publish()
        createHalted = false
        createAttempt = 0
        createOperation++
        sendCreate(intent)
        publish()
    }

    private fun currentCreate(intent: CreateIntent, operation: Long): Boolean =
        operation == createOperation && record()?.createIntent == intent

    private fun sendCreate(intent: CreateIntent, operation: Long = createOperation) {
        if (createHalted || !currentCreate(intent, operation) || !isReady()) return
        val at = now()
        if (at != null && at.serverMs - intent.preparedAtMs >= timing.createRetryWindowMs) {
            createHalted = true
            notice = RelayNotice.CREATE_EXPIRED
            return
        }
        val call = try {
            RelayCalls.createConversation(intent.requestId, intent.title)
        } catch (_: IllegalArgumentException) {
            return
        }
        request(call) { result ->
            if (!currentCreate(intent, operation)) return@request
            when (result) {
                is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
                    is RelayOutcome.Success -> onCreated(intent, outcome.value)
                    is RelayOutcome.Failed -> onCreateFailed(intent, outcome.error)
                    is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> {
                        notice = RelayNotice.LINK_FAILED
                        retryCreate(intent)
                    }
                }
                RelayCallResult.TimedOut -> retryCreate(intent)
                RelayCallResult.Closed -> Unit
                is RelayCallResult.Refused -> if (busyLocally(result)) {
                    retryCreate(intent)
                } else {
                    notice =
                        RelayNotice.CREATE_PENDING
                }
            }
            publish()
        }
    }

    private fun retryCreate(intent: CreateIntent) {
        notice = RelayNotice.CREATE_PENDING
        if (createAttempt < timing.maxRetries) {
            val attempt = createAttempt++
            val operation = createOperation
            createTimer?.cancel()
            createTimer = later(backoff(attempt)) { sendCreate(intent, operation) }
        }
    }

    private fun onCreateFailed(intent: CreateIntent, error: RelayError) {
        val record = record() ?: return
        when {
            error.code == RelayErrorCode.INVALID_REQUEST ||
                error.code == RelayErrorCode.UNSUPPORTED -> {
                // Refused before any effect: nothing was created and the intent is spent.
                if (commitSection(withRecord(record.copy(createIntent = null)))) {
                    notice = RelayNotice.CREATE_REFUSED
                }
            }
            error.code == RelayErrorCode.EXPIRED -> {
                createHalted = true
                notice = RelayNotice.CREATE_EXPIRED
            }
            error.action == RelayErrorAction.RETRY_SAME_REQUEST -> retryCreate(intent)
            commonError(error, null, 0) -> Unit
            else -> {
                createHalted = true
                notice = RelayNotice.CREATE_FAILED
            }
        }
    }

    private fun onCreated(intent: CreateIntent, summary: ConversationSummary) {
        val record = record() ?: return
        if (record.createIntent != intent) return
        val chosen = record.copy(
            createIntent = null,
            selectedConversationId = summary.conversationId
        )
        if (!commitSection(withRecord(chosen))) {
            // The intent stays: a retry of the SAME id answers with the SAME conversation.
            notice = RelayNotice.CREATE_PENDING
            return
        }
        notice = RelayNotice.NONE
        conversations.removeAll { it.id == summary.conversationId.value }
        conversations.add(
            0,
            itemView(summary)
        )
        if (!summary.canContinue) {
            notice = RelayNotice.CONVERSATION_NOT_CONTINUABLE
            return
        }
        selectedSummary = itemView(summary)
        retainedSelected = null
        transcript.clear()
        attachedOnce = true
        attach(summary.conversationId)
    }

    // -- select, attach, leave --------------------------------------------------------------------------------

    private fun selectConversation(id: ConversationId) {
        val record = record() ?: return
        if (!isReady() || !supports(RelayMethod.CONVERSATION_ATTACH)) return publish()
        val item =
            conversations.firstOrNull { it.id == id.value }
                ?: selectedSummary?.takeIf { it.id == id.value }
        retainedSelected = null
        cacheReview = null
        selectedSummary = item
        if (item != null && !item.canContinue) {
            notice = RelayNotice.CONVERSATION_NOT_CONTINUABLE
            return publish()
        }
        if (record.selectedConversationId != id) {
            if (!commitSection(
                    withRecord(record.copy(selectedConversationId = id))
                )
            ) {
                return publish()
            }
            transcript.clear()
        }
        attachedOnce = true
        attach(id)
        publish()
    }

    private fun attach(
        id: ConversationId,
        attempt: Int = 0,
        refreshListBeforeAttach: Boolean = false
    ) {
        if (record()?.selectedConversationId != id || !isReady()) return
        transport?.invalidatePending()
        flow++
        val mine = flow
        attaching = true
        attachAttempt = attempt
        token = null
        historyIncomplete = false
        historyComplete = false
        tasksComplete = false
        tasks.clear()
        pollTimer?.cancel()
        // Both fresh requests must be queued after withdrawal, in the replacement transport scope.
        if (refreshListBeforeAttach) refreshList()
        request(RelayCalls.attach(id)) { result ->
            if (mine != flow) return@request
            when (result) {
                is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
                    is RelayOutcome.Success -> attached(outcome.value, mine)
                    is RelayOutcome.Failed -> onAttachFailed(outcome.error, id, attempt)
                    is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> {
                        attaching = false
                        notice = RelayNotice.LINK_FAILED
                    }
                }
                RelayCallResult.TimedOut -> retryAttach(id, attempt)
                RelayCallResult.Closed -> attaching = false
                is RelayCallResult.Refused -> if (busyLocally(result)) {
                    retryAttach(id, attempt)
                } else {
                    attaching = false
                    notice = RelayNotice.LINK_FAILED
                }
            }
            publish()
        }
        publish()
    }

    /** Bounded: after [RelayTiming.maxRetries] the user is asked (the selection stays; Resume tries again). */
    private fun retryAttach(id: ConversationId, attempt: Int) {
        attaching = false
        notice = RelayNotice.RECONNECTING
        if (attempt <
            timing.maxRetries
        ) {
            val mine = flow
            val scope = scope()
            retryTimer = later(backoff(attempt)) {
                if (mine == flow && scope == scope() && record()?.selectedConversationId == id) {
                    attach(id, attempt + 1)
                }
            }
        }
    }

    private fun onAttachFailed(error: RelayError, id: ConversationId, attempt: Int) {
        val mine = flow
        val expectedScope = scope()
        attaching = false
        when {
            error.code == RelayErrorCode.NOT_FOUND -> {
                // The conversation is gone: forget the selection, never create another one in its place.
                conversationDeleted()
            }
            commonError(error, {
                if (mine == flow && expectedScope == scope() &&
                    record()?.selectedConversationId == id
                ) {
                    attach(id, attempt + 1)
                }
            }, attempt) -> Unit
            else -> notice = RelayNotice.SERVER_ERROR
        }
    }

    private fun attached(binding: Binding, mine: Long) {
        if (mine != flow || record()?.selectedConversationId != binding.conversationId) {
            attaching = false
            notice = RelayNotice.ANOMALY
            return
        }
        val scope = scope() ?: return
        val rt = runtime ?: return
        val step = rt.authority.attach(scope, binding)
        val delivery = (
            rt.delivery(
                scope
            ) ?: EnrollmentDelivery.begin(scope)
            ).attach(step.authority, step.token)
        if (delivery.result != null) {
            // The state layer refused this binding (wrong scope, not current): never proceed on it.
            attaching = false
            notice = RelayNotice.ANOMALY
            return
        }
        advance(rt.copy(authority = step.authority).withDelivery(delivery.delivery))
        token = step.token
        // Receipt first, then history, then the task snapshot; only then is the view current and events are pulled.
        reconcileAll(step.token, mine) {
            loadHistory(step.token, mine) {
                loadTasks(step.token, mine) {
                    finishAttach(step.token, mine)
                }
            }
        }
        publish()
    }

    private fun finishAttach(bound: BindingToken, mine: Long) {
        if (mine != flow) return
        val rt = runtime ?: return
        attaching = false
        notice = when {
            !tasksComplete -> RelayNotice.SNAPSHOT_INCOMPLETE
            !historyComplete -> RelayNotice.HISTORY_INCOMPLETE
            else -> RelayNotice.NONE
        }
        rt.delivery(bound.scope)?.let { delivery ->
            val cleared = delivery.refreshed(
                bound.conversation,
                buildSet {
                    if (historyComplete) add(RefreshKind.HISTORY)
                    if (tasksComplete) add(RefreshKind.TASKS)
                }
            )
            // A failed commit sets its own notice: it must not be overwritten by the one above.
            advance(rt.withDelivery(cleared.delivery))
        }
        publish()
        schedulePoll()
    }

    private fun leaveConversation() {
        transport?.invalidatePending()
        val record = record() ?: return
        val bound = token
        if (bound != null && supports(RelayMethod.CONVERSATION_DETACH) &&
            channel is RelayChannelState.Ready
        ) {
            request(RelayCalls.detach(bound.bindingId)) { }
        }
        flow++
        pollTimer?.cancel()
        pollTimer = null
        val scope = record.scope()
        val rt = runtime
        if (rt != null && scope != null) {
            var next = rt.copy(authority = rt.authority.retireScope(scope))
            rt.delivery(scope)?.bindingLost()?.let { next = next.withDelivery(it.delivery) }
            runtime = next
        }
        token = null
        attaching = false
        attachedOnce = false
        transcript.clear()
        tasks.clear()
        if (record.selectedConversationId != null) {
            commitSection(withRecord(record.copy(selectedConversationId = null)))
        }
        publish()
    }

    // -- receipt-first reconcile -------------------------------------------------------------------------------

    private fun unresolved(entry: TurnEntry): Boolean =
        entry.closure == null && when (entry.phase) {
            TurnPhase.UNCERTAIN, TurnPhase.ABSENT_SO_FAR, TurnPhase.QUEUED, TurnPhase.STARTED,
            TurnPhase.ADMITTED_UNKNOWN -> true
            else -> false
        }

    /** A turn whose outcome is still being waited for: poll quickly. */
    private fun awaiting(entry: TurnEntry): Boolean = entry.closure == null && when (entry.phase) {
        TurnPhase.PREPARED, TurnPhase.TERMINAL, TurnPhase.REFUSED -> false
        else -> true
    }

    private fun progressing(entry: TurnEntry): Boolean =
        entry.closure == null && when (entry.phase) {
            TurnPhase.QUEUED, TurnPhase.STARTED, TurnPhase.ADMITTED_UNKNOWN -> true
            else -> false
        }

    private fun reconcileAll(bound: BindingToken, mine: Long, then: () -> Unit) {
        val rt = runtime ?: return then()
        val keys = rt.ledger.entriesFor(bound.scope)
            .filter { it.key.conversation.conversationId == bound.conversationId && unresolved(it) }
            .map { it.key }
        reconcileNext(keys, 0, bound, mine, then)
    }

    private fun reconcileNext(
        keys: List<TurnKey>,
        index: Int,
        bound: BindingToken,
        mine: Long,
        then: () -> Unit
    ) {
        if (mine != flow) return
        if (index >= keys.size) return then()
        lookupThen(keys[index], bound, mine) { reconcileNext(keys, index + 1, bound, mine, then) }
    }

    private fun lookup(key: TurnKey) {
        val bound = token
        if (bound == null) {
            reattach()
            return
        }
        lookupThen(key, bound, flow, userInitiated = true) { publish() }
    }

    private fun lookupThen(
        key: TurnKey,
        bound: BindingToken,
        mine: Long,
        userInitiated: Boolean = false,
        then: () -> Unit
    ) {
        val rt = runtime
        val at = now()
        val link = transport
        if (rt == null || at == null || link == null) return then()
        val correlation = link.correlation()
        val lookup = if (supports(
                RelayMethod.TURN_RECEIPT_OWN
            )
        ) {
            ReceiptLookup.OWN
        } else {
            ReceiptLookup.LEGACY
        }
        val step = rt.ledger.planLookup(key, rt.authority, bound, at, correlation, lookup)
        val planned = step.result as? PlanOutcome.Planned
        if (planned == null) {
            // After uncertainty the ledger wants a binding made AFTER it: the user's "check" attaches again first.
            val refused = (step.result as? PlanOutcome.Refused)?.reason
            if (userInitiated && refused == PlanRefusal.FRESH_ATTACH_REQUIRED) reattach()
            return then()
        }
        runtime = rt.copy(ledger = step.ledger)
        val ref = planned.dispatch.ref
        request(planned.dispatch.call, correlation) { result ->
            val applied = answerTurnCall(ref, key, result)
            // A failed lookup retains historical absence. Only this applied negative can release a retry.
            val negative = if (result is RelayCallResult.Outcome) {
                result.outcome as? RelayOutcome.Failed
            } else {
                null
            }
            if (mine == flow && token === bound &&
                negative?.error?.code == RelayErrorCode.NOT_FOUND &&
                applied is ReplyOutcome.Applied && applied.key == key &&
                applied.phase == TurnPhase.ABSENT_SO_FAR &&
                runtime?.ledger?.entry(key)?.phase == TurnPhase.ABSENT_SO_FAR
            ) {
                resubmit(key, deliberate = false)
            }
            then()
        }
    }

    /** One turn call ended: the ledger decides what it means. A call that never left is treated as unanswered. */
    private fun answerTurnCall(
        ref: CallRef,
        key: TurnKey,
        result: RelayCallResult<Receipt>
    ): ReplyOutcome? {
        val rt = runtime ?: return null
        val step = when (result) {
            is RelayCallResult.Outcome -> rt.ledger.onReply(ref, result.outcome)
            RelayCallResult.TimedOut, is RelayCallResult.Refused -> rt.ledger.onTimeout(ref)
            RelayCallResult.Closed -> return null
        }
        val before = token
        applyLedger(step)
        // An uncertain SUBMIT needs a fresh attach before its receipt can be asked (the ledger's rule): do it, with backoff.
        val entry = runtime?.ledger?.entry(key)
        if (ref.kind == CallKind.SUBMIT && entry != null && entry.phase == TurnPhase.UNCERTAIN &&
            entry.hold == null && before === token && channel is RelayChannelState.Ready
        ) {
            val attempt = uncertainAttempts[key] ?: 0
            if (attempt < timing.maxRetries) {
                uncertainAttempts[key] = attempt + 1
                retryTimer = later(backoff(attempt)) { reattach() }
            }
        }
        return step.result
    }

    private fun applyLedger(step: LedgerStep<ReplyOutcome>) {
        val rt = runtime ?: return
        var next = rt.copy(ledger = step.ledger)
        var reattach = false
        var evict = false
        var review = false
        for (effect in step.effects) {
            when (effect) {
                TurnEffect.PersistLedger, is TurnEffect.PersistBeforeSend -> Unit
                is TurnEffect.ReattachRequired, is TurnEffect.RefreshHistoryAndTasks ->
                    reattach =
                        true
                is TurnEffect.EvictScopeContent -> evict = true
                is TurnEffect.InvalidateRuntimeAuthority -> {
                    next = next.copy(authority = next.authority.retireScope(effect.scope))
                    token = null
                }
                is TurnEffect.RequireUserReview -> review = true
                is TurnEffect.SurfaceAnomaly -> notice = RelayNotice.ANOMALY
            }
        }
        advance(next)
        when {
            evict -> stopScope()
            review -> requireReview()
            reattach -> reattach()
        }
        publish()
    }

    // -- typed turns ------------------------------------------------------------------------------------------

    private fun sendTyped(text: String): Boolean {
        val rt = runtime
        val bound = token
        val link = transport
        if (retainedSelected != null || rt == null || bound == null || link == null ||
            !isReady() || attaching ||
            !supports(RelayMethod.TURN_SUBMIT)
        ) {
            publish()
            return false
        }
        val at = now()
        if (at == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            publish()
            return false
        }
        val prepared = rt.ledger.prepare(TurnDraft(bound.conversation, text), at, entropy)
        val key = when (val outcome = prepared.result) {
            is PrepareOutcome.Prepared -> outcome.key
            is PrepareOutcome.Backpressure -> {
                notice = RelayNotice.LEDGER_FULL
                publish()
                return false
            }
            is PrepareOutcome.Refused -> {
                notice = RelayNotice.TURN_REFUSED
                publish()
                return false
            }
        }
        val correlation = link.correlation()
        val planned = prepared.ledger.planSubmit(key, rt.authority, bound, at, correlation)
        val dispatch = (planned.result as? PlanOutcome.Planned)?.dispatch
        if (dispatch == null) {
            notice = RelayNotice.TURN_REFUSED
            publish()
            return false
        }
        // Prepare and submit-plan are ONE write: stored first, written to the socket only afterwards.
        if (!adopt(rt.copy(ledger = planned.ledger))) {
            publish()
            return false
        }
        resubmits[key] = 0
        notice = RelayNotice.NONE
        request(dispatch.call, correlation) { result ->
            answerTurnCall(dispatch.ref, key, result)
            schedulePoll()
            publish()
        }
        schedulePoll()
        publish()
        return true
    }

    /** The SAME request (id, text, expiry) is written again; never a new turn and never after the deadline. */
    private fun resubmit(key: TurnKey, deliberate: Boolean) {
        val rt = runtime ?: return
        val bound = token ?: return
        val link = transport ?: return
        val at = now()
        if (at == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            return publish()
        }
        val attempts = resubmits[key]
        // Without the user only a turn written in this process, and only a limited number of times.
        if (!deliberate && (attempts == null || attempts >= timing.maxResubmits)) return publish()
        val correlation = link.correlation()
        val planned = rt.ledger.planSubmit(key, rt.authority, bound, at, correlation)
        val dispatch = (planned.result as? PlanOutcome.Planned)?.dispatch ?: return publish()
        if (!adopt(rt.copy(ledger = planned.ledger))) return publish()
        resubmits[key] = (attempts ?: 0) + 1
        request(dispatch.call, correlation) { result ->
            answerTurnCall(dispatch.ref, key, result)
            schedulePoll()
            publish()
        }
        publish()
    }

    private fun turnKey(requestId: String): TurnKey? {
        val id = RequestId.of(requestId) ?: return null
        return runtime?.ledger?.entries?.firstOrNull { it.key.requestId == id }?.key
    }

    private fun forgetTurn(key: TurnKey) {
        val rt = runtime ?: return
        val step = rt.ledger.discard(key, DiscardMode.ABANDON_TRACKING)
        if (adopt(rt.copy(ledger = step.ledger))) resubmits.remove(key)
        publish()
    }

    private fun clearSettledTurns() {
        val rt = runtime ?: return
        var ledger = rt.ledger
        for (entry in rt.ledger.entries) {
            if (entry.phase == TurnPhase.TERMINAL || entry.phase == TurnPhase.REFUSED) {
                ledger = ledger.discard(entry.key, DiscardMode.ONLY_IF_SETTLED).ledger
            }
        }
        if (ledger !== rt.ledger) adopt(rt.copy(ledger = ledger))
        publish()
    }

    // -- history and tasks --------------------------------------------------------------------------------------

    private fun loadHistory(bound: BindingToken, mine: Long, then: () -> Unit) {
        historyComplete = false
        historyIncomplete = true
        historyRequests = 0
        val rt = runtime ?: return then()
        if (!supports(RelayMethod.HISTORY_PAGE)) return then()
        val scope = HistoryScope(bound.conversation)
        val fresh = rt.history(scope)?.restart() ?: HistoryAssembly.begin(scope)
        transcript.clear()
        historyAnchor = HistoryAnchor.Beginning
        historyChainStarted = false
        historyListing = "$storeSequence:$serial:$mine:${fresh.provenance.generation}"
        runtime = rt.withHistory(fresh)
        historyPage(fresh, 0, 0, mine, then)
    }

    private fun historyPage(
        assembly: HistoryAssembly,
        pages: Int,
        attempt: Int,
        mine: Long,
        then: () -> Unit
    ) {
        if (mine != flow) return
        if (historyRequests >= timing.maxHistoryRequests) {
            historyIncomplete = true
            return then()
        }
        val planStep = assembly.planPage(timing.historyPageItems)
        val plan = (planStep.result as? HistoryPlanOutcome.Planned)?.plan ?: return then()
        var current = planStep.assembly
        storeHistory(current)
        historyRequests++
        request(plan.call) { result ->
            if (mine != flow) return@request
            val next = when (result) {
                is RelayCallResult.Outcome -> when (val outcome = result.outcome) {
                    is RelayOutcome.Success -> current.accept(plan.request, outcome.value)
                    is RelayOutcome.Failed -> current.onError(plan.request, outcome.error)
                    is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> current.onTimeout(
                        plan.request
                    )
                }
                RelayCallResult.TimedOut, is RelayCallResult.Refused -> current.onTimeout(
                    plan.request
                )
                RelayCallResult.Closed -> return@request
            }
            current = next.assembly
            when (val outcome = next.result) {
                is PageOutcome.Accepted -> {
                    if (plan.request.isFirstPage) historyChainStarted = true
                    if (outcome.refusedInListing > 0 || !historyChainStarted) {
                        retainedProblem = HistoryProblem.REBUILD_REQUIRED
                        historyIncomplete = true
                        return@request then()
                    }
                    val record = record() ?: return@request
                    val namespace = HistoryNamespace.from(record)
                    val metadata =
                        selectedSummary?.takeIf {
                            it.id ==
                                current.scope.conversation.conversationId.value
                        }
                            ?: conversations.firstOrNull {
                                it.id ==
                                    current.scope.conversation.conversationId.value
                            }
                    if (retainedHistory != null) {
                        if (namespace == null || metadata == null || metadata.revision < 1) {
                            retainedProblem = HistoryProblem.STALE_METADATA
                            return@request then()
                        }
                        val messages = outcome.emitted.map {
                            RetainedMessage(
                                it.messageId.value,
                                if (it.role ==
                                    dev.quantumink.hermesgadget.protocol.relay.HistoryRole.USER
                                ) {
                                    RetainedRole.USER
                                } else {
                                    RetainedRole.ASSISTANT
                                },
                                it.text,
                                it.createdAtMs
                            )
                        }
                        try {
                            val saved = retainedHistory.capture(
                                HistoryCapture(
                                    namespace,
                                    RetainedThread(
                                        metadata.id,
                                        metadata.revision,
                                        metadata.title.orEmpty(),
                                        metadata.bookmarked,
                                        messages,
                                        outcome.listingComplete
                                    ),
                                    historyAnchor, historyListing,
                                    plan.request.serial, plan.request.generation, mine,
                                    historyChainStarted, current.scope
                                )
                            )
                            retentionDays = saved.retentionDays
                            retained = saved.threads
                            retainedProblem = null
                        } catch (failure: HistoryFailure) {
                            retainedProblem = failure.problem
                            historyIncomplete = true
                            return@request then()
                        }
                    }
                    for (message in outcome.emitted) {
                        transcript.put(
                            TranscriptLine(
                                message.messageId.value,
                                message.role,
                                message.text,
                                message.createdAtMs
                            )
                        )
                    }
                    val staged = outcome.emitted.lastOrNull()
                    if (staged != null) {
                        val committed = current.commit(staged.ordinal).assembly
                        val nextRuntime = requireNotNull(runtime).withHistory(committed)
                        if (retainedHistory != null && retentionDays != 0) {
                            // Cache is confirmed first; only a confirmed aggregate commit advances durable resume.
                            if (!adopt(nextRuntime)) {
                                historyIncomplete = true
                                return@request then()
                            }
                        } else {
                            // No retention: transcript is display-only. Never claim a durable text resume point.
                            runtime =
                                requireNotNull(runtime).copy(
                                    histories = requireNotNull(runtime).histories.filterNot {
                                        it.scope ==
                                            committed.scope
                                    }
                                )
                        }
                        current = committed
                        historyAnchor = HistoryAnchor.After(staged.messageId.value)
                    }
                    storeHistory(current)
                    publish()
                    when {
                        outcome.listingComplete -> {
                            historyComplete = outcome.refusedInListing == 0
                            historyIncomplete = !historyComplete
                            then()
                        }
                        pages + 1 >= timing.maxHistoryPages -> {
                            historyIncomplete = true
                            then()
                        }
                        else -> historyPage(current, pages + 1, 0, mine, then)
                    }
                }
                is PageOutcome.Backpressure -> {
                    historyIncomplete = true
                    retainedProblem = HistoryProblem.CAPACITY
                    then()
                }
                is PageOutcome.Invalidated -> {
                    if (outcome.reason == HistoryInvalidation.SNAPSHOT_REQUIRED &&
                        attempt < MAX_HISTORY_RESTARTS
                    ) {
                        transcript.clear()
                        val restarted = current.restart(outcome.reason)
                        historyAnchor = HistoryAnchor.Beginning
                        historyChainStarted = false
                        historyListing =
                            "$storeSequence:$serial:$mine:${restarted.provenance.generation}"
                        storeHistory(restarted)
                        historyPage(restarted, 0, attempt + 1, mine, then)
                    } else {
                        historyIncomplete = true
                        val failed =
                            (result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Failed
                        if (failed != null) commonError(failed.error, null, 0)
                        then()
                    }
                }
                is PageOutcome.RetrySameRequest -> {
                    notice = RelayNotice.BUSY_TRY_AGAIN
                    if (attempt < timing.maxRetries) {
                        retryTimer = later(backoff(attempt)) {
                            historyPage(current, pages, attempt + 1, mine, then)
                        }
                    } else {
                        historyIncomplete = true
                        then()
                    }
                }
                is PageOutcome.Failed, is PageOutcome.Ignored -> {
                    historyIncomplete = true
                    then()
                }
            }
        }
    }

    private fun storeHistory(assembly: HistoryAssembly) {
        val rt = runtime ?: return
        runtime =
            if (retainedHistory != null && retentionDays != 0) rt.withHistory(assembly) else rt
    }

    private fun loadTasks(bound: BindingToken, mine: Long, then: () -> Unit) {
        tasksComplete = false
        tasks.clear()
        if (!supports(RelayMethod.TASKS_LIST)) return then()
        taskPage(bound, mine, null, 0, 0, HashSet(), then)
    }

    /** Enrollment-wide producer pages, bounded even when pages are empty but carry progress cursors. */
    private fun taskPage(
        bound: BindingToken,
        mine: Long,
        cursor: String?,
        pages: Int,
        attempt: Int,
        cursors: MutableSet<String>,
        then: () -> Unit
    ) {
        if (mine != flow) return
        if (pages >= timing.maxTaskPages) return then()
        request(RelayCalls.tasks(cursor, timing.listPageItems)) { result ->
            if (mine != flow) return@request
            val outcome = (result as? RelayCallResult.Outcome)?.outcome
            if (outcome is RelayOutcome.Success) {
                for (task in outcome.value.items) {
                    if (task.conversationId != bound.conversationId) continue
                    if (task.taskId.value !in tasks &&
                        tasks.size >= MAX_TASKS
                    ) {
                        return@request then()
                    }
                    tasks[task.taskId.value] = task.state
                }
                val next = outcome.value.nextCursor
                if (next == null) {
                    tasksComplete = true
                    then()
                } else if (!cursors.add(next)) {
                    then()
                } else {
                    taskPage(bound, mine, next, pages + 1, 0, cursors, then)
                }
                return@request
            }
            val error = (outcome as? RelayOutcome.Failed)?.error
            if (error != null && error.action != RelayErrorAction.RETRY_SAME_REQUEST &&
                commonError(error, null, 0)
            ) {
                if (mine == flow) then()
                return@request
            }
            val retryable = error?.action == RelayErrorAction.RETRY_SAME_REQUEST ||
                result == RelayCallResult.TimedOut || outcome is RelayOutcome.Invalid ||
                outcome is RelayOutcome.Uncorrelated ||
                (result is RelayCallResult.Refused && busyLocally(result))
            if (retryable && attempt < timing.maxRetries) {
                notice = RelayNotice.BUSY_TRY_AGAIN
                retryTimer = later(backoff(attempt)) {
                    taskPage(bound, mine, cursor, pages, attempt + 1, cursors, then)
                }
            } else if (result != RelayCallResult.Closed) {
                then()
            }
        }
    }

    // -- polling and events ------------------------------------------------------------------------------------

    private fun schedulePoll() {
        pollTimer?.cancel()
        val bound = token ?: return
        val active = runtime?.ledger?.entriesFor(bound.scope)?.any { awaiting(it) } == true
        pollTimer = later(if (active) timing.pollActiveMs else timing.pollIdleMs) { pollOnce() }
    }

    private fun pollOnce() {
        val bound = token ?: return
        val rt = runtime ?: return
        if (pollInFlight || attaching || channel !is RelayChannelState.Ready) return schedulePoll()
        maybeRefreshInfo()
        // An admitted turn is looked up (read only, one per round); the events then bring the content.
        val progressing = rt.ledger.entriesFor(bound.scope).filter {
            it.key.conversation.conversationId == bound.conversationId && progressing(it) &&
                it.phase != TurnPhase.SUBMITTING && it.phase != TurnPhase.LOOKING_UP
        }
        pollInFlight = true
        val mine = flow
        if (progressing.isEmpty()) {
            readEvents(bound, mine)
        } else {
            val key = progressing[progressCursor++ % progressing.size].key
            lookupThen(key, bound, mine) { readEvents(bound, mine) }
        }
    }

    private fun maybeRefreshInfo() {
        val current = info ?: return
        val at = now() ?: return
        val link = transport ?: return
        if (infoRefreshing || at.serverMs + timing.infoRefreshLeadMs < current.expiresAtMs) return
        infoRefreshing = true
        val expected = serial
        link.refreshInfo {
            post { if (expected == serial) infoRefreshing = false }
        }
    }

    private fun readEvents(bound: BindingToken, mine: Long) {
        val rt = runtime
        val link = transport
        val delivery = rt?.delivery(bound.scope)
        if (rt == null || link == null || delivery == null || mine != flow ||
            !supports(RelayMethod.EVENTS_READ)
        ) {
            pollInFlight = false
            return
        }
        val step = delivery.planRead(rt.authority, link.correlation(), timing.listPageItems)
        val plan = (step.result as? ReadPlanOutcome.Planned)?.plan
        if (plan == null) {
            pollInFlight = false
            return schedulePoll()
        }
        runtime = rt.withDelivery(step.delivery)
        request(plan.call, plan.ref.correlation) { result ->
            pollInFlight = false
            if (mine != flow) return@request
            val current = runtime ?: return@request
            val now = current.delivery(bound.scope) ?: return@request
            when (result) {
                is RelayCallResult.Outcome -> {
                    val staged = now.stageEvents(current.authority, plan.ref, result.outcome)
                    runtime = current.withDelivery(staged.delivery)
                    onStaged(staged.result, staged.effects, bound)
                }
                RelayCallResult.TimedOut, is RelayCallResult.Refused ->
                    runtime = current.withDelivery(now.onReadTimeout(plan.ref).delivery)
                RelayCallResult.Closed -> return@request
            }
            publish()
            if (mine == flow && token != null) schedulePoll()
        }
    }

    private fun onStaged(
        outcome: StageOutcome,
        effects: List<DeliveryEffect>,
        bound: BindingToken
    ) {
        when (outcome) {
            is StageOutcome.Staged -> applyBatch(outcome, bound)
            is StageOutcome.NeedsReattach -> reattach()
            is StageOutcome.Rejected -> notice = RelayNotice.ANOMALY
            is StageOutcome.Backpressure -> relaxDedup(bound)
            is StageOutcome.RetrySameRead -> notice = RelayNotice.BUSY_TRY_AGAIN
            is StageOutcome.Failed -> notice = RelayNotice.SERVER_ERROR
            is StageOutcome.Ignored -> Unit
        }
        for (effect in effects) {
            when (effect) {
                is DeliveryEffect.EvictScopeContent -> stopScope()
                is DeliveryEffect.RequireUserReview -> requireReview()
                else -> Unit
            }
        }
    }

    private fun relaxDedup(bound: BindingToken) {
        val rt = runtime ?: return
        val at = now() ?: return
        val delivery = rt.delivery(bound.scope) ?: return
        advance(
            rt.withDelivery(
                delivery.retireOlderThan(at.serverMs - StateLimits.ID_RETENTION_MS).delivery
            )
        )
    }

    private fun applyBatch(staged: StageOutcome.Staged, bound: BindingToken) {
        val batch = staged.batch
        var deleted = false
        for (event in batch.events) {
            when (val payload = event.payload) {
                is EventPayload.Message -> transcript.put(
                    TranscriptLine(
                        payload.messageId.value,
                        payload.role,
                        payload.text,
                        payload.createdAtMs
                    )
                )
                is EventPayload.TaskChanged -> if (payload.task.conversationId ==
                    bound.conversationId
                ) {
                    tasks[payload.task.taskId.value] = payload.task.state
                }
                is EventPayload.ConversationChanged -> {
                    if (payload.deleted) deleted = true
                    updateTitle(payload.conversation)
                }
                is EventPayload.PromptShown -> notice = RelayNotice.PROMPT_ELSEWHERE
                EventPayload.Gap, EventPayload.Changed -> Unit
            }
        }
        val rt = runtime ?: return
        val at = now() ?: return
        val delivery = rt.delivery(bound.scope) ?: return
        val committed = delivery.commitEvents(batch.batchId, at)
        var again = false
        for (effect in committed.effects) {
            when (effect) {
                is DeliveryEffect.ReattachRequired, is DeliveryEffect.RefreshRequired ->
                    again =
                        true
                is DeliveryEffect.ConversationDeleted -> deleted = true
                else -> Unit
            }
        }
        if (committed.result is EventCommit.Backpressure) notice = RelayNotice.SERVER_ERROR
        advance(rt.withDelivery(committed.delivery))
        if (deleted) {
            conversationDeleted()
        } else if (again) {
            // A gap or a snapshot requirement: history and tasks are read again from a fresh attach. Nothing is resent.
            reattach()
        }
    }

    private fun updateTitle(summary: ConversationSummary) {
        val known = conversations.firstOrNull { it.id == summary.conversationId.value }
            ?: selectedSummary?.takeIf { it.id == summary.conversationId.value }
        // A recorded same-ID outcome can predate another device's already observed edit.
        if (known != null && known.revision > summary.revision) return
        if (record()?.selectedConversationId ==
            summary.conversationId
        ) {
            selectedSummary = itemView(summary)
        }
        val index = conversations.indexOfFirst { it.id == summary.conversationId.value }
        if (index >= 0) {
            conversations[index] = itemView(summary)
        }
    }

    private fun conversationDeleted() {
        val record = record() ?: return
        val selected = record.selectedConversationId
        pollTimer?.cancel()
        flow++
        token = null
        attaching = false
        attachedOnce = false
        transcript.clear()
        tasks.clear()
        conversations.removeAll { it.id == selected?.value }
        if (selected != null) {
            val rt = runtime
            val namespace = HistoryNamespace.from(record)
            if (rt != null && namespace != null) {
                advance(
                    rt.copy(
                        section = withRecord(record.copy(selectedConversationId = null))
                            .owing(HistoryEviction(namespace, selected.value, EvictionKind.DELETED))
                    )
                )
                evictOwed()
            }
        }
        selectedSummary = null
        retainedSelected = null
        retained = emptyList()
        notice = RelayNotice.CONVERSATION_DELETED
    }

    // -- the user interface state ------------------------------------------------------------------------------

    private fun turnViews(): List<TurnView> {
        val rt = runtime ?: return emptyList()
        val who = identity ?: return emptyList()
        val scope = scope()
        val at = now()
        val bound = token
        val views = ArrayList<TurnView>()
        for (entry in rt.ledger.entries) {
            val owner = entry.key.conversation.enrollment
            val mine = owner == scope
            val sameEnrollment = owner.relay.endpointKey == who.endpointKey &&
                owner.enrollmentKey == who.enrollmentLabel
            if (!mine && !sameEnrollment) continue
            val inConversation =
                bound != null && entry.key.conversation.conversationId == bound.conversationId
            if (mine && !inConversation && bound != null) continue
            val idle = entry.phase != TurnPhase.SUBMITTING && entry.phase != TurnPhase.LOOKING_UP
            val canCheck = mine && inConversation && idle && unresolved(entry)
            val canResend = mine && inConversation && entry.phase == TurnPhase.ABSENT_SO_FAR &&
                entry.hold == null && entry.text != null && at != null && !entry.deadlinePassed(at)
            views.add(
                TurnView(
                    entry.key.requestId.value,
                    entry.status,
                    entry.text?.take(PREVIEW_CHARS).orEmpty(),
                    entry.expiresAtMs,
                    canCheck,
                    canResend,
                    idle,
                    previousProfile = !mine
                )
            )
        }
        return views
    }

    private fun digest(profile: ProfileId?): String? = profile?.let {
        MessageDigest.getInstance("SHA-256").digest(it.value.toByteArray())
            .take(DIGEST_BYTES).joinToString("") { b -> "%02x".format(b) }
    }

    private fun publish() {
        val record = record()
        val enabled = record?.enabled == true
        val problem = (stored as? Stored.Problem)?.problem
        val selectedId = record?.selectedConversationId
        val selectedItem = selectedId?.let { id ->
            conversations.firstOrNull { it.id == id.value }
                ?: selectedSummary?.takeIf { it.id == id.value }
                ?: ConversationItem(id.value, null, 0, true)
        }
        val standing = record?.standing ?: Standing.ACTIVE
        val ledgerStatus = scope()?.let { runtime?.ledger?.scopeStatus(it) } ?: ScopeStatus.ACTIVE
        val phase = when {
            identity == null -> RelayPhase.OFF
            stored is Stored.NotLoaded -> RelayPhase.CHECKING
            problem != null -> RelayPhase.STORAGE_PROBLEM
            !enabled -> RelayPhase.OFF
            standing == Standing.REVIEW_REQUIRED || ledgerStatus == ScopeStatus.NEEDS_REVIEW ->
                RelayPhase.REVIEW_REQUIRED
            standing == Standing.STOPPED ||
                ledgerStatus == ScopeStatus.STOPPED -> RelayPhase.STOPPED
            unavailable != null -> RelayPhase.UNAVAILABLE
            attaching -> RelayPhase.ATTACHING
            token != null && selectedItem != null -> RelayPhase.SELECTED
            channel is RelayChannelState.Ready && runtime != null -> RelayPhase.READY
            else -> RelayPhase.CONNECTING
        }
        val pending = record?.createIntent
        val scope = scope()
        ui = RelayUiState(
            identity = identity,
            foregroundSession = foregroundSession,
            phase = phase,
            reason = unavailable ?: RelayReason.NONE,
            enabled = enabled && problem == null,
            storage = problem,
            pinnedDigest = digest(record?.pinnedProfileId),
            observedDigest = digest(record?.observedProfileId),
            conversations = conversations.toList(),
            hasMore = listCursor != null,
            listing = listing,
            onlineQuery = onlineQuery.orEmpty(),
            bookmarkFilter = bookmarkFilter,
            listUnavailableCount = listUnavailable,
            metadataIntents = baseSection().intents.filter { it.namespace == namespace() },
            canEditMetadata =
            isReady() && supports(RelayMethod.CONVERSATION_UPDATE) && !unsaved &&
                metadataSending == null,
            retained = retained,
            retainedSelected = retainedSelected,
            retainedProblem = retainedProblem,
            retentionDays = retentionDays,
            cacheReview = cacheReview,
            pendingCreate = pending?.let { PendingCreate(it.title) },
            selected = if (phase == RelayPhase.SELECTED ||
                phase == RelayPhase.ATTACHING
            ) {
                selectedItem
            } else {
                null
            },
            resumable = if (phase == RelayPhase.READY) selectedItem else null,
            transcript = transcript.lines(),
            historyIncomplete = historyIncomplete,
            turns = turnViews(),
            tasksWorking = if (token != null && tasksComplete) {
                tasks.values.count { it == TaskState.WORKING }
            } else {
                null
            },
            tasksNeedInput = if (token != null && tasksComplete) {
                tasks.values.count { it == TaskState.NEEDS_INPUT }
            } else {
                null
            },
            refreshOwed =
            scope != null && runtime?.delivery(scope)?.refreshOwed?.isNotEmpty() == true,
            notice = notice,
            unsaved = unsaved,
            contentEvictionPending = evictionPending(phase, scope),
            busy = attaching || listing,
            canSend = retainedSelected == null && phase == RelayPhase.SELECTED && token != null,
            micAvailable = false
        )
        listener(ui)
    }

    private fun evictionPending(phase: RelayPhase, scope: EnrollmentScope?): Boolean {
        if (baseSection().evictions.any {
                it.namespace.endpointKey == identity?.endpointKey &&
                    it.namespace.enrollmentLabel == identity?.enrollmentLabel
            }
        ) {
            return true
        }
        if (phase != RelayPhase.STOPPED) return false
        val rt = runtime ?: return true // fresh time/restore has not established removal yet
        if (scope == null || unsaved) return true
        val delivery = rt.delivery(scope)
        return rt.ledger.scopeRecord(scope)?.contentEvictionOwed == true ||
            rt.ledger.entriesFor(scope).any { it.text != null } ||
            delivery?.let { it.seenCount > 0 || it.refreshOwed.isNotEmpty() } == true ||
            rt.histories.any { it.scope.conversation.enrollment == scope }
    }

    private class Transcript {
        private val map = LinkedHashMap<String, TranscriptLine>()
        private var chars = 0L

        fun put(line: TranscriptLine) {
            if (map.containsKey(line.id)) return
            map[line.id] = line
            chars += line.text.length
            while ((map.size > MAX_LINES || chars > MAX_CHARS) && map.size > 1) {
                val oldest = map.entries.first()
                chars -= oldest.value.text.length
                map.remove(oldest.key)
            }
        }

        fun clear() {
            map.clear()
            chars = 0
        }

        fun lines(): List<TranscriptLine> = map.values.toList()
    }

    private companion object {
        val REQUIRED = setOf(
            RelayMethod.CONVERSATION_ATTACH,
            RelayMethod.TURN_SUBMIT,
            RelayMethod.TURN_RECEIPT,
            RelayMethod.HISTORY_PAGE,
            RelayMethod.EVENTS_READ
        )
        const val MAX_LISTED = 100
        const val MAX_TASKS = 100
        const val MAX_LINES = 400
        const val MAX_CHARS = 524_288L
        const val PREVIEW_CHARS = 80
        const val DIGEST_BYTES = 4
        const val MAX_BACKOFF_SHIFT = 5
        const val MAX_HISTORY_RESTARTS = 2
    }
}
