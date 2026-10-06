package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.EndpointProfiles
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileVault(
    context: Context,
    private val directory: File = File(context.filesDir, "profile-vault"),
    private val alias: String = "hermesgadget.profiles.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized fun profiles(): List<EndpointProfile> {
        val bytes = read("catalog") ?: return emptyList()
        try {
            return EndpointProfiles.decode(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized fun save(profile: EndpointProfile) {
        val previous = profiles()
        val updated = if (previous.any { it.endpoint.url == profile.endpoint.url }) {
            previous.map {
                if (it.endpoint.url == profile.endpoint.url) profile else it
            }
        } else {
            previous + profile
        }
        write("catalog", EndpointProfiles.encode(updated).toByteArray())
    }

    @Synchronized fun remove(url: String) {
        write(
            "catalog",
            EndpointProfiles.encode(
                profiles().filterNot {
                    it.endpoint.url == url
                }
            ).toByteArray()
        )
    }
    private fun key(): SecretKey {
        if (store.containsAlias(alias)) {
            return store.getKey(alias, null) as? SecretKey
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
