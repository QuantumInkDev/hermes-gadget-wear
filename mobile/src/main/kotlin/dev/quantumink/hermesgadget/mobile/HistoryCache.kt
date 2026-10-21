package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Public identity only. An enrollment tag is a public-key SHA-256, never an enrollment key. */
internal data class HistoryScope(
    val profileKey: String,
    val endpointUrl: String,
    val serverId: String,
    val profileId: String,
    val enrollmentTag: String
) {
    override fun toString(): String = "HistoryScope(redacted)"
}
internal enum class HistoryRole { USER, ASSISTANT }

/** Complete display text only; no tool/system payloads, raw segments, actions or audio. */
internal data class HistoryMessage(
    val id: String,
    val role: HistoryRole,
    val text: String,
    val createdAtMs: Long
) {
    override fun toString(): String = "HistoryMessage(redacted)"
}
internal class HistoryThread(
    val id: String,
    val publicRevision: Long,
    val title: String,
    val bookmarked: Boolean,
    messages: List<HistoryMessage>
) {
    private val content = ownedHistoryItems(messages, 1024)
    val messages: List<HistoryMessage> get() = content
    override fun toString(): String = "HistoryThread(redacted)"
}
internal class HistorySnapshot(
    val revision: Long,
    val retentionDays: Int = 90,
    threads: List<HistoryThread> = emptyList()
) {
    private val content = ownedHistoryItems(threads, 256)
    val threads: List<HistoryThread> get() = content
    override fun toString(): String = "HistorySnapshot(redacted)"
}

/** Element-wise ownership also on API29, whose ArrayList(Collection) can alias toArray(). */
internal fun <T : Any> ownedHistoryItems(values: List<T>, maximum: Int): List<T> {
    require(values.size <= maximum) { "History capacity exceeded." }
    val owned = ArrayList<T>(minOf(values.size, maximum))
    for (value in values) {
        require(owned.size < maximum) { "History capacity exceeded." }
        owned.add(requireNotNull(value) { "Invalid history item." })
    }
    return Collections.unmodifiableList(owned)
}

/**
 * Private bounded text cache. No enrollment, network, transport binding or canonical deletion.
 * All entry points perform disk/crypto work and must be called off the Android main thread.
 * A process-wide monitor serializes instances; this app has no separate history process.
 */
internal class EncryptedHistoryCache(
    private val directory: File,
    private val key: (create: Boolean) -> SecretKey,
    private val clock: () -> Long
) {
    private data class CachedMessage(val message: HistoryMessage, val receivedAt: Long)
    private data class CachedThread(
        val scope: HistoryScope,
        val id: String,
        val publicRevision: Long,
        val title: String,
        val bookmarked: Boolean,
        val receivedAt: Long,
        val messages: List<CachedMessage>
    ) {
        fun display() = HistoryThread(
            id,
            publicRevision,
            title,
            bookmarked,
            messages.map {
                it.message
            }
        )
    }
    private data class Document(
        val revision: Long = 0,
        val days: Int = 90,
        val threads: List<CachedThread> = emptyList()
    )

    fun read(scope: HistoryScope): HistorySnapshot = synchronized(lock) {
        validate(scope)
        val original = load()
        val retained = retain(original, time())
        snapshot(scope, saveIfChanged(original, retained))
    }

    /**
     * Ordered canonical page, beginning immediately after [afterMessageId], or at the beginning
     * when null. The caller must assemble complete display messages from one current snapshot.
     * Overlap must match the cached sequence; unknown anchors/gaps/conflicting order are refused.
     * The local revision prevents late callbacks from undoing a purge or newer cache write.
     */
    fun put(
        scope: HistoryScope,
        thread: HistoryThread,
        expectedRevision: Long,
        afterMessageId: String? = null
    ): HistorySnapshot = update(scope, expectedRevision) { document, now ->
        validate(thread)
        if (document.days == 0) {
            document
        } else {
            val previous = document.threads.singleOrNull {
                it.scope == scope && it.id == thread.id
            }
            if (previous != null) {
                check(thread.publicRevision >= previous.publicRevision) {
                    "Stale history metadata."
                }
                check(
                    thread.publicRevision != previous.publicRevision ||
                        (
                            thread.title == previous.title &&
                                thread.bookmarked == previous.bookmarked
                            )
                ) {
                    "Conflicting history metadata."
                }
            }
            val messages = previous?.messages?.toMutableList() ?: mutableListOf()
            val start = if (afterMessageId == null) {
                0
            } else {
                token(afterMessageId, 256)
                val index = messages.indexOfFirst { it.message.id == afterMessageId }
                check(index >= 0) { "History anchor unavailable. Reload canonical history." }
                index + 1
            }
            var changed = previous == null || previous.publicRevision != thread.publicRevision
            thread.messages.forEachIndexed { offset, message ->
                val position = start + offset
                val known = messages.getOrNull(position)
                if (known != null) {
                    check(known.message == message) {
                        "Conflicting history sequence. Reload canonical history."
                    }
                } else {
                    check(messages.none { it.message.id == message.id }) {
                        "Conflicting history sequence. Reload canonical history."
                    }
                    messages.add(CachedMessage(message, now))
                    changed = true
                }
            }
            val saved = CachedThread(
                scope,
                thread.id,
                thread.publicRevision,
                thread.title,
                thread.bookmarked,
                if (changed) now else requireNotNull(previous).receivedAt,
                messages
            )
            val entries = document.threads.toMutableList()
            if (previous ==
                null
            ) {
                entries += saved
            } else {
                entries[entries.indexOf(previous)] = saved
            }
            document.copy(threads = entries)
        }
    }

    /** Device cache only. This never cancels work or deletes a canonical conversation. */
    fun purge(
        scope: HistoryScope,
        conversationId: String?,
        expectedRevision: Long
    ): HistorySnapshot = update(scope, expectedRevision, force = true) { document, _ ->
        conversationId?.let(::uuid)
        document.copy(
            threads = document.threads.filterNot {
                it.scope == scope && (conversationId == null || it.id == conversationId)
            }
        )
    }

    /** Global cache preference; zero clears and disables all scopes. */
    fun setRetention(scope: HistoryScope, days: Int, expectedRevision: Long): HistorySnapshot =
        update(scope, expectedRevision) { document, _ ->
            require(days in 0..365) { "Invalid history retention." }
            document.copy(days = days)
        }

    fun search(scope: HistoryScope, query: String, limit: Int = 50): List<HistoryThread> {
        require(limit in 1..50) { "Invalid history search limit." }
        text(query, 256)
        val needle = query.lowercase(Locale.ROOT)
        return read(scope).threads.filter { entry ->
            entry.title.lowercase(Locale.ROOT).contains(needle) || entry.messages.any {
                it.text.lowercase(Locale.ROOT).contains(needle)
            }
        }.take(limit)
    }

    private fun update(
        scope: HistoryScope,
        expectedRevision: Long,
        force: Boolean = false,
        transform: (Document, Long) -> Document
    ): HistorySnapshot = synchronized(lock) {
        validate(scope)
        require(expectedRevision in 0..MAX_TIME) { "Invalid history revision." }
        val original = load()
        check(expectedRevision == original.revision) { "History changed. Reload before saving." }
        val now = time()
        // Merge against unpruned IDs so an exact retry cannot refresh their receipt time at expiry.
        val retained = retain(transform(original, now), now)
        snapshot(scope, saveIfChanged(original, retained, force))
    }

    private fun retain(document: Document, now: Long): Document {
        if (document.days == 0) return document.copy(threads = emptyList())
        val cutoff = now - document.days * DAY
        return document.copy(
            threads = document.threads.mapNotNull { entry ->
                val messages = entry.messages.filter { it.receivedAt > cutoff }
                if (messages.isEmpty() && entry.receivedAt <= cutoff) {
                    null
                } else {
                    entry.copy(messages = messages)
                }
            }
        )
    }

    private fun snapshot(scope: HistoryScope, document: Document) = HistorySnapshot(
        document.revision,
        document.days,
        document.threads.filter { it.scope == scope }.sortedWith(
            compareByDescending<CachedThread> { it.receivedAt }.thenBy { it.id }
        ).map { it.display() }
    )

    private fun time(): Long = clock().also {
        require(it in 0..MAX_TIME) { "History clock unavailable." }
    }

    private fun saveIfChanged(
        original: Document,
        updated: Document,
        force: Boolean = false
    ): Document {
        if (!force && original == updated) return original
        check(original.revision < MAX_TIME) { "History revision exhausted." }
        val next = updated.copy(revision = original.revision + 1)
        val plaintext = encode(next)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(!File(directory, RECORD).exists()))
            check(cipher.iv.size == 12) { "Unsupported history encryption." }
            cipher.updateAAD(aad)
            val sealed = byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(plaintext)
            persist(sealed)
        } finally {
            plaintext.fill(0)
        }
        return next
    }

    private fun load(): Document {
        check(!Files.isSymbolicLink(directory.toPath())) { "Invalid history directory." }
        if (!directory.exists()) return Document()
        check(directory.isDirectory) { "Invalid history directory." }
        val record = File(directory, RECORD)
        if (!record.exists()) {
            check(directory.listFiles()?.isEmpty() == true) {
                "History record missing. Existing data was preserved."
            }
            return Document()
        }
        val marker = File(directory, MARKER)
        check(
            marker.isFile && !Files.isSymbolicLink(marker.toPath()) &&
                marker.length() == 1L && marker.readBytes().contentEquals(byteArrayOf(1))
        ) {
            "History initialization record unavailable."
        }
        check(record.isFile && !Files.isSymbolicLink(record.toPath())) { "Invalid history record." }
        val encrypted = boundedRead(record)
        require(encrypted.size >= 30 && encrypted[0].toInt() == 1 && encrypted[1].toInt() == 12) {
            "Unsupported history record."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(false),
            GCMParameterSpec(128, encrypted.copyOfRange(2, 14))
        )
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
        return try {
            decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun boundedRead(file: File): ByteArray = FileInputStream(file).use { input ->
        require(input.channel.size() in 30..(MAX_BYTES + 30).toLong()) { "Invalid history size." }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 31 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES + 30) { "Invalid history size." }
        }
        output.toByteArray()
    }

    private fun persist(bytes: ByteArray) {
        check(directory.exists() || directory.mkdirs()) { "History directory unavailable." }
        val marker = File(directory, MARKER)
        if (!marker.exists()) {
            // Write before first promotion: failed initialization is never mistaken for an empty cache.
            FileOutputStream(marker).use {
                it.write(1)
                it.fd.sync()
            }
            syncDirectory()
        }
        val pending = File(directory, PENDING)
        check(!Files.isSymbolicLink(pending.toPath())) { "Invalid history staging record." }
        FileOutputStream(pending).use {
            it.write(bytes)
            it.fd.sync()
        }
        Files.move(
            pending.toPath(),
            File(directory, RECORD).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        // A failure here is uncertain durability, never a successful acknowledgment.
        syncDirectory()
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun encode(document: Document): ByteArray {
        require(document.days in 0..365 && document.threads.size <= 256) {
            "History capacity exceeded."
        }
        require(
            document.threads.sumOf {
                it.messages.size
            } <= 4096
        ) { "History capacity exceeded." }
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                require(output.size() < MAX_BYTES) { "History capacity exceeded." }
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(length <= MAX_BYTES - output.size()) { "History capacity exceeded." }
                output.write(bytes, offset, length)
            }
        }
        DataOutputStream(bounded).use { data ->
            data.writeInt(2)
            data.writeLong(document.revision)
            data.writeInt(document.days)
            data.writeInt(document.threads.size)
            document.threads.forEach { entry ->
                validate(entry.scope)
                validate(entry.display())
                data.string(entry.scope.profileKey, 512)
                data.string(entry.scope.endpointUrl, 2048)
                data.string(entry.scope.serverId, 36)
                data.string(entry.scope.profileId, 36)
                data.string(entry.scope.enrollmentTag, 64)
                data.string(entry.id, 36)
                data.writeLong(entry.publicRevision)
                data.string(entry.title, 512)
                data.writeBoolean(entry.bookmarked)
                data.writeLong(entry.receivedAt)
                data.writeInt(entry.messages.size)
                entry.messages.forEach {
                    data.string(it.message.id, 256)
                    data.writeByte(it.message.role.ordinal)
                    data.string(it.message.text, MAX_TEXT)
                    data.writeLong(it.message.createdAtMs)
                    data.writeLong(it.receivedAt)
                }
            }
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Document =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == 2) {
                "Unsupported history format. Existing record was preserved."
            }
            val revision = data.readLong().also { require(it in 1..MAX_TIME) }
            val days = data.readInt().also { require(it in 0..365) }
            val count = data.readInt().also { require(it in 0..256 && (days != 0 || it == 0)) }
            var total = 0
            val threads = List(count) {
                val scope = HistoryScope(
                    data.string(512),
                    data.string(2048),
                    data.string(36),
                    data.string(36),
                    data.string(64)
                ).also(::validate)
                val id = data.string(36).also(::uuid)
                val metadata = data.readLong().also { require(it in 0..MAX_TIME) }
                val title = data.string(512)
                val bookmarked = data.readUnsignedByte().also { require(it in 0..1) } == 1
                val received = data.readLong().also { require(it in 0..MAX_TIME) }
                val size = data.readInt().also {
                    require(it in 0..1024 && total + it <= 4096)
                    total += it
                }
                val messages = List(size) {
                    val mid = data.string(256)
                    val role = data.readUnsignedByte().also {
                        require(it in HistoryRole.entries.indices)
                    }
                    val text = data.string(MAX_TEXT)
                    val created = data.readLong()
                    val at = data.readLong().also { require(it in 0..MAX_TIME) }
                    CachedMessage(HistoryMessage(mid, HistoryRole.entries[role], text, created), at)
                }
                CachedThread(scope, id, metadata, title, bookmarked, received, messages).also {
                    validate(it.display())
                }
            }
            require(threads.map { it.scope to it.id }.toSet().size == count && data.read() == -1) {
                "Invalid history entries."
            }
            Document(revision, days, threads)
        }

    private fun DataOutputStream.string(value: String, limit: Int) {
        val bytes = utf8(value, limit)
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }
    private fun DataInputStream.string(limit: Int): String {
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

    private fun validate(scope: HistoryScope) {
        token(scope.profileKey, 512)
        require(Endpoint.parse(scope.endpointUrl).let { it.isTls && it.url == scope.endpointUrl }) {
            "Invalid history endpoint."
        }
        uuid(scope.serverId)
        uuid(scope.profileId)
        require(scope.enrollmentTag.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid history enrollment tag."
        }
    }
    private fun validate(thread: HistoryThread) {
        uuid(thread.id)
        require(thread.publicRevision in 0..MAX_TIME) { "Invalid history metadata revision." }
        text(thread.title, 512)
        require(thread.title.codePointCount(0, thread.title.length) <= 120) {
            "Invalid history title."
        }
        val messages = thread.messages
        require(messages.size <= 1024 && messages.map { it.id }.toSet().size == messages.size) {
            "Invalid history messages."
        }
        messages.forEach {
            token(it.id, 256)
            text(it.text, MAX_TEXT)
            require(it.createdAtMs in 0..MAX_TIME) { "Invalid history timestamp." }
        }
    }
    private fun token(value: String, limit: Int) {
        require(
            value.isNotEmpty() && value.none {
                it.isISOControl()
            }
        ) { "Invalid history identity." }
        text(value, limit)
    }
    private fun uuid(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Invalid history UUID."
        }
    }
    private fun text(value: String, limit: Int) {
        utf8(value, limit).fill(0)
    }
    private fun utf8(value: String, limit: Int): ByteArray {
        require(value.length <= limit) { "History text is too large." }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        try {
            require(encoded.remaining() <= limit) { "History text is too large." }
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    companion object {
        private val lock = Any()
        private val aad = "quantum-relay|phone-history|v1".toByteArray()
        private const val RECORD = "history.v1"
        private const val MARKER = "initialized"
        private const val PENDING = "history.pending"
        private const val DAY = 86_400_000L
        private const val MAX_TIME = 9_007_199_254_740_991L
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TEXT = 256 * 1024
    }
}
