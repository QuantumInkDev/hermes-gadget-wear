package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.HistoryMessage
import dev.quantumink.hermesgadget.protocol.relay.HistoryPage
import dev.quantumink.hermesgadget.protocol.relay.HistoryRole
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import dev.quantumink.hermesgadget.protocol.relay.RelayCall
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RelayError
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorAction
import dev.quantumink.hermesgadget.protocol.relay.RelayErrorCode
import dev.quantumink.hermesgadget.protocol.relay.RelayJson
import dev.quantumink.hermesgadget.protocol.relay.validCursor
import java.util.Collections

/** Which history a listing reads: the Relay conversation's own, or an existing (read-only) session. */
enum class HistorySource { RELAY, EXISTING }

/** One history stream under its enrollment. Fragments of independent scopes are never mixed. */
data class HistoryScope(
    val conversation: ConversationScope,
    val source: HistorySource = HistorySource.RELAY
) {
    override fun toString(): String =
        "HistoryScope($source, ${conversation.conversationId.value}, redacted)"
}

/** How a listing began. A new beginning after an invalidation is EXPLICIT: it is never silently claimed. */
enum class ListingStart {
    /** The first listing of this stream: no cursor, from the canonical beginning. */
    BEGINNING,

    /** A deliberate restart from the beginning after the previous listing was abandoned ([ListingProvenance.after]). */
    REBUILD,

    /** Resumed from a cursor saved by an earlier process; the server decides whether that cursor still holds. */
    RESUMED_FROM_SAVED_CURSOR
}

enum class ListingStatus { ACTIVE, COMPLETE, INVALIDATED }

/** Why a listing's cursor chain can no longer be trusted. Always followed by an explicit rebuild, never a guess. */
enum class HistoryInvalidation {
    /** The server's cursor expired or its snapshot changed (`snapshot_required`). */
    SNAPSHOT_REQUIRED,

    /** The same fragment arrived twice with different text. */
    MISMATCHED_DUPLICATE,

    /** A fragment disagrees with its message on role, timestamp or segment count. */
    MISMATCHED_METADATA,

    /** A segment index was skipped. */
    MISSING_SEGMENT,

    /** Another message began before the open one was complete. */
    INTERLEAVED,

    /** A continuation fragment of a message that never began (or began in an earlier listing). */
    UNEXPECTED_FRAGMENT,

    /** The listing ended while a message still lacked segments. */
    INCOMPLETE_AT_END,

    /** The server answered a cursor with itself or with one it already issued. */
    NO_PROGRESS,

    /** Too many cursored pages in a row delivered nothing new. */
    STALLED,

    /** More messages than the local quota allowed were refused for size. */
    TOO_MANY_REFUSED,

    /** Permission was withdrawn or the profile changed for the scope. */
    SCOPE_REVOKED,

    /** The server refused the page in a way a retry cannot cure (e.g. `projection_too_large`). */
    SERVER_REFUSED,

    /** A gap event: canonical content may have changed since the listing began. */
    GAP_EVENT,

    /** The consumer abandoned the listing. */
    CONSUMER,

    /** A restored listing had committed content but no cursor to continue from: rebuild explicitly. */
    RESTORED_WITHOUT_CURSOR
}

/** Why a message was refused locally. A refused message is neither complete nor truncated: it is absent. */
enum class RefusalReason {
    /** Its declared `segment_count` exceeds [StateLimits.MAX_MESSAGE_SEGMENTS]; nothing was allocated for it. */
    TOO_MANY_SEGMENTS,

    /** Its text would exceed [StateLimits.MAX_MESSAGE_UTF8_BYTES]. */
    TOO_MANY_BYTES
}

class RefusedMessage internal constructor(
    val messageId: MessageId,
    val reason: RefusalReason,
    val declaredSegments: Int
) {
    override fun equals(other: Any?): Boolean =
        other is RefusedMessage && other.messageId == messageId &&
            other.reason == reason && other.declaredSegments == declaredSegments

    override fun hashCode(): Int = messageId.hashCode() * 31 + reason.hashCode()

    override fun toString(): String =
        "RefusedMessage(${messageId.value}, $reason, segments=$declaredSegments)"
}

/** The local resource that stopped a page from being consumed (policy). The page was NOT consumed. */
enum class HistoryLimit { UNCOMMITTED_MESSAGES, UNCOMMITTED_BYTES, KNOWN_MESSAGES }

/** What a finished (or unfinished) listing may honestly be called. */
enum class ListingCompleteness { INCOMPLETE, COMPLETE, COMPLETE_WITH_LOCAL_REFUSALS }

enum class HistoryIgnore { STALE_REQUEST, NOT_ACTIVE }

enum class HistoryPlanRefusal { NOT_ACTIVE, REQUEST_OUTSTANDING, INVALID_LIMIT }

enum class CommitRefusal { NOTHING_STAGED, BEYOND_STAGED, STALE }

/** The provenance of a listing: generation, how it began and how (if at all) it ended. */
class ListingProvenance internal constructor(
    val generation: Long,
    val start: ListingStart,
    /** For [ListingStart.REBUILD]: why the previous listing was abandoned. */
    val after: HistoryInvalidation?,
    val status: ListingStatus,
    val invalidation: HistoryInvalidation?
) {
    override fun toString(): String =
        "ListingProvenance(generation=$generation, $start, $status, after=$after, invalidation=$invalidation)"
}

/** One canonical message assembled from every one of its segments, in the authenticated page order. */
class AssembledMessage internal constructor(
    /** Position in this listing's authenticated page order, starting at 1. Never derived from ids or timestamps. */
    val ordinal: Long,
    val messageId: MessageId,
    val role: HistoryRole,
    val createdAtMs: Long,
    val text: String,
    val segmentCount: Int
) {
    override fun equals(other: Any?): Boolean = other is AssembledMessage &&
        other.ordinal == ordinal &&
        other.messageId == messageId &&
        other.role == role &&
        other.createdAtMs == createdAtMs &&
        other.text == text &&
        other.segmentCount == segmentCount

    override fun hashCode(): Int = messageId.hashCode() * 31 + ordinal.hashCode()

    override fun toString(): String =
        "AssembledMessage(#$ordinal, ${messageId.value}, segments=$segmentCount, redacted)"
}

/** One page request: only a reply naming exactly this request is applied. The cursor is never printed. */
class PageRequest internal constructor(
    val serial: Long,
    val generation: Long,
    internal val cursor: String?,
    val limit: Int?
) {
    val isFirstPage: Boolean get() = cursor == null

    override fun equals(other: Any?): Boolean = other is PageRequest && other.serial == serial

    override fun hashCode(): Int = serial.hashCode()

    override fun toString(): String =
        "PageRequest(serial=$serial, generation=$generation, redacted)"
}

class PagePlan internal constructor(val request: PageRequest, val call: RelayCall<HistoryPage>) {
    override fun toString(): String = "PagePlan($request)"
}

sealed interface HistoryPlanOutcome {
    class Planned(val plan: PagePlan) : HistoryPlanOutcome {
        override fun toString(): String = "Planned($plan)"
    }

    class Refused(val reason: HistoryPlanRefusal) : HistoryPlanOutcome {
        override fun toString(): String = "Refused($reason)"
    }
}

sealed interface PageOutcome {
    class Accepted(
        /** Messages that became complete in this page, in order. They are STAGED until committed. */
        val emitted: List<AssembledMessage>,
        val newFragments: Int,
        val duplicateFragments: Int,
        /** The listing reached its end (`next_cursor` null) with no message left partly assembled. */
        val listingComplete: Boolean,
        /** Messages refused locally so far in this listing. A listing with refusals is not the whole history. */
        val refusedInListing: Int
    ) : PageOutcome {
        val completeness: ListingCompleteness
            get() = when {
                !listingComplete -> ListingCompleteness.INCOMPLETE
                refusedInListing > 0 -> ListingCompleteness.COMPLETE_WITH_LOCAL_REFUSALS
                else -> ListingCompleteness.COMPLETE
            }

        override fun toString(): String = "Accepted(emitted=${emitted.size}, new=$newFragments, " +
            "duplicates=$duplicateFragments, $completeness)"
    }

    /** The listing can no longer be trusted. Everything staged was discarded; an explicit restart is required. */
    class Invalidated(val reason: HistoryInvalidation, val discardedStaged: Int) : PageOutcome {
        override fun toString(): String = "Invalidated($reason, discarded=$discardedStaged)"
    }

    /** A local limit was reached. The page was NOT consumed and the request is still outstanding: commit, then retry. */
    class Backpressure(val limit: HistoryLimit) : PageOutcome {
        override fun toString(): String = "Backpressure($limit)"
    }

    /** `busy` / `rate_limited` / `temporarily_unavailable`: the same request (same cursor) may be repeated. */
    class RetrySameRequest(val code: RelayErrorCode) : PageOutcome {
        override fun toString(): String = "RetrySameRequest(${code.wire})"
    }

    /** An error that neither invalidates nor is retried automatically; surfaced as is. */
    class Failed(val code: RelayErrorCode) : PageOutcome {
        override fun toString(): String = "Failed(${code.wire})"
    }

    class Ignored(val reason: HistoryIgnore) : PageOutcome {
        override fun toString(): String = "Ignored($reason)"
    }
}

sealed interface HistoryEffect {
    /** Rebuild the history from the beginning (an explicit [HistoryAssembly.restart]); nothing is guessed. */
    data class RebuildRequired(val scope: HistoryScope, val reason: HistoryInvalidation) :
        HistoryEffect

    /** `permission_denied`: evict cached content for the enrollment. */
    data class EvictScopeContent(val enrollment: EnrollmentScope) : HistoryEffect
}

class HistoryStep<out R>(
    val assembly: HistoryAssembly,
    val result: R,
    val effects: List<HistoryEffect> = emptyList()
) {
    override fun toString(): String = "HistoryStep($result, effects=${effects.size})"
}

/**
 * The pure assembly of history fragments into whole messages.
 *
 * WIRE FACTS (the accepted core/SDK source): a plain-text row is cut at NOMINAL 8,192-byte UTF-8 boundaries moved
 * forward to a code point end, so a fragment holds up to 8,195 bytes and a message of any length has a
 * `segment_count` of ceil(bytes / 8192); the SDK bounds one fragment's text at 32,768 bytes and a page at 65,536;
 * fragments of one message are consecutive in canonical order, a message may continue on the next page, a page may be
 * EMPTY and still carry a cursor (hidden rows were consumed: real progress, never "the end"), and a cursor is opaque,
 * HMAC-bound to the stream and expires after 15 minutes (`snapshot_required`).
 *
 * LOCAL POLICY ([StateLimits]): how many segments/bytes of one message, how many completed-but-uncommitted
 * messages/bytes and how many identities are kept. A limit is a typed refusal or backpressure result, never a silent
 * truncation, and a refused message is absent, not "complete". The remaining fragments of a refused message are read in
 * order (bounded: id, metadata, next index; no text) and each advancing index is progress; an out-of-order, missing or
 * mismatched one invalidates the listing. Quota-driven invalidations ([HistoryInvalidation.TOO_MANY_REFUSED]) are
 * permanent for that stream until the policy changes: a rebuild reads the same stream and reaches the same place.
 *
 * Contract with the consumer (not proven here): [AssembledMessage]s are STAGED. The consumer writes them durably, then
 * calls [commit]; only then does the resume point advance. Canonical order is the authenticated page order — nothing
 * here parses a message id or sorts a timestamp.
 */
class HistoryAssembly private constructor(
    val scope: HistoryScope,
    val provenance: ListingProvenance,
    private val expectedCursor: String?,
    private val outstanding: PageRequest?,
    private val nextRequest: Long,
    private val pending: OpenMessage?,
    private val known: Map<MessageId, Known>,
    private val staged: List<AssembledMessage>,
    private val stagedBytes: Long,
    private val boundaries: List<Boundary>,
    private val resume: Resume?,
    private val committedOrdinal: Long,
    private val nextOrdinal: Long,
    private val refused: List<RefusedMessage>,
    private val emptyStreak: Int,
    private val recentCursors: List<String>,
    /** The locally refused message whose later fragments are still due, with the next index expected. */
    private val openRefused: OpenRefused?,
    /** Refused messages whose fragments were all consumed in THIS cursor chain (reset by abort, restore, rebuild). */
    private val readRefused: Set<MessageId>
) {
    val status: ListingStatus get() = provenance.status

    /** Messages assembled and waiting to be committed, in order. */
    val stagedMessages: List<AssembledMessage> get() = Collections.unmodifiableList(staged)

    val committedThrough: Long get() = committedOrdinal

    /** Messages refused locally in this listing (over quota). The listing is then complete only with these absent. */
    val refusedMessages: List<RefusedMessage> get() = Collections.unmodifiableList(refused)

    /** Whether a message is partly assembled (its remaining segments are still due). */
    val hasOpenMessage: Boolean get() = pending != null

    /**
     * Whether a locally refused message is still being read: its remaining fragments are due in order and are
     * consumed without allocating anything for them. Never a resume point while true.
     */
    val hasOpenRefusedMessage: Boolean get() = openRefused != null

    val hasOutstandingRequest: Boolean get() = outstanding != null

    /** Identities kept for duplicate detection. */
    val knownCount: Int get() = known.size

    /** The cursor the next request would carry (null: from the beginning). Opaque; never printed. */
    internal val nextCursor: String? get() = expectedCursor

    /** The saved resume point: null if nothing was committed at a clean boundary. */
    val resumePoint: Resume? get() = resume

    // -- requests -----------------------------------------------------------------------------------------

    fun planPage(limit: Int? = null): HistoryStep<HistoryPlanOutcome> {
        fun refused(reason: HistoryPlanRefusal) =
            HistoryStep(this, HistoryPlanOutcome.Refused(reason))
        if (provenance.status != ListingStatus.ACTIVE) return refused(HistoryPlanRefusal.NOT_ACTIVE)
        if (outstanding != null) return refused(HistoryPlanRefusal.REQUEST_OUTSTANDING)
        val call = try {
            when (scope.source) {
                HistorySource.RELAY ->
                    RelayCalls.history(scope.conversation.conversationId, expectedCursor, limit)
                HistorySource.EXISTING ->
                    RelayCalls.existingHistory(
                        scope.conversation.conversationId,
                        expectedCursor,
                        limit
                    )
            }
        } catch (_: IllegalArgumentException) {
            return refused(HistoryPlanRefusal.INVALID_LIMIT)
        }
        val request = PageRequest(nextRequest, provenance.generation, expectedCursor, limit)
        val next = copy(outstanding = request, nextRequest = Math.addExact(nextRequest, 1L))
        return HistoryStep(next, HistoryPlanOutcome.Planned(PagePlan(request, call)))
    }

    /** The request got no answer in time: it is dropped; the cursor and everything assembled are unchanged. */
    fun onTimeout(request: PageRequest): HistoryStep<PageOutcome> {
        if (outstanding != request) return ignored(HistoryIgnore.STALE_REQUEST)
        return HistoryStep(
            copy(outstanding = null),
            PageOutcome.RetrySameRequest(RelayErrorCode.TEMPORARILY_UNAVAILABLE)
        )
    }

    fun onError(request: PageRequest, error: RelayError): HistoryStep<PageOutcome> {
        if (provenance.status != ListingStatus.ACTIVE) {
            return ignored(HistoryIgnore.NOT_ACTIVE)
        }
        if (outstanding != request) return ignored(HistoryIgnore.STALE_REQUEST)
        val code = error.code
        return when {
            error.action == RelayErrorAction.REATTACH_AND_REFRESH ->
                invalidated(HistoryInvalidation.SNAPSHOT_REQUIRED)
            error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE ||
                error.action == RelayErrorAction.INVALIDATE_PROFILE_AND_REVIEW ->
                invalidated(
                    HistoryInvalidation.SCOPE_REVOKED,
                    if (error.action == RelayErrorAction.STOP_AND_EVICT_SCOPE) {
                        listOf(HistoryEffect.EvictScopeContent(scope.conversation.enrollment))
                    } else {
                        emptyList()
                    }
                )
            code == RelayErrorCode.PROJECTION_TOO_LARGE -> invalidated(
                HistoryInvalidation.SERVER_REFUSED
            )
            error.action == RelayErrorAction.RETRY_SAME_REQUEST ->
                HistoryStep(copy(outstanding = null), PageOutcome.RetrySameRequest(code))
            else -> HistoryStep(copy(outstanding = null), PageOutcome.Failed(code))
        }
    }

    // -- accepting a page ---------------------------------------------------------------------------------

    /**
     * Assembles [page] (the answer to [request]). The page is consumed atomically: either every fragment is applied,
     * or (on backpressure) none is and the request stays outstanding, or the listing is invalidated.
     */
    fun accept(request: PageRequest, page: HistoryPage): HistoryStep<PageOutcome> {
        if (provenance.status != ListingStatus.ACTIVE) {
            return ignored(HistoryIgnore.NOT_ACTIVE)
        }
        if (outstanding != request || request.generation != provenance.generation) {
            return ignored(HistoryIgnore.STALE_REQUEST)
        }
        val work = Work(this)
        for (fragment in page.items) {
            when (val verdict = work.consume(fragment)) {
                is Verdict.Continue -> Unit
                is Verdict.Invalid -> return invalidated(verdict.reason)
                is Verdict.Full -> return HistoryStep(this, PageOutcome.Backpressure(verdict.limit))
            }
        }
        val next = page.nextCursor
        val fingerprint = next?.let { sha256Hex(it) }
        if (next != null) {
            if (next == request.cursor || fingerprint in recentCursors) {
                return invalidated(HistoryInvalidation.NO_PROGRESS)
            }
        }
        val streak = if (next != null && work.newFragments == 0) emptyStreak + 1 else 0
        if (streak > STALL_LIMIT) return invalidated(HistoryInvalidation.STALLED)
        val endedMidMessage = next == null && (work.pending != null || work.openRefused != null)
        if (endedMidMessage) return invalidated(HistoryInvalidation.INCOMPLETE_AT_END)
        val complete = next == null
        val ring = ArrayList<String>(StateLimits.CURSOR_RING)
        val keepFrom = maxOf(0, recentCursors.size + 1 - StateLimits.CURSOR_RING)
        for (index in keepFrom until recentCursors.size) ring.add(recentCursors[index])
        fingerprint?.let { ring.add(it) }
        val newBoundaries = when {
            work.pending != null || work.openRefused != null -> boundaries
            boundaries.lastOrNull()?.ordinal == work.nextOrdinal ->
                boundaries.dropLast(1) + Boundary(work.nextOrdinal, next)
            else -> boundaries + Boundary(work.nextOrdinal, next)
        }
        val updated = copy(
            provenance = if (complete) {
                provenance.withStatus(
                    ListingStatus.COMPLETE
                )
            } else {
                provenance
            },
            expectedCursor = next,
            outstanding = null,
            pending = work.pending?.freeze(),
            known = work.known,
            staged = Collections.unmodifiableList(work.staged),
            stagedBytes = work.stagedBytes,
            boundaries = newBoundaries,
            nextOrdinal = work.nextOrdinal,
            refused = Collections.unmodifiableList(work.refused),
            emptyStreak = streak,
            recentCursors = Collections.unmodifiableList(ring),
            openRefused = work.openRefused,
            readRefused = Collections.unmodifiableSet(work.readRefused)
        )
        val outcome = PageOutcome.Accepted(
            Collections.unmodifiableList(work.emitted),
            work.newFragments,
            work.duplicateFragments,
            complete,
            work.refused.size
        )
        return HistoryStep(updated, outcome)
    }

    // -- committing and rewinding -------------------------------------------------------------------------

    /**
     * The consumer durably stored every staged message with ordinal <= [through]. They stop being staged, their
     * identities remain known (without text), and the resume point advances to the latest clean page boundary at or
     * before [through]. Nothing advances before this call.
     */
    fun commit(through: Long): HistoryStep<CommitOutcome> {
        if (staged.isEmpty()) return commitRefused(CommitRefusal.NOTHING_STAGED)
        if (through <= committedOrdinal) return commitRefused(CommitRefusal.STALE)
        if (through > staged.last().ordinal) return commitRefused(CommitRefusal.BEYOND_STAGED)
        val remaining = staged.filter { it.ordinal > through }
        val committed = staged.filter { it.ordinal <= through }
        val newKnown = LinkedHashMap(known)
        for (message in committed) {
            val entry = newKnown[message.messageId]
            if (entry != null) newKnown[message.messageId] = entry.committed()
        }
        val usable = boundaries.lastOrNull { it.ordinal <= through }
        val newResume = usable?.let { Resume(it.cursor, it.cursor == null) } ?: resume
        val newBoundaries = boundaries.filter { it.ordinal > through }
        val freed = committed.sumOf { RelayJson.utf8Length(it.text).toLong() }
        val next = copy(
            known = newKnown,
            staged = Collections.unmodifiableList(remaining),
            stagedBytes = stagedBytes - freed,
            boundaries = Collections.unmodifiableList(newBoundaries),
            resume = newResume,
            committedOrdinal = through
        )
        return HistoryStep(next, CommitOutcome.Committed(committed.size, newResume))
    }

    /**
     * The consumer could not store what is staged. Staged and partly assembled messages are dropped and the cursor
     * rewinds to the last COMMITTED clean boundary (or the beginning of this listing), so they are fetched again.
     */
    fun abortUncommitted(): HistoryStep<Int> {
        if (provenance.status == ListingStatus.INVALIDATED || resume?.atEnd == true) {
            return HistoryStep(this, 0)
        }
        val dropped = staged.size
        val newKnown = LinkedHashMap<MessageId, Known>()
        for ((id, entry) in known) if (entry.isCommitted) newKnown[id] = entry
        val next = copy(
            provenance = provenance.withStatus(ListingStatus.ACTIVE),
            expectedCursor = resume?.cursor,
            outstanding = null,
            pending = null,
            known = newKnown,
            staged = emptyList(),
            stagedBytes = 0,
            boundaries = emptyList(),
            nextOrdinal = committedOrdinal,
            emptyStreak = 0,
            recentCursors = emptyList(),
            openRefused = null,
            readRefused = emptySet()
        )
        return HistoryStep(next, dropped)
    }

    /** Explicitly drops the oldest committed identities beyond the newest [keep] (relaxing replay detection there). */
    fun pruneCommittedKnown(keep: Int): HistoryStep<Int> {
        require(keep >= 0)
        val committedIds = known.filterValues { it.isCommitted }.keys.toList()
        val drop = committedIds.size - keep
        if (drop <= 0) return HistoryStep(this, 0)
        val doomed = committedIds.take(drop).toSet()
        val newKnown = LinkedHashMap<MessageId, Known>()
        for ((id, entry) in known) if (id !in doomed) newKnown[id] = entry
        return HistoryStep(copy(known = newKnown), drop)
    }

    // -- invalidation and the explicit rebuild ------------------------------------------------------------

    /** The consumer reports a reason the listing is no longer trustworthy (a gap event, a revoked scope, ...). */
    fun invalidate(reason: HistoryInvalidation): HistoryStep<PageOutcome> = invalidated(reason)

    /**
     * Starts a NEW listing from the canonical beginning. This is the one place a "new beginning" is claimed, and it
     * records why. Nothing staged or partly assembled survives; identities and the resume point of the old chain do
     * not carry over (they belong to a cursor chain that is gone).
     */
    fun restart(after: HistoryInvalidation? = null): HistoryAssembly = create(
        scope,
        ListingProvenance(
            Math.addExact(provenance.generation, 1L),
            ListingStart.REBUILD,
            after ?: provenance.invalidation ?: HistoryInvalidation.CONSUMER,
            ListingStatus.ACTIVE,
            null
        ),
        nextRequest
    )

    private fun ignored(reason: HistoryIgnore): HistoryStep<PageOutcome> =
        HistoryStep(this, PageOutcome.Ignored(reason))

    private fun commitRefused(reason: CommitRefusal): HistoryStep<CommitOutcome> =
        HistoryStep(this, CommitOutcome.Refused(reason))

    private fun invalidated(
        reason: HistoryInvalidation,
        effects: List<HistoryEffect> = emptyList()
    ): HistoryStep<PageOutcome> {
        val discarded = staged.size
        val next = copy(
            provenance = ListingProvenance(
                provenance.generation,
                provenance.start,
                provenance.after,
                ListingStatus.INVALIDATED,
                reason
            ),
            outstanding = null,
            pending = null,
            staged = emptyList(),
            stagedBytes = 0,
            boundaries = emptyList(),
            openRefused = null,
            readRefused = emptySet()
        )
        return HistoryStep(
            next,
            PageOutcome.Invalidated(reason, discarded),
            listOf<HistoryEffect>(HistoryEffect.RebuildRequired(scope, reason)) + effects
        )
    }

    // -- snapshot ------------------------------------------------------------------------------------------

    /**
     * The COMMITTED progress of this listing: its provenance, the resume point, the identities (no text) of committed
     * messages and the locally refused ones. Staged messages, a partly assembled message, an outstanding request and
     * the cursor memory are not saved: whatever was not committed is fetched again.
     */
    fun snapshot(): HistorySnapshot = HistorySnapshot(
        scope.conversation.enrollment.toSnapshot(),
        scope.conversation.conversationId.value,
        scope.source.name,
        provenance.generation,
        provenance.start.name,
        provenance.after?.name,
        provenance.status.name,
        provenance.invalidation?.name,
        committedOrdinal,
        resume != null,
        resume?.cursor,
        resume?.atEnd ?: false,
        known.filterValues { it.isCommitted }.map { (id, entry) ->
            KnownMessageSnapshot(id.value, entry.role.wire, entry.createdAtMs, entry.count)
        },
        refused.map {
            RefusedMessageSnapshot(it.messageId.value, it.reason.name, it.declaredSegments)
        }
    )

    override fun toString(): String =
        "HistoryAssembly($scope, $provenance, staged=${staged.size}, known=${known.size})"

    // -- internals ----------------------------------------------------------------------------------------

    sealed interface CommitOutcome {
        class Committed(val count: Int, val resume: Resume?) : CommitOutcome {
            override fun toString(): String = "Committed($count, resume=$resume)"
        }

        class Refused(val reason: CommitRefusal) : CommitOutcome {
            override fun toString(): String = "Refused($reason)"
        }
    }

    /** Where to continue after the last commit: a cursor, or the end of a complete listing. Opaque; never printed. */
    class Resume internal constructor(internal val cursor: String?, val atEnd: Boolean) {
        override fun toString(): String = "Resume(atEnd=$atEnd, redacted)"
    }

    internal class Boundary(val ordinal: Long, val cursor: String?)

    /**
     * A message refused locally (over the segment or byte quota) whose remaining fragments are still due. Only its
     * identity, metadata and the next expected index are kept: bounded, and nothing of its text. Its fragments must
     * arrive in order (an advancing index is cursor progress) and with the metadata of the first one.
     */
    internal class OpenRefused(
        val id: MessageId,
        val role: HistoryRole,
        val createdAtMs: Long,
        val count: Int,
        val next: Int
    )

    internal class OpenMessage(
        val id: MessageId,
        val role: HistoryRole,
        val createdAtMs: Long,
        val count: Int,
        val parts: ArrayList<String>,
        var bytes: Long
    ) {
        fun freeze(): OpenMessage =
            OpenMessage(id, role, createdAtMs, count, ArrayList(parts), bytes)
    }

    internal class Known(
        val role: HistoryRole,
        val createdAtMs: Long,
        val count: Int,
        /** SHA-256 of each stored fragment while the message is staged; null once committed. */
        val digests: List<String>?
    ) {
        val isCommitted: Boolean get() = digests == null

        fun committed(): Known = Known(role, createdAtMs, count, null)
    }

    private sealed interface Verdict {
        data object Continue : Verdict

        class Invalid(val reason: HistoryInvalidation) : Verdict

        class Full(val limit: HistoryLimit) : Verdict
    }

    /** A private working copy of one page's effect, so a refused page leaves the assembly untouched. */
    private class Work(source: HistoryAssembly) {
        var pending: OpenMessage? = source.pending?.freeze()
        val known = LinkedHashMap(source.known)
        val staged = ArrayList(source.staged)
        var stagedBytes = source.stagedBytes
        var nextOrdinal = source.nextOrdinal
        val refused = ArrayList(source.refused)
        var openRefused: OpenRefused? = source.openRefused
        val readRefused = HashSet(source.readRefused)
        val emitted = ArrayList<AssembledMessage>()
        var newFragments = 0
        var duplicateFragments = 0

        fun consume(fragment: HistoryMessage): Verdict {
            val current = pending
            if (current != null) return continueOpen(current, fragment)
            val refusing = openRefused
            if (refusing != null) return continueRefused(refusing, fragment)
            val earlier = refused.firstOrNull { it.messageId == fragment.messageId }
            if (earlier != null) return seenRefused(earlier, fragment)
            val seen = known[fragment.messageId]
            if (seen != null) return duplicateOfKnown(seen, fragment)
            val beginsMessage = fragment.segmentIndex == 0
            if (!beginsMessage) return Verdict.Invalid(HistoryInvalidation.UNEXPECTED_FRAGMENT)
            if (fragment.segmentCount > SEGMENT_LIMIT) {
                return refuse(fragment, RefusalReason.TOO_MANY_SEGMENTS)
            }
            val bytes = RelayJson.utf8Length(fragment.text).toLong()
            if (bytes > MESSAGE_BYTES_LIMIT) return refuse(fragment, RefusalReason.TOO_MANY_BYTES)
            val started = OpenMessage(
                fragment.messageId,
                fragment.role,
                fragment.createdAtMs,
                fragment.segmentCount,
                arrayListOf(fragment.text),
                bytes
            )
            newFragments++
            return finishIfComplete(started)
        }

        private fun continueOpen(current: OpenMessage, fragment: HistoryMessage): Verdict {
            val sameMessage = fragment.messageId == current.id
            if (!sameMessage) return Verdict.Invalid(HistoryInvalidation.INTERLEAVED)
            val sameMetadata = fragment.role == current.role &&
                fragment.createdAtMs == current.createdAtMs &&
                fragment.segmentCount == current.count
            if (!sameMetadata) return Verdict.Invalid(HistoryInvalidation.MISMATCHED_METADATA)
            val index = fragment.segmentIndex
            if (index < current.parts.size) {
                if (current.parts[index] != fragment.text) {
                    return Verdict.Invalid(HistoryInvalidation.MISMATCHED_DUPLICATE)
                }
                duplicateFragments++
                return Verdict.Continue
            }
            val inOrder = index == current.parts.size
            if (!inOrder) return Verdict.Invalid(HistoryInvalidation.MISSING_SEGMENT)
            val bytes = current.bytes + RelayJson.utf8Length(fragment.text)
            if (bytes > MESSAGE_BYTES_LIMIT) {
                pending = null
                return refuse(fragment, RefusalReason.TOO_MANY_BYTES)
            }
            current.parts.add(fragment.text)
            current.bytes = bytes
            newFragments++
            return finishIfComplete(current)
        }

        /** Another fragment of the refused message being read: only the next index advances, in order. */
        private fun continueRefused(open: OpenRefused, fragment: HistoryMessage): Verdict {
            if (fragment.messageId !=
                open.id
            ) {
                return Verdict.Invalid(HistoryInvalidation.INTERLEAVED)
            }
            val sameMetadata = fragment.role == open.role &&
                fragment.createdAtMs == open.createdAtMs &&
                fragment.segmentCount == open.count
            if (!sameMetadata) return Verdict.Invalid(HistoryInvalidation.MISMATCHED_METADATA)
            val index = fragment.segmentIndex
            if (index < open.next) {
                duplicateFragments++
                return Verdict.Continue
            }
            if (index != open.next) return Verdict.Invalid(HistoryInvalidation.MISSING_SEGMENT)
            newFragments++
            advanceRefused(OpenRefused(open.id, open.role, open.createdAtMs, open.count, index + 1))
            return Verdict.Continue
        }

        /**
         * A fragment of a message this listing already refused. Read through in this cursor chain it is an idempotent
         * duplicate; otherwise (a rebuild after an abort or restore re-reads it) it must begin at its first fragment and
         * is tracked in order again, as progress.
         */
        private fun seenRefused(earlier: RefusedMessage, fragment: HistoryMessage): Verdict {
            if (fragment.segmentCount != earlier.declaredSegments) {
                return Verdict.Invalid(HistoryInvalidation.MISMATCHED_METADATA)
            }
            if (earlier.messageId in readRefused) {
                duplicateFragments++
                return Verdict.Continue
            }
            if (fragment.segmentIndex != 0) {
                return Verdict.Invalid(HistoryInvalidation.UNEXPECTED_FRAGMENT)
            }
            newFragments++
            advanceRefused(
                OpenRefused(
                    fragment.messageId,
                    fragment.role,
                    fragment.createdAtMs,
                    fragment.segmentCount,
                    1
                )
            )
            return Verdict.Continue
        }

        private fun advanceRefused(open: OpenRefused) {
            if (open.next >= open.count) {
                openRefused = null
                readRefused.add(open.id)
            } else {
                openRefused = open
            }
        }

        private fun duplicateOfKnown(seen: Known, fragment: HistoryMessage): Verdict {
            val sameMetadata = fragment.role == seen.role &&
                fragment.createdAtMs == seen.createdAtMs &&
                fragment.segmentCount == seen.count
            if (!sameMetadata) return Verdict.Invalid(HistoryInvalidation.MISMATCHED_METADATA)
            val digests = seen.digests
            if (digests != null) {
                // A staged message: the fragment can be compared byte for byte.
                val index = fragment.segmentIndex
                val identical = index < digests.size && digests[index] == sha256Hex(fragment.text)
                if (!identical) return Verdict.Invalid(HistoryInvalidation.MISMATCHED_DUPLICATE)
            }
            duplicateFragments++
            return Verdict.Continue
        }

        private fun refuse(fragment: HistoryMessage, reason: RefusalReason): Verdict {
            if (refused.size >= StateLimits.MAX_REFUSED_MESSAGES) {
                return Verdict.Invalid(HistoryInvalidation.TOO_MANY_REFUSED)
            }
            pending = null
            refused.add(RefusedMessage(fragment.messageId, reason, fragment.segmentCount))
            newFragments++
            advanceRefused(
                OpenRefused(
                    fragment.messageId,
                    fragment.role,
                    fragment.createdAtMs,
                    fragment.segmentCount,
                    fragment.segmentIndex + 1
                )
            )
            return Verdict.Continue
        }

        private fun finishIfComplete(message: OpenMessage): Verdict {
            if (message.parts.size < message.count) {
                pending = message
                return Verdict.Continue
            }
            if (staged.size >= STAGED_MESSAGES_LIMIT) {
                return Verdict.Full(HistoryLimit.UNCOMMITTED_MESSAGES)
            }
            if (stagedBytes + message.bytes > STAGED_BYTES_LIMIT) {
                return Verdict.Full(HistoryLimit.UNCOMMITTED_BYTES)
            }
            if (known.size >= KNOWN_LIMIT) return Verdict.Full(HistoryLimit.KNOWN_MESSAGES)
            val text = StringBuilder(message.bytes.toInt()).also { out ->
                message.parts.forEach { out.append(it) }
            }.toString()
            nextOrdinal = Math.addExact(nextOrdinal, 1L)
            val assembled = AssembledMessage(
                nextOrdinal,
                message.id,
                message.role,
                message.createdAtMs,
                text,
                message.count
            )
            staged.add(assembled)
            emitted.add(assembled)
            stagedBytes += message.bytes
            known[message.id] =
                Known(
                    message.role,
                    message.createdAtMs,
                    message.count,
                    message.parts.map(::sha256Hex)
                )
            pending = null
            return Verdict.Continue
        }
    }

    private fun ListingProvenance.withStatus(status: ListingStatus): ListingProvenance {
        val reason = invalidation.takeIf { status == ListingStatus.INVALIDATED }
        return ListingProvenance(generation, start, after, status, reason)
    }

    private fun copy(
        provenance: ListingProvenance = this.provenance,
        expectedCursor: String? = this.expectedCursor,
        outstanding: PageRequest? = this.outstanding,
        nextRequest: Long = this.nextRequest,
        pending: OpenMessage? = this.pending,
        known: Map<MessageId, Known> = this.known,
        staged: List<AssembledMessage> = this.staged,
        stagedBytes: Long = this.stagedBytes,
        boundaries: List<Boundary> = this.boundaries,
        resume: Resume? = this.resume,
        committedOrdinal: Long = this.committedOrdinal,
        nextOrdinal: Long = this.nextOrdinal,
        refused: List<RefusedMessage> = this.refused,
        emptyStreak: Int = this.emptyStreak,
        recentCursors: List<String> = this.recentCursors,
        openRefused: OpenRefused? = this.openRefused,
        readRefused: Set<MessageId> = this.readRefused
    ) = HistoryAssembly(
        scope,
        provenance,
        expectedCursor,
        outstanding,
        nextRequest,
        pending,
        known,
        staged,
        stagedBytes,
        boundaries,
        resume,
        committedOrdinal,
        nextOrdinal,
        refused,
        emptyStreak,
        recentCursors,
        openRefused,
        readRefused
    )

    companion object {
        /**
         * Restores a listing from its committed progress. A saved cursor is only a CANDIDATE: the server decides whether
         * it still holds (a `snapshot_required` then invalidates). Without a usable cursor the chain is NOT silently
         * re-read from its top while committed identities are trusted: the listing comes back invalidated and an
         * explicit [restart] claims the new beginning. A listing that never committed anything just starts again.
         */
        internal fun restore(saved: HistorySnapshot): RestoreResult<HistoryAssembly> {
            fun refused(reason: RestoreRefusal, path: String) = RestoreResult.Refused(reason, path)
            val enrollment =
                saved.scope.toScope() ?: return refused(RestoreRefusal.INVALID_SCOPE, "scope")
            val conversation = ConversationId.of(saved.conversationId)
                ?: return refused(RestoreRefusal.INVALID_ID, "conversationId")
            val source = HistorySource.entries.firstOrNull { it.name == saved.source }
                ?: return refused(RestoreRefusal.INVALID_FIELD, "source")
            val start = ListingStart.entries.firstOrNull { it.name == saved.start }
                ?: return refused(RestoreRefusal.INVALID_FIELD, "start")
            val after = saved.after?.let { name ->
                HistoryInvalidation.entries.firstOrNull { it.name == name }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "after")
            }
            val status = ListingStatus.entries.firstOrNull { it.name == saved.status }
                ?: return refused(RestoreRefusal.INVALID_FIELD, "status")
            val invalidation = saved.invalidation?.let { name ->
                HistoryInvalidation.entries.firstOrNull { it.name == name }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "invalidation")
            }
            if (saved.generation < 1) return refused(RestoreRefusal.INVALID_FIELD, "generation")
            if ((status == ListingStatus.INVALIDATED) != (invalidation != null)) {
                return refused(RestoreRefusal.INCONSISTENT, "invalidation")
            }
            if (saved.committedOrdinal <
                0
            ) {
                return refused(RestoreRefusal.INVALID_FIELD, "committedOrdinal")
            }
            val resume = when {
                !saved.hasResume -> if (saved.resumeCursor == null && !saved.resumeAtEnd) {
                    null
                } else {
                    return refused(RestoreRefusal.INCONSISTENT, "resume")
                }
                saved.resumeAtEnd ->
                    if (saved.resumeCursor == null) {
                        Resume(null, true)
                    } else {
                        return refused(RestoreRefusal.INCONSISTENT, "resume")
                    }
                else -> {
                    val cursor = saved.resumeCursor
                    if (cursor == null ||
                        !validCursor(cursor)
                    ) {
                        return refused(RestoreRefusal.INVALID_FIELD, "resume")
                    }
                    Resume(cursor, false)
                }
            }
            if (saved.known.size >
                StateLimits.MAX_KNOWN_MESSAGES
            ) {
                return refused(RestoreRefusal.TOO_MANY, "known")
            }
            if (saved.known.size >
                saved.committedOrdinal
            ) {
                return refused(RestoreRefusal.INCONSISTENT, "known")
            }
            val known = LinkedHashMap<MessageId, Known>()
            for ((index, item) in saved.known.withIndex()) {
                val path = "known[$index]"
                val id =
                    MessageId.of(item.messageId)
                        ?: return refused(RestoreRefusal.INVALID_ID, "$path.messageId")
                val role = HistoryRole.entries.firstOrNull { it.wire == item.role }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.role")
                val shape =
                    item.createdAtMs >= 0 &&
                        item.segmentCount in 1..StateLimits.MAX_MESSAGE_SEGMENTS
                if (!shape) return refused(RestoreRefusal.INVALID_FIELD, path)
                if (known.put(id, Known(role, item.createdAtMs, item.segmentCount, null)) != null) {
                    return refused(RestoreRefusal.DUPLICATE, path)
                }
            }
            if (saved.refused.size >
                StateLimits.MAX_REFUSED_MESSAGES
            ) {
                return refused(RestoreRefusal.TOO_MANY, "refused")
            }
            val refusedList = ArrayList<RefusedMessage>()
            for ((index, item) in saved.refused.withIndex()) {
                val path = "refused[$index]"
                val id =
                    MessageId.of(item.messageId)
                        ?: return refused(RestoreRefusal.INVALID_ID, "$path.messageId")
                val reason = RefusalReason.entries.firstOrNull { it.name == item.reason }
                    ?: return refused(RestoreRefusal.INVALID_FIELD, "$path.reason")
                if (item.declaredSegments <
                    1
                ) {
                    return refused(RestoreRefusal.INVALID_FIELD, "$path.declaredSegments")
                }
                if (refusedList.any {
                        it.messageId == id
                    }
                ) {
                    return refused(RestoreRefusal.DUPLICATE, path)
                }
                refusedList.add(RefusedMessage(id, reason, item.declaredSegments))
            }
            val notices = ArrayList<RestoreNotice>()
            val untouched = saved.committedOrdinal == 0L && known.isEmpty()
            val restoredStatus: ListingStatus
            val restoredStart: ListingStart
            val restoredInvalidation: HistoryInvalidation?
            val cursor: String?
            when {
                status == ListingStatus.INVALIDATED -> {
                    restoredStatus = ListingStatus.INVALIDATED
                    restoredStart = start
                    restoredInvalidation = invalidation
                    cursor = null
                }
                resume != null && resume.atEnd -> {
                    restoredStatus = ListingStatus.COMPLETE
                    restoredStart = start
                    restoredInvalidation = null
                    cursor = null
                }
                resume != null -> {
                    restoredStatus = ListingStatus.ACTIVE
                    restoredStart = ListingStart.RESUMED_FROM_SAVED_CURSOR
                    restoredInvalidation = null
                    cursor = resume.cursor
                }
                untouched -> {
                    restoredStatus = ListingStatus.ACTIVE
                    restoredStart = start
                    restoredInvalidation = null
                    cursor = null
                }
                else -> {
                    restoredStatus = ListingStatus.INVALIDATED
                    restoredStart = start
                    restoredInvalidation = HistoryInvalidation.RESTORED_WITHOUT_CURSOR
                    cursor = null
                    notices.add(RestoreNotice.HISTORY_NEEDS_REBUILD)
                }
            }
            val assembly = HistoryAssembly(
                HistoryScope(ConversationScope(enrollment, conversation), source),
                ListingProvenance(
                    saved.generation,
                    restoredStart,
                    after,
                    restoredStatus,
                    restoredInvalidation
                ),
                cursor,
                null,
                1,
                null,
                known,
                emptyList(),
                0,
                emptyList(),
                resume,
                saved.committedOrdinal,
                saved.committedOrdinal,
                refusedList,
                0,
                emptyList(),
                null,
                emptySet()
            )
            return RestoreResult.Restored(assembly, notices)
        }

        /** The first listing of [scope]: from the canonical beginning, generation 1. */
        fun begin(scope: HistoryScope): HistoryAssembly = create(
            scope,
            ListingProvenance(1, ListingStart.BEGINNING, null, ListingStatus.ACTIVE, null),
            1
        )

        private fun create(scope: HistoryScope, provenance: ListingProvenance, nextRequest: Long) =
            HistoryAssembly(
                scope,
                provenance,
                null,
                null,
                nextRequest,
                null,
                emptyMap(),
                emptyList(),
                0,
                emptyList(),
                null,
                0,
                0,
                emptyList(),
                0,
                emptyList(),
                null,
                emptySet()
            )
    }
}

private const val STALL_LIMIT = StateLimits.MAX_CONSECUTIVE_EMPTY_PAGES
private const val SEGMENT_LIMIT = StateLimits.MAX_MESSAGE_SEGMENTS
private const val MESSAGE_BYTES_LIMIT = StateLimits.MAX_MESSAGE_UTF8_BYTES
private const val STAGED_MESSAGES_LIMIT = StateLimits.MAX_UNCOMMITTED_MESSAGES
private const val STAGED_BYTES_LIMIT = StateLimits.MAX_UNCOMMITTED_UTF8_BYTES
private const val KNOWN_LIMIT = StateLimits.MAX_KNOWN_MESSAGES
