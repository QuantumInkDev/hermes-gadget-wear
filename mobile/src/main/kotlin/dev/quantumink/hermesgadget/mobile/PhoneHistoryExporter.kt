package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.HistoryArchive
import dev.quantumink.hermesgadget.protocol.HistoryArchiveConversation
import dev.quantumink.hermesgadget.protocol.HistoryArchiveMessage
import dev.quantumink.hermesgadget.protocol.HistoryArchiveRole
import dev.quantumink.hermesgadget.protocol.HistoryArchiveSnapshot
import dev.quantumink.hermesgadget.protocol.HistoryArchiveSource
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reviewed immutable selection, with thread-safe invalidation on navigation/edit/purge. */
internal class HistoryExportReview(
    val scope: HistoryScope,
    val expectedRevision: Long,
    conversationIds: List<String>,
    val sourceLabel: String
) {
    val conversationIds: List<String>
    init {
        require(expectedRevision >= 0)
        val owned = ownedHistoryItems(conversationIds, 256)
        require(owned.isNotEmpty() && owned.size == owned.toSet().size)
        this.conversationIds = owned
    }
    private val active = AtomicBoolean(true)
    fun cancel() {
        active.set(false)
    }
    fun current(): Boolean = active.get()
    override fun toString(): String = "HistoryExportReview(redacted)"
}

/** All bytes accepted/flushed, not a document-provider close/commit acknowledgment. */
internal class HistoryExportWritten(val conversations: Int, val messages: Int) {
    override fun toString(): String = "HistoryExportWritten(counts-only)"
}

/**
 * Explicit selected-history export. No chooser, connection, send, import or default-all behavior.
 * UI must cancel its review on replacement/hide/edit and close the provider before showing Saved.
 * Failed or cancelled output can contain ciphertext already accepted by the caller's stream.
 */
internal class PhoneHistoryExporter(
    private val cache: PhoneHistoryCache,
    private val encode: (HistoryArchiveSnapshot, CharArray) -> ByteArray = HistoryArchive::encrypt
) {
    suspend fun write(
        review: HistoryExportReview,
        passphrase: CharArray,
        output: OutputStream
    ): HistoryExportWritten {
        // Copy before the first suspension; UI retains ownership of its original password buffer.
        require(passphrase.size in 1..MigrationArchive.MAX_PASSPHRASE_CHARS)
        val ownedPassword = passphrase.copyOf()
        try {
            return withContext(Dispatchers.IO) {
                live(review)
                val snapshot = reviewed(review)
                val indexed = snapshot.threads.associateBy { it.id }
                val selected = review.conversationIds.map {
                    checkNotNull(indexed[it]) { "Selected history is unavailable. Review again." }
                }
                val source = HistoryArchiveSource(
                    review.scope.serverId,
                    review.scope.profileId,
                    review.sourceLabel
                )
                val document =
                    HistoryArchiveSnapshot(
                        System.currentTimeMillis(),
                        selected.map { thread ->
                            HistoryArchiveConversation(
                                source,
                                thread.id,
                                thread.publicRevision,
                                thread.title,
                                thread.bookmarked,
                                thread.messages.map {
                                    val role = when (it.role) {
                                        HistoryRole.USER -> HistoryArchiveRole.USER
                                        HistoryRole.ASSISTANT -> HistoryArchiveRole.ASSISTANT
                                    }
                                    HistoryArchiveMessage(it.id, role, it.text, it.createdAtMs)
                                }
                            )
                        }
                    )
                live(review)
                val envelope = encode(document, ownedPassword)
                try {
                    live(review)
                    reviewed(review)
                    live(review)
                    var offset = 0
                    while (offset < envelope.size) {
                        live(review)
                        val count = minOf(65536, envelope.size - offset)
                        output.write(envelope, offset, count)
                        offset += count
                    }
                    live(review)
                    output.flush()
                    live(review)
                    reviewed(review)
                    live(review)
                    HistoryExportWritten(selected.size, selected.sumOf { it.messages.size })
                } finally {
                    envelope.fill(0)
                }
            }
        } finally {
            ownedPassword.fill('\u0000')
        }
    }

    private suspend fun reviewed(review: HistoryExportReview): HistorySnapshot =
        cache.read(review.scope).also {
            check(it.revision == review.expectedRevision) {
                "History changed. Review export again."
            }
        }

    private suspend fun live(review: HistoryExportReview) {
        currentCoroutineContext().ensureActive()
        check(review.current()) { "Export review changed. Review again." }
    }
}
