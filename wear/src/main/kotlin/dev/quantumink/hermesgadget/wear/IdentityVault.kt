package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.Message
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EndpointProfile(val endpoint: Endpoint, val label: String, val accessToken: String) {
    init {
        require(label.isNotBlank() && label.length <= 32) { "Name must be 1–32 characters." }
        require(accessToken.length <= 2048) { "Access token is too long." }
    }
    override fun toString(): String = "EndpointProfile(content=redacted)"
}

/** At-rest encryption for settings and independent endpoint identities; all failures are explicit. */
class IdentityVault(
    context: Context,
    private val directory: File = File(context.filesDir, "gadget-vault"),
    private val alias: String = "hermesgadget.vault.v1"
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun profile(): EndpointProfile? {
        val bytes = read("endpoint") ?: return null
        try {
            val message = Message.parse(bytes.toString(Charsets.UTF_8))
                ?: error("Saved setup is invalid.")
            require(message.type == "endpoint")
            val endpoint = Endpoint.parse(
                requireNotNull(message.string("url")),
                requireNotNull(message.boolean("private")),
                requireNotNull(message.boolean("cleartext"))
            )
            return EndpointProfile(
                endpoint,
                requireNotNull(message.string("label")),
                message.string("token").orEmpty()
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun save(profile: EndpointProfile): DeviceIdentity {
        val identity = identity(
            profile.endpoint,
            create =
            this.profile()?.endpoint?.url != profile.endpoint.url
        )
        val message = Message.create(
            "endpoint",
            buildJsonObject {
                put("url", profile.endpoint.url)
                put("private", profile.endpoint.privateNetwork)
                put("cleartext", profile.endpoint.cleartextAccepted)
                put("label", profile.label)
                put("token", profile.accessToken)
            }
        )
        write("endpoint", message.encode().toByteArray())
        return identity
    }

    fun identity(endpoint: Endpoint, create: Boolean = false): DeviceIdentity {
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.url.toByteArray())
        val name = "identity-" + digest.joinToString("") { "%02x".format(it) }
        val existing = read(name)
        if (existing != null) {
            try {
                return requireNotNull(
                    DeviceIdentity.fromBase64(existing.toString(Charsets.UTF_8))
                ) {
                    "Saved identity is invalid."
                }
            } finally {
                existing.fill(0)
            }
        }
        check(create) { "Saved device identity is missing." }
        val identity = DeviceIdentity.generate()
        write(name, identity.enrollmentKey().toByteArray())
        return identity
    }

    /** Called only after an explicit reset confirmation. Existing host enrollment is unaffected. */
    fun reset() {
        if (directory.exists()) check(directory.deleteRecursively()) { "Cannot reset saved setup." }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as? SecretKey
                ?: error("Stored encryption key is unavailable.")
        }
        check(directory.listFiles().isNullOrEmpty()) { "Stored encryption key is unavailable." }
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

    private fun read(name: String): ByteArray? {
        val file = AtomicFile(File(directory, name))
        if (!file.baseFile.exists() && !File(directory, name + ".bak").exists()) return null
        val encrypted = file.openRead().use { input ->
            require(input.channel.size() in 30..16384) { "Saved record has invalid size." }
            input.readBytes()
        }
        require(encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12) {
            "Unsupported saved record."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        cipher.updateAAD((name + "|v1").toByteArray())
        return cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
    }

    private fun write(name: String, plaintext: ByteArray) {
        try {
            require(plaintext.size <= 8192) { "Saved record is too large." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD((name + "|v1").toByteArray())
            val iv = cipher.iv
            require(iv.size == 12)
            val encrypted = byteArrayOf(1, 12) + iv + cipher.doFinal(plaintext)
            check(directory.exists() || directory.mkdirs()) { "Cannot create private storage." }
            val file = AtomicFile(File(directory, name))
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } finally {
            plaintext.fill(0)
        }
    }
}
