package dev.quantumink.hermesgadget.protocol.relay.state

import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.RelayScope
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceLimits
import java.util.Collections

/*
 * Persistence-ready snapshots of the pure state. They are plain immutable values (strings and numbers in their wire
 * spelling) so that a future consumer can serialise, encrypt and write them in whatever format and order it owns; none
 * of that is done or proven here. Restoring RE-VALIDATES everything and refuses on the first violation.
 *
 * What a snapshot never holds, by construction: a transport binding, an authority epoch of a binding, a replay epoch,
 * a clock offset or any server-time reading, a prompt or control that was fetched earlier, an automatic-send or playback
 * flag, a correlation id, an outstanding call, or staged (uncommitted) content. Restoring never invents an identity,
 * a baseline, an authority proof or a new turn.
 */

/** Why a restore was refused. The reason carries a field PATH, never a value (values can be user text). */
enum class RestoreRefusal {
    VERSION,
    TOO_MANY,
    BUDGET,
    INVALID_SCOPE,
    INVALID_ID,
    INVALID_FIELD,
    DUPLICATE,
    INCONSISTENT,
    AUTHORITY_NOT_FRESH
}

sealed interface RestoreResult<out T> {
    class Restored<T>(val value: T, val notices: List<RestoreNotice>) : RestoreResult<T> {
        override fun toString(): String = "Restored(notices=${notices.size})"
    }

    class Refused(val reason: RestoreRefusal, val path: String) : RestoreResult<Nothing> {
        override fun toString(): String = "Refused($reason, $path)"
    }
}

/** Facts worth telling the consumer about a successful restore. None of them changes any state. */
enum class RestoreNotice {
    /** Turns that may have reached the server are unresolved and past their deadline: still unknown. */
    UNRESOLVED_TURNS_PAST_DEADLINE,

    /** `RECORDING` captures were restored: their audio died with the process. Reconcile them (they become `LOST`); nothing is sent for them. */
    CAPTURE_RECORDINGS_LOST,

    /** An `ADMITTING` capture names a turn the restored ledger does not hold. Reconcile with the ledger; nothing is prepared again. */
    CAPTURE_LINK_WITHOUT_TURN,

    /** A turn was prepared later than the fresh server time: the server clock is behind the saved state. */
    SERVER_CLOCK_BEHIND_SNAPSHOT,

    /** A history listing without a resume cursor was invalidated: an explicit rebuild is required. */
    HISTORY_NEEDS_REBUILD
}

/** An enrollment as saved: the consumer's endpoint label, the profile id, its own enrollment label, the review epoch. */
data class ScopeRecordSnapshot(
    val endpointKey: String,
    val profileId: String,
    val enrollmentKey: String,
    val reviewEpoch: Long
) {
    override fun toString(): String = "ScopeRecordSnapshot(redacted)"
}

data class AdmissionFactsSnapshot(
    val admissionId: String,
    val authorityEpoch: Long,
    val status: String,
    val executionGeneration: Long?,
    val outcome: String?,
    /** See [AdmissionFacts.priorStartedGeneration]; null except for a requeued-then-cancelled admission. */
    val priorStartedGeneration: Long? = null
) {
    override fun toString(): String = "AdmissionFactsSnapshot(redacted)"
}

/** One saved turn. The text is the user's; [toString] prints none of it. */
data class TurnSnapshot(
    val scope: ScopeRecordSnapshot,
    val conversationId: String,
    val requestId: String,
    val expiresAtMs: Long,
    val preparedAtMs: Long,
    val fingerprint: String,
    val text: String?,
    val sent: Boolean,
    val facts: AdmissionFactsSnapshot?,
    /** `refused:<code>`, or null while the turn is open. */
    val closure: String?,
    val held: Boolean,
    val lastErrorCode: String?,
    val anomalies: Int
) {
    override fun toString(): String = "TurnSnapshot(redacted)"
}

data class ScopeStateSnapshot(
    val scope: ScopeRecordSnapshot,
    val status: String,
    val contentEvictionOwed: Boolean
) {
    override fun toString(): String = "ScopeStateSnapshot(redacted)"
}

class TurnLedgerSnapshot(turns: List<TurnSnapshot>, scopes: List<ScopeStateSnapshot>) {
    val turns: List<TurnSnapshot> = ownedBoundedForSnapshot(
        turns,
        StateLimits.MAX_RETAINED_TURNS + 1
    )
    val scopes: List<ScopeStateSnapshot> = ownedBoundedForSnapshot(
        scopes,
        StateLimits.MAX_SCOPE_DECISIONS + 1
    )

    override fun toString(): String =
        "TurnLedgerSnapshot(turns=${turns.size}, scopes=${scopes.size})"
}

data class KnownMessageSnapshot(
    val messageId: String,
    val role: String,
    val createdAtMs: Long,
    val segmentCount: Int
) {
    override fun toString(): String = "KnownMessageSnapshot(redacted)"
}

data class RefusedMessageSnapshot(
    val messageId: String,
    val reason: String,
    val declaredSegments: Int
) {
    override fun toString(): String = "RefusedMessageSnapshot(redacted)"
}

/** The committed progress of one history listing. Staged and partly assembled messages are NOT saved. */
class HistorySnapshot(
    val scope: ScopeRecordSnapshot,
    val conversationId: String,
    val source: String,
    val generation: Long,
    val start: String,
    val after: String?,
    val status: String,
    val invalidation: String?,
    val committedOrdinal: Long,
    val hasResume: Boolean,
    val resumeCursor: String?,
    val resumeAtEnd: Boolean,
    known: List<KnownMessageSnapshot>,
    refused: List<RefusedMessageSnapshot>
) {
    val known: List<KnownMessageSnapshot> = ownedBoundedForSnapshot(
        known,
        StateLimits.MAX_KNOWN_MESSAGES + 1
    )
    val refused: List<RefusedMessageSnapshot> = ownedBoundedForSnapshot(
        refused,
        StateLimits.MAX_REFUSED_MESSAGES + 1
    )

    override fun toString(): String =
        "HistorySnapshot(generation=$generation, $status, known=${known.size}, redacted)"
}

data class SeenKeySnapshot(
    val kind: String,
    val conversation: String,
    val event: String,
    val committedAtMs: Long
) {
    override fun toString(): String = "SeenKeySnapshot(redacted)"
}

data class ObligationSnapshot(val conversationId: String?, val kind: String, val cause: String) {
    override fun toString(): String = "ObligationSnapshot($kind, $cause)"
}

/** What was delivered to one enrollment: event identities, owed refreshes, the O5 baseline and unacknowledged ids. */
class DeliverySnapshot(
    val scope: ScopeRecordSnapshot,
    val gapBaseline: Long?,
    seen: List<SeenKeySnapshot>,
    obligations: List<ObligationSnapshot>,
    pendingAcks: List<String>
) {
    val seen: List<SeenKeySnapshot> = ownedBoundedForSnapshot(seen, StateLimits.MAX_SEEN_KEYS + 1)
    val obligations: List<ObligationSnapshot> = ownedBoundedForSnapshot(
        obligations,
        MAX_OBLIGATIONS + 1
    )
    val pendingAcks: List<String> = ownedBoundedForSnapshot(
        pendingAcks,
        StateLimits.MAX_PENDING_ACKS + 1
    )

    override fun toString(): String =
        "DeliverySnapshot(seen=${seen.size}, obligations=${obligations.size}, acks=${pendingAcks.size}, redacted)"

    internal companion object {
        const val MAX_OBLIGATIONS = 128
    }
}

/**
 * Everything the pure state keeps durably, in one versioned value. [captures] is the canonical-voice capture section (version 1) added by aggregate
 * version 2; a version 1 value has none.
 */
class RelayStateSnapshot(
    val version: Int,
    val ledger: TurnLedgerSnapshot,
    histories: List<HistorySnapshot>,
    deliveries: List<DeliverySnapshot>,
    val captures: CaptureLedgerSnapshot = CaptureLedgerSnapshot.EMPTY
) {
    val histories: List<HistorySnapshot> = ownedBoundedForSnapshot(
        histories,
        StateLimits.MAX_HISTORY_SCOPES + 1
    )
    val deliveries: List<DeliverySnapshot> = ownedBoundedForSnapshot(
        deliveries,
        StateLimits.MAX_ENROLLMENTS + 1
    )

    override fun toString(): String =
        "RelayStateSnapshot(v$version, turns=${ledger.turns.size}, histories=${histories.size}, " +
            "deliveries=${deliveries.size}, captures=${captures.records.size})"

    companion object {
        const val VERSION = StateLimits.SNAPSHOT_VERSION
        const val LEGACY_VERSION = StateLimits.LEGACY_SNAPSHOT_VERSION

        /**
         * Builds the aggregate snapshot, or refuses with [SnapshotBuild.TooMany] when a component exceeds its policy
         * limit: a snapshot that restore would refuse (or, worse, shorten) is never handed out as a durable value. The
         * caller's collections are read element by element and only up to one past their limit, before anything is
         * mapped or copied, so an arbitrarily large collection costs a bounded amount of work.
         */
        fun of(
            ledger: TurnLedger,
            histories: List<HistoryAssembly>,
            deliveries: List<EnrollmentDelivery>,
            captures: VoiceCaptureLedger = VoiceCaptureLedger.empty()
        ): SnapshotBuild {
            val ownedHistories = ownedBoundedForSnapshot(
                histories,
                StateLimits.MAX_HISTORY_SCOPES + 1
            )
            val ownedDeliveries = ownedBoundedForSnapshot(
                deliveries,
                StateLimits.MAX_ENROLLMENTS + 1
            )
            aggregateTooMany(
                ledger,
                AggregateShape(ownedHistories.size, ownedDeliveries.size),
                captures
            )?.let {
                return it
            }
            return SnapshotBuild.Built(
                RelayStateSnapshot(
                    VERSION,
                    ledger.snapshot(),
                    ownedHistories.map { it.snapshot() },
                    ownedDeliveries.map { it.snapshot() },
                    captures.snapshot()
                )
            )
        }
    }
}

/**
 * The sizes of the aggregate components a capture transition cannot see (histories and deliveries are other layers' state). It lets a capture
 * transition prove, BEFORE it hands out an effect, that the combined snapshot can still be built.
 */
class AggregateShape(val histories: Int, val deliveries: Int) {
    init {
        require(histories >= 0 && deliveries >= 0)
    }

    override fun toString(): String = "AggregateShape(histories=$histories, deliveries=$deliveries)"

    companion object {
        val EMPTY = AggregateShape(0, 0)
    }
}

/**
 * The one definition of "this aggregate cannot be built": the first component over its policy limit, in the order the snapshot checks them. Nothing is
 * dropped, truncated or built; the caller must refuse the action that needed the snapshot (persist-before-send), never send.
 */
internal fun aggregateTooMany(
    ledger: TurnLedger,
    shape: AggregateShape,
    captures: VoiceCaptureLedger
): SnapshotBuild.TooMany? = when {
    shape.histories > StateLimits.MAX_HISTORY_SCOPES -> SnapshotBuild.TooMany(
        "histories",
        StateLimits.MAX_HISTORY_SCOPES
    )
    shape.deliveries > StateLimits.MAX_ENROLLMENTS -> SnapshotBuild.TooMany(
        "deliveries",
        StateLimits.MAX_ENROLLMENTS
    )
    ledger.scopeDecisionCount > StateLimits.MAX_SCOPE_DECISIONS ->
        SnapshotBuild.TooMany("ledger.scopes", StateLimits.MAX_SCOPE_DECISIONS)
    captures.size > VoiceLimits.CAPTURES_TOTAL -> SnapshotBuild.TooMany(
        "captures",
        VoiceLimits.CAPTURES_TOTAL
    )
    captures.maxPerEnrollment() > VoiceLimits.CAPTURES_PER_ENROLLMENT ->
        SnapshotBuild.TooMany("captures.enrollment", VoiceLimits.CAPTURES_PER_ENROLLMENT)
    else -> null
}

/** The result of building an aggregate snapshot: the value, or a typed refusal naming the component and its limit. */
sealed interface SnapshotBuild {
    class Built(val snapshot: RelayStateSnapshot) : SnapshotBuild {
        override fun toString(): String = "Built($snapshot)"
    }

    /** More than [limit] entries of [path] exist. Nothing was dropped, truncated or built. */
    class TooMany(val path: String, val limit: Int) : SnapshotBuild {
        override fun toString(): String = "TooMany($path, limit=$limit)"
    }
}

/** The state a snapshot restores to. Every transient part (bindings, calls, staging) starts empty. */
class RestoredState internal constructor(
    val ledger: TurnLedger,
    val histories: List<HistoryAssembly>,
    val deliveries: List<EnrollmentDelivery>,
    val captures: VoiceCaptureLedger = VoiceCaptureLedger.empty()
) {
    override fun toString(): String =
        "RestoredState(turns=${ledger.size}, histories=${histories.size}, deliveries=${deliveries.size}, captures=${captures.size})"
}

object RelayStateRestore {
    /**
     * Restores [snapshot]. Needs a FRESH [ServerInstant] (the saved state holds no clock and no offset) and a fresh
     * [RelayAuthority] with no binding: a restored turn that may have reached the server is uncertain and needs an
     * attach that happens AFTER this restore, then a receipt lookup, before any resubmit.
     */
    fun restore(
        snapshot: RelayStateSnapshot,
        now: ServerInstant,
        authority: RelayAuthority
    ): RestoreResult<RestoredState> {
        // Version 2 is the current aggregate; version 1 (no capture section) is restored unchanged with no captures: a preserving migration
        if (snapshot.version != RelayStateSnapshot.VERSION &&
            snapshot.version != RelayStateSnapshot.LEGACY_VERSION
        ) {
            return RestoreResult.Refused(RestoreRefusal.VERSION, "version")
        }
        if (snapshot.version == RelayStateSnapshot.LEGACY_VERSION &&
            snapshot.captures.records.isNotEmpty()
        ) {
            return RestoreResult.Refused(RestoreRefusal.INCONSISTENT, "captures")
        }
        if (!authority.isEmpty) {
            return RestoreResult.Refused(
                RestoreRefusal.AUTHORITY_NOT_FRESH,
                "authority"
            )
        }
        if (snapshot.histories.size > StateLimits.MAX_HISTORY_SCOPES) {
            return RestoreResult.Refused(RestoreRefusal.TOO_MANY, "histories")
        }
        if (snapshot.deliveries.size > StateLimits.MAX_ENROLLMENTS) {
            return RestoreResult.Refused(RestoreRefusal.TOO_MANY, "deliveries")
        }
        val notices = LinkedHashSet<RestoreNotice>()
        val ledger = when (val result = TurnLedger.restore(snapshot.ledger, now, authority)) {
            is RestoreResult.Restored -> {
                notices.addAll(result.notices)
                result.value
            }
            is RestoreResult.Refused -> return RestoreResult.Refused(
                result.reason,
                "ledger.${result.path}"
            )
        }
        val histories = ArrayList<HistoryAssembly>()
        val historyScopes = HashSet<HistoryScope>()
        for ((index, saved) in snapshot.histories.withIndex()) {
            when (val result = HistoryAssembly.restore(saved)) {
                is RestoreResult.Restored -> {
                    if (!historyScopes.add(result.value.scope)) {
                        return RestoreResult.Refused(RestoreRefusal.DUPLICATE, "histories[$index]")
                    }
                    notices.addAll(result.notices)
                    histories.add(result.value)
                }
                is RestoreResult.Refused ->
                    return RestoreResult.Refused(result.reason, "histories[$index].${result.path}")
            }
        }
        val deliveries = ArrayList<EnrollmentDelivery>()
        val enrollments = HashSet<EnrollmentScope>()
        for ((index, saved) in snapshot.deliveries.withIndex()) {
            when (val result = EnrollmentDelivery.restore(saved)) {
                is RestoreResult.Restored -> {
                    if (!enrollments.add(result.value.enrollment)) {
                        return RestoreResult.Refused(RestoreRefusal.DUPLICATE, "deliveries[$index]")
                    }
                    deliveries.add(result.value)
                }
                is RestoreResult.Refused ->
                    return RestoreResult.Refused(result.reason, "deliveries[$index].${result.path}")
            }
        }
        val captures = when (
            val result = VoiceCaptureLedger.restore(
                snapshot.captures,
                ledger,
                now
            )
        ) {
            is RestoreResult.Restored -> {
                notices.addAll(result.notices)
                result.value
            }
            is RestoreResult.Refused -> return RestoreResult.Refused(
                result.reason,
                "captures.${result.path}"
            )
        }
        return RestoreResult.Restored(
            RestoredState(
                ledger,
                Collections.unmodifiableList(histories),
                Collections.unmodifiableList(deliveries),
                captures
            ),
            notices.toList()
        )
    }
}

// -- shared parsing ------------------------------------------------------------------------------------------

internal fun <T : Any> ownedBoundedForSnapshot(values: List<T>, maximum: Int): List<T> {
    val owned = ArrayList<T>(minOf(maximum, 16))
    val source = values.iterator()
    // One past the policy limit is kept so that restore can refuse it; the element after that is never even read
    while (owned.size < maximum && source.hasNext()) owned.add(source.next())
    return Collections.unmodifiableList(owned)
}

internal fun ScopeRecordSnapshot.toScope(): EnrollmentScope? = try {
    val profile = ProfileId.of(profileId) ?: return null
    EnrollmentScope(RelayScope(endpointKey, profile), enrollmentKey, reviewEpoch)
} catch (_: IllegalArgumentException) {
    null
}

internal fun EnrollmentScope.toSnapshot(): ScopeRecordSnapshot =
    ScopeRecordSnapshot(relay.endpointKey, relay.profileId.value, enrollmentKey, reviewEpoch)
