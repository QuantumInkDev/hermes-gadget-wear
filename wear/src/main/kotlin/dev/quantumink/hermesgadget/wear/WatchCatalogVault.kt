package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WatchCatalogVault(
    context: Context,
    private val directory: File = File(context.filesDir, "watch-catalog-vault"),
    private val alias: String = "hermesgadget.watch.catalog.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized fun pages(): List<CatalogPage> = read(
        "pages"
    )?.let { bytes ->
        try {
            ProfileCatalog.decodePages(
                bytes.toString(Charsets.UTF_8)
            )
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

    @Synchronized fun savePages(pages: List<CatalogPage>) {
        write(
            "pages",
            ProfileCatalog.encodePages(pages).toByteArray()
        )
    }

    @Synchronized fun selected(): String = read("selected")?.let { bytes ->
        try {
            bytes.toString(Charsets.UTF_8).also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}")))
            }
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

    @Synchronized fun select(key: String) {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}")))
        write("selected", key.toByteArray())
    }

    @Synchronized fun reset() {
        if (directory.exists()) check(directory.deleteRecursively())
        if (store.containsAlias(alias)) store.deleteEntry(alias)
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
