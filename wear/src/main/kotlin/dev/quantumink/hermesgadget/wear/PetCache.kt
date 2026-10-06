package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.core.graphics.scale
import dev.quantumink.hermesgadget.protocol.Message
import dev.quantumink.hermesgadget.protocol.PetManifest
import dev.quantumink.hermesgadget.protocol.PetSheet
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class PetAtlas(val name: String, val loopMs: Int, val rows: Map<String, List<Bitmap>>) {
    override fun toString(): String = "PetAtlas(redacted)"
}

/** App-private, endpoint-isolated cache. All decode/crop work runs on a storage worker. */
class PetCache(context: Context) {
    private val root = File(context.filesDir, "pet-cache")
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun directory(endpoint: String) =
        File(root, hash(endpoint.toByteArray())).apply { mkdirs() }

    @Synchronized fun remove(endpoint: String) {
        val file = File(root, hash(endpoint.toByteArray()))
        check(!file.exists() || file.deleteRecursively())
    }

    @Synchronized fun load(endpoint: String): PetAtlas? = runCatching {
        val file = File(directory(endpoint), "manifest.json")
        if (!file.isFile || file.length() > 16384) return null
        val message =
            Message.parse(
                AtomicFile(file).openRead().use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            )
                ?: return null
        build(endpoint, PetManifest.parse(message))
    }.getOrNull()

    @Synchronized fun saveManifest(endpoint: String, manifest: PetManifest): PetAtlas? {
        require(manifest.encoded.toByteArray().size <= 16384)
        atomic(File(directory(endpoint), "manifest.json"), manifest.encoded.toByteArray())
        evict()
        return build(endpoint, manifest)
    }

    @Synchronized fun saveAsset(
        endpoint: String,
        manifest: PetManifest,
        sheet: PetSheet,
        bytes: ByteArray
    ): PetAtlas? {
        require(manifest.sheets.any { it.sha256 == sheet.sha256 })
        val frames = decode(sheet, bytes)
        frames.values.flatten().forEach(Bitmap::recycle)
        atomic(File(directory(endpoint), sheet.sha256 + ".png"), bytes)
        evict()
        return build(endpoint, manifest)
    }

    private fun build(endpoint: String, manifest: PetManifest): PetAtlas? {
        val result = mutableMapOf<String, List<Bitmap>>()
        for (sheet in manifest.sheets.sortedBy { if (it.kind == "base") 0 else 1 }) {
            val file = File(directory(endpoint), sheet.sha256 + ".png")
            if (!file.isFile || file.length() != sheet.bytes.toLong()) continue
            val decoded = runCatching { decode(sheet, file.readBytes()) }.getOrNull() ?: continue
            file.setLastModified(System.currentTimeMillis())
            result.putAll(decoded)
        }
        return if (result["idle"].isNullOrEmpty()) {
            null
        } else {
            PetAtlas(
                manifest.name,
                manifest.loopMs,
                result.toMap()
            )
        }
    }

    internal fun decode(sheet: PetSheet, bytes: ByteArray): Map<String, List<Bitmap>> {
        require(
            bytes.size == sheet.bytes && bytes.size in 32..2097152 && hash(bytes) == sheet.sha256
        )
        require(
            bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        )
        require(bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray()))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(
            header.getInt(16) == sheet.width && header.getInt(20) == sheet.height &&
                bytes[25].toInt() in setOf(4, 6)
        )
        require(sheet.width.toLong() * sheet.height <= 3200000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == sheet.width && bounds.outHeight == sheet.height)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val prepared = mutableListOf<Bitmap>()
        try {
            require(bitmap.hasAlpha())
            val rows = sheet.rows.associate { row ->
                val frames = mutableListOf<Bitmap>()
                for (column in 0 until row.frames) {
                    val frame = Bitmap.createBitmap(bitmap, column * 192, row.index * 208, 192, 208)
                    val pixels = IntArray(192 * 208)
                    frame.getPixels(pixels, 0, 192, 0, 0, 192, 208)
                    if (pixels.none { (it ushr 24) > 16 }) {
                        frame.recycle()
                        break
                    }
                    if (pixels.none { (it ushr 24) < 255 }) {
                        frame.recycle()
                        error("Opaque frame")
                    }
                    val scaled = frame.scale(96, 104, false)
                    frame.recycle()
                    frames.add(scaled)
                    prepared.add(scaled)
                }
                require(frames.isNotEmpty() && (row.name != "talking" || frames.size == 3))
                row.name to frames.toList()
            }
            return rows
        } catch (failure: Exception) {
            prepared.forEach(Bitmap::recycle)
            throw failure
        } finally {
            bitmap.recycle()
        }
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (failure: Exception) {
            target.failWrite(stream)
            throw failure
        }
    }
    private fun evict() {
        val files = root.walkTopDown().filter { it.isFile }.toList().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (total <= 8 * 1024 * 1024 && count <= 64) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                count--
            }
        }
    }
}
