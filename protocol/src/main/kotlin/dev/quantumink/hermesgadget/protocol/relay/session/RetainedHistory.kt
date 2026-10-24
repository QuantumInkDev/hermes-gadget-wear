package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.state.HistoryScope
import java.util.Collections

/** Local public separation only. Neither these values nor cached text confer server authority. */
data class HistoryNamespace(
    val endpointKey: String,
    val endpointUrl: String,
    val enrollmentLabel: String,
    val profileId: String,
    val reviewEpoch: Long
) {
    init {
        require(Endpoint.parse(endpointUrl).let { it.isTls && it.url == endpointUrl })
        require(endpointKey == EnrollmentRecord.endpointKey(endpointUrl))
        require(enrollmentLabel.length in 1..128 && enrollmentLabel.none { it.isISOControl() })
        require(ProfileId.of(profileId) != null && reviewEpoch >= 0)
    }
    override fun toString(): String = "HistoryNamespace(redacted)"

    companion object {
        fun from(record: EnrollmentRecord): HistoryNamespace? = record.pinnedProfileId?.let {
            HistoryNamespace(
                record.endpointKey,
                record.endpointUrl,
                record.enrollmentKey,
                it.value,
                record.reviewEpoch
            )
        }
    }
}

sealed interface HistoryAnchor {
    data object Beginning : HistoryAnchor
    data class After(val messageId: String) : HistoryAnchor
}

enum class RetainedRole { USER, ASSISTANT }
enum class HistoryProblem {
    LOCAL_REVISION,
    STALE_METADATA,
    METADATA_CONFLICT,
    MISSING_ANCHOR,
    SEQUENCE_CONFLICT,
    CAPACITY,
    KEY_UNAVAILABLE,
    UNREADABLE,
    RECORD_MISSING,
    UNSUPPORTED,
    LEGACY_UNBOUND,
    STORAGE,
    WRITE_UNCERTAIN,
    REBUILD_REQUIRED,
    STALE_REVIEW,
    INVALID_VALUE,
    UNBOUND_PROVENANCE
}
class HistoryFailure(val problem: HistoryProblem) :
    IllegalStateException("Retained history: $problem")

fun <T : Any> ownHistoryItems(values: List<T>, maximum: Int): List<T> {
    if (values.size > maximum) throw HistoryFailure(HistoryProblem.CAPACITY)
    val owned = ArrayList<T>(minOf(values.size, maximum))
    for (value in values) {
        if (owned.size >= maximum) throw HistoryFailure(HistoryProblem.CAPACITY)
        owned.add(requireNotNull(value))
    }
    return Collections.unmodifiableList(owned)
}

data class RetainedMessage(
    val id: String,
    val role: RetainedRole,
    val text: String,
    val createdAtMs: Long
) {
    override fun toString(): String = "RetainedMessage(redacted)"
}
class RetainedThread(
    val id: String,
    val publicRevision: Long,
    val title: String,
    val bookmarked: Boolean,
    messages: List<RetainedMessage>,
    val complete: Boolean = false,
    val rebuildRequired: Boolean = false
) {
    val messages = ownHistoryItems(messages, 1024)
    override fun toString(): String = "RetainedThread(redacted)"
}
class RetainedSnapshot(
    val revision: Long,
    val retentionDays: Int = 90,
    threads: List<RetainedThread> = emptyList()
) {
    val threads = ownHistoryItems(threads, 256)
    override fun toString(): String = "RetainedSnapshot(redacted)"
}

/** Created only at the controller's current authenticated HistoryAssembly acceptance boundary. */
class HistoryCapture internal constructor(
    val namespace: HistoryNamespace,
    val thread: RetainedThread,
    val anchor: HistoryAnchor,
    val listing: String,
    val pageSerial: Long,
    val listingGeneration: Long,
    val flow: Long,
    val beginsChain: Boolean,
    val scope: HistoryScope
) {
    override fun toString(): String = "HistoryCapture(redacted)"
}

/** Exact reviewed bytes; unreadable/legacy data always requires ALL profiles to be named. */
data class HistoryEraseReview(
    val namespace: HistoryNamespace?,
    val conversationId: String?,
    val allProfiles: Boolean,
    val recordHash: String,
    val markerHash: String,
    val recordBytes: Long,
    val version: Int?,
    val revision: Long?,
    val retentionDays: Int? = null,
    val pendingHash: String = "absent"
)

/** Blocking disk/crypto interface: call exclusively on the off-main controller owner. No pending operation store. */
interface RetainedHistory {
    fun read(namespace: HistoryNamespace): RetainedSnapshot
    fun capture(page: HistoryCapture): RetainedSnapshot
    fun evict(namespace: HistoryNamespace, conversationId: String?)
    fun absent(namespace: HistoryNamespace, conversationId: String?): Boolean
    fun review(namespace: HistoryNamespace, conversationId: String?): HistoryEraseReview
    fun reviewRetention(namespace: HistoryNamespace, days: Int): HistoryEraseReview
    fun erase(review: HistoryEraseReview, namespace: HistoryNamespace)
    fun retention(namespace: HistoryNamespace, days: Int): RetainedSnapshot
}

/** Owner-issued identity of the exact review rendered in one foreground operation. It is never persisted. */
class CacheReviewConfirmation internal constructor(
    val review: HistoryEraseReview,
    internal val namespace: HistoryNamespace,
    internal val foreground: Long,
    internal val session: Long,
    internal val flow: Long,
    internal val operation: Long
) {
    override fun toString(): String = "CacheReviewConfirmation(redacted)"
}
