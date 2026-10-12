package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CatalogVault(
    context: Context,
    private val directory: File = File(context.filesDir, "catalog-vault"),
    private val alias: String = "hermesgadget.catalog.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized fun sources(): List<CatalogSource> = read(
        "sources"
    )?.let { bytes ->
        try {
            ProfileCatalog.decodeSources(
                bytes.toString(Charsets.UTF_8)
            )
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

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

    @Synchronized fun saveSources(sources: List<CatalogSource>) {
        write(
            "sources",
            ProfileCatalog.encodeSources(sources).toByteArray()
        )
    }

    /** A missing record migrates the old roster; an empty box still has an order slot. */
    @Synchronized fun sourceOrder(): List<String>? = read("source-order")?.let { bytes ->
        try {
            val item = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            require((item["version"] as? JsonPrimitive)?.content == "1")
            val order = (item["sources"] as JsonArray).map { (it as JsonPrimitive).content }
            validateOrder(order)
            order
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized fun saveSourceOrder(order: List<String>) {
        validateOrder(order)
        write(
            "source-order",
            buildJsonObject {
                put("version", 1)
                put("sources", JsonArray(order.map(::JsonPrimitive)))
            }.toString().toByteArray()
        )
    }

    private fun validateOrder(order: List<String>) {
        require(order.size <= ProfileCatalog.MAX_SOURCES + 1 && order.distinct().size == order.size)
        require(order.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
    }

    @Synchronized fun savePages(pages: List<CatalogPage>) {
        write(
            "pages",
            ProfileCatalog.encodePages(pages).toByteArray()
        )
    }

    @Synchronized fun fallbacks(): List<CatalogPage> = read("fallbacks")?.let { bytes ->
        try {
            ProfileCatalog.decodePages(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

    @Synchronized fun saveFallbacks(pages: List<CatalogPage>) {
        write("fallbacks", ProfileCatalog.encodePages(pages).toByteArray())
    }

    @Synchronized fun overrides(): List<CatalogPage> = read("watch-pictures")?.let { bytes ->
        try {
            ProfileCatalog.decodePages(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

    @Synchronized fun saveOverrides(pages: List<CatalogPage>) {
        // Overrides are keyed artwork, not an endpoint roster. A dormant manual picture may
        // share the endpoint of a discovered page without replacing either picture choice.
        val pictures = pages.map { page ->
            CatalogPage(
                page.sourceId,
                page.sourceLabel,
                CatalogProfile(page.profile.id, page.profile.name, null, page.profile.portrait),
                page.status,
                page.checkedAt
            )
        }
        write("watch-pictures", ProfileCatalog.encodePages(pictures).toByteArray())
    }

    @Synchronized fun animations(): Map<String, AnimationDescriptor> = read(
        "watch-animations"
    )?.let { bytes ->
        try {
            dev.quantumink.hermesgadget.protocol.PortraitAnimations.decode(
                bytes.toString(Charsets.UTF_8)
            )
        } finally {
            bytes.fill(0)
        }
    }.orEmpty()

    @Synchronized fun saveAnimations(animations: Map<String, AnimationDescriptor>) {
        write(
            "watch-animations",
            dev.quantumink.hermesgadget.protocol.PortraitAnimations.encode(animations).toByteArray()
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
