package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Public provenance for a user-selected export. No endpoints, devices, grants or credentials. */
data class HistoryArchiveSource(val serverId: String, val profileId: String, val label: String) {
    override fun toString(): String = "HistoryArchiveSource(redacted)"
}
enum class HistoryArchiveRole { USER, ASSISTANT }
data class HistoryArchiveMessage(
    val id: String,
    val role: HistoryArchiveRole,
    val text: String,
    val createdAtMs: Long
) {
    override fun toString(): String = "HistoryArchiveMessage(redacted)"
}
class HistoryArchiveConversation(
    val source: HistoryArchiveSource,
    val id: String,
    val publicRevision: Long,
    val title: String,
    val bookmarked: Boolean,
    messages: List<HistoryArchiveMessage>
) {
    val messages: List<HistoryArchiveMessage> = ownedItems(messages)
    override fun toString(): String = "HistoryArchiveConversation(redacted)"
}
class HistoryArchiveSnapshot(
    val exportedAtMs: Long,
    conversations: List<HistoryArchiveConversation>
) {
    val conversations: List<HistoryArchiveConversation> = ownedItems(conversations)
    override fun toString(): String = "HistoryArchiveSnapshot(redacted)"
}
sealed interface HistoryArchiveReadResult {
    /** Readable content, never enrollment, server authority or permission to import/send. */
    class Success internal constructor(val snapshot: HistoryArchiveSnapshot) :
        HistoryArchiveReadResult {
        override fun toString(): String = "HistoryArchiveReadResult.Success(redacted)"
    }

    /** Shared by wrong passphrases, tamper, unsupported format, malformed input and I/O failure. */
    data object Invalid : HistoryArchiveReadResult
}

/**
 * Android-free text-only portable export; IO worker only. No plaintext file or key persistence.
 * Caller owns streams and password; do not mutate them during a call. Immutable text and JCA
 * internals cannot be guaranteed erased. Decoding follows complete GCM authentication.
 */
object HistoryArchive {
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = MAX_PAYLOAD_BYTES + 68
    const val KDF_ITERATIONS = 600000
    private const val HEADER = 52
    private const val TAG = 16
    private const val MAX_TEXT = 256 * 1024
    private const val MAX_TIME = 9_007_199_254_740_991L
    private val magic = "QRHIST01".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(snapshot: HistoryArchiveSnapshot, passphrase: CharArray): ByteArray {
        MigrationArchive.requirePassphrase(passphrase)
        val payload = encode(snapshot)
        try {
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(HEADER).put(magic).put(1).put(1).put(1).put(0)
                .putInt(KDF_ITERATIONS).putInt(payload.size).putInt(payload.size + TAG)
                .put(salt).put(nonce).array()
            val key = MigrationArchive.deriveKey(passphrase, salt, KDF_ITERATIONS)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonce)
                )
                cipher.updateAAD(header)
                return header + cipher.doFinal(payload)
            } finally {
                key.fill(0)
            }
        } finally {
            payload.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): HistoryArchiveReadResult = try {
        require(envelope.size <= MAX_ENVELOPE_BYTES)
        read(ByteArrayInputStream(envelope), passphrase)
    } catch (_: Exception) {
        HistoryArchiveReadResult.Invalid
    }

    fun read(input: InputStream, passphrase: CharArray): HistoryArchiveReadResult = try {
        MigrationArchive.requirePassphrase(passphrase)
        val header = input.exact(HEADER)
        val bodyLength = validateHeader(header)
        val encrypted = input.exact(bodyLength)
        require(input.read() == -1)
        val key = MigrationArchive.deriveKey(passphrase, header.copyOfRange(24, 40), KDF_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(40, 52))
            )
            cipher.updateAAD(header)
            val plaintext = cipher.doFinal(encrypted)
            try {
                require(plaintext.size == ByteBuffer.wrap(header).getInt(16))
                HistoryArchiveReadResult.Success(decode(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    } catch (_: Exception) {
        HistoryArchiveReadResult.Invalid
    }

    /** Caller must finish/close its document successfully before displaying Saved. */
    fun writeEncrypted(
        snapshot: HistoryArchiveSnapshot,
        passphrase: CharArray,
        output: OutputStream
    ) {
        val envelope = encrypt(snapshot, passphrase)
        try {
            output.write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    private fun validateHeader(header: ByteArray): Int {
        val buffer = ByteBuffer.wrap(header)
        require(ByteArray(8).also(buffer::get).contentEquals(magic))
        require(
            buffer.get().toInt() == 1 && buffer.get().toInt() == 1 &&
                buffer.get().toInt() == 1 && buffer.get().toInt() == 0
        )
        require(buffer.int == KDF_ITERATIONS)
        val length = buffer.int
        val body = buffer.int
        require(length in 16..MAX_PAYLOAD_BYTES && body == length + TAG)
        return body
    }

    private fun InputStream.exact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            require(count >= 0)
            if (count == 0) {
                val value = read()
                require(value >= 0)
                result[offset++] = value.toByte()
            } else {
                offset += count
            }
        }
        return result
    }

    private fun encode(snapshot: HistoryArchiveSnapshot): ByteArray {
        validate(snapshot)
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                require(bytes.size() < MAX_PAYLOAD_BYTES) { "History export is too large." }
                bytes.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                require(length <= MAX_PAYLOAD_BYTES - bytes.size()) {
                    "History export is too large."
                }
                bytes.write(value, offset, length)
            }
        }
        DataOutputStream(bounded).use { data ->
            data.writeInt(1)
            data.writeLong(snapshot.exportedAtMs)
            data.writeInt(snapshot.conversations.size)
            snapshot.conversations.forEach { entry ->
                data.text(entry.source.serverId, 36)
                data.text(entry.source.profileId, 36)
                data.text(entry.source.label, 256)
                data.text(entry.id, 36)
                data.writeLong(entry.publicRevision)
                data.text(entry.title, 512)
                data.writeBoolean(entry.bookmarked)
                data.writeInt(entry.messages.size)
                entry.messages.forEach {
                    data.text(it.id, 256)
                    data.writeByte(it.role.ordinal)
                    data.text(it.text, MAX_TEXT)
                    data.writeLong(it.createdAtMs)
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): HistoryArchiveSnapshot =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == 1)
            val exported = data.readLong()
            val count = data.readInt().also { require(it in 1..256) }
            var total = 0
            val entries = List(count) {
                val source = HistoryArchiveSource(data.text(36), data.text(36), data.text(256))
                val id = data.text(36)
                val revision = data.readLong()
                val title = data.text(512)
                val bookmarked = data.readUnsignedByte().also { require(it in 0..1) } == 1
                val size = data.readInt().also {
                    require(it in 0..1024 && total + it <= 4096)
                    total += it
                }
                val messages = List(size) {
                    val mid = data.text(256)
                    val role = data.readUnsignedByte().also {
                        require(it in HistoryArchiveRole.entries.indices)
                    }
                    HistoryArchiveMessage(
                        mid,
                        HistoryArchiveRole.entries[role],
                        data.text(MAX_TEXT),
                        data.readLong()
                    )
                }
                HistoryArchiveConversation(source, id, revision, title, bookmarked, messages)
            }
            require(data.read() == -1)
            HistoryArchiveSnapshot(exported, entries).also(::validate)
        }

    private fun validate(snapshot: HistoryArchiveSnapshot) {
        require(snapshot.exportedAtMs in 0..MAX_TIME)
        require(snapshot.conversations.size in 1..256)
        require(snapshot.conversations.sumOf { it.messages.size } <= 4096)
        val identities = snapshot.conversations.map {
            Triple(it.source.serverId, it.source.profileId, it.id)
        }
        require(identities.toSet().size == identities.size) { "Duplicate exported conversation." }
        snapshot.conversations.forEach { entry ->
            uuid(entry.source.serverId)
            uuid(entry.source.profileId)
            checked(entry.source.label, 256)
            uuid(entry.id)
            require(entry.publicRevision in 0..MAX_TIME)
            checked(entry.title, 512)
            require(entry.title.codePointCount(0, entry.title.length) <= 120)
            require(entry.messages.size <= 1024)
            require(entry.messages.map { it.id }.toSet().size == entry.messages.size)
            entry.messages.forEach {
                require(it.id.isNotEmpty() && it.id.none(Char::isISOControl))
                checked(it.id, 256)
                checked(it.text, MAX_TEXT)
                require(it.createdAtMs in 0..MAX_TIME)
            }
        }
    }

    private fun uuid(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Invalid history export identity."
        }
    }
    private fun checked(value: String, limit: Int) {
        TransferJson.bytes(value, limit).fill(0)
    }
    private fun DataOutputStream.text(value: String, limit: Int) {
        val bytes = TransferJson.bytes(value, limit)
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }
    private fun DataInputStream.text(limit: Int): String {
        val size = readInt().also { require(it in 0..limit && it <= available()) }
        val bytes = ByteArray(size)
        return try {
            readFully(bytes)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                ).decode(ByteBuffer.wrap(bytes)).toString()
        } finally {
            bytes.fill(0)
        }
    }
}
