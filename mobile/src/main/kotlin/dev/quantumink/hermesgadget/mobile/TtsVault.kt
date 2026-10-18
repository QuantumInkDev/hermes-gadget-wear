package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class SpeechMode { SERVER, SHARED, PROFILE }
class SpeechSettings(val mode: SpeechMode, val key: String, val voice: String) {
    init {
        require(key.length <= 256 && (key.isEmpty() || key.all { it.code in 33..126 }))
        require(voice.isEmpty() || voice.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
    }
    override fun toString(): String = "SpeechSettings(redacted)"
}

class TtsVault(
    context: Context,
    private val directory: File = File(context.filesDir, "speech-vault"),
    private val alias: String = "hermesgadget.speech.v1"
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun name(endpoint: String): String {
        require(endpoint == "shared" || endpoint.matches(Regex("[a-f0-9]{64}")))
        return endpoint + ".sealed"
    }

    fun read(endpoint: String): SpeechSettings = synchronized(lock) {
        val base = File(directory, name(endpoint))
        val backup = File(directory, name(endpoint) + ".bak")
        if (!present(base) && !present(backup)) {
            return@synchronized SpeechSettings(SpeechMode.SERVER, "", "")
        }
        // AtomicFile.openRead repairs/deletes files. Inspect without mutating corrupt evidence.
        readArtifact(if (present(backup)) backup else base, endpoint)
    }

    private fun present(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun readArtifact(file: File, endpoint: String): SpeechSettings {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val encrypted = file.inputStream().use {
            require(it.channel.size() in 30..4096)
            it.readBytes()
        }
        require(encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, 14)))
        cipher.updateAAD(name(endpoint).toByteArray())
        val bytes = cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
        try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            return SpeechSettings(
                SpeechMode.valueOf(json.getValue("mode").jsonPrimitive.content),
                json["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                json["voice"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        } finally {
            bytes.fill(0)
        }
    }

    fun save(endpoint: String, settings: SpeechSettings) = synchronized(lock) {
        val base = File(directory, name(endpoint))
        val backup = File(directory, name(endpoint) + ".bak")
        val pending = File(directory, name(endpoint) + ".new")
        val exists = present(base) || present(backup)
        require(endpoint == "shared" || exists || endpoints().size < 64)
        // startWrite may restore .bak over base and truncate .new; finish/fail also mutate .new.
        // Validate every present artifact before any of those operations, under the same lock.
        listOf(base, backup, pending).filter(::present).forEach { readArtifact(it, endpoint) }
        val bytes = buildJsonObject {
            put("mode", settings.mode.name)
            put("key", settings.key)
            put("voice", settings.voice)
        }.toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name(endpoint).toByteArray())
            val encrypted = byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(bytes)
            check(directory.exists() || directory.mkdirs())
            val file = AtomicFile(File(directory, name(endpoint)))
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (
                failure: Exception
            ) {
                file.failWrite(output)
                throw failure
            }
        } finally {
            bytes.fill(0)
        }
    }
    fun endpoints(): List<String> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isFile }.mapNotNull {
            sealedName.matchEntire(it.name)?.groupValues?.get(1)
        }.distinct().sorted()
    }

    fun selected(endpoint: String): SpeechSettings = synchronized(lock) {
        val profile = read(endpoint)
        if (profile.mode == SpeechMode.SERVER) return@synchronized profile
        val shared = read("shared")
        SpeechSettings(
            profile.mode,
            if (profile.mode ==
                SpeechMode.SHARED
            ) {
                shared.key
            } else {
                profile.key
            },
            profile.voice.ifEmpty { shared.voice }
        )
    }
    companion object {
        private val lock = Any()
        private val sealedName = Regex("([a-f0-9]{64})\\.sealed(?:\\.bak)?")
    }

    private fun key(): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(directory.listFiles().isNullOrEmpty()) { "Speech vault key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build()
            )
        }.generateKey()
    }
}
