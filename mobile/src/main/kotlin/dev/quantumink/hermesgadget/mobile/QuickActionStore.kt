package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.QuickAction
import dev.quantumink.hermesgadget.protocol.QuickActions
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private user prompt content. Independent of both phone and watch enrollment vaults. */
class QuickActionStore(
    context: Context,
    private val directory: File = File(context.filesDir, "quick-actions"),
    private val alias: String = "quantumink.relay.quick-actions.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val file get() = AtomicFile(File(directory, "actions"))
    fun load(): List<QuickAction> = synchronized(lock) {
        read() ?: QuickActions.seeds().also { write(it) }
    }
    fun save(actions: List<QuickAction>, expected: List<QuickAction>? = null) = synchronized(lock) {
        val previous = read() // Refuse to overwrite corrupt data or a missing encryption key.
        check(expected == null || previous == expected) {
            "Actions changed. Reopen to review the latest data."
        }
        write(actions.toList())
    }
    private fun read(): List<QuickAction>? {
        if (!file.baseFile.exists() && !File(directory, "actions.bak").exists()) {
            check(directory.listFiles().isNullOrEmpty()) {
                "Saved actions are missing. Existing data was preserved."
            }
            return null
        }
        val encrypted = file.openRead().use {
            require(it.channel.size() in 30..(QuickActions.MAX_BYTES + 30).toLong()) {
                "Invalid saved actions size."
            }
            it.readBytes()
        }
        require(encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12) {
            "Unsupported saved actions."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
        return try {
            QuickActions.decode(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }
    private fun write(actions: List<QuickAction>) {
        val bytes = QuickActions.encode(actions).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            check(directory.exists() || directory.mkdirs())
            val atomic = file
            val output = atomic.startWrite()
            try {
                output.write(byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(bytes))
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
            // A missing record on a later launch must not silently recreate defaults.
            File(directory, "initialized").writeBytes(byteArrayOf(1))
        } finally {
            bytes.fill(0)
        }
    }
    private fun key(): SecretKey {
        if (store.containsAlias(alias)) {
            return store.getKey(alias, null) as? SecretKey
                ?: error("Action key unavailable.")
        }
        check(directory.listFiles().isNullOrEmpty()) {
            "Action encryption key unavailable. Existing data was preserved."
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build()
            )
        }.generateKey()
    }
    companion object {
        private val lock = Any()
        private val aad = "quantum-relay|quick-actions|v1".toByteArray()
    }
}
