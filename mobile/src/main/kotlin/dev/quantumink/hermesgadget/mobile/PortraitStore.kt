package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Decode/normalize on the caller's IO worker; no source metadata is written to the PNG. */
class PortraitStore(private val directory: File) {
    private val allowedChunks = setOf(
        "IHDR", "PLTE", "tRNS", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "iCCP", "sBIT", "pHYs",
        "bKGD"
    )
    private var protected = emptySet<String>()

    @Synchronized fun protect(hashes: Set<String>) {
        protected = hashes.toSet()
    }

    @Synchronized fun normalize(bytes: ByteArray): PortraitDescriptor = store(prepare(bytes))

    /** The exact bytes [normalize] would store, without touching storage. */
    fun prepare(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..8 * 1024 * 1024)
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder,
                info,
                _
            ->
            require(!info.isAnimated && info.size.width.toLong() * info.size.height <= 16_000_000)
            val scale = minOf(1f, 256f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (
                    info.size.height *
                        scale
                    ).roundToInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val normalized = try {
            ByteArrayOutputStream().also {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            image.recycle()
        }
        require(normalized.size <= ProfileCatalog.MAX_PORTRAIT_BYTES)
        return normalized
    }

    /**
     * Exact bytes of an already normalized picture, as written by [normalize]: a native decode
     * check, at most 256 px per side, no animation and no textual/EXIF/time metadata chunks.
     * Returns false for anything else; the caller may then [normalize] it to a new hash.
     */
    fun adoptable(bytes: ByteArray): Boolean = runCatching {
        require(bytes.size in 1..ProfileCatalog.MAX_PORTRAIT_BYTES && metadataFreePng(bytes))
        val image = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ) { decoder, info, _ ->
            require(!info.isAnimated && info.size.width in 1..256 && info.size.height in 1..256)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
        image.recycle()
    }.isSuccess

    /** Content-addressed storage of [adoptable] bytes without re-encoding (same hash). */
    @Synchronized fun adopt(bytes: ByteArray): PortraitDescriptor {
        require(adoptable(bytes)) { "Unsupported picture." }
        return store(bytes.copyOf())
    }

    /** Removes one unprotected file; used only to undo an interrupted import's additions. */
    @Synchronized fun discard(hash: String) {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        if (hash !in protected) AtomicFile(File(directory, "$hash.png")).delete()
    }

    /** Snapshot all settled assets so an import cannot evict unrelated cached originals. */
    @Synchronized fun hashes(): Set<String> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}\\.png")) }
        .map { it.nameWithoutExtension }.toSet()

    /** Bytes the 8 MiB budget would keep if [hashes] were protected; hashes absent count as 0. */
    @Synchronized fun protectedBytes(hashes: Set<String>): Long = directory.listFiles().orEmpty()
        .filter { it.name.removeSuffix(".png") in hashes && it.name.endsWith(".png") }
        .sumOf { it.length() }

    private fun store(normalized: ByteArray): PortraitDescriptor {
        require(normalized.size <= ProfileCatalog.MAX_PORTRAIT_BYTES)
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") {
            "%02x".format(it)
        }
        check(directory.exists() || directory.mkdirs())
        val destination = File(directory, "$hash.png")
        if (!destination.exists()) {
            var used = directory.listFiles().orEmpty().sumOf { it.length() }
            val disposable = directory.listFiles().orEmpty().filter {
                it.name.matches(Regex("[a-f0-9]{64}\\.png")) &&
                    it.name.removeSuffix(".png") !in protected
            }.sortedBy { it.lastModified() }
            for (old in disposable) {
                if (used + normalized.size <= 8 * 1024 * 1024) break
                val size = old.length()
                if (old.delete()) used -= size
            }
            require(used + normalized.size <= 8 * 1024 * 1024) { "Portrait storage is full." }
        }
        val file = AtomicFile(destination)
        val output = file.startWrite()
        try {
            output.write(normalized)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        return PortraitDescriptor(hash, normalized.size, "/assets/$hash.png")
    }

    private fun metadataFreePng(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return false
        val input = ByteBuffer.wrap(bytes)
        input.position(8)
        while (input.remaining() >= 12) {
            val length = input.int
            if (length < 0 || length > input.remaining() - 8) return false
            val type = ByteArray(4).also(input::get).toString(Charsets.US_ASCII)
            if (type !in allowedChunks) return false
            input.position(input.position() + length + 4)
            if (type == "IEND") return !input.hasRemaining()
        }
        return false
    }

    @Synchronized fun file(hash: String): File? {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$hash.png").takeIf {
            it.exists() &&
                it.length() in 1..ProfileCatalog.MAX_PORTRAIT_BYTES
        }
    }

    @Synchronized fun retain(hashes: Set<String>) {
        val files = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("[a-f0-9]{64}\\.png"))
        }
        var bytes = files.sumOf { it.length() }
        val ordered = files.sortedWith(
            compareBy<File> {
                it.name.removeSuffix(".png") in hashes
            }.thenBy { it.lastModified() }
        )
        for (file in ordered) {
            if (file.name.removeSuffix(".png") !in hashes || bytes > 8 * 1024 * 1024) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }
}
