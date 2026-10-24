package dev.quantumink.hermesgadget.protocol.relay.session

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.RelayCalls
import dev.quantumink.hermesgadget.protocol.relay.RequestId

enum class MetadataStatus {
    PENDING,
    UNKNOWN,
    TRANSIENT,
    ACCEPTED,
    SUPERSEDED,
    RETIRED,
    STALE_SNAPSHOT,
    UNAVAILABLE,
    EXPIRED,
    REFUSED
}

/** Immutable operation body; a refreshed public revision never rebases it. Stored only in RelayDocument. */
data class MetadataIntent(
    val namespace: HistoryNamespace,
    val conversationId: ConversationId,
    val requestId: RequestId,
    val expectedRevision: Long,
    val title: String?,
    val bookmarked: Boolean?,
    val preparedAtMs: Long,
    val status: MetadataStatus = MetadataStatus.PENDING,
    val coreCommitted: Boolean? = null
) {
    init {
        require(preparedAtMs >= 0)
        RelayCalls.update(conversationId, requestId, expectedRevision, title, bookmarked)
    }
    fun call() = RelayCalls.update(conversationId, requestId, expectedRevision, title, bookmarked)
    val retryable: Boolean get() = status in
        setOf(MetadataStatus.PENDING, MetadataStatus.UNKNOWN, MetadataStatus.TRANSIENT)
    override fun toString(): String = "MetadataIntent($status, redacted)"
}

/** Persist before cache/ledger deletion, clear only after BOTH removals are confirmed. */
enum class EvictionKind { CACHE_ONLY, REVOKED, DELETED }
data class HistoryEviction(
    val namespace: HistoryNamespace,
    val conversationId: String?,
    val kind: EvictionKind = EvictionKind.REVOKED
) {
    override fun toString(): String = "HistoryEviction(redacted)"
}
