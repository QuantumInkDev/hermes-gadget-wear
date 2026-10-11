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
    @Synchronized fun normalize(bytes: ByteArray): PortraitDescriptor {
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
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") {
            "%02x".format(it)
        }
        check(directory.exists() || directory.mkdirs())
        val file = AtomicFile(File(directory, "$hash.png"))
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
