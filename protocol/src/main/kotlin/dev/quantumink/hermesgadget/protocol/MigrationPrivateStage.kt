package dev.quantumink.hermesgadget.protocol

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Private temporary bytes encrypted in independently authenticated 64-KiB blocks. The random
 * key exists only in this object, never on disk. Caller owns the stage exclusively and closes it
 * on cancellation/failure/disposal. Lost-process directories are retired at first create per root.
 * This is temporary storage, not a wire format. Never put its root on shared/external storage.
 */
class MigrationPrivateStage private constructor(private val directory: File) : AutoCloseable {
    private val file = RandomAccessFile(File(directory, "blocks"), "rw")
    private val key = ByteArray(32).also(random::nextBytes)
    private val noncePrefix = ByteArray(8).also(random::nextBytes)
    private val pending = ByteArray(BLOCK)
    private var pendingCount = 0
    private var blocks = 0
    private var sealed = false
    private var closed = false
    private var cachedIndex = -1
    private var cached = ByteArray(0)
    var size: Int = 0
        private set

    @Synchronized fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        check(!closed && !sealed)
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size)
        require(size.toLong() + length <= MigrationArchive.MAX_ENVELOPE_BYTES)
        val start = size
        var read = offset
        while (read < offset + length) {
            val count = minOf(BLOCK - pendingCount, offset + length - read)
            bytes.copyInto(pending, pendingCount, read, read + count)
            pendingCount += count
            size += count
            read += count
            if (pendingCount == BLOCK) flush()
        }
        return start
    }

    private fun cipher(index: Int, length: Int, encrypt: Boolean): GCMModeCipher {
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(index).array()
        val aad = ByteBuffer.allocate(8).putInt(index).putInt(length).array()
        return GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        }
    }

    private fun flush() {
        if (pendingCount == 0) return
        val cipher = cipher(blocks, pendingCount, true)
        val output = ByteArray(pendingCount + TAG)
        try {
            var written = cipher.processBytes(pending, 0, pendingCount, output, 0)
            written += cipher.doFinal(output, written)
            check(written == output.size)
            file.write(output)
            blocks++
        } finally {
            output.fill(0)
            pending.fill(0)
            pendingCount = 0
        }
    }

    @Synchronized fun seal() {
        check(!closed && !sealed)
        flush()
        sealed = true
    }

    /** Exactly bounded random access, authenticating each block before returning its bytes. */
    @Synchronized fun read(offset: Int, output: ByteArray, start: Int, length: Int) {
        check(!closed && sealed)
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= size)
        require(start >= 0 && start.toLong() + length <= output.size)
        check(file.length() == size.toLong() + blocks.toLong() * TAG)
        var cursor = offset
        var destination = start
        while (cursor < offset + length) {
            val index = cursor / BLOCK
            if (cachedIndex != index) {
                cached.fill(0)
                cachedIndex = -1
                val count = minOf(BLOCK, size - index * BLOCK)
                val encrypted = ByteArray(count + TAG)
                val plain = ByteArray(count)
                try {
                    file.seek(index.toLong() * (BLOCK + TAG))
                    file.readFully(encrypted)
                    val cipher = cipher(index, count, false)
                    var written = cipher.processBytes(encrypted, 0, encrypted.size, plain, 0)
                    written += cipher.doFinal(plain, written)
                    check(written == count)
                    cached = plain
                    cachedIndex = index
                } catch (failure: Exception) {
                    plain.fill(0)
                    throw failure
                } finally {
                    encrypted.fill(0)
                }
            }
            val count = minOf(cached.size - cursor % BLOCK, offset + length - cursor)
            cached.copyInto(output, destination, cursor % BLOCK, cursor % BLOCK + count)
            cursor += count
            destination += count
        }
    }

    fun bytes(offset: Int, length: Int): ByteArray = ByteArray(length).also { output ->
        try {
            read(offset, output, 0, length)
        } catch (failure: Exception) {
            output.fill(0)
            throw failure
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        noncePrefix.fill(0)
        pending.fill(0)
        cached.fill(0)
        try {
            file.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val BLOCK = 64 * 1024
        private const val TAG = 16
        private val random = SecureRandom()
        private val cleaned = mutableSetOf<String>()

        fun create(privateRoot: File): MigrationPrivateStage {
            check(privateRoot.isDirectory || privateRoot.mkdirs())
            synchronized(cleaned) {
                if (cleaned.add(privateRoot.canonicalPath)) {
                    privateRoot.listFiles().orEmpty().filter {
                        it.isDirectory && it.name.startsWith("migration-stage-")
                    }.forEach { check(it.deleteRecursively()) }
                }
            }
            val directory = File(privateRoot, "migration-stage-${UUID.randomUUID()}")
            check(directory.mkdir())
            return try {
                MigrationPrivateStage(directory)
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw failure
            }
        }
    }
}
