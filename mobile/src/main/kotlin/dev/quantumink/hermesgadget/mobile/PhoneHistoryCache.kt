package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryCapture
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryEraseReview
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import dev.quantumink.hermesgadget.protocol.relay.session.RetainedHistory
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Off-main private cache access. Construction never opens a key, starts a socket or reads history. */
internal class PhoneHistoryCache(factory: () -> EncryptedHistoryCache) {
    constructor(
        context: Context,
        directory: File? = null,
        alias: String = "quantumink.relay.phone.history.v1"
    ) : this({
        EncryptedHistoryCache(
            directory ?: File(context.applicationContext.noBackupFilesDir, "phone-history"),
            { create -> androidKey(alias, create) },
            System::currentTimeMillis
        )
    })

    private val cache by lazy(factory)
    suspend fun read(scope: HistoryScope): HistorySnapshot = withContext(Dispatchers.IO) {
        cache.read(scope)
    }
    suspend fun put(
        scope: HistoryScope,
        thread: HistoryThread,
        expectedRevision: Long,
        anchor: HistoryAnchor
    ): HistorySnapshot = withContext(Dispatchers.IO) {
        cache.put(scope, thread, expectedRevision, anchor)
    }
    suspend fun purge(
        scope: HistoryScope,
        conversationId: String?,
        expectedRevision: Long
    ): HistorySnapshot =
        withContext(Dispatchers.IO) { cache.purge(scope, conversationId, expectedRevision) }
    suspend fun setRetention(
        scope: HistoryScope,
        days: Int,
        expectedRevision: Long
    ): HistorySnapshot =
        withContext(Dispatchers.IO) { cache.setRetention(scope, days, expectedRevision) }
    suspend fun search(scope: HistoryScope, query: String, limit: Int = 50): List<HistoryThread> =
        withContext(Dispatchers.IO) { cache.search(scope, query, limit) }

    /** Only the production off-main serial controller calls this blocking adapter. */
    fun retained(): RetainedHistory = object : RetainedHistory {
        override fun read(namespace: HistoryScope): HistorySnapshot = cache.read(namespace)
        override fun capture(page: HistoryCapture): HistorySnapshot {
            if (page.listingGeneration < 1 || page.pageSerial < 1 ||
                (page.anchor == HistoryAnchor.Beginning && !page.beginsChain) ||
                page.scope.source !=
                dev.quantumink.hermesgadget.protocol.relay.state.HistorySource.RELAY ||
                page.scope.conversation.conversationId.value != page.thread.id ||
                page.scope.conversation.enrollment.let {
                    it.relay.endpointKey != page.namespace.endpointKey ||
                        it.relay.profileId.value != page.namespace.profileId ||
                        it.enrollmentKey != page.namespace.enrollmentLabel ||
                        it.reviewEpoch != page.namespace.reviewEpoch
                }
            ) {
                throw HistoryFailure(HistoryProblem.MISSING_ANCHOR)
            }
            val revision = cache.read(page.namespace).revision
            return cache.put(page.namespace, page.thread, revision, page.anchor, page.listing)
        }
        override fun evict(namespace: HistoryScope, conversationId: String?) {
            cache.purge(namespace, conversationId, cache.read(namespace).revision)
        }
        override fun absent(namespace: HistoryScope, conversationId: String?): Boolean =
            cache.absent(namespace, conversationId)
        override fun review(namespace: HistoryScope, conversationId: String?): HistoryEraseReview =
            cache.review(namespace, conversationId)
        override fun reviewRetention(namespace: HistoryScope, days: Int): HistoryEraseReview =
            cache.review(namespace, null, global = true, days = days)
        override fun erase(review: HistoryEraseReview, namespace: HistoryScope) {
            cache.erase(review, namespace)
        }
        override fun retention(namespace: HistoryScope, days: Int): HistorySnapshot =
            cache.setRetention(namespace, days, cache.read(namespace).revision)
    }

    companion object {
        private fun androidKey(alias: String, create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) {
                return store.getKey(alias, null) as? SecretKey
                    ?: error("History encryption key unavailable. Existing cache was preserved.")
            }
            check(create) { "History encryption key unavailable. Existing cache was preserved." }
            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).setRandomizedEncryptionRequired(true).build()
                )
            }.generateKey()
        }
    }
}
