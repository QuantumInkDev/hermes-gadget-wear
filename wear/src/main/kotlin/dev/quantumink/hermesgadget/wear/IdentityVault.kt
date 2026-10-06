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

typealias EndpointProfile = dev.quantumink.hermesgadget.protocol.EndpointProfile

/** At-rest encryption for settings and independent endpoint identities; all failures are explicit. */
class IdentityVault(
    context: Context,
    private val directory: File = File(context.filesDir, "gadget-vault"),
    private val alias: String = "hermesgadget.vault.v1"
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun activeRecord(): EndpointProfile? {
        val bytes = read("endpoint") ?: return null
        try {
            return EndpointProfile.parse(
                requireNotNull(Message.parse(bytes.toString(Charsets.UTF_8)))
            )
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized fun profile(): EndpointProfile? {
        val active = activeRecord()
        val bytes = read("catalog") ?: return active
        try {
            val catalog = dev.quantumink.hermesgadget.protocol.EndpointProfiles.decode(
                bytes.toString(Charsets.UTF_8)
            )
            return catalog.firstOrNull { it.endpoint.url == active?.endpoint?.url }
                ?: catalog.firstOrNull()
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized fun profiles(): List<EndpointProfile> {
        val bytes = read("catalog")
        if (bytes != null) {
            try {
                return dev.quantumink.hermesgadget.protocol.EndpointProfiles.decode(
                    bytes.toString(Charsets.UTF_8)
                )
            } finally {
                bytes.fill(0)
            }
        }
        val previous = profile() ?: return emptyList()
        identity(previous.endpoint) // Validate the existing key before migration.
        val imported = listOf(previous)
        writeCatalog(imported)
        return imported
    }

    @Synchronized fun save(profile: EndpointProfile): DeviceIdentity {
        val previous = profiles()
        val known = previous.any { it.endpoint.url == profile.endpoint.url }
        val updated = if (known) {
            previous.map {
                if (it.endpoint.url == profile.endpoint.url) profile else it
            }
        } else {
            previous + profile
        }
        val encoded = dev.quantumink.hermesgadget.protocol.EndpointProfiles.encode(updated)
        val identity = identity(profile.endpoint, create = !known)
        write("catalog", encoded.toByteArray())
        write("endpoint", profile.message().encode().toByteArray())
        return identity
    }

    @Synchronized fun select(url: String): Pair<EndpointProfile, DeviceIdentity> {
        val selected = profiles().single { it.endpoint.url == url }
        val identity = identity(selected.endpoint)
        write("endpoint", selected.message().encode().toByteArray())
        return selected to identity
    }

    @Synchronized fun remove(url: String): Pair<EndpointProfile, DeviceIdentity>? {
        val previous = profiles()
        require(previous.any { it.endpoint.url == url })
        val remaining = previous.filterNot { it.endpoint.url == url }
        val selected = if (profile()?.endpoint?.url == url) remaining.firstOrNull() else profile()
        val identity = selected?.let { identity(it.endpoint) }
        writeCatalog(remaining)
        if (selected == null) {
            AtomicFile(File(directory, "endpoint")).delete()
        } else {
            write("endpoint", selected.message().encode().toByteArray())
        }
        return selected?.let { it to requireNotNull(identity) }
    }

    private fun writeCatalog(profiles: List<EndpointProfile>) = write(
        "catalog",
        dev.quantumink.hermesgadget.protocol.EndpointProfiles.encode(profiles).toByteArray()
    )

    @Synchronized fun identity(endpoint: Endpoint, create: Boolean = false): DeviceIdentity {
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.url.toByteArray())
        val id = digest.joinToString("") { "%02x".format(it) }
        val name = "identity-" + id
        val known = read("known-" + id) != null
        val existing = read(name)
        if (existing != null) {
            try {
                val saved = requireNotNull(
                    DeviceIdentity.fromBase64(existing.toString(Charsets.UTF_8))
                ) {
                    "Saved identity is invalid."
                }
                if (!known) write("known-" + id, byteArrayOf(1))
                return saved
            } finally {
                existing.fill(0)
            }
        }
        check(create && !known) { "Saved device identity is missing." }
        val identity = DeviceIdentity.generate()
        write(name, identity.enrollmentKey().toByteArray())
        write("known-" + id, byteArrayOf(1))
        return identity
    }

    /** Called only after an explicit reset confirmation. Existing host enrollment is unaffected. */
    @Synchronized fun reset() {
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
            require(input.channel.size() in 30..65566) { "Saved record has invalid size." }
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
            require(plaintext.size <= 65536) { "Saved record is too large." }
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
