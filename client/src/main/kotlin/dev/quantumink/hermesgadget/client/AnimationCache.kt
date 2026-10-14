package dev.quantumink.hermesgadget.client

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.GifPortrait
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Verified, bounded hash assets. Binding to a profile and its PNG is owned by the manifest. */
class AnimationCache(private val directory: File) {
    private var protected = emptySet<String>()

    @Synchronized fun protect(hashes: Set<String>) {
        protected = hashes.toSet()
    }

    @Synchronized fun store(descriptor: AnimationDescriptor, bytes: ByteArray) {
        validate(descriptor, bytes)
        check(directory.exists() || directory.mkdirs())
        val destination = File(directory, descriptor.sha256 + ".gif")
        val files = files()
        var count = files.size
        var used = files.sumOf { it.length() }
        if (!destination.exists()) {
            for (file in files.filter {
                it.nameWithoutExtension !in protected
            }.sortedBy { it.lastModified() }) {
                if (count < 32 && used + bytes.size <= MAX_BYTES) break
                val size = file.length()
                if (file.delete()) {
                    count--
                    used -= size
                }
            }
            require(count < 32 && used + bytes.size <= MAX_BYTES) { "Animation storage is full." }
        }
        write(destination, bytes)
    }

    /** One durable owner import, separate from the settled 32-file/8-MiB cache. */
    @Synchronized fun stage(descriptor: AnimationDescriptor, bytes: ByteArray) {
        validate(descriptor, bytes)
        if (file(descriptor)?.extension == "gif") return
        val pending = stagedBytes()
        require(pending == null || sha256(pending) == descriptor.sha256) {
            "Sync the previous picture before importing another animation."
        }
        if (pending != null) return
        write(File(directory, STAGED_FILE), bytes)
    }

    private fun validate(descriptor: AnimationDescriptor, bytes: ByteArray) {
        require(bytes.size == descriptor.bytes && sha256(bytes) == descriptor.sha256)
        GifPortrait.validate(bytes)
        GifDecoder.drawable(bytes).let { if (it is AnimatedImageDrawable) it.stop() }
    }

    private fun write(destination: File, bytes: ByteArray) {
        check(directory.exists() || directory.mkdirs())
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (
            failure: Exception
        ) {
            atomic.failWrite(output)
            throw failure
        }
    }

    @Synchronized fun file(descriptor: AnimationDescriptor): File? = runCatching {
        val file = File(directory, descriptor.sha256 + ".gif")
        require(file.length() == descriptor.bytes.toLong())
        val bytes = file.readBytes()
        require(sha256(bytes) == descriptor.sha256)
        GifPortrait.validate(bytes)
        file
    }.getOrNull() ?: runCatching {
        val bytes = stagedBytes() ?: return null
        require(bytes.size == descriptor.bytes && sha256(bytes) == descriptor.sha256)
        GifPortrait.validate(bytes)
        File(directory, STAGED_FILE)
    }.getOrNull()

    /** Call only after the corresponding manifest has been published/accepted successfully. */
    @Synchronized fun retain(hashes: Set<String>) {
        require(hashes.size <= 32 && hashes.all { it.matches(Regex("[a-f0-9]{64}")) })
        val pending = stagedBytes()
        val pendingHash = pending?.let(::sha256)
        val promote = pendingHash != null && pendingHash in hashes
        val survivors = files().filter {
            it.nameWithoutExtension in hashes &&
                (!promote || it.nameWithoutExtension != pendingHash)
        }
        require(
            survivors.size + (if (promote) 1 else 0) <= 32 &&
                survivors.sumOf {
                    it.length()
                } + (if (promote) requireNotNull(pending).size else 0) <=
                MAX_BYTES
        ) {
            "Animation storage is full."
        }
        files().filter { it.nameWithoutExtension !in hashes }.forEach {
            check(!it.exists() || it.delete()) { "Cannot retire an animation asset." }
        }
        if (promote) {
            write(File(directory, "$pendingHash.gif"), requireNotNull(pending))
        }
        AtomicFile(File(directory, STAGED_FILE)).delete()
        protected = hashes.toSet()
    }

    private fun stagedBytes(): ByteArray? {
        val file = AtomicFile(File(directory, STAGED_FILE))
        if (!file.baseFile.exists() && !File(directory, "$STAGED_FILE.bak").exists()) return null
        return file.openRead().use {
            require(it.channel.size() in 1..GifPortrait.MAX_BYTES) { "Invalid pending animation." }
            it.readBytes()
        }
    }

    @Synchronized fun reset() {
        if (directory.exists()) check(directory.deleteRecursively())
        protected = emptySet()
    }
    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}\\.gif")) }
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val STAGED_FILE = "pending.staged"
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

object GifDecoder {
    /** IO worker only. Reject partial platform decodes; parser bounds all frame allocations. */
    fun drawable(bytes: ByteArray): Drawable {
        val info = GifPortrait.validate(bytes)
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder,
                header,
                _
            ->
            require(header.size.width == info.width && header.size.height == info.height)
            decoder.setOnPartialImageListener { false }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun firstFrame(bytes: ByteArray): ByteArray {
        val info = GifPortrait.validate(bytes)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder,
                header,
                _
            ->
            require(header.size.width == info.width && header.size.height == info.height)
            decoder.setOnPartialImageListener { false }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            return ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
