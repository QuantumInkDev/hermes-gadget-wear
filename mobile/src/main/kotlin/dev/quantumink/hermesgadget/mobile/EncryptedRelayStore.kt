package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.quantumink.hermesgadget.protocol.relay.session.DecodeRefusal
import dev.quantumink.hermesgadget.protocol.relay.session.DecodeResult
import dev.quantumink.hermesgadget.protocol.relay.session.EncodeResult
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.RelayStore
import dev.quantumink.hermesgadget.protocol.relay.session.SnapshotCodec
import dev.quantumink.hermesgadget.protocol.relay.session.StoreBounds
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The file operations whose failure order matters; tests inject faults here (the production values are plain `java.nio`). */
internal interface RelayFiles {
    fun move(from: File, to: File)
    fun syncDirectory(directory: File)
    fun write(file: File, bytes: ByteArray)
}

internal object RealRelayFiles : RelayFiles {
    override fun move(from: File, to: File) {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use {
            it.write(bytes)
            it.fd.sync()
        }
    }
}

/**
 * THE encrypted store of Relay state: the controller section and the complete aggregate snapshot in ONE record. It has its
 * own alias, version, AAD, size bound and commit contract; it is independent of the `ConversationVault` identity records
 * (alias `quantumink.relay.phone.identity.v1`, 256-byte format) and of the `PhoneHistoryCache` retention.
 *
 * Container (`state.v1`): `[1][12][iv(12)][sequence u64][AES-GCM ciphertext || 16-byte tag]`, at most
 * [StoreBounds.MAX_FILE_BYTES] bytes. The AAD is `hermes-gadget|phone-relay-state|container-v1|<alias>|<sequence>`, so a
 * record cannot be replayed under another alias, another format or another sequence; the IV is random per commit. The
 * plaintext is parsed ONLY after authentication ([SnapshotCodec], bounded).
 *
 * Never: generate a key while any stored byte exists, erase or reset after a failed read, replace an unreadable record by
 * an empty document, or treat a record that disappeared after initialisation as an empty store. The result of every read
 * and write is explicit ([StoreProblem], [StoreCommit]).
 *
 * Durability: `AtomicFile`/`ATOMIC_MOVE` plus an `fsync` of the file and of the directory is NOT proof of power-loss
 * durability on a real device (that is the later native gate V7). A failure after the atomic replace is reported as
 * UNCERTAIN, never as success. All methods block on disk and crypto: call them off the main thread.
 */
internal class EncryptedRelayStore(
    private val directory: File,
    private val key: (create: Boolean) -> SecretKey,
    private val alias: String = ALIAS,
    private val files: RelayFiles = RealRelayFiles
) : RelayStore {
    private val lock = locks.getOrPut(directory.absoluteFile.normalize().path) { Any() }

    override fun load(): StoreLoad = synchronized(lock) {
        when (val state = inspect()) {
            is Inspection.Absent -> StoreLoad.Empty
            is Inspection.Broken -> StoreLoad.Unreadable(state.problem)
            is Inspection.Present -> readRecord(state.record)
        }
    }

    override fun currentSequence(): Long = synchronized(lock) {
        when (val state = inspect()) {
            is Inspection.Absent -> 0L
            is Inspection.Broken -> -1L
            is Inspection.Present -> header(state.record)?.second ?: -1L
        }
    }

    override fun commit(document: RelayDocument, base: Long): StoreCommit = synchronized(lock) {
        val plaintext = when (val encoded = SnapshotCodec.encode(document)) {
            is EncodeResult.Refused -> return StoreCommit.Refused(encoded.reason, encoded.path)
            is EncodeResult.Encoded -> encoded.bytes
        }
        try {
            commitBytes(plaintext, base)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun commitBytes(plaintext: ByteArray, base: Long): StoreCommit {
        val state = inspect()
        if (state is Inspection.Broken) return StoreCommit.Failed(state.problem, false)
        val record = File(directory, RECORD)
        val onDisk = when (state) {
            is Inspection.Present -> header(state.record)?.second
                ?: return StoreCommit.Failed(StoreProblem.CORRUPT, false)
            else -> 0L
        }
        if (onDisk != base) return StoreCommit.Stale
        val fresh = state is Inspection.Absent
        val secret = try {
            // A key may be created only for a store that has never held a byte.
            key(fresh && !File(directory, MARKER).exists())
        } catch (_: Exception) {
            return StoreCommit.Failed(StoreProblem.KEY_UNAVAILABLE, false)
        }
        val next = base + 1
        val sealed = try {
            seal(secret, plaintext, next)
        } catch (_: GeneralSecurityException) {
            return StoreCommit.Failed(StoreProblem.KEY_UNAVAILABLE, false)
        }
        if (sealed.size > StoreBounds.MAX_FILE_BYTES) {
            return StoreCommit.Failed(StoreProblem.TOO_LARGE, false)
        }
        val pending = File(directory, PENDING)
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                return StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
            }
            check(!Files.isSymbolicLink(pending.toPath())) { "Invalid relay staging record." }
            files.write(pending, sealed)
            // The staged record must authenticate and decode BEFORE it may replace the committed one.
            check(verifies(pending, secret, next)) { "Staged relay record did not verify." }
        } catch (_: Exception) {
            pending.delete()
            return StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
        }
        try {
            files.move(pending, record)
        } catch (_: Exception) {
            // Atomic replace: either the old record is still in place and unchanged, or the outcome is unknown.
            val unchanged = pending.exists() && when (state) {
                is Inspection.Present -> header(record)?.second == onDisk &&
                    sameBytes(record, state)
                else -> !record.exists()
            }
            if (unchanged) pending.delete()
            return StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, !unchanged)
        }
        try {
            if (!File(directory, MARKER).exists()) {
                files.write(File(directory, MARKER), byteArrayOf(1))
            }
            files.syncDirectory(directory)
        } catch (_: Exception) {
            // The new record is in place but its durability is unproven: never reported as success.
            return StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, true)
        }
        return StoreCommit.Committed(next)
    }

    private fun sameBytes(record: File, state: Inspection.Present): Boolean =
        record.length() == state.size

    private sealed interface Inspection {
        data object Absent : Inspection
        class Present(val record: File, val size: Long) : Inspection
        class Broken(val problem: StoreProblem) : Inspection
    }

    private fun inspect(): Inspection {
        if (!directory.exists()) return Inspection.Absent
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) {
            return Inspection.Broken(StoreProblem.STORAGE_UNAVAILABLE)
        }
        val record = File(directory, RECORD)
        val marker = File(directory, MARKER)
        if (!record.exists()) {
            val others = directory.listFiles()?.map { it.name }?.filter { it != PENDING }
                ?: return Inspection.Broken(StoreProblem.STORAGE_UNAVAILABLE)
            // Initialised, or unknown files, but no record: that is lost data, not an empty store.
            return if (marker.exists() || others.isNotEmpty()) {
                Inspection.Broken(StoreProblem.RECORD_MISSING)
            } else {
                Inspection.Absent
            }
        }
        if (Files.isSymbolicLink(record.toPath()) || !record.isFile) {
            return Inspection.Broken(StoreProblem.STORAGE_UNAVAILABLE)
        }
        return Inspection.Present(record, record.length())
    }

    /** Version, IV length and sequence from the 22 clear header bytes (the sequence is authenticated by the AAD). */
    private fun header(record: File): Pair<Int, Long>? = try {
        FileInputStream(record).use { input ->
            val head = ByteArray(HEADER)
            var read = 0
            while (read < HEAD_READ) {
                val count = input.read(head, read, HEAD_READ - read)
                if (count < 0) break
                read += count
            }
            if (read < HEAD_READ || head[0].toInt() != VERSION || head[1].toInt() != IV_BYTES) {
                null
            } else {
                head[0].toInt() to ByteBuffer.wrap(head, 2 + IV_BYTES, 8).long
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readRecord(record: File): StoreLoad {
        val size = record.length()
        if (size > StoreBounds.MAX_FILE_BYTES) return StoreLoad.Unreadable(StoreProblem.TOO_LARGE)
        if (size < HEADER + TAG_BYTES) return StoreLoad.Unreadable(StoreProblem.CORRUPT)
        val raw = try {
            FileInputStream(record).use { it.readNBytes(size.toInt()) }
        } catch (_: Exception) {
            return StoreLoad.Unreadable(StoreProblem.STORAGE_UNAVAILABLE)
        }
        if (raw.size.toLong() != size) return StoreLoad.Unreadable(StoreProblem.STORAGE_UNAVAILABLE)
        if (raw[0].toInt() != VERSION || raw[1].toInt() != IV_BYTES) {
            return StoreLoad.Unreadable(StoreProblem.UNSUPPORTED_VERSION)
        }
        val secret = try {
            key(false)
        } catch (_: Exception) {
            return StoreLoad.Unreadable(StoreProblem.KEY_UNAVAILABLE)
        }
        val sequence = ByteBuffer.wrap(raw, 2 + IV_BYTES, 8).long
        val plaintext = try {
            open(secret, raw, sequence)
        } catch (_: AEADBadTagException) {
            return StoreLoad.Unreadable(StoreProblem.CORRUPT)
        } catch (_: GeneralSecurityException) {
            return StoreLoad.Unreadable(StoreProblem.KEY_UNAVAILABLE)
        }
        return try {
            when (val decoded = SnapshotCodec.decode(plaintext)) {
                is DecodeResult.Decoded -> StoreLoad.Loaded(decoded.document, sequence)
                is DecodeResult.Rejected -> StoreLoad.Unreadable(
                    if (decoded.reason == DecodeRefusal.UNSUPPORTED_VERSION) {
                        StoreProblem.UNSUPPORTED_VERSION
                    } else {
                        StoreProblem.CORRUPT
                    }
                )
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun verifies(file: File, secret: SecretKey, sequence: Long): Boolean {
        val raw = FileInputStream(file).use { it.readNBytes(StoreBounds.MAX_FILE_BYTES + 1) }
        if (raw.size > StoreBounds.MAX_FILE_BYTES) return false
        val plaintext = try {
            open(secret, raw, sequence)
        } catch (_: GeneralSecurityException) {
            return false
        }
        return try {
            SnapshotCodec.decode(plaintext) is DecodeResult.Decoded
        } finally {
            plaintext.fill(0)
        }
    }

    private fun aad(sequence: Long): ByteArray {
        val head = "hermes-gadget|phone-relay-state|container-v1|$alias|".toByteArray(
            Charsets.UTF_8
        )
        return head + ByteBuffer.allocate(8).putLong(sequence).array()
    }

    private fun seal(secret: SecretKey, plaintext: ByteArray, sequence: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        check(cipher.iv.size == IV_BYTES) { "Unsupported relay state encryption." }
        cipher.updateAAD(aad(sequence))
        val body = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION.toByte(), IV_BYTES.toByte()) + cipher.iv +
            ByteBuffer.allocate(8).putLong(sequence).array() + body
    }

    private fun open(secret: SecretKey, raw: ByteArray, sequence: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret,
            GCMParameterSpec(TAG_BYTES * 8, raw, 2, IV_BYTES)
        )
        cipher.updateAAD(aad(sequence))
        return cipher.doFinal(raw, HEADER, raw.size - HEADER)
    }

    companion object {
        const val ALIAS = "quantumink.relay.phone.state.v1"
        const val DIRECTORY = "phone-relay-state"
        private const val RECORD = "state.v1"
        private const val PENDING = "state.pending"
        private const val MARKER = "initialized"
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val HEADER = 2 + IV_BYTES + 8
        private const val HEAD_READ = HEADER
        private val locks = ConcurrentHashMap<String, Any>()

        /** The production store: private no-backup directory, its own AndroidKeyStore key. Construction touches nothing. */
        fun android(
            context: Context,
            directory: File? = null,
            alias: String = ALIAS
        ): EncryptedRelayStore = EncryptedRelayStore(
            directory ?: File(context.applicationContext.noBackupFilesDir, DIRECTORY),
            { create -> androidKey(alias, create) },
            alias
        )

        private fun androidKey(alias: String, create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) {
                return store.getKey(alias, null) as? SecretKey
                    ?: error("Relay state key unavailable. Existing state was preserved.")
            }
            check(create) { "Relay state key unavailable. Existing state was preserved." }
            return javax.crypto.KeyGenerator.getInstance(
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
