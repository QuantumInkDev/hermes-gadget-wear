package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Phone enrollment only. Never opens the watch or endpoint-setup vaults. */
class ConversationVault(
    context: Context,
    private val directory: File = File(context.filesDir, "phone-conversations"),
    private val alias: String = "quantumink.relay.phone.identity.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized fun identity(endpoint: Endpoint, create: Boolean = false): DeviceIdentity {
        require(endpoint.isTls) { "Phone conversations require TLS (wss)." }
        val id = MessageDigest.getInstance("SHA-256").digest(endpoint.url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val known = read("known-" + id)?.let {
            try {
                require(it.contentEquals(byteArrayOf(1)))
                true
            } finally {
                it.fill(0)
            }
        } ?: false
        val previous = read("identity-" + id)
        if (previous != null) {
            try {
                val identity =
                    requireNotNull(DeviceIdentity.fromBase64(previous.toString(Charsets.UTF_8)))
                if (!known) write("known-" + id, byteArrayOf(1))
                return identity
            } finally {
                previous.fill(0)
            }
        }
        check(create && !known) { "Phone identity is missing. Existing enrollment was preserved." }
        require(directory.listFiles().orEmpty().count { it.name.startsWith("identity-") } < 32) {
            "At most 32 phone enrollments are supported."
        }
        val identity = DeviceIdentity.generate()
        write("identity-" + id, identity.enrollmentKey().toByteArray())
        write("known-" + id, byteArrayOf(1))
        return identity
    }

    private fun key(): SecretKey {
        if (store.containsAlias(alias)) {
            return store.getKey(alias, null) as? SecretKey
                ?: error("Stored phone encryption key is unavailable.")
        }
        check(directory.listFiles().isNullOrEmpty()) {
            "Stored phone encryption key is unavailable."
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

    private fun read(name: String): ByteArray? {
        val file = AtomicFile(File(directory, name))
        if (!file.baseFile.exists() && !File(directory, name + ".bak").exists()) return null
        val encrypted = file.openRead().use {
            require(it.channel.size() in 30..286) { "Saved phone identity has invalid size." }
            it.readBytes()
        }
        require(encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        cipher.updateAAD((name + "|phone-v1").toByteArray())
        return cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
    }

    private fun write(name: String, bytes: ByteArray) {
        try {
            require(bytes.size <= 256)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD((name + "|phone-v1").toByteArray())
            check(directory.exists() || directory.mkdirs())
            val file = AtomicFile(File(directory, name))
            val output = file.startWrite()
            try {
                output.write(byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(bytes))
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } finally {
            bytes.fill(0)
        }
    }
}
