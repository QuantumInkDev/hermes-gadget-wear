package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryEraseReview
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal typealias HistoryScope =
    dev.quantumink.hermesgadget.protocol.relay.session.HistoryNamespace
internal typealias HistoryRole = dev.quantumink.hermesgadget.protocol.relay.session.RetainedRole
internal typealias HistoryMessage =
    dev.quantumink.hermesgadget.protocol.relay.session.RetainedMessage
internal typealias HistoryThread = dev.quantumink.hermesgadget.protocol.relay.session.RetainedThread
internal typealias HistorySnapshot =
    dev.quantumink.hermesgadget.protocol.relay.session.RetainedSnapshot

/** Element ownership is shared with the Android-free consumer; never ArrayList(Collection). */
internal fun <T : Any> ownedHistoryItems(values: List<T>, maximum: Int): List<T> =
    dev.quantumink.hermesgadget.protocol.relay.session.ownHistoryItems(values, maximum)

/**
 * Private bounded text cache. No enrollment, network, transport binding or canonical deletion.
 * All entry points perform disk/crypto work and must be called off the Android main thread.
 * A process-wide monitor serializes instances; this app has no separate history process.
 */
internal class EncryptedHistoryCache(
    private val directory: File,
    private val key: (create: Boolean) -> SecretKey,
    private val clock: () -> Long,
    /** Owned filesystem fault seam; production uses the real fsync immediately after promotion. */
    private val afterPromote: () -> Unit = {}
) {
    private var inspectedVersion: Int? = null
    private var inspectedRevision: Long? = null
    private data class CachedMessage(val message: HistoryMessage, val receivedAt: Long)
    private data class CachedThread(
        val scope: HistoryScope,
        val id: String,
        val publicRevision: Long,
        val title: String,
        val bookmarked: Boolean,
        val receivedAt: Long,
        val messages: List<CachedMessage>,
        val complete: Boolean = false,
        val rebuild: Boolean = false,
        val listing: String = "",
        val coverage: Int = 0
    ) {
        fun display() = HistoryThread(
            id,
            publicRevision,
            title,
            bookmarked,
            messages.map { it.message },
            complete,
            rebuild
        )
    }
    private data class Document(
        val revision: Long = 0,
        val days: Int = 90,
        val threads: List<CachedThread> = emptyList(),
        val highWater: Long = 0
    )

    fun read(scope: HistoryScope): HistorySnapshot = guarded {
        synchronized(lock) {
            validate(scope)
            val original = load()
            val retained = retain(original, time())
            snapshot(scope, saveIfChanged(original, retained))
        }
    }

    /** Explicit canonical placement. Only the authenticated controller supplies production captures. */
    fun put(
        scope: HistoryScope,
        thread: HistoryThread,
        expectedRevision: Long,
        anchor: HistoryAnchor,
        listing: String = "fixture",
        complete: Boolean = thread.complete
    ): HistorySnapshot = update(scope, expectedRevision) { document, now ->
        validate(thread)
        if (document.days == 0) {
            document
        } else {
            val previous = document.threads.singleOrNull { it.scope == scope && it.id == thread.id }
            if (previous?.rebuild == true) fail(HistoryProblem.REBUILD_REQUIRED)
            if (previous != null) {
                if (thread.publicRevision <
                    previous.publicRevision
                ) {
                    fail(HistoryProblem.STALE_METADATA)
                }
                if (thread.publicRevision == previous.publicRevision &&
                    (thread.title != previous.title || thread.bookmarked != previous.bookmarked)
                ) {
                    fail(HistoryProblem.METADATA_CONFLICT)
                }
            }
            val messages = previous?.messages?.toMutableList() ?: mutableListOf()
            val start = when (anchor) {
                HistoryAnchor.Beginning -> 0
                is HistoryAnchor.After -> {
                    token(anchor.messageId, 256)
                    val index = messages.indexOfFirst { it.message.id == anchor.messageId }
                    if (index < 0) fail(HistoryProblem.MISSING_ANCHOR)
                    if (previous?.listing != listing) fail(HistoryProblem.REBUILD_REQUIRED)
                    if (index >= previous.coverage) fail(HistoryProblem.MISSING_ANCHOR)
                    index + 1
                }
            }
            var changed = previous == null || previous.publicRevision != thread.publicRevision
            thread.messages.forEachIndexed { offset, message ->
                val position = start + offset
                val known = messages.getOrNull(position)
                if (known != null) {
                    if (known.message != message) fail(HistoryProblem.SEQUENCE_CONFLICT)
                } else {
                    if (messages.any {
                            it.message.id == message.id
                        }
                    ) {
                        fail(HistoryProblem.SEQUENCE_CONFLICT)
                    }
                    messages.add(CachedMessage(message, now))
                    changed = true
                }
            }
            val coverage = start + thread.messages.size
            if (complete && coverage != messages.size) fail(HistoryProblem.SEQUENCE_CONFLICT)
            val saved =
                CachedThread(
                    scope, thread.id, thread.publicRevision, thread.title, thread.bookmarked,
                    if (changed) now else requireNotNull(previous).receivedAt, messages,
                    complete ||
                        (
                            previous?.complete == true && previous.listing == listing &&
                                messages.size == previous.messages.size
                            ),
                    false, listing,
                    maxOf(coverage, if (previous?.listing == listing) previous.coverage else 0)
                )
            val entries = document.threads.toMutableList()
            if (previous == null) entries.add(saved) else entries[entries.indexOf(previous)] = saved
            document.copy(threads = entries, highWater = if (changed) now else document.highWater)
        }
    }

    /** Device cache only. This never cancels work or deletes a canonical conversation. */
    fun purge(
        scope: HistoryScope,
        conversationId: String?,
        expectedRevision: Long
    ): HistorySnapshot = update(scope, expectedRevision, force = true) { document, _ ->
        conversationId?.let { token(it, 128) }
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
    ): HistorySnapshot = guarded {
        synchronized(lock) {
            validate(scope)
            val original = load()
            if (expectedRevision != original.revision) fail(HistoryProblem.LOCAL_REVISION)
            val now = maxOf(time(), original.highWater)
            // Expiry is durable BEFORE merge. Even a refused replay cannot restore an expired ID.
            val pruned = saveIfChanged(original, retain(original, now))
            val next = retain(transform(pruned, now), now)
            snapshot(scope, saveIfChanged(pruned, next, force))
        }
    }

    private fun retain(document: Document, wall: Long): Document {
        val now = maxOf(wall, document.highWater)
        val cutoff = now - document.days * DAY
        var changed = false
        val threads = document.threads.map { entry ->
            val messages = if (document.days ==
                0
            ) {
                emptyList()
            } else {
                entry.messages.filter { it.receivedAt > cutoff }
            }
            val trimmed = messages.size != entry.messages.size ||
                (entry.messages.isEmpty() && entry.receivedAt <= cutoff && !entry.rebuild)
            if (trimmed) changed = true
            if (trimmed) {
                entry.copy(
                    messages = messages,
                    complete = false,
                    rebuild = true,
                    title = if (messages.isEmpty()) "" else entry.title,
                    bookmarked = if (messages.isEmpty()) false else entry.bookmarked
                )
            } else {
                entry
            }
        }
        return if (changed) document.copy(threads = threads, highWater = now) else document
    }

    private fun snapshot(scope: HistoryScope, document: Document) = HistorySnapshot(
        document.revision,
        document.days,
        document.threads.filter {
            it.scope == scope && !(it.rebuild && it.messages.isEmpty())
        }.sortedWith(
            compareByDescending<CachedThread> { it.receivedAt }.thenBy { it.id }
        ).map { it.display() }
    )

    fun absent(scope: HistoryScope, conversationId: String?): Boolean = guarded {
        synchronized(lock) {
            load().threads.none {
                it.scope == scope &&
                    (conversationId == null || it.id == conversationId)
            }
        }
    }

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
            cipher.init(Cipher.ENCRYPT_MODE, secret(!File(directory, RECORD).exists()))
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
        inspectedVersion = null
        inspectedRevision = null
        check(!Files.isSymbolicLink(directory.toPath())) { "Invalid history directory." }
        if (!directory.exists()) return Document()
        check(directory.isDirectory) { "Invalid history directory." }
        val record = File(directory, RECORD)
        if (!record.exists()) {
            if (directory.listFiles()?.isEmpty() != true) fail(HistoryProblem.RECORD_MISSING)
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
        if (encrypted.size < 30 || encrypted[0].toInt() != 1 ||
            encrypted[1].toInt() != 12
        ) {
            fail(HistoryProblem.UNSUPPORTED)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret(false),
            GCMParameterSpec(128, encrypted.copyOfRange(2, 14))
        )
        cipher.updateAAD(aad)
        val plaintext = try {
            cipher.doFinal(encrypted.copyOfRange(14, encrypted.size))
        } catch (
            _: javax.crypto.AEADBadTagException
        ) {
            fail(HistoryProblem.UNREADABLE)
        }
        return try {
            if (plaintext.size >= 4) inspectedVersion = ByteBuffer.wrap(plaintext).getInt(0)
            if (inspectedVersion in 1..3 && plaintext.size >= 12) {
                inspectedRevision =
                    ByteBuffer.wrap(plaintext).getLong(4).takeIf { it in 1..MAX_TIME }
            }
            decode(plaintext)
        } catch (failure: HistoryFailure) {
            throw failure
        } catch (_: Exception) {
            fail(HistoryProblem.UNREADABLE)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun boundedRead(file: File): ByteArray = FileInputStream(file).use { input ->
        if (input.channel.size() > MAX_BYTES + 30L) fail(HistoryProblem.CAPACITY)
        if (input.channel.size() < 30) fail(HistoryProblem.UNREADABLE)
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
        try {
            afterPromote()
            syncDirectory()
        } catch (
            _: IOException
        ) {
            fail(HistoryProblem.WRITE_UNCERTAIN)
        }
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun encode(document: Document): ByteArray {
        if (document.days !in 0..365 || document.threads.size > 256) fail(HistoryProblem.CAPACITY)
        if (document.threads.sumOf { it.messages.size } > 4096) fail(HistoryProblem.CAPACITY)
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) {
                if (output.size() >= MAX_BYTES) fail(HistoryProblem.CAPACITY)
                output.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_BYTES - output.size()) fail(HistoryProblem.CAPACITY)
                output.write(bytes, offset, length)
            }
        }
        DataOutputStream(bounded).use { data ->
            data.writeInt(3)
            data.writeLong(document.revision)
            data.writeInt(document.days)
            data.writeLong(document.highWater)
            data.writeInt(document.threads.size)
            document.threads.forEach { entry ->
                validate(entry.scope)
                validate(entry.display())
                data.string(entry.scope.endpointKey, 512)
                data.string(entry.scope.endpointUrl, 2048)
                data.string(entry.scope.enrollmentLabel, 512)
                data.string(entry.scope.profileId, 36)
                data.writeLong(entry.scope.reviewEpoch)
                data.string(entry.id, 128)
                data.writeLong(entry.publicRevision)
                data.string(entry.title, 512)
                data.writeBoolean(entry.bookmarked)
                data.writeLong(entry.receivedAt)
                data.writeBoolean(entry.complete)
                data.writeBoolean(entry.rebuild)
                data.string(entry.listing, 256)
                data.writeInt(entry.coverage)
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
            when (data.readInt()) {
                1, 2 -> fail(HistoryProblem.LEGACY_UNBOUND)
                3 -> Unit
                else -> fail(HistoryProblem.UNSUPPORTED)
            }
            val revision = data.readLong().also { require(it in 1..MAX_TIME) }
            val days = data.readInt().also { require(it in 0..365) }
            val highWater = data.readLong().also { require(it in 0..MAX_TIME) }
            val count = data.readInt().also { require(it in 0..256) }
            var total = 0
            val threads = List(count) {
                val scope = HistoryScope(
                    data.string(512),
                    data.string(2048),
                    data.string(512),
                    data.string(36),
                    data.readLong()
                ).also(::validate)
                val id = data.string(128).also { token(it, 128) }
                val metadata = data.readLong().also { require(it in 0..MAX_TIME) }
                val title = data.string(512)
                val bookmarked = data.readUnsignedByte().also { require(it in 0..1) } == 1
                val received = data.readLong().also { require(it in 0..MAX_TIME) }
                val complete = data.readUnsignedByte().also { require(it in 0..1) } == 1
                val rebuild = data.readUnsignedByte().also { require(it in 0..1) } == 1
                require(!complete || !rebuild)
                val listing = data.string(256)
                val coverage = data.readInt().also { require(it in 0..1024) }
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
                CachedThread(
                    scope, id, metadata, title, bookmarked, received, messages,
                    complete, rebuild, listing, coverage
                ).also {
                    validate(it.display())
                }
            }
            require(threads.map { it.scope to it.id }.toSet().size == count && data.read() == -1) {
                "Invalid history entries."
            }
            Document(revision, days, threads, highWater)
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
        // The typed namespace constructor validates actual public endpoint/enrollment/profile/review facts.
        require(scope.reviewEpoch >= 0)
    }
    private fun validate(thread: HistoryThread) {
        token(thread.id, 128)
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
        if (value.length > limit) fail(HistoryProblem.CAPACITY)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        try {
            if (encoded.remaining() > limit) fail(HistoryProblem.CAPACITY)
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun fail(problem: HistoryProblem): Nothing = throw HistoryFailure(problem)
    private fun secret(create: Boolean): SecretKey = try {
        key(create)
    } catch (_: Exception) {
        fail(HistoryProblem.KEY_UNAVAILABLE)
    }
    private fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (
        failure: HistoryFailure
    ) {
        throw failure
    } catch (
        _: java.nio.charset.CharacterCodingException
    ) {
        fail(HistoryProblem.INVALID_VALUE)
    } catch (
        _: IOException
    ) {
        fail(HistoryProblem.STORAGE)
    } catch (
        _: java.security.GeneralSecurityException
    ) {
        fail(HistoryProblem.UNREADABLE)
    } catch (
        _: IllegalArgumentException
    ) {
        fail(HistoryProblem.INVALID_VALUE)
    } catch (
        _: IllegalStateException
    ) {
        fail(HistoryProblem.UNREADABLE)
    }

    /** Review does not prune, write, create a key or guess legacy ownership. */
    fun review(
        scope: HistoryScope,
        conversationId: String?,
        global: Boolean = false,
        days: Int? = null
    ): HistoryEraseReview = synchronized(lock) {
        if (Files.isSymbolicLink(directory.toPath())) fail(HistoryProblem.STORAGE)
        if (directory.listFiles()?.any { it.name !in setOf(RECORD, MARKER, PENDING) } ==
            true
        ) {
            fail(HistoryProblem.STORAGE)
        }
        val record = File(directory, RECORD)
        val marker = File(directory, MARKER)
        val document = try {
            load()
        } catch (_: Exception) {
            null
        }
        val bytes = if (record.isFile && !Files.isSymbolicLink(record.toPath()) &&
            record.length() <= MAX_BYTES + 30L
        ) {
            record.readBytes()
        } else {
            null
        }
        fun hash(value: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {
                "%02x".format(it)
            }
        if (record.exists() && bytes == null) fail(HistoryProblem.STORAGE)
        val markerBytes = if (marker.isFile && !Files.isSymbolicLink(marker.toPath()) &&
            marker.length() <= 16
        ) {
            marker.readBytes()
        } else {
            byteArrayOf()
        }
        val pending = File(directory, PENDING)
        val pendingHash = if (!pending.exists()) {
            "absent"
        } else {
            if (!pending.isFile || Files.isSymbolicLink(pending.toPath()) ||
                pending.length() > MAX_BYTES + 30L
            ) {
                fail(HistoryProblem.STORAGE)
            }
            hash(pending.readBytes())
        }
        if (days != null && document == null) fail(HistoryProblem.UNREADABLE)
        HistoryEraseReview(
            if (document == null ||
                global
            ) {
                null
            } else {
                scope
            },
            if (document == null || global) null else conversationId,
            document == null || global,
            bytes?.let(
                ::hash
            ) ?: "absent",
            if (marker.exists()) hash(markerBytes) else "absent",
            bytes?.size?.toLong() ?: 0,
            inspectedVersion, document?.revision ?: inspectedRevision, days, pendingHash
        )
    }

    /** Recheck exact record/revision/hash at mutation. Global unreadable bytes affect ALL profiles. */
    fun erase(review: HistoryEraseReview, scope: HistoryScope) = guarded {
        synchronized(lock) {
            if (review !=
                review(scope, review.conversationId, review.allProfiles, review.retentionDays)
            ) {
                fail(HistoryProblem.STALE_REVIEW)
            }
            if (review.retentionDays != null) {
                setRetention(
                    scope,
                    requireNotNull(review.retentionDays),
                    requireNotNull(review.revision)
                )
            } else if (review.allProfiles) {
                for (name in listOf(RECORD, PENDING, MARKER)) {
                    val file = File(directory, name)
                    if (Files.isSymbolicLink(file.toPath())) fail(HistoryProblem.STORAGE)
                    if (file.exists() && !file.delete()) fail(HistoryProblem.STORAGE)
                }
                if (directory.exists()) syncDirectory()
            } else {
                purge(
                    requireNotNull(review.namespace),
                    review.conversationId,
                    requireNotNull(review.revision)
                )
            }
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
