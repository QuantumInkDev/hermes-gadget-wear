package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.Binding
import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ConversationPage
import dev.quantumink.hermesgadget.protocol.relay.ConversationSummary
import dev.quantumink.hermesgadget.protocol.relay.CorrelationId
import dev.quantumink.hermesgadget.protocol.relay.EventPayload
import dev.quantumink.hermesgadget.protocol.relay.EventsPage
import dev.quantumink.hermesgadget.protocol.relay.ExistingBinding
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.Info
import dev.quantumink.hermesgadget.protocol.relay.MetadataState
import dev.quantumink.hermesgadget.protocol.relay.MutationOutcome
import dev.quantumink.hermesgadget.protocol.relay.OutboxItem
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.Prompt
import dev.quantumink.hermesgadget.protocol.relay.PromptAnswer
import dev.quantumink.hermesgadget.protocol.relay.PromptDeadline
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
import dev.quantumink.hermesgadget.protocol.relay.RelayListener
import dev.quantumink.hermesgadget.protocol.relay.RelayMethod
import dev.quantumink.hermesgadget.protocol.relay.RelayOutcome
import dev.quantumink.hermesgadget.protocol.relay.RelayRefusal
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.RelayTransport
import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.TaskState
import dev.quantumink.hermesgadget.protocol.relay.state.AckOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
import dev.quantumink.hermesgadget.protocol.relay.state.CallKind
import dev.quantumink.hermesgadget.protocol.relay.state.CallRef
import dev.quantumink.hermesgadget.protocol.relay.state.ConversationScope
import dev.quantumink.hermesgadget.protocol.relay.state.DeliveryEffect
import dev.quantumink.hermesgadget.protocol.relay.state.DiscardMode
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentDelivery
import dev.quantumink.hermesgadget.protocol.relay.state.EnrollmentScope
import dev.quantumink.hermesgadget.protocol.relay.state.EventCommit
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryAssembly
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryInvalidation
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryScope
import dev.quantumink.hermesgadget.protocol.relay.state.HistorySource
import dev.quantumink.hermesgadget.protocol.relay.state.InstantRead
import dev.quantumink.hermesgadget.protocol.relay.state.LedgerStep
import dev.quantumink.hermesgadget.protocol.relay.state.MintResult
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxCommit
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.OutboxStageOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PageOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.PlanRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.PrepareOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.Reactivation
import dev.quantumink.hermesgadget.protocol.relay.state.ReadPlanOutcome
import dev.quantumink.hermesgadget.protocol.relay.state.ReadRef
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
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeAnswer
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeCalls
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTarget
import dev.quantumink.hermesgadget.protocol.relay.wake.WakeTargetId
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
    private val writerEpoch: () -> Long = { 0L },
    private val inbox: InboxRepository? = null,
    private val localMillis: () -> Long = System::currentTimeMillis,
    private val inboxUpdates: (InboxSnapshot?, String) -> Unit = { _, _ -> },
    private val inboxAlerts: (List<InboxCopy>) -> Unit = {},
    private val listener: (RelayUiState) -> Unit
) {
    // -- state (scheduler thread only) -------------------------------------------------------------------------

    private sealed interface Stored {
        data object NotLoaded : Stored
        data object Empty : Stored
        class Doc(val document: RelayDocument) : Stored
        class Problem(val problem: StoreProblem) : Stored
    }

    private val inboxEpoch = java.util.concurrent.atomic.AtomicLong()
    private val inputEpoch = java.util.concurrent.atomic.AtomicLong()
    private var admittedWriter = writerEpoch()
    private var inboxInput: InboxInput? = null
    private var existingInboxBinding: ExistingBinding? = null
    private fun localInboxRoute(handle: String): InboxRoute? {
        val repository = inbox ?: return null
        if (stored is Stored.NotLoaded) load()
        if (stored is Stored.Problem) return null
        val route = InboxRoute.resolve(repository.snapshot(), handle) ?: return null
        if (baseSection().enrollments.none {
                HistoryNamespace.from(it) == route.namespace &&
                    it.enabled && it.standing == Standing.ACTIVE
            } ||
            inboxRemovalOwed(route.namespace, route.canonicalConversation)
        ) {
            return null
        }
        return route
    }
    fun inboxRouteBlocking(handle: String): InboxRoute? {
        val latch = CountDownLatch(1)
        var result: InboxRoute? = null
        post {
            try {
                result = localInboxRoute(handle)
            } catch (_: InboxFailure) { }
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) result else null
    }

    fun markInboxRead(handle: String) = post {
        val route = localInboxRoute(handle) ?: return@post
        inbox?.markRead(route.namespace, handle)
        publish()
    }

    /** Deliberate continuation only. A notification read never calls this. */
    fun continueInbox(handle: String) = post {
        val route = try {
            localInboxRoute(handle)
        } catch (_: InboxFailure) {
            null
        } ?: return@post
        if (inboxOnly || namespace() != route.namespace || !isReady()) return@post
        val conversation = route.canonicalConversation?.let(ConversationId::of) ?: return@post
        if (!route.existing) {
            selectConversation(conversation)
            return@post
        }
        if (!supports(RelayMethod.EXISTING_ATTACH)) return@post
        endSession(attachAgain = false)
        val mine = flow
        request(RelayCalls.existingAttach(conversation)) { result ->
            if (mine != flow || namespace() != route.namespace || !isReady()) return@request
            if (inboxInputError(result, route)) return@request
            val binding =
                ((result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Success)?.value
            if (binding?.conversationId == conversation &&
                binding.canContinue
            ) {
                existingInboxBinding = binding
            }
            publish()
        }
    }

    /** The registered CURRENT getter is the sole source of an eligible one-shot input token. */
    fun getInboxInput(handle: String, done: (InboxInput?) -> Unit) = post {
        inboxInput = null
        val inputGeneration = inputEpoch.incrementAndGet()
        val route = try {
            localInboxRoute(handle)
        } catch (_: InboxFailure) {
            null
        }
        if (route == null || inboxOnly || !isReady() || namespace() != route.namespace ||
            route.canonicalConversation == null
        ) {
            done(null)
            return@post
        }
        val authority = InboxAuthorityLease.captureInput(info, clock, monotonicNanos()) ?: run {
            done(null)
            return@post
        }
        val mine = flow
        val generation = serial
        val observer = inboxEpoch.get()
        val writer = writerEpoch()
        fun permit() = observer == inboxEpoch.get() && writer == writerEpoch() &&
            inputGeneration == inputEpoch.get()
        fun currentInput(epoch: Long = observer) =
            generation == serial && mine == flow && writer == writerEpoch() &&
                inputGeneration == inputEpoch.get() && epoch == inboxEpoch.get() &&
                isReady() && !inboxOnly && namespace() == route.namespace
        fun sendPermit() = permit() && authority.allows(monotonicNanos())
        fun accept(prompt: Prompt?) {
            if (!currentInput()) return
            val currentRoute = try {
                localInboxRoute(handle)
            } catch (_: InboxFailure) {
                if (!currentInput()) return
                inboxInput = null
                val withdrawn = inboxStorageUnavailable()
                if (currentInput(withdrawn)) done(null)
                return
            }
            // Route IO and storage publication may reenter cancellation or a new action.
            if (!currentInput()) return
            if (currentRoute == null || prompt?.asActionable() == null) {
                done(null)
                return
            }
            val promptDeadline: Long? = when (prompt.deadline) {
                PromptDeadline.BOUNDED -> prompt.expiresAtMs ?: run {
                    done(null)
                    return
                }
                PromptDeadline.UNLIMITED -> null
                PromptDeadline.UNKNOWN -> {
                    done(null)
                    return
                }
            }
            if (!authority.allows(monotonicNanos(), promptDeadline)) {
                done(null)
                return
            }
            val bound = if (route.existing) {
                existingInboxBinding?.takeIf {
                    it.conversationId.value == route.conversation && it.canContinue
                }?.ref
            } else {
                token?.takeIf {
                    it.conversationId.value == route.conversation &&
                        runtime?.authority?.isCurrent(it) == true
                }?.ref
            }
            val marker: Any? = if (route.existing) existingInboxBinding else token
            val issued =
                InboxInput(
                    prompt, handle, route.namespace, serial, mine, writerEpoch(),
                    observer, inputGeneration, authority, promptDeadline, bound,
                    marker, bound != null && supports(RelayMethod.PROMPT_RESPOND)
                )
            inboxInput = issued
            done(issued)
        }
        if (route.existing) {
            val binding = existingInboxBinding?.takeIf {
                it.conversationId.value ==
                    route.conversation &&
                    it.canContinue
            }
            if (binding == null ||
                !supports(RelayMethod.EXISTING_PROMPT_GET)
            ) {
                done(null)
                return@post
            }
            request(
                RelayCalls.existingPrompt(binding.bindingId),
                permit = ::sendPermit,
                callbackPermit = ::permit
            ) { result ->
                if (inboxInputError(result, route)) {
                    done(null)
                    return@request
                }
                val value =
                    ((result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Success)?.value
                accept(value?.prompt)
            }
        } else {
            val task = route.relayTask
            if (task == null || !supports(RelayMethod.TASK_GET)) {
                done(null)
                return@post
            }
            request(
                RelayCalls.task(task.taskId),
                permit = ::sendPermit,
                callbackPermit = ::permit
            ) { result ->
                if (inboxInputError(result, route)) {
                    done(null)
                    return@request
                }
                val value =
                    ((result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Success)?.value
                if (value?.taskId != task.taskId || value.conversationId != task.conversationId ||
                    value.admissionId != task.admissionId ||
                    value.state != TaskState.NEEDS_INPUT
                ) {
                    done(null)
                } else {
                    accept(value.prompt)
                }
            }
        }
    }

    /**
     * No stored callback, list, O5 copy or notification can be supplied as this token. No
     * automatic answer retry.
     */
    fun answerInboxInput(issued: InboxInput, answer: PromptAnswer, done: (Boolean) -> Unit) = post {
        val marker: Any? = if (existingInboxBinding != null) existingInboxBinding else token
        if (inboxInput !== issued || !issued.canAnswer || issued.serial != serial ||
            issued.flow != flow ||
            issued.writer != writerEpoch() || issued.observer != inboxEpoch.get() ||
            issued.inputGeneration != inputEpoch.get() || issued.owner != namespace() ||
            issued.boundToken !== marker ||
            !isReady() || inboxOnly
        ) {
            done(false)
            return@post
        }
        fun permit() = issued.writer == writerEpoch() &&
            issued.observer == inboxEpoch.get() && issued.inputGeneration == inputEpoch.get()
        if (!issued.authority.allows(monotonicNanos(), issued.promptDeadlineMs)) {
            inboxInput = null
            done(false)
            return@post
        }
        fun currentInput(epoch: Long = issued.observer) =
            issued.serial == serial && issued.flow == flow && issued.writer == writerEpoch() &&
                issued.inputGeneration == inputEpoch.get() && epoch == inboxEpoch.get() &&
                issued.owner == namespace() && isReady() && !inboxOnly
        val route = try {
            localInboxRoute(issued.route)
        } catch (_: InboxFailure) {
            if (!currentInput()) return@post
            inboxInput = null
            val withdrawn = inboxStorageUnavailable()
            if (currentInput(withdrawn)) done(false)
            return@post
        }
        if (!currentInput()) return@post
        if (route == null) {
            done(false)
            return@post
        }
        val actionable = issued.prompt.asActionable() ?: run {
            done(false)
            return@post
        }
        val at = issued.authority.instant(monotonicNanos(), issued.promptDeadlineMs) ?: run {
            inboxInput = null
            done(false)
            return@post
        }
        val id =
            (RequestIds.mint(at, entropy) as? MintResult.Minted)?.id
                ?: run {
                    done(false)
                    return@post
                }
        val call = try {
            RelayCalls.respond(requireNotNull(issued.binding), actionable, answer, id)
        } catch (
            _: IllegalArgumentException
        ) {
            done(false)
            return@post
        }
        val committed = tryCommit(requireNotNull(runtime))
        if (!permit()) return@post
        if (!committed) {
            if (!issued.authority.allows(monotonicNanos(), issued.promptDeadlineMs)) {
                inboxInput =
                    null
            }
            done(false)
            return@post
        }
        inboxInput = null
        request(
            call,
            permit = {
                permit() && issued.authority.allows(monotonicNanos(), issued.promptDeadlineMs)
            },
            callbackPermit = ::permit
        ) { result ->
            val failed = inboxInputError(result, route)
            done(!failed && (result as? RelayCallResult.Outcome)?.outcome is RelayOutcome.Success)
        }
    }

    /** Apply correlated authority before completing a deliberate input action; never retry it. */
    private fun inboxInputError(result: RelayCallResult<*>, route: InboxRoute): Boolean {
        val error = ((result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Failed)?.error
            ?: return false
        when (error.action) {
            RelayErrorAction.STOP_AND_EVICT_SCOPE -> stopScope()
            RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> requireReview()
            else -> {
                // Exact existing-target absence is not enrollment-wide withdrawal.
                if (route.existing && error.code == RelayErrorCode.NOT_FOUND) {
                    try {
                        inbox?.sourceUnavailable(route.namespace, route.conversation, false, true)
                    } catch (_: InboxFailure) {
                        inboxStatus = "Partial / existing source unavailable"
                    }
                    publish()
                }
            }
        }
        return true
    }

    private var inboxOnly = false
    private var inboxRun: InboxRun? = null

    @Volatile var inboxStatus: String = "Retained / offline"
        private set
    private var inboxCompleted: (String) -> Unit = {}
    fun observeInboxOnly(on: Boolean, done: (String) -> Unit = {}) = post {
        inboxCompleted = done
        if (inboxOnly != on) {
            endSession(attachAgain = false)
            inboxOnly = on
        }
    }
    fun refreshInbox(done: (String) -> Unit = {}) = post {
        var completed = false
        val finishOnce: (String) -> Unit = { status ->
            if (!completed) {
                completed = true
                done(status)
            }
        }
        inboxCompleted = finishOnce
        if (inboxRun?.current() == false) {
            withdrawInboxObserver()
            inboxStatus = "Partial / fresh authority required"
        }
        if (inboxRun != null || !isReady()) {
            finishOnce(inboxStatus)
            return@post
        }
        startInbox()
        if (inboxRun == null) finishOnce(inboxStatus)
    }
    private var inboxTargetKey: String? = null
    private var inboxTargetLabel = "Relay profile"
    private var inboxTargetBox = "Relay box"
    fun inboxTarget(key: String, label: String, box: String) = post {
        if (key.length !in 1..512 || label.length > 512 || box.length > 512) return@post
        inboxTargetKey = key
        inboxTargetLabel = label
        inboxTargetBox = box
    }
    fun configureInbox(profile: InboxProfile) {
        inboxEpoch.incrementAndGet() // synchronous final-SEND fence, before serialized settings IO
        post {
            withdrawInboxObserver()
            if (baseSection().enrollments.none {
                    HistoryNamespace.from(it) == profile.namespace
                }
            ) {
                return@post
            }
            try {
                inbox?.configure(profile)
                inboxStatus = "Retained / offline"
            } catch (
                _: InboxFailure
            ) {
                inboxStatus = "Inbox storage unavailable"
            }
            publish()
        }
    }
    private fun withdrawInboxObserver() {
        inboxRun = null
        runtime =
            runtime?.let { rt -> rt.copy(deliveries = rt.deliveries.map { it.withdrawOutbox() }) }
    }
    fun inboxRetention(days: Int) {
        inboxEpoch.incrementAndGet()
        post {
            withdrawInboxObserver()
            try {
                inbox?.retention(days)
                publish()
            } catch (
                _: InboxFailure
            ) {
                inboxStatus = "Inbox storage unavailable"
            }
        }
    }

    /**
     * Reviewed durable targets only; JobScheduler configuration never borrows a prepared
     * identity.
     */
    fun inboxBackgroundTargetsBlocking(): Set<HistoryNamespace> {
        val latch = CountDownLatch(1)
        var result = emptySet<HistoryNamespace>()
        post {
            if (stored is Stored.NotLoaded) load()
            if (stored !is Stored.Problem) {
                result = baseSection().enrollments.filter {
                    it.enabled && it.standing == Standing.ACTIVE
                }.mapNotNull(HistoryNamespace::from).filterNot {
                    removalOwed(it, null, wholeScopeOnly = true)
                }.toSet()
            }
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) result else emptySet()
    }
    fun nextInboxRoundRobinBlocking(): Int? {
        val latch = CountDownLatch(1)
        var result: Int? = null
        post {
            try {
                result = inbox?.nextRoundRobin()
            } catch (_: InboxFailure) { }
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) result else null
    }
    fun inboxBackground(on: Boolean) = post {
        try {
            inbox?.background(on)
        } catch (_: InboxFailure) {
            inboxStatus =
                "Inbox storage unavailable"
        }
    }

    /** Called off main. Exact current removals also fence offline local routes. */
    fun inboxBlocking(timeoutMs: Long = 5000): InboxSnapshot? {
        val latch = CountDownLatch(1)
        var result: InboxSnapshot? = null
        post {
            try {
                if (stored is Stored.NotLoaded) load()
                if (stored is Stored.Problem) return@post latch.countDown()
                val repository = inbox
                val initial = repository?.snapshot()
                val protected =
                    runtime?.deliveries?.flatMap { it.acknowledgementsOwed.map { it.value } }
                        ?: (stored as? Stored.Doc)?.document?.snapshot?.deliveries?.flatMap {
                            it.pendingAcks
                        }
                        ?: emptyList()
                for (p in initial?.profiles ?: emptyList()) {
                    repository?.sweep(
                        p.namespace,
                        localMillis(),
                        protected
                    )
                }
                result = filteredInbox(repository?.peek())
            } catch (_: InboxFailure) {
                inboxStatus = "Inbox storage unavailable"
            }
            latch.countDown()
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
    }

    private fun filteredInbox(s: InboxSnapshot?): InboxSnapshot? = s?.takeIf {
        stored !is Stored.Problem &&
            stored !is Stored.NotLoaded
    }?.let { s ->
        s.copy(
            copies = s.copies.filter { c ->
                s.profiles.any {
                    baseSection().enrollments.any { r ->
                        HistoryNamespace.from(r) ==
                            it.namespace &&
                            r.enabled && r.standing == Standing.ACTIVE
                    } && it.namespace == c.namespace &&
                        it.enabled &&
                        (
                            !c.existing ||
                                it.existingTasks && it.existingAvailable &&
                                s.unavailable.none { u ->
                                    u.namespace == c.namespace &&
                                        u.conversation == c.conversation &&
                                        !u.history
                                }
                            )
                } &&
                    !inboxRemovalOwed(c.namespace, c.canonicalConversation)
            },
            tasks = s.tasks.filter { t ->
                s.profiles.any {
                    baseSection().enrollments.any { r ->
                        HistoryNamespace.from(r) ==
                            it.namespace &&
                            r.enabled && r.standing == Standing.ACTIVE
                    } && it.namespace == t.namespace &&
                        it.enabled &&
                        (
                            t.existing == null ||
                                it.existingTasks && it.existingAvailable &&
                                s.unavailable.none { u ->
                                    u.namespace == t.namespace &&
                                        u.conversation == t.conversation &&
                                        !u.history
                                }
                            )
                } &&
                    !removalOwed(t.namespace, t.conversation)
            },
            histories = s.histories.filter { h ->
                s.profiles.any {
                    baseSection().enrollments.any { r ->
                        HistoryNamespace.from(r) ==
                            it.namespace &&
                            r.enabled && r.standing == Standing.ACTIVE
                    } && it.namespace == h.namespace &&
                        it.enabled &&
                        (
                            !h.existing ||
                                it.existingHistory && it.existingHistoryAvailable &&
                                s.unavailable.none { u ->
                                    u.namespace == h.namespace &&
                                        u.conversation == h.conversation &&
                                        u.history
                                }
                            )
                } &&
                    !removalOwed(h.namespace, h.conversation)
            }
        )
    }

    private var identity: EnrollmentIdentity? = null
    private var foregroundSession = 0L
    private var stored: Stored = Stored.NotLoaded
    private var storeSequence = 0L
    private var runtime: RelayRuntime? = null
    private var unsaved = false

    /** Confirmed by THIS controller's format4 writer before admitting the selected attach/subscription. */
    private var selectionReservation: HistoryEviction? = null

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
        set(value) {
            inputEpoch.incrementAndGet()
            inboxEpoch.incrementAndGet()
            withdrawInboxObserver()
            discardDeferredEvents()
            inboxInput = null
            existingInboxBinding = null
            field = value
        }

    /** Bumped by every attach flow and every selection change. */
    private var flow = 0L
        set(value) {
            inputEpoch.incrementAndGet()
            discardDeferredEvents()
            inboxInput = null
            existingInboxBinding = null
            field = value
        }
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
    private var cacheReviewOperation = 0L
    private var cacheReviewConfirmation: CacheReviewConfirmation? = null
    private var cacheReview: HistoryEraseReview? = null
        set(value) {
            field = value
            cacheReviewOperation++
            cacheReviewConfirmation = value?.let { review ->
                namespace()?.let { owner ->
                    CacheReviewConfirmation(
                        review,
                        owner,
                        foregroundSession,
                        serial,
                        flow,
                        cacheReviewOperation
                    )
                }
            }
        }
    private val stoppedRemovalConfirmed = HashSet<HistoryNamespace>()

    /** A refused exact deletion is fenced in this process, but is explicitly NOT durable. No erase uses it. */
    private var undurableDeletion: HistoryEviction? = null
    private var historyAnchor: HistoryAnchor = HistoryAnchor.Beginning
    private var historyChainStarted = false
    private var historyListing = ""
    private var metadataSending: RequestId? = null

    /** Last retired outcome for this foreground only; it is not a pending operation or removal authority. */
    private var retiredMetadataOutcome: MetadataIntent? = null
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
    private var listRetryTimer: Cancelable? = null
    private var pollInFlight = false

    /** At most ONE already decoded/validated bounded reply, still owned by its original outstanding read. */
    private class DeferredEvents(
        val session: Long,
        val flow: Long,
        val bound: BindingToken,
        val ref: ReadRef,
        val page: EventsPage
    )
    private var deferredEvents: DeferredEvents? = null
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

    /** Remote wake: transient, never stored. The session serial a read or request was written under ends it. */
    private var wakeTargets: List<WakeTarget>? = null
    private var wakeListAnswer: WakeAnswer? = null
    private var wakeListSerial: Long? = null
    private var wakeSending: WakeTargetId? = null
    private var wakeSendSerial = 0L
    private val wakeAnswers = LinkedHashMap<WakeTargetId, WakeAnswer>()

    // -- public API (thread-safe) ------------------------------------------------------------------------------

    fun prepare(identity: EnrollmentIdentity, foregroundSession: Long = 0) = post {
        if (foregroundSession > 0 && this.foregroundSession > foregroundSession) return@post
        //  An ordinary actor/identity handover is not process loss. Keep applied unsaved evidence
        // and fences.
        if (this.identity != null && this.identity != identity &&
            (unsaved || undurableDeletion != null)
        ) {
            notice = RelayNotice.OBSERVATION_UNSAVED
            return@post publish()
        }
        admittedWriter = writerEpoch()
        if (this.foregroundSession != foregroundSession) {
            selectionReservation = null
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
            clearWake()
        }
        if (stored is Stored.NotLoaded) load()
        loadRetained()
        publish()
    }

    fun inboxAdmissionBlocking(owner: HistoryNamespace): Boolean {
        val latch = CountDownLatch(1)
        var valid = false
        post {
            valid =
                namespace() == owner &&
                record()?.let { it.enabled && it.standing == Standing.ACTIVE } == true &&
                !removalOwed(owner, null, wholeScopeOnly = true)
            latch.countDown()
        }
        return latch.await(5, TimeUnit.SECONDS) && valid
    }

    /**
     * Barrier for an actor handover: a refused prepare cannot lend the prior identity's enabled
     * setting.
     */
    fun preparedBlocking(
        expected: EnrollmentIdentity,
        session: Long,
        timeoutMs: Long = 5000
    ): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        post {
            accepted = identity == expected && foregroundSession == session
            latch.countDown()
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && accepted
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
            selectedConversationId = if (on ||
                existing?.selectionDeleted == true
            ) {
                existing?.selectedConversationId
            } else {
                null
            }
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
            clearWake()
        }
        unavailable = null
        notice = RelayNotice.NONE
        publish()
    }

    /** The connection just built (or closed: null) for the prepared profile. */
    fun attachTransport(transport: RelayTransport?) = post {
        admittedWriter = writerEpoch()
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
        } else if (stored is Stored.Problem && runtime == null) {
            stored = Stored.NotLoaded
            load()
        }
        evictOwed()
        publish()
    }

    /** The screen is hidden or replaced: transient work stops; the stored state stays; nothing re-attaches by itself. */
    fun hide(expectedSession: Long? = null) = post {
        if (expectedSession != null && foregroundSession != expectedSession) return@post
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
        clearWake()
        publish()
    }

    /**
     * Remote wake: one deliberate read of the targets this device's grant may see on the current connection. Only
     * when `relay.info` lists `wake.targets`; nothing is stored or repeated by itself.
     */
    fun listWakeTargets() = post {
        if (!isReady() || wakeListSerial != null) return@post
        if (!supports(RelayMethod.WAKE_TARGETS)) {
            wakeListAnswer = WakeAnswer.NOT_OFFERED
            return@post publish()
        }
        val expected = serial
        wakeListSerial = expected
        wakeListAnswer = null
        request(WakeCalls.targets()) { result ->
            if (wakeListSerial != expected) return@request
            wakeListSerial = null
            val outcome = (result as? RelayCallResult.Outcome)?.outcome
            if (outcome is RelayOutcome.Success) {
                wakeTargets = outcome.value.targets
                wakeAnswers.keys.retainAll(outcome.value.targets.map { it.targetId }.toSet())
            } else {
                wakeListAnswer = WakeCalls.answer(result)
            }
            publish()
        }
        publish()
    }

    /**
     * Remote wake: exactly ONE `wake.request` for a listed, available target, after the user confirmed it. A fresh
     * UUIDv7 and a 20 s expiry are both formed from the server clock. No retry, no persistence; a lost answer stays
     * [WakeAnswer.NOT_CONFIRMED] and a new tap is a new request. A wake answer never stops or reviews the conversation
     * scope: wake authority is independent of it.
     */
    fun wake(targetId: String) = post {
        val id = WakeTargetId.of(targetId) ?: return@post
        if (!isReady() || wakeSending != null) return@post
        val target = wakeTargets?.firstOrNull { it.targetId == id } ?: return@post
        if (!target.available) return@post
        if (!supports(RelayMethod.WAKE_REQUEST)) {
            wakeAnswers[id] = WakeAnswer.NOT_OFFERED
            return@post publish()
        }
        val at = now()
        val requestId = at?.let { (RequestIds.mint(it, entropy) as? MintResult.Minted)?.id }
        if (at == null || requestId == null ||
            WakeCalls.expiry(at.serverMs, WakeCalls.DEFAULT_TTL_MS) == null
        ) {
            wakeAnswers[id] = WakeAnswer.NO_CLOCK
            return@post publish()
        }
        val expected = serial
        wakeSending = id
        wakeSendSerial = expected
        wakeAnswers.remove(id)
        request(WakeCalls.request(id, requestId, at)) { result ->
            if (wakeSending != id || wakeSendSerial != expected) return@request
            wakeSending = null
            wakeAnswers[id] = WakeCalls.answer(result)
            publish()
        }
        publish()
    }

    private fun clearWake() {
        wakeTargets = null
        wakeListAnswer = null
        wakeListSerial = null
        wakeSending = null
        wakeAnswers.clear()
    }

    /** A read or request whose session ended gets no callback: its result is unknown, never silently pending. */
    private fun wakeView(): WakeView {
        if (wakeListSerial != null && wakeListSerial != serial) {
            wakeListSerial = null
            wakeListAnswer = WakeAnswer.NOT_CONFIRMED
        }
        wakeSending?.takeIf { wakeSendSerial != serial }?.let {
            wakeSending = null
            wakeAnswers[it] = WakeAnswer.NOT_CONFIRMED
        }
        val ready = isReady()
        return WakeView(
            canList = ready && supports(RelayMethod.WAKE_TARGETS),
            canRequest = ready && supports(RelayMethod.WAKE_REQUEST),
            listing = wakeListSerial != null,
            targets = wakeTargets,
            listAnswer = wakeListAnswer,
            sending = wakeSending?.value,
            answers = wakeAnswers.entries.associate { it.key.value to it.value }
        )
    }

    /** The newest state, for tests and for a first paint. */
    val currentState: RelayUiState get() = ui

    private fun namespace(): HistoryNamespace? = record()?.let(HistoryNamespace::from)

    private fun boundedSection(build: () -> ControllerSection): ControllerSection? = try {
        build()
    } catch (failure: HistoryFailure) {
        retainedProblem = failure.problem
        notice =
            if (failure.problem ==
                HistoryProblem.CAPACITY
            ) {
                RelayNotice.STORAGE_FULL_PENDING_ITEMS
            } else {
                RelayNotice.STORAGE_WRITE_FAILED
            }
        null
    }

    /** RETIRED is already durable immutable exact-target evidence even if an extra eviction slot was refused. */
    private fun durableRemovals(section: ControllerSection = baseSection()): List<HistoryEviction> =
        (
            section.removals + section.enrollments.filter { it.selectionDeleted }.map { record ->
                HistoryEviction(
                    requireNotNull(HistoryNamespace.from(record)),
                    requireNotNull(record.selectedConversationId).value,
                    EvictionKind.DELETED
                )
            } + section.intents.filter { it.status == MetadataStatus.RETIRED }.map {
                HistoryEviction(it.namespace, it.conversationId.value, EvictionKind.DELETED)
            } + section.enrollments.filter { it.standing == Standing.STOPPED }.mapNotNull {
                HistoryNamespace.from(it)?.takeUnless { owner ->
                    owner in stoppedRemovalConfirmed
                }?.let { owner -> HistoryEviction(owner, null) }
            }
            ).distinct()

    private fun removalOwed(
        owner: HistoryNamespace,
        conversation: String?,
        wholeScopeOnly: Boolean = false
    ): Boolean = (durableRemovals() + listOfNotNull(undurableDeletion)).any {
        it.namespace == owner &&
            (it.conversationId == null || (!wholeScopeOnly && it.conversationId == conversation))
    }

    private fun inboxRemovalOwed(owner: HistoryNamespace, canonical: String?): Boolean =
        removalOwed(owner, canonical, wholeScopeOnly = canonical == null)

    private fun blocked(conversation: String): Boolean =
        namespace()?.let { removalOwed(it, conversation) } == true

    private fun selectionTarget(section: ControllerSection): HistoryEviction? {
        val who = identity ?: return null
        val record = section.find(who.endpointKey, who.enrollmentLabel) ?: return null
        val owner = HistoryNamespace.from(record) ?: return null
        val id = record.selectedConversationId ?: return null
        return HistoryEviction(owner, id.value, EvictionKind.DELETED)
    }

    private fun selectionReplaceable(): Boolean {
        if (record()?.selectionDeleted != true && undurableDeletion == null) return true
        retainedProblem = HistoryProblem.CAPACITY
        notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
        return false
    }

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
        if (!isReady() || blocked(conversationId) || !supports(RelayMethod.CONVERSATION_UPDATE) ||
            unsaved ||
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
        val section = boundedSection { baseSection().withIntent(intent) } ?: return@post publish()
        if (!commitSection(section)) return@post publish()
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
        if (retiredMetadataOutcome?.let {
                it.namespace == namespace() &&
                    it.requestId.value == requestId
            } ==
            true
        ) {
            retiredMetadataOutcome = null
        }
        val intent =
            baseSection().intents.firstOrNull {
                it.namespace == namespace() &&
                    it.requestId.value == requestId
            }
                ?: return@post publish()
        val section = boundedSection {
            val base = if (intent.status == MetadataStatus.RETIRED) {
                baseSection().owing(
                    HistoryEviction(
                        intent.namespace,
                        intent.conversationId.value,
                        EvictionKind.DELETED
                    )
                )
            } else {
                baseSection()
            }
            base.withoutIntent(intent)
        } ?: return@post publish()
        transport?.invalidatePending()
        metadataSending = null
        if (commitSection(section) && intent.status == MetadataStatus.RETIRED) evictOwed()
        publish()
    }

    private fun sendMetadata(intent: MetadataIntent) {
        if (!isReady() || blocked(intent.conversationId.value) || unsaved ||
            !supports(RelayMethod.CONVERSATION_UPDATE) ||
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
        retiredMetadataOutcome = null
        // A possibly executed mutation invalidates its old cursors, even if its reply is lost.
        if (record()?.selectedConversationId == intent.conversationId) {
            flow++
            historyComplete = false
            historyIncomplete = true
            historyChainStarted = false
            historyAnchor = HistoryAnchor.Beginning
        }
        listOperation++
        listCursor = null
        listing = false
        cacheReview = null
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
            val observed = intent.copy(status = status, coreCommitted = committed)
            if (status == MetadataStatus.RETIRED) retiredMetadataOutcome = observed
            val next = baseSection().withIntent(observed)
            runtime?.let { advance(it.copy(section = next)) }
            // Any mutation outcome invalidates old public list/history cursor chains.
            listOperation++
            listCursor = null
            cacheReview = null
            if (status == MetadataStatus.RETIRED) {
                conversationDeleted(intent.namespace, intent.conversationId)
            } else if (fresh && isReady()) {
                val selected = record()?.selectedConversationId
                if (selected == intent.conversationId && token != null) {
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
        if (owner == null || record()?.enabled != true || record()?.standing != Standing.ACTIVE ||
            removalOwed(owner, null, wholeScopeOnly = true)
        ) {
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
                    !removalOwed(owner, it.id) && (
                        it.title.lowercase(java.util.Locale.ROOT).contains(needle) ||
                            it.messages.any { message ->
                                message.text.lowercase(java.util.Locale.ROOT).contains(needle)
                            }
                        )
                }.take(50)
            if (retainedSelected?.let { removalOwed(owner, it.id) } == true) retainedSelected = null
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
        cacheReview = null
        retainedSelected = null
        publish()
    }

    fun reviewCache(conversationId: String?) = post {
        cacheReview = null
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

    fun confirmCacheReview(rendered: CacheReviewConfirmation) = post {
        val owner = namespace() ?: return@post
        if (rendered !== cacheReviewConfirmation || rendered.review != cacheReview ||
            rendered.namespace != owner || rendered.foreground != foregroundSession ||
            rendered.session != serial || rendered.flow != flow ||
            rendered.operation != cacheReviewOperation
        ) {
            return@post
        }
        val review = rendered.review
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
        section = boundedSection {
            var next = section
            for (namespace in owners) {
                if (review.retentionDays == null) {
                    next = next.owing(
                        HistoryEviction(
                            namespace,
                            if (review.allProfiles) null else review.conversationId,
                            EvictionKind.CACHE_ONLY
                        )
                    )
                }
            }
            next
        } ?: return@post publish()
        if (!commitSection(section)) return@post publish()
        // Consume before erase, so a duplicate cannot reuse the confirmation.
        cacheReview = null
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

    private fun post(task: () -> Unit) {
        val epoch = writerEpoch()
        scheduler.post(
            Runnable {
                if (epoch == writerEpoch()) {
                    try {
                        task()
                    } catch (_: InboxFailure) {
                        inboxStorageUnavailable()
                    }
                }
            }
        )
    }

    /** The returned epoch distinguishes this fault's withdrawal from a real replacement. */
    private fun inboxStorageUnavailable(): Long {
        val observer = inboxEpoch.incrementAndGet()
        val writer = writerEpoch()
        val generation = serial
        val completed = inboxCompleted
        withdrawInboxObserver()
        if (admittedWriter == writer) {
            inboxStatus = "Inbox storage unavailable"
            publish()
            if (writer == writerEpoch() && generation == serial &&
                observer == inboxEpoch.get() && inboxCompleted === completed
            ) {
                completed("Inbox storage unavailable")
            }
        }
        return observer
    }

    private fun later(delayMs: Long, task: () -> Unit): Cancelable {
        val expected = serial
        val writer = writerEpoch()
        return scheduler.postDelayed(
            delayMs,
            Runnable {
                if (expected == serial && writer == writerEpoch()) task()
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
        selectionReservation = null
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
        if (admittedWriter != writerEpoch()) return false
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
        selectionReservation = selectionTarget(document.section)
        return true
    }

    /** Stores [next]; only when that succeeds does it become the runtime. The send that needed it is refused otherwise. */
    private fun adopt(next: RelayRuntime): Boolean {
        if (!tryCommit(next)) return false
        runtime = next
        unsaved = false
        return true
    }

    private fun tryCommit(next: RelayRuntime, observed: Boolean = false): Boolean {
        if (admittedWriter != writerEpoch()) return false
        val at = now()
        if (at == null) {
            notice = RelayNotice.CLOCK_UNAVAILABLE
            return false
        }
        val document = when (val built = next.build(at)) {
            is DocumentBuild.Built -> built.document
            is DocumentBuild.TooMany -> {
                notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
                if (observed &&
                    (token != null || attaching || inboxRun != null)
                ) {
                    endSession(attachAgain = false)
                }
                return false
            }
            is DocumentBuild.Unrestorable -> {
                notice = RelayNotice.STORAGE_WRITE_FAILED
                if (observed &&
                    (token != null || attaching || inboxRun != null)
                ) {
                    endSession(attachAgain = false)
                }
                return false
            }
        }
        val result = store.commit(document, storeSequence)
        if (result !is StoreCommit.Committed) {
            noticeFor(result)
            // A deterministic representation refusal cannot leave an active observer with no storable aggregate.
            if (observed && result is StoreCommit.Refused &&
                (token != null || attaching || inboxRun != null)
            ) {
                endSession(attachAgain = false)
            }
            return false
        }
        storeSequence = result.sequence
        stored = Stored.Doc(document)
        selectionReservation = selectionTarget(document.section)
        return true
    }

    /** A server answer or a retired binding changed the state: it is applied; a failed commit only marks it unsaved. */
    private fun advance(next: RelayRuntime) {
        runtime = next
        unsaved = !tryCommit(next, observed = true)
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
        inboxRun = null
        runtime =
            runtime?.let { rt -> rt.copy(deliveries = rt.deliveries.map { it.withdrawOutbox() }) }
        inboxStatus = "Retained / offline"
        serial++
        flow++
        pollTimer?.cancel()
        retryTimer?.cancel()
        listRetryTimer?.cancel()
        listRetryTimer = null
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
        retiredMetadataOutcome = null
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
        if (refresh) {
            evictOwed()
            schedulePoll()
            return
        } // still recover independently authorized removals
        serial++
        flow++
        readyGeneration = generation
        unavailable = null
        if (stored is Stored.Problem) return
        val granted = infoDigest != null && infoDigest != digest
        infoDigest = digest
        if (!ensureRuntime()) return
        evictOwed()
        var record = record() ?: return
        if (!record.enabled) return
        val offered = state.info.profileId
        var rt = requireNotNull(runtime)
        if (inboxOnly && record.pinnedProfileId == null) return
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
        if (inbox != null && !inboxOnly && inboxTargetKey != null) {
            val n = namespace()
            if (n != null) {
                try {
                    val old = inbox.snapshot().profiles.firstOrNull { it.namespace == n }
                    inbox.configure(
                        old?.copy(
                            key = requireNotNull(inboxTargetKey),
                            label = inboxTargetLabel,
                            box = inboxTargetBox
                        )
                            ?: InboxProfile(
                                n,
                                requireNotNull(inboxTargetKey),
                                inboxTargetLabel,
                                inboxTargetBox
                            )
                    )
                } catch (_: InboxFailure) {
                    inboxStatus = "Inbox storage unavailable"
                }
            }
        }
        if (inboxOnly) {
            if (record.pinnedProfileId != offered) return
            startInbox()
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
            listRetryTimer?.cancel()
            listRetryTimer = null
            if (notice == RelayNotice.HISTORY_INCOMPLETE ||
                notice == RelayNotice.BUSY_TRY_AGAIN
            ) {
                notice = RelayNotice.NONE
            }
            listOperation++
            listPages = 0
            listRestarts = 0
            listRequests = 0
            listCursors.clear()
            listCursor = null
            listPage(null, 0)
        }
    }

    private fun startInbox() {
        val repository = inbox ?: return
        if (inboxRun?.current() == false) withdrawInboxObserver()
        if (!isReady() || inboxRun != null) return
        val owner = namespace() ?: return
        val scope = scope() ?: return
        val at = now() ?: return
        if (info?.profileId?.value != owner.profileId ||
            at.serverMs >= (info?.expiresAtMs ?: 0)
        ) {
            return
        }
        try {
            val profile = repository.snapshot().profiles.firstOrNull { it.namespace == owner }
            if (profile == null) {
                repository.configure(
                    InboxProfile(
                        owner,
                        owner.endpointKey,
                        "Relay profile",
                        "Relay box"
                    )
                )
            }
            if (profile?.enabled == false) return
            if (unsaved) {
                if (!tryCommit(requireNotNull(runtime))) return
                unsaved = false
            }
            val rt = requireNotNull(runtime)
            runtime =
                rt.withDelivery(
                    (rt.delivery(scope) ?: EnrollmentDelivery.begin(scope)).withdrawOutbox()
                )
            InboxRun(owner, scope, serial).also {
                inboxRun = it
                it.begin()
            }
        } catch (_: InboxFailure) {
            inboxStatus = "Inbox storage unavailable"
        }
    }

    /**
     * One bounded read-only observer. All callbacks, cache IO and full aggregate writes share
     * this controller thread.
     */
    private inner class InboxRun(
        val owner: HistoryNamespace,
        val enrollment: EnrollmentScope,
        val generation: Long
    ) {
        val writer = writerEpoch()
        val observer = inboxEpoch.get()
        private val authority = InboxAuthorityLease.capture(info, clock, monotonicNanos())
        private fun ackPermit(): Boolean = permit() && authority?.allows(monotonicNanos()) == true
        fun permit(): Boolean = writer == writerEpoch() && observer == inboxEpoch.get()
        val deadline = monotonicNanos() + 20_000_000_000L
        var requests = 0
        var pages = 0
        val cursors = HashSet<String>()
        val repository = requireNotNull(inbox)
        fun currentLifetime(): Boolean =
            inboxRun === this && generation == serial && permit() && isReady() &&
                namespace() == owner && info?.profileId?.value == owner.profileId
        fun current(): Boolean = currentLifetime() && authority?.allows(monotonicNanos()) == true
        fun finish(status: String) {
            if (inboxRun !== this) return
            runtime = runtime?.let { rt ->
                rt.delivery(enrollment)?.let {
                    rt.withDelivery(it.withdrawOutbox())
                } ?: rt
            }
            inboxRun = null
            if (!permit()) return
            inboxStatus = status
            publish()
            inboxCompleted(status)
        }
        fun budget(): Boolean {
            if (!current()) return false
            if (++requests > 64 || monotonicNanos() >= deadline) {
                finish("Partial / refresh budget reached")
                return false
            }
            return true
        }
        fun delivery(): EnrollmentDelivery = requireNotNull(runtime?.delivery(enrollment))
        fun transient(d: EnrollmentDelivery) {
            runtime = requireNotNull(runtime).withDelivery(d)
        }
        fun commit(d: EnrollmentDelivery): Boolean {
            advance(requireNotNull(runtime).withDelivery(d))
            publish()
            return current() && !unsaved
        }
        fun <T : Any> read(call: RelayCall<T>, target: String? = null, done: (T) -> Unit) {
            if (!budget()) return
            request(call, permit = ::permit) { result ->
                if (!currentLifetime()) return@request
                val outcome = (result as? RelayCallResult.Outcome)?.outcome
                if (outcome is RelayOutcome.Success) {
                    if (!current()) {
                        finish("Partial / fresh authority required")
                        return@request
                    }
                    try {
                        done(outcome.value)
                    } catch (
                        _: InboxFailure
                    ) {
                        finish("Inbox storage unavailable")
                    }
                } else {
                    val error = (outcome as? RelayOutcome.Failed)?.error
                    if (withdrawAuthority(error)) return@request
                    if (!current()) {
                        finish("Partial / fresh authority required")
                        return@request
                    }
                    if (call.method in
                        setOf(
                            RelayMethod.EXISTING_LIST,
                            RelayMethod.EXISTING_TASKS_LIST,
                            RelayMethod.EXISTING_HISTORY_PAGE
                        )
                    ) {
                        if (target != null && error?.code == RelayErrorCode.NOT_FOUND) {
                            repository.sourceUnavailable(
                                owner,
                                target,
                                call.method == RelayMethod.EXISTING_HISTORY_PAGE,
                                true
                            )
                        }
                        finish("Partial / existing source unavailable")
                        return@request
                    }
                    finish("Partial / source unavailable")
                }
            }
        }

        /** Authority verdicts survive local time expiry, but never a replaced observer. */
        fun withdrawAuthority(error: RelayError?): Boolean {
            if (error?.action !in setOf(
                    RelayErrorAction.STOP_AND_EVICT_SCOPE,
                    RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
                )
            ) {
                return false
            }
            val completed = inboxCompleted
            // Both existing withdrawal owners advance serial/observer exactly once.
            val withdrawnSerial = generation + 1
            val withdrawnObserver = observer + 1
            fun currentWithdrawal() = writer == writerEpoch() && serial == withdrawnSerial &&
                inboxEpoch.get() == withdrawnObserver && namespace() == owner &&
                inboxCompleted === completed
            when (error?.action) {
                RelayErrorAction.STOP_AND_EVICT_SCOPE -> stopScope()
                RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW -> requireReview()
                else -> Unit
            }
            if (currentWithdrawal()) {
                inboxStatus = "Partial / source authority withdrawn"
                publish()
                if (currentWithdrawal()) completed("Partial / source authority withdrawn")
            }
            return true
        }

        var refreshBeforeAck = false
        fun begin() {
            inboxStatus = "Refreshing"
            val profile = repository.snapshot().profiles.single { it.namespace == owner }
            val available = profile.existingTasks && supports(RelayMethod.EXISTING_TASKS_LIST)
            val historyAvailable = profile.existingHistory && supports(RelayMethod.EXISTING_LIST) &&
                supports(RelayMethod.EXISTING_HISTORY_PAGE)
            // Task reads require exact targets; the history-purpose catalog is not exhaustive
            // for the independent task-purpose scope. Known-target progress stays bounded Partial.
            if (profile.existingTasks || (profile.existingHistory && !historyAvailable)) {
                incomplete = true
            }
            if (available != profile.existingAvailable ||
                historyAvailable != profile.existingHistoryAvailable
            ) {
                repository.configure(
                    profile.copy(
                        existingAvailable = available,
                        existingHistoryAvailable = historyAvailable
                    )
                )
            }
            repository.sweep(owner, localMillis(), delivery().acknowledgementsOwed.map { it.value })
            val snapshot = repository.snapshot()
            val expired = snapshot.copies.filter {
                it.namespace == owner &&
                    it.item.deliveryId in delivery().acknowledgementsOwed &&
                    it.item.expiresAtMs <= requireNotNull(now()).serverMs
            }.map { it.item.deliveryId }.toSet()
            if (expired.isNotEmpty() &&
                (
                    !repository.confirmCopies(owner, expired.map { it.value }) ||
                        !adopt(
                            requireNotNull(
                                runtime
                            ).withDelivery(delivery().retireAcknowledgements(expired))
                        )
                    )
            ) {
                finish("Partial / expiry unsaved")
                return
            }
            if (snapshot.unavailable.any { it.namespace == owner }) {
                refreshBeforeAck = true
                tasks()
            } else {
                ack { outbox() }
            }
        }
        fun ack(next: () -> Unit) {
            if (!current()) return
            if (delivery().acknowledgementsOwed.isEmpty()) {
                next()
                return
            }
            if (!supports(RelayMethod.OUTBOX_ACK)) {
                finish("Partial / acknowledgement unavailable")
                return
            }
            val retained = repository.snapshot().copies.filter { it.namespace == owner }
            val at = requireNotNull(now()).serverMs
            val expired = retained.filter {
                it.item.expiresAtMs <= at &&
                    it.item.deliveryId in delivery().acknowledgementsOwed
            }.map { it.item.deliveryId }.toSet()
            if (expired.isNotEmpty()) {
                if (!repository.confirmCopies(owner, expired.map { it.value }) ||
                    !adopt(
                        requireNotNull(
                            runtime
                        ).withDelivery(delivery().retireAcknowledgements(expired))
                    )
                ) {
                    finish("Partial / expiry unsaved")
                    return
                }
                if (delivery().acknowledgementsOwed.isEmpty()) {
                    next()
                    return
                }
            }
            val plan = delivery().planAck(requireNotNull(transport).correlation())
            val planned =
                (plan.result as? OutboxPlanOutcome.Planned)?.plan
                    ?: run {
                        finish("Partial / acknowledgement pending")
                        return
                    }
            val confirmation = repository.snapshot()
            val profile = confirmation.profiles.single { it.namespace == owner }
            val bodies = confirmation.copies.filter { it.namespace == owner }
            if (planned.ref.ids.any { id ->
                    bodies.none {
                        it.item.deliveryId == id &&
                            !inboxRemovalOwed(owner, it.canonicalConversation) &&
                            (
                                !it.existing || profile.existingTasks &&
                                    profile.existingAvailable &&
                                    supports(RelayMethod.EXISTING_TASKS_LIST) &&
                                    confirmation.unavailable.none { u ->
                                        u.namespace == owner &&
                                            u.conversation == it.conversation && !u.history
                                    }
                                )
                    }
                } ||
                !repository.confirmCopies(owner, planned.ref.ids.map { it.value }) || unsaved
            ) {
                finish("Partial / payload copy required")
                return
            }
            //  Confirm the complete aggregate again under THIS lease before the actual transport
            // call.
            if (!tryCommit(requireNotNull(runtime)) ||
                !current()
            ) {
                finish("Partial / acknowledgement pending")
                return
            }
            transient(plan.delivery)
            if (!budget()) return
            request(planned.call, planned.ref.correlation, ::ackPermit, ::permit) { result ->
                if (!currentLifetime()) return@request
                val error =
                    ((result as? RelayCallResult.Outcome)?.outcome as? RelayOutcome.Failed)?.error
                if (withdrawAuthority(error)) return@request
                if (!current()) {
                    finish("Partial / fresh authority required")
                    return@request
                }
                val step = (result as? RelayCallResult.Outcome)?.let {
                    delivery().onAckReply(planned.ref, it.outcome)
                } ?: delivery().onAckTimeout(planned.ref)
                if (!commit(step.delivery)) {
                    finish("Partial / acknowledgement pending")
                    return@request
                }
                if (step.result is AckOutcome.Confirmed) {
                    ack(next)
                } else {
                    finish("Partial / acknowledgement pending")
                }
            }
        }
        fun outbox() {
            if (!supports(RelayMethod.OUTBOX_READ)) {
                tasks()
                return
            }
            if (++pages > 24 || !budget()) {
                finish("Partial / page limit")
                return
            }
            val plan = delivery().planOutboxRead(requireNotNull(transport).correlation(), 50)
            val planned =
                (plan.result as? OutboxPlanOutcome.Planned)?.plan
                    ?: run {
                        finish("Partial / outbox pending")
                        return
                    }
            transient(plan.delivery)
            request(planned.call, planned.ref.correlation, ::permit) { result ->
                if (!currentLifetime()) return@request
                val outcome = (result as? RelayCallResult.Outcome)?.outcome
                if (withdrawAuthority((outcome as? RelayOutcome.Failed)?.error)) return@request
                if (!current()) {
                    finish("Partial / fresh authority required")
                    return@request
                }
                if (outcome == null) {
                    finish("Partial / outbox unavailable")
                    return@request
                }
                fun stage(): Boolean {
                    val staged = delivery().stageOutbox(planned.ref, outcome)
                    transient(staged.delivery)
                    when (val value = staged.result) {
                        is OutboxStageOutcome.Backpressure -> return false
                        is OutboxStageOutcome.Staged -> {
                            val batch = value.batch
                            val all = batch.items + batch.duplicates
                            if (all.any {
                                    inboxRemovalOwed(
                                        owner,
                                        (it as? OutboxItem.Change)?.conversationId?.value
                                    )
                                }
                            ) {
                                finish("Content removal pending")
                                return true
                            }
                            val accepted = try {
                                repository.accept(
                                    owner,
                                    all,
                                    localMillis(),
                                    requireNotNull(now()).serverMs
                                )
                            } catch (
                                _: InboxFailure
                            ) {
                                finish("Inbox storage unavailable")
                                return true
                            }
                            if (!current()) return true
                            val committed = delivery().commitOutbox(
                                batch.batchId,
                                requireNotNull(now())
                            )
                            if (committed.result !is OutboxCommit.Committed ||
                                !commit(committed.delivery)
                            ) {
                                finish("Partial / delivery unsaved")
                                return true
                            }
                            runCatching { inboxAlerts(accepted.filter { it.alert }) }
                            ack { if (all.size == 50) outbox() else tasks() }
                            return true
                        }
                        else -> {
                            finish("Partial / outbox unavailable")
                            return true
                        }
                    }
                }
                if (!stage() && current()) {
                    //  The held immutable answer belongs only to this serial lifetime. No new read
                    // or no-op commit.
                    val pruned = delivery().retireOlderThan(
                        requireNotNull(now()).serverMs - 7 * 86_400_000L
                    )
                    if (pruned.result > 0 &&
                        adopt(requireNotNull(runtime).withDelivery(pruned.delivery))
                    ) {
                        if (!stage()) finish("Partial / outbox capacity")
                    } else {
                        finish("Partial / outbox capacity")
                    }
                }
            }
        }
        fun tasks(cursor: String? = null) {
            if (!supports(RelayMethod.TASKS_LIST)) {
                incomplete = true
                conversations()
                return
            }
            if (cursor == null) {
                pages = 0
                cursors.clear()
            }
            if (++pages > 24 || (cursor != null && !cursors.add(cursor))) {
                finish("Partial / task listing incomplete")
                return
            }
            read(RelayCalls.tasks(cursor, 50)) { page ->
                if (page.items.any { removalOwed(owner, it.conversationId.value) }) {
                    finish("Content removal pending")
                    return@read
                }
                repository.project(owner, relay = page.items, at = localMillis())
                if (page.nextCursor != null) {
                    tasks(page.nextCursor)
                } else {
                    conversations()
                }
            }
        }
        val relayConversations =
            ArrayList<ConversationSummary>()
        val existingConversations = ArrayList<ConversationId>()
        var incomplete = false
        fun chain(cursor: String?): Boolean {
            if (cursor == null) {
                pages = 0
                cursors.clear()
            }
            if (++pages > 24 || (cursor != null && !cursors.add(cursor))) {
                finish("Partial / listing incomplete")
                return false
            }
            return true
        }
        fun conversations(cursor: String? = null) {
            if (!supports(RelayMethod.CONVERSATIONS_LIST) || !chain(cursor)) {
                finish("Partial / conversations unavailable")
                return
            }
            read(RelayCalls.conversations(cursor, 50)) { page ->
                if ((page.unavailableCount ?: 0) > 0) incomplete = true
                if (relayConversations.size + page.items.size > 64) {
                    finish("Partial / conversation capacity")
                    return@read
                }
                relayConversations.addAll(page.items)
                if (page.nextCursor != null) {
                    conversations(page.nextCursor)
                } else {
                    relayHistory(0)
                }
            }
        }
        fun relayHistory(index: Int) {
            val c = relayConversations.getOrNull(index)
            if (c == null) {
                existingList()
                return
            }
            if (!supports(RelayMethod.HISTORY_PAGE)) {
                finish("Partial / history unavailable")
                return
            }
            history(c.conversationId, false) { relayHistory(index + 1) }
        }
        fun history(id: ConversationId, existing: Boolean, then: () -> Unit) {
            if (removalOwed(owner, id.value)) {
                finish("Content removal pending")
                return
            }
            val scope = HistoryScope(
                ConversationScope(enrollment, id),
                if (existing) {
                    HistorySource.EXISTING
                } else {
                    HistorySource.RELAY
                }
            )
            var assembly = HistoryAssembly.begin(scope)
            var count = 0
            fun page() {
                if (++count > 24) {
                    finish("Partial / history page limit")
                    return
                }
                val planned = assembly.planPage(50)
                val plan = (planned.result as? HistoryPlanOutcome.Planned)?.plan
                    ?: run {
                        finish("Partial / history capacity")
                        return
                    }
                assembly = planned.assembly
                read(plan.call, if (existing) id.value else null) { value ->
                    val accepted = assembly.accept(plan.request, value)
                    assembly = accepted.assembly
                    val result = accepted.result as? PageOutcome.Accepted
                        ?: run {
                            finish("Partial / history incomplete")
                            return@read
                        }
                    if (result.listingComplete) {
                        if (result.refusedInListing !=
                            0
                        ) {
                            finish("Partial / history too large")
                            return@read
                        }
                        repository.history(
                            owner,
                            id.value,
                            existing,
                            assembly.stagedMessages.map {
                                RetainedMessage(
                                    it.messageId.value,
                                    if (it.role ==
                                        HistoryRole.USER
                                    ) {
                                        RetainedRole.USER
                                    } else {
                                        RetainedRole.ASSISTANT
                                    },
                                    it.text,
                                    it.createdAtMs
                                )
                            },
                            localMillis()
                        )
                        if (existing) repository.sourceUnavailable(owner, id.value, true, false)
                        then()
                    } else {
                        page()
                    }
                }
            }
            page()
        }
        fun existingList(cursor: String? = null) {
            val p = repository.snapshot().profiles.single { it.namespace == owner }
            if (!p.existingTasks && !p.existingHistory) {
                complete()
                return
            }
            if (cursor == null) {
                val snapshot = repository.snapshot()
                val known =
                    snapshot.copies.filter {
                        it.namespace == owner && it.existing
                    }.mapNotNull { it.canonicalConversation } +
                        snapshot.tasks.filter {
                            it.namespace == owner && it.existing != null
                        }.map { it.conversation }
                for (id in known.distinct()) {
                    if (existingConversations.size >=
                        64
                    ) {
                        finish("Partial / existing source capacity")
                        return
                    }
                    existingConversations.add(requireNotNull(ConversationId.of(id)))
                }
            }
            // Existing task-purpose access is independent of the history/list grant.
            if (!p.existingHistory) {
                existing(0)
                return
            }
            if (!supports(RelayMethod.EXISTING_LIST) || !chain(cursor)) {
                finish("Partial / existing source unavailable")
                return
            }
            read(RelayCalls.existingList(cursor, 50)) { page ->
                if (existingConversations.size + page.items.size > 64) {
                    finish("Partial / existing conversation capacity")
                    return@read
                }
                for (item in page.items) {
                    if (item.conversationId !in existingConversations) {
                        existingConversations.add(item.conversationId)
                    }
                }
                if (page.nextCursor != null) existingList(page.nextCursor) else existing(0)
            }
        }
        fun existing(index: Int) {
            val c = existingConversations.getOrNull(index) ?: run {
                complete()
                return
            }
            val p = repository.snapshot().profiles.single { it.namespace == owner }
            fun afterTasks() {
                if (p.existingHistory) {
                    if (!supports(RelayMethod.EXISTING_HISTORY_PAGE)) {
                        finish("Partial / existing history unavailable")
                        return
                    }
                    history(c, true) { existing(index + 1) }
                } else {
                    existing(index + 1)
                }
            }
            if (!p.existingTasks) {
                afterTasks()
                return
            }
            if (!supports(RelayMethod.EXISTING_TASKS_LIST)) {
                finish("Partial / existing tasks unavailable")
                return
            }
            fun taskPage(cursor: String? = null) {
                if (!chain(cursor)) return
                read(RelayCalls.existingTasks(c, cursor, 50), c.value) { page ->
                    if (page.items.any { it.conversationId != c }) {
                        finish("Partial / existing scope mismatch")
                        return@read
                    }
                    repository.sourceUnavailable(owner, c.value, false, false)
                    repository.project(owner, existing = page.items, at = localMillis())
                    if (page.nextCursor != null) taskPage(page.nextCursor) else afterTasks()
                }
            }
            taskPage()
        }
        fun complete() {
            if (!current()) return
            if (repository.snapshot().unavailable.any { it.namespace == owner }) incomplete = true
            if (refreshBeforeAck) {
                refreshBeforeAck = false
                if (incomplete) {
                    finish("Partial / source refresh incomplete")
                    return
                }
                ack { outbox() }
                return
            }
            // Clearing debt is a proposal: failed persistence retains the old runtime and owed work.
            if (!incomplete &&
                (
                    !adopt(
                        requireNotNull(runtime).withDelivery(
                            delivery().refreshed(null, RefreshKind.entries.toSet()).delivery
                        )
                    ) || !current()
                    )
            ) {
                finish("Partial / refresh unsaved")
                return
            }
            repository.sweep(owner, localMillis(), delivery().acknowledgementsOwed.map { it.value })
            if (!incomplete && delivery().refreshOwed.isEmpty() && !unsaved && current()) {
                repository.synced(owner, localMillis())
            }
            finish(
                if (incomplete ||
                    delivery().refreshOwed.isNotEmpty()
                ) {
                    "Partial / source refresh incomplete"
                } else {
                    "Current"
                }
            )
        }
    }

    // -- request plumbing --------------------------------------------------------------------------------------

    private fun <R : Any> request(
        call: RelayCall<R>,
        correlation: CorrelationId? = null,
        permit: () -> Boolean = { true },
        callbackPermit: () -> Boolean = permit,
        done: (RelayCallResult<R>) -> Unit
    ) {
        val link = transport
        if (link == null || channel !is RelayChannelState.Ready ||
            admittedWriter != writerEpoch()
        ) {
            done(RelayCallResult.Refused(RelayRefusal.NOT_READY))
            return
        }
        val expected = serial
        val epoch = writerEpoch()
        val id = correlation ?: link.correlation()
        link.callGuarded(id, call, timing.requestTimeoutMs, permit) { result ->
            post {
                if (expected == serial && epoch == writerEpoch() &&
                    callbackPermit()
                ) {
                    done(result)
                }
            }
        }
    }

    private fun canList(): Boolean = isReady() && supports(RelayMethod.CONVERSATIONS_LIST)

    private fun isReady(): Boolean = channel is RelayChannelState.Ready && runtime != null &&
        record()?.let {
            it.enabled && it.standing == Standing.ACTIVE &&
                HistoryNamespace.from(it)?.let { owner ->
                    !removalOwed(owner, null, wholeScopeOnly = true)
                } !=
                false
        } ==
        true

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
        endSession(attachAgain = false)
        token = null
        transcript.clear()
        conversations.clear()
        tasks.clear()
        retained = emptyList()
        retainedSelected = null
        cacheReview = null
        val record = record() ?: return
        val rt = runtime ?: return
        val owner = HistoryNamespace.from(record)
        if (owner != null) stoppedRemovalConfirmed.remove(owner)
        val stoppedSection = rt.section.with(record.copy(standing = Standing.STOPPED))
        // STOPPED itself is durable scope-wide REVOKED authority if the ordinary64 list is full.
        val section = if (owner != null && stoppedSection.evictions.size < 64) {
            stoppedSection.owing(HistoryEviction(owner, null))
        } else {
            stoppedSection
        }
        advance(rt.copy(section = section))
        notice = RelayNotice.PERMISSION_DENIED
        evictOwed()
        publish()
    }

    /** Confirm obligation durability, remove only its authorized target, then confirm final aggregate clearance. */
    private fun evictOwed() {
        var rt = runtime ?: return
        // A previous exact-target construction refusal may become representable after other obligations complete.
        val held = undurableDeletion
        if (held != null) {
            val section = boundedSection { rt.section.owing(held) }
            if (section != null) {
                val next = rt.copy(section = section)
                advance(next)
                rt = requireNotNull(runtime)
                undurableDeletion = null
            }
        }
        if (unsaved && !tryCommit(rt)) return
        unsaved = false
        // Destructive independent authority first. A stale cache-only review can confirm absence only.
        val removals = durableRemovals(rt.section).sortedBy { it.kind == EvictionKind.CACHE_ONLY }
        for (obligation in removals) {
            rt = requireNotNull(runtime)
            if (unsaved && !tryCommit(rt)) continue
            unsaved = false
            try {
                if (obligation.kind == EvictionKind.CACHE_ONLY) {
                    if (retainedHistory?.absent(obligation.namespace, obligation.conversationId) ==
                        false
                    ) {
                        continue
                    }
                } else {
                    retainedHistory?.evict(obligation.namespace, obligation.conversationId)
                    inbox?.evict(obligation.namespace, obligation.conversationId)
                }
            } catch (_: InboxFailure) {
                inboxStatus = "Content removal pending"
                continue
            } catch (failure: HistoryFailure) {
                retainedProblem = failure.problem
                continue
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
            if (obligation.kind ==
                EvictionKind.REVOKED
            ) {
                rt.delivery(owner)?.evictScopeContent()?.let {
                    next =
                        next.withDelivery(it.delivery)
                }
            }
            if (!adopt(next)) {
                unsaved = true
                continue
            }
            if (obligation.kind == EvictionKind.REVOKED &&
                obligation.conversationId == null
            ) {
                stoppedRemovalConfirmed.add(obligation.namespace)
            }
        }
        loadRetained()
    }

    private fun requireReview() {
        transport?.invalidatePending()
        listOperation++
        listCursor = null
        listing = false
        listRetryTimer?.cancel()
        listRetryTimer = null
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
        evictOwed()
        if (!selectionReplaceable()) return publish()
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
        if (record()?.standing != Standing.STOPPED) return publish()
        evictOwed()
        if (!selectionReplaceable()) return publish()
        val record = record() ?: return
        val owner = HistoryNamespace.from(record)
        if (unsaved ||
            (owner != null && removalOwed(owner, null, wholeScopeOnly = true))
        ) {
            return publish()
        }
        val rt = runtime ?: return
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

    private fun finishListPartial() {
        listOperation++
        listRetryTimer?.cancel()
        listRetryTimer = null
        listCursor = null
        listing = false
        notice = RelayNotice.HISTORY_INCOMPLETE
    }

    private fun retryList(cursor: String?, attempt: Int, operation: Long) {
        if (attempt >= timing.maxRetries || listRequests >= timing.maxHistoryRequests) {
            finishListPartial()
        } else {
            notice = RelayNotice.BUSY_TRY_AGAIN
            listRetryTimer = later(backoff(attempt)) { listPage(cursor, attempt + 1, operation) }
        }
    }

    private fun listPage(cursor: String?, attempt: Int, operation: Long = listOperation) {
        if (operation != listOperation || !canList()) return
        if (attempt > timing.maxRetries || listPages >= timing.maxHistoryPages ||
            listRequests >= timing.maxHistoryRequests
        ) {
            finishListPartial()
            publish()
            return
        }
        listRequests++
        listing = true
        val call = try {
            RelayCalls.conversations(cursor, timing.listPageItems, onlineQuery, bookmarkFilter)
        } catch (
            _: IllegalArgumentException
        ) {
            finishListPartial()
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
                        if (outcome.value.items.isEmpty() &&
                            listCursor != null
                        ) {
                            listPage(listCursor, 0, operation)
                        }
                    }
                    is RelayOutcome.Failed -> {
                        if (outcome.error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE ||
                            outcome.error.action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW
                        ) {
                            commonError(outcome.error, null, attempt)
                        } else if (outcome.error.action == RelayErrorAction.REATTACH_AND_REFRESH &&
                            outcome.error.needsFreshView && cursor != null
                        ) {
                            if (attempt >= timing.maxRetries ||
                                listRestarts >= MAX_HISTORY_RESTARTS
                            ) {
                                finishListPartial()
                            } else {
                                listRestarts++
                                listCursors.clear()
                                listPages = 0
                                listPage(null, attempt + 1, operation)
                            }
                        } else if (outcome.error.action ==
                            RelayErrorAction.RETRY_SAME_REQUEST
                        ) {
                            retryList(cursor, attempt, operation)
                        } else if (!commonError(outcome.error, null, attempt)) {
                            notice =
                                RelayNotice.SERVER_ERROR
                        }
                    }
                    is RelayOutcome.Uncorrelated, is RelayOutcome.Invalid -> {
                        finishListPartial()
                    }
                }
                RelayCallResult.TimedOut -> retryList(cursor, attempt, operation)
                RelayCallResult.Closed -> Unit
                is RelayCallResult.Refused -> if (busyLocally(
                        result
                    )
                ) {
                    retryList(cursor, attempt, operation)
                } else {
                    finishListPartial()
                }
            }
            publish()
        }
        publish()
    }

    private fun onListed(page: ConversationPage, first: Boolean) {
        if (first) conversations.clear()
        for (item in page.items) {
            if (blocked(item.conversationId.value)) continue
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
                    listRequests < timing.maxHistoryRequests &&
                    listCursors.add(it)
            }
        if (next != null && listCursor == null) notice = RelayNotice.HISTORY_INCOMPLETE
        if (notice == RelayNotice.BUSY_TRY_AGAIN) notice = RelayNotice.NONE
    }

    // -- create ------------------------------------------------------------------------------------------------

    private fun createConversation(title: String?) {
        if (!selectionReplaceable()) return publish()
        if (undurableDeletion !=
            null
        ) {
            notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
            return publish()
        }
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
        if (!selectionReplaceable()) return publish()
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
        if (!selectionReplaceable()) return publish()
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
        if (!selectionReplaceable()) return publish()
        val record = record() ?: return
        if (!isReady() || !supports(RelayMethod.CONVERSATION_ATTACH)) return publish()
        if (blocked(id.value) || undurableDeletion != null) {
            notice =
                if (undurableDeletion !=
                    null
                ) {
                    RelayNotice.STORAGE_FULL_PENDING_ITEMS
                } else {
                    RelayNotice.HISTORY_INCOMPLETE
                }
            return publish()
        }
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
        if (!selectionReplaceable()) return publish()
        if (record()?.selectedConversationId != id || !isReady() || blocked(id.value)) return
        // Legacy/restored selections must first reserve their fixed format4 flag on disk. New selection/create already did.
        val target = selectionTarget(baseSection()) ?: return
        if ((selectionReservation != target || unsaved) &&
            !commitSection(baseSection())
        ) {
            return publish()
        }
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
        if (mine != flow) return publish()
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
        cacheReview = null
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
        if (record.selectedConversationId != null && !record.selectionDeleted) {
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
            blocked(bound.conversationId.value) ||
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
        if (!isReady() || blocked(key.conversation.conversationId.value)) return publish()
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
        if (blocked(bound.conversationId.value)) return then()
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
                    if (namespace != null &&
                        removalOwed(namespace, current.scope.conversation.conversationId.value)
                    ) {
                        return@request then()
                    }
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
                                    HistoryRole.USER
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

    private fun discardDeferredEvents() {
        val held = deferredEvents ?: return
        deferredEvents = null
        val rt = runtime ?: return
        val delivery = rt.delivery(held.bound.scope) ?: return
        runtime = rt.withDelivery(delivery.abortRead(held.ref).delivery)
    }

    private fun currentDeferred(held: DeferredEvents): Boolean =
        held.session == serial && held.flow == flow && token === held.bound &&
            held.bound.scope == scope() && isReady() && supports(RelayMethod.EVENTS_READ) &&
            runtime?.authority?.isCurrent(held.bound) == true

    /** No new event reads or no-op commits while full. Existing Info and real pending-turn work still have their owners. */
    private fun capacityPollDelay(): Long? {
        val held = deferredEvents ?: return null
        if (!currentDeferred(held)) {
            discardDeferredEvents()
            return null
        }
        val retry = maxOf(timing.pollIdleMs, timing.backoffMaxMs, 1L)
        val at = now() ?: return retry
        val oldest = runtime?.delivery(held.bound.scope)?.oldestSeenAtMs ?: return retry
        val roomAt = if (oldest > Long.MAX_VALUE - StateLimits.ID_RETENTION_MS - 1) {
            Long.MAX_VALUE
        } else {
            oldest + StateLimits.ID_RETENTION_MS + 1
        }
        val roomDelay = if (roomAt > at.serverMs) roomAt - at.serverMs else retry
        val refreshAt = info?.expiresAtMs?.minus(timing.infoRefreshLeadMs)
        val maintenance = if (!infoRefreshing && refreshAt != null) {
            if (refreshAt > at.serverMs) refreshAt - at.serverMs else retry
        } else {
            roomDelay
        }
        return minOf(roomDelay, maintenance).coerceAtLeast(timing.pollIdleMs)
    }

    private fun schedulePoll() {
        pollTimer?.cancel()
        val bound = token ?: return
        val active = runtime?.ledger?.entriesFor(bound.scope)?.any { awaiting(it) } == true
        val delay = if (active) timing.pollActiveMs else capacityPollDelay() ?: timing.pollIdleMs
        pollTimer = later(delay) { pollOnce() }
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
        if (rt == null || link == null || delivery == null || mine != flow || token !== bound ||
            bound.scope != scope() || !isReady() || !rt.authority.isCurrent(bound) ||
            !supports(RelayMethod.EVENTS_READ)
        ) {
            pollInFlight = false
            return
        }
        val held = deferredEvents
        if (held != null) {
            pollInFlight = false
            retryDeferredEvents(held)
            publish()
            if (mine == flow && token === bound) schedulePoll()
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
            if (mine != flow || token !== bound || deferredEvents?.ref === plan.ref) return@request
            val current = runtime ?: return@request
            val now = current.delivery(bound.scope) ?: return@request
            when (result) {
                is RelayCallResult.Outcome -> {
                    val answer = result.outcome
                    val staged = now.stageEvents(current.authority, plan.ref, answer)
                    runtime = current.withDelivery(staged.delivery)
                    if (staged.result is StageOutcome.Backpressure &&
                        answer is RelayOutcome.Success
                    ) {
                        val deferred = DeferredEvents(serial, mine, bound, plan.ref, answer.value)
                        deferredEvents = deferred
                        retryDeferredEvents(deferred)
                    } else {
                        onStaged(staged.result, staged.effects, bound)
                    }
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
            is StageOutcome.Backpressure -> notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
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

    private fun retryDeferredEvents(held: DeferredEvents) {
        if (!currentDeferred(held)) {
            discardDeferredEvents()
            return
        }
        val rt = runtime ?: return
        val at = now() ?: return
        val delivery = rt.delivery(held.bound.scope) ?: return
        val pruned = delivery.retireOlderThan(at.serverMs - StateLimits.ID_RETENTION_MS)
        if (pruned.result == 0) {
            notice = RelayNotice.STORAGE_FULL_PENDING_ITEMS
            return
        }
        // This is a proposal, not a newly observed server fact: a refused prune keeps the old runtime and answer.
        if (!adopt(rt.withDelivery(pruned.delivery))) return
        val current = runtime ?: return
        val staged = pruned.delivery.stageEvents(
            current.authority,
            held.ref,
            RelayOutcome.Success(held.page)
        )
        if (staged.result !is StageOutcome.Backpressure) deferredEvents = null
        runtime = current.withDelivery(staged.delivery)
        if (staged.result is StageOutcome.Staged &&
            notice == RelayNotice.STORAGE_FULL_PENDING_ITEMS
        ) {
            notice = RelayNotice.NONE
        }
        onStaged(staged.result, staged.effects, held.bound)
    }

    private fun applyBatch(staged: StageOutcome.Staged, bound: BindingToken) {
        val batch = staged.batch
        if (token !== bound || bound.scope != scope() || !isReady() ||
            runtime?.authority?.isCurrent(bound) != true
        ) {
            return
        }
        // Fully validated page-level evidence, including a seen terminal identity. Never allocate dedup/ACK facts for it.
        if (batch.conversationDeleted) {
            conversationDeleted()
            return
        }
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
        if (deleted) {
            conversationDeleted()
        } else {
            advance(rt.withDelivery(committed.delivery))
        }
        if (!deleted && again) {
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

    private fun conversationDeleted(
        owner: HistoryNamespace? = namespace(),
        target: ConversationId? = record()?.selectedConversationId
    ) {
        if (owner == null || target == null) return
        val rt = runtime ?: return
        val record = record() ?: return
        val selected = owner == namespace() && record.selectedConversationId == target
        if (selected) {
            transport?.invalidatePending()
            pollTimer?.cancel()
            flow++
            token = null
            attaching = false
            attachedOnce = false
            transcript.clear()
            tasks.clear()
            selectedSummary = null
        }
        conversations.removeAll { it.id == target.value && owner == namespace() }
        val obligation = HistoryEviction(owner, target.value, EvictionKind.DELETED)
        val section = if (selected) {
            // The exact selected identity was committed before attach. Flipping its reserved byte needs no new slot/bytes.
            rt.section.with(record.copy(selectionDeleted = true))
        } else {
            boundedSection { rt.section.owing(obligation) }
        }
        if (section != null) {
            advance(rt.copy(section = section))
        } else if (rt.section.intents.any {
                it.namespace == owner && it.conversationId == target &&
                    it.status == MetadataStatus.RETIRED
            }
        ) {
            // An unselected metadata target is already represented by its immutable RETIRED intent.
            advance(rt)
        } else {
            // Defensive only: current bound/attach deletions use their reserved identity; metadata has its durable intent.
            undurableDeletion = obligation
        }
        if (retainedSelected?.id == target.value && owner == namespace()) retainedSelected = null
        retained = retained.filterNot { it.id == target.value && owner == namespace() }
        evictOwed()
        if (section != null) notice = RelayNotice.CONVERSATION_DELETED
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
            val contentBlocked = (durableRemovals() + listOfNotNull(undurableDeletion)).any {
                it.namespace.endpointKey == owner.relay.endpointKey &&
                    it.namespace.profileId == owner.relay.profileId.value &&
                    it.namespace.enrollmentLabel == owner.enrollmentKey &&
                    it.namespace.reviewEpoch == owner.reviewEpoch &&
                    (
                        it.conversationId == null ||
                            it.conversationId == entry.key.conversation.conversationId.value
                        )
            }
            val idle =
                !contentBlocked && entry.phase != TurnPhase.SUBMITTING &&
                    entry.phase != TurnPhase.LOOKING_UP
            val canCheck = mine && inConversation && idle && unresolved(entry)
            val canResend =
                !contentBlocked && mine && inConversation &&
                    entry.phase == TurnPhase.ABSENT_SO_FAR &&
                    entry.hold == null && entry.text != null && at != null &&
                    !entry.deadlinePassed(at)
            views.add(
                TurnView(
                    entry.key.requestId.value,
                    entry.status,
                    if (contentBlocked) "" else entry.text?.take(PREVIEW_CHARS).orEmpty(),
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
        if (admittedWriter != writerEpoch()) return
        val record = record()
        val enabled = record?.enabled == true
        val problem = (stored as? Stored.Problem)?.problem
        val owner = namespace()
        if (owner != null) {
            retained = retained.filterNot { removalOwed(owner, it.id) }
            if (retainedSelected?.let { removalOwed(owner, it.id) } == true) retainedSelected = null
        }
        val selectedId = record?.selectedConversationId?.takeUnless { blocked(it.value) }
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
            metadataIntents = baseSection().intents.filter { it.namespace == namespace() } +
                listOfNotNull(
                    retiredMetadataOutcome?.takeIf { outcome ->
                        outcome.namespace ==
                            namespace() &&
                            baseSection().intents.none {
                                it.namespace == outcome.namespace &&
                                    it.requestId == outcome.requestId
                            }
                    }
                ),
            canEditMetadata =
            isReady() && supports(RelayMethod.CONVERSATION_UPDATE) && !unsaved &&
                metadataSending == null,
            retained = retained,
            retainedSelected = retainedSelected,
            retainedProblem = retainedProblem,
            retentionDays = retentionDays,
            cacheReview = cacheReview,
            cacheReviewConfirmation = cacheReviewConfirmation,
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
            canSend =
            retainedSelected == null && phase == RelayPhase.SELECTED && token != null &&
                selectedId != null,
            micAvailable = false,
            wake = wakeView()
        )
        inboxUpdates(filteredInbox(inbox?.peek()), inboxStatus)
        listener(ui)
    }

    private fun evictionPending(phase: RelayPhase, scope: EnrollmentScope?): Boolean {
        if ((durableRemovals() + listOfNotNull(undurableDeletion)).any {
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
