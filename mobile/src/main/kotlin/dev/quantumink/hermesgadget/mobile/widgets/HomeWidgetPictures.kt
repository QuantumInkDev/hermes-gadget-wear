package dev.quantumink.hermesgadget.mobile.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AtomicFile
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Static local PNG derivatives only. Never opens a catalog, GIF decoder or identity vault. */
class HomeWidgetPictures(private val directory: File) {
    private var protected = emptySet<String>()

    @Synchronized fun prepare(hash: String, source: File?): Boolean {
        require(validWidgetHash(hash))
        bitmap(hash, 256)?.let {
            it.recycle()
            return true
        }
        val corrupt = File(directory, "$hash.png")
        if (corrupt.exists() && !corrupt.delete()) return false
        if (source == null) return false
        return runCatching {
            val bytes = boundedBytes(source) ?: return false
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
            if (actual != hash) return false
            val image = decode(bytes) ?: return false
            val circle = createBitmap(256, 256)
            val canvas = Canvas(circle)
            canvas.clipPath(Path().apply { addCircle(128f, 128f, 128f, Path.Direction.CW) })
            val scale = minOf(256f / image.width, 256f / image.height)
            val width = image.width * scale
            val height = image.height * scale
            canvas.drawBitmap(
                image,
                null,
                RectF(
                    (256 - width) / 2,
                    (256 - height) / 2,
                    (256 + width) / 2,
                    (256 + height) / 2
                ),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            image.recycle()
            val encoded = try {
                ByteArrayOutputStream().also {
                    check(circle.compress(Bitmap.CompressFormat.PNG, 100, it))
                }.toByteArray()
            } finally {
                circle.recycle()
            }
            require(encoded.size <= ProfileCatalog.MAX_PORTRAIT_BYTES)
            check(directory.isDirectory || directory.mkdirs())
            var used = files().sumOf { it.length() }
            files().filter {
                it.name.removeSuffix(".png") !in protected
            }.sortedBy { it.lastModified() }.forEach { old ->
                if (used + encoded.size > MAX_BYTES) {
                    val size = old.length()
                    if (old.delete()) used -= size
                }
            }
            require(used + encoded.size <= MAX_BYTES)
            val destination = AtomicFile(File(directory, "$hash.png"))
            val output = destination.startWrite()
            try {
                output.write(encoded)
                destination.finishWrite(output)
            } catch (
                error: Exception
            ) {
                destination.failWrite(output)
                throw error
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized fun bitmap(hash: String, side: Int): Bitmap? {
        require(validWidgetHash(hash) && side in 16..256)
        val image = boundedBytes(File(directory, "$hash.png"))?.let(::decode) ?: return null
        if (image.width == side && image.height == side) return image
        return image.scale(side, side).also {
            if (it !==
                image
            ) {
                image.recycle()
            }
        }
    }

    @Synchronized fun retain(hashes: Set<String>) {
        require(hashes.size <= ProfileCatalog.MAX_PROFILES && hashes.all(::validWidgetHash))
        protected = hashes.toSet()
        files().filter { it.name.removeSuffix(".png") !in hashes }.forEach { it.delete() }
    }

    private fun files(): List<File> = directory.listFiles().orEmpty().filter {
        it.isFile &&
            it.name.matches(Regex("[a-f0-9]{64}\\.png"))
    }
    private fun boundedBytes(file: File): ByteArray? = runCatching {
        require(file.length() in 1..ProfileCatalog.MAX_PORTRAIT_BYTES.toLong())
        file.inputStream().use { input ->
            val buffer = ByteArray(ProfileCatalog.MAX_PORTRAIT_BYTES + 1)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            require(used in 1..ProfileCatalog.MAX_PORTRAIT_BYTES)
            buffer.copyOf(used)
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        require(bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..256 && bounds.outHeight in 1..256)
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig =
                    Bitmap.Config.ARGB_8888
            }
        )
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
