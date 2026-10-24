package dev.quantumink.hermesgadget.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.quantumink.hermesgadget.protocol.relay.session.InboxCodec
import dev.quantumink.hermesgadget.protocol.relay.session.InboxFailure
import dev.quantumink.hermesgadget.protocol.relay.session.InboxFile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProblem
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.InboxWrite
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Display-only cache. Existing bytes always require the existing key and a valid marker.
 * Promotion uncertainty is never a confirmed copy. Blocking: the application serial worker owns
 * every call.
 */
internal class EncryptedInboxFile(
    private val directory: File,
    private val key: (Boolean) -> SecretKey,
    private val files: RelayFiles = RealRelayFiles
) : InboxFile {
    private val lock = locks.getOrPut(directory.absoluteFile.normalize().path) { Any() }
    private fun problem(p: InboxProblem): Nothing = throw InboxFailure(p)
    private fun absent(): Boolean {
        if (!directory.exists()) return true
        if (!directory.isDirectory ||
            Files.isSymbolicLink(directory.toPath())
        ) {
            problem(InboxProblem.STORAGE)
        }
        val entries = directory.listFiles() ?: problem(InboxProblem.STORAGE)
        if (entries.isEmpty()) return true
        val record = File(directory, RECORD)
        val marker = File(directory, MARKER)
        if (!record.isFile || !marker.isFile) problem(InboxProblem.MISSING)
        if (entries.any {
                it.name !in setOf(RECORD, MARKER) || Files.isSymbolicLink(it.toPath())
            }
        ) {
            problem(InboxProblem.STORAGE)
        }
        if (marker.length() != 1L ||
            !marker.readBytes().contentEquals(byteArrayOf(1))
        ) {
            problem(InboxProblem.CORRUPT)
        }
        return false
    }
    override fun load(): InboxSnapshot = synchronized(lock) {
        try {
            if (absent()) InboxSnapshot() else read()
        } catch (
            e: InboxFailure
        ) {
            throw e
        } catch (_: Exception) {
            problem(InboxProblem.STORAGE)
        }
    }
    private fun secret(create: Boolean): SecretKey = try {
        key(create)
    } catch (_: Exception) {
        problem(InboxProblem.KEY)
    }
    private fun aad(revision: Long) =
        "hermes-gadget|phone-inbox|container1|$ALIAS|$revision".toByteArray()
    private fun read(): InboxSnapshot {
        val record = File(directory, RECORD)
        if (record.length() !in 46L..(InboxCodec.MAX_BYTES + 38L)) problem(InboxProblem.CORRUPT)
        val raw = record.inputStream().use { input ->
            val bytes = ByteArray(record.length().toInt())
            var offset = 0
            while (offset < bytes.size) {
                val n = input.read(bytes, offset, bytes.size - offset)
                if (n < 0) problem(InboxProblem.STORAGE)
                offset += n
            }
            if (input.read() != -1) problem(InboxProblem.STORAGE)
            bytes
        }
        if (raw.size.toLong() != record.length()) problem(InboxProblem.STORAGE)
        if (raw[0] != 1.toByte() || raw[1] != 12.toByte()) problem(InboxProblem.UNSUPPORTED)
        val revision = ByteBuffer.wrap(raw, 14, 8).long
        if (revision <= 0) problem(InboxProblem.CORRUPT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = secret(false)
        val plain = try {
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, raw.copyOfRange(2, 14)))
            cipher.updateAAD(aad(revision))
            cipher.doFinal(raw, 22, raw.size - 22)
        } catch (_: Exception) {
            problem(InboxProblem.CORRUPT)
        }
        return try {
            InboxCodec.decode(plain).also {
                if (it.revision !=
                    revision
                ) {
                    problem(InboxProblem.CORRUPT)
                }
            }
        } finally {
            plain.fill(0)
            raw.fill(0)
        }
    }
    override fun commit(next: InboxSnapshot, base: Long): InboxWrite = synchronized(lock) {
        val plain = try {
            InboxCodec.encode(next)
        } catch (
            _: InboxFailure
        ) {
            return InboxWrite.Refused
        }
        try {
            val fresh = absent()
            val old = if (fresh) 0L else read().revision
            if (old != base) return InboxWrite.Stale
            if (base == Long.MAX_VALUE || next.revision != base + 1) return InboxWrite.Refused
            val secret = secret(fresh)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(aad(next.revision))
            val raw = ByteBuffer.allocate(22 + plain.size + 16).put(1).put(12).put(cipher.iv)
                .putLong(next.revision).put(cipher.doFinal(plain)).array()
            if (!directory.exists() && !directory.mkdirs()) return InboxWrite.Failed(false)
            val pending = File(directory, PENDING)
            var promoted = false
            try {
                files.write(pending, raw)
                files.move(pending, File(directory, RECORD))
                promoted = true
                if (fresh) files.write(File(directory, MARKER), byteArrayOf(1))
                files.syncDirectory(directory)
            } catch (_: Exception) {
                // Do not guess an outcome or delete a potentially promoted recovery record.
                return InboxWrite.Failed(promoted || !pending.exists())
            } finally {
                raw.fill(0)
            }
            InboxWrite.Committed
        } catch (_: Exception) {
            InboxWrite.Failed(false)
        } finally {
            plain.fill(0)
        }
    }
    companion object {
        private const val ALIAS = "quantumink.relay.phone.inbox.v1"
        private const val RECORD = "inbox.v1"
        private const val MARKER = "initialized"
        private const val PENDING = "inbox.pending"
        private val locks = ConcurrentHashMap<String, Any>()
        fun create(context: Context): EncryptedInboxFile = EncryptedInboxFile(
            File(context.noBackupFilesDir, "phone-inbox"),
            { create ->
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (store.getKey(ALIAS, null) as? SecretKey) ?: run {
                    check(create) { "Existing inbox key unavailable" }
                    KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore"
                    ).apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or
                                    KeyProperties.PURPOSE_DECRYPT
                            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(
                                    KeyProperties.ENCRYPTION_PADDING_NONE
                                ).setKeySize(256).build()
                        )
                    }.generateKey()
                }
            }
        )
    }
}
