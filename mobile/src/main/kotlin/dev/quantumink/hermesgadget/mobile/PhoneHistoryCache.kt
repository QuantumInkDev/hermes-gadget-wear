package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        afterMessageId: String? = null
    ): HistorySnapshot = withContext(Dispatchers.IO) {
        cache.put(scope, thread, expectedRevision, afterMessageId)
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
