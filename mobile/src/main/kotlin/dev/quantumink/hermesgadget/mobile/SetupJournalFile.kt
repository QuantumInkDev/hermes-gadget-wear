package dev.quantumink.hermesgadget.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One encrypted, atomically replaced import journal. It holds pre-import settings (private host
 * names, prompts and picture choices), never access tokens or catalog credentials. A record
 * that cannot be opened is reported, never silently replaced.
 */
internal class SetupJournalFile(private val directory: File, private val alias: String) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val file get() = AtomicFile(File(directory, NAME))

    fun exists(): Boolean = file.baseFile.exists() || File(directory, "$NAME.bak").exists()

    /** Null when absent; throws when present but unreadable (fail closed). */
    fun read(): String? {
        if (!exists()) return null
        val encrypted = file.openRead().use { input ->
            require(input.channel.size() in 30..(MAX_BYTES + 30).toLong())
            input.readBytes()
        }
        require(encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12)
        val key = store.getKey(alias, null) as? SecretKey ?: error("Journal key unavailable.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(encrypted, 14, encrypted.size - 14)
        return try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    fun write(text: String) {
        val plaintext = text.toByteArray(Charsets.UTF_8)
        try {
            require(plaintext.size <= MAX_BYTES) { "Import journal is too large." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val iv = cipher.iv
            require(iv.size == 12)
            val encrypted = byteArrayOf(1, 12) + iv + cipher.doFinal(plaintext)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create private storage." }
            val atomic = file
            val output = atomic.startWrite()
            try {
                output.write(encrypted)
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun delete() {
        file.delete()
    }

    private fun key(): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // Never mint a new key while an old record exists; it could not be opened again.
        check(!exists()) { "Journal key unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        /** Cheap startup check without Keystore access. */
        fun present(directory: File): Boolean =
            File(directory, NAME).exists() || File(directory, "$NAME.bak").exists()

        private const val NAME = "import-journal"
        const val MAX_BYTES = 2 * 1024 * 1024
        private val AAD = "quantum-relay|setup-import-journal|v1".toByteArray()
    }
}
