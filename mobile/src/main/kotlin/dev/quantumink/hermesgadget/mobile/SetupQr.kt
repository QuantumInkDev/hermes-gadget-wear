package dev.quantumink.hermesgadget.mobile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import dev.quantumink.hermesgadget.protocol.UntrustedSetupReview
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Raster QR only. A decoded code is untrusted text for SetupMetadataCodec, never authority. */
internal object SetupQr {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 40_000_000L
    private const val DECODE_SIDE = 1600

    fun render(text: String, size: Int = 720): Bitmap {
        require(text.toByteArray(Charsets.UTF_8).size <= SetupMetadataCodec.MAX_BYTES)
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 4
            )
        )
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }.toByteArray()

    /**
     * Bounded decode of a user-selected picture. Dimensions are checked from the header before
     * any pixel allocation; large pictures are scaled down while decoding. IO worker only.
     */
    fun decode(bytes: ByteArray): String? {
        requireTransferWorker()
        require(bytes.size in 1..MAX_IMAGE_BYTES)
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width in 1..MAX_SIDE && height in 1..MAX_SIDE)
            require(width.toLong() * height <= MAX_PIXELS)
            val scale = minOf(1f, DECODE_SIDE.toFloat() / max(width, height))
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            runCatching {
                QRCodeReader().decode(
                    BinaryBitmap(HybridBinarizer(source)),
                    mapOf(
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.CHARACTER_SET to "UTF-8"
                    )
                ).text
            }.getOrNull()?.takeIf {
                it.toByteArray(Charsets.UTF_8).size <= SetupMetadataCodec.MAX_BYTES
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/** Reads at most [limit] bytes from a user-selected document into a private array. */
internal fun readBounded(resolver: ContentResolver, uri: Uri, limit: Int): ByteArray {
    requireTransferWorker()
    val declared = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L
    require(declared <= limit) { "Selected file is too large." }
    return requireNotNull(resolver.openInputStream(uri)) { "Selected file is unavailable." }
        .use { readBounded(it, limit, declared) }
}

internal fun readBounded(input: InputStream, limit: Int, declared: Long = -1): ByteArray {
    if (declared in 0..limit.toLong()) {
        val bytes = ByteArray(declared.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            require(count > 0) { "Selected file changed while reading." }
            offset += count
        }
        require(input.read() == -1) { "Selected file changed while reading." }
        return bytes
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Selected file is too large." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/**
 * Installation-local record of setup codes already added. Setup IDs are not secrets. Bounded
 * and pruned after expiry; any unreadable state refuses new adds until the owner clears it.
 */
internal class SetupReplayJournal(private val directory: File) {
    private val file get() = AtomicFile(File(directory, "replay.json"))

    @Synchronized fun spent(id: String, now: Long): Boolean = read(now).containsKey(id)

    @Synchronized fun spend(id: String, expires: Long, now: Long) {
        val entries = read(now)
        if (entries[id] == expires) return // Recovery after a completed replay write.
        require(entries.size < MAX_ENTRIES) { "Too many recent setup codes." }
        write(entries + (id to expires))
    }

    @Synchronized fun requireCapacity(now: Long) {
        require(read(now).size < MAX_ENTRIES) { "Too many recent setup codes." }
    }

    /** True when the record exists but cannot be read; adds stay refused until cleared. */
    @Synchronized fun unreadable(now: Long): Boolean = runCatching { read(now) }.isFailure

    @Synchronized fun clearUnreadable(now: Long) {
        if (unreadable(now)) file.delete()
    }

    private fun read(now: Long): Map<String, Long> {
        val atomic = file
        if (!atomic.baseFile.exists() && !File(directory, "replay.json.bak").exists()) {
            return emptyMap()
        }
        val text = atomic.openRead().use { input ->
            require(input.channel.size() in 1..MAX_BYTES.toLong())
            input.readBytes().toString(Charsets.UTF_8)
        }
        val item = Json.parseToJsonElement(text) as JsonObject
        require(item.keys == setOf("version", "spent"))
        require((item.getValue("version") as JsonPrimitive).content == "1")
        val spent = item.getValue("spent") as JsonArray
        require(spent.size <= MAX_ENTRIES)
        return spent.associate { value ->
            val entry = value as JsonObject
            require(entry.keys == setOf("id", "expires"))
            val id = (entry.getValue("id") as JsonPrimitive).also { require(it.isString) }.content
            require(id.matches(Regex("[a-f0-9]{32}")))
            val expires = (entry.getValue("expires") as JsonPrimitive).content.toLong()
            id to expires
        }.also { require(it.size == spent.size) }.filterValues { it > now }
    }

    private fun write(entries: Map<String, Long>) {
        val bytes = buildJsonObject {
            put("version", 1)
            put(
                "spent",
                JsonArray(
                    entries.map { (id, expires) ->
                        buildJsonObject {
                            put("id", id)
                            put("expires", expires)
                        }
                    }
                )
            )
        }.toString().toByteArray()
        check(directory.isDirectory || directory.mkdirs())
        val atomic = file
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val MAX_ENTRIES = 256
        const val MAX_BYTES = 64 * 1024
    }
}

/** Owner choices on the review screen; nothing here comes from the QR code itself. */
internal class QrAddChoice(
    val privateNetwork: Boolean = false,
    val cleartextAccepted: Boolean = false,
    val catalogCredential: String = ""
) {
    override fun toString(): String = "QrAddChoice(content=redacted)"
}

internal enum class QrAddResult { ADDED, ALREADY_SAVED, ALREADY_USED, EXPIRED, UNAVAILABLE }

internal class QrAddOutcome(val result: QrAddResult, val publication: SetupPublication?)

internal object SetupQrImport {
    /**
     * Adds reviewed metadata only (no token, no discovery, no pairing), then spends the setup ID.
     * A failed write leaves the ID unspent; an existing entry is never changed.
     */
    fun add(
        stores: SetupTransferStores,
        review: UntrustedSetupReview,
        choice: QrAddChoice,
        now: Long
    ): QrAddResult = addReviewed(stores, review, choice, now).result

    fun addReviewed(
        stores: SetupTransferStores,
        review: UntrustedSetupReview,
        choice: QrAddChoice,
        now: Long
    ): QrAddOutcome {
        requireTransferWorker()
        val replay = SetupReplayJournal(stores.directory)
        val metadata = review.metadata
        if (metadata.expiresAtEpochSeconds <= now) return QrAddOutcome(QrAddResult.EXPIRED, null)
        val (result, catalog) = stores.catalog.importExclusive({ it == QrAddResult.ADDED }) {
            check(!SetupImport.blocked(stores)) { "An earlier import record needs attention." }
            if (replay.unreadable(now)) return@importExclusive QrAddResult.UNAVAILABLE
            if (replay.spent(metadata.setupId, now)) return@importExclusive QrAddResult.ALREADY_USED
            // Build every value first so an invalid choice cannot leave a partial add.
            val endpoint = metadata.endpoint?.takeIf { setup ->
                stores.profiles.profiles().none { it.endpoint.url == setup.url }
            }?.let { setup ->
                val parsed = if (setup.url.startsWith("ws:")) {
                    Endpoint.parse(setup.url, choice.privateNetwork, choice.cleartextAccepted)
                } else {
                    Endpoint.parse(setup.url)
                }
                EndpointProfile(parsed, setup.label, "", setup.transport)
            }
            val sources = stores.catalog.vault.sources()
            val source = metadata.catalog?.takeIf { catalog ->
                sources.none {
                    runCatching { dev.quantumink.hermesgadget.protocol.SetupCatalog.from(it).url }
                        .getOrNull() == catalog.url
                }
            }?.let { catalog ->
                val taken = sources.map { it.id }.toSet()
                val id = (listOf(catalog.id) + (2..99).map { catalog.id.take(60) + "-" + it })
                    .first { it !in taken }
                CatalogSource(id, catalog.label, catalog.url, choice.catalogCredential.trim())
            }
            if (source != null) {
                require(
                    sources.size < dev.quantumink.hermesgadget.protocol.ProfileCatalog.MAX_SOURCES
                )
            }
            val added = endpoint != null || source != null
            if (!added) return@importExclusive QrAddResult.ALREADY_SAVED
            replay.requireCapacity(now)
            val current = LocalTransferState.read(stores)
            dev.quantumink.hermesgadget.protocol.EndpointProfiles.encode(
                current.endpoints + listOfNotNull(endpoint)
            )
            val plan = ImportPlan(
                listOfNotNull(endpoint),
                emptyList(),
                listOfNotNull(source),
                emptyMap(),
                current.view.raw,
                current.view.fallbacks,
                current.view.overrides,
                current.view.animations,
                null,
                null,
                current.templates,
                current.appearance,
                emptyList(),
                emptyList(),
                0
            )
            SetupImport.applyJournaled(
                stores,
                current,
                plan,
                metadata.setupId to metadata.expiresAtEpochSeconds
            )
            QrAddResult.ADDED
        }
        val publication = if (result ==
            QrAddResult.ADDED
        ) {
            SetupImport.publishAfterCommit(stores, catalog)
        } else {
            null
        }
        return QrAddOutcome(result, publication)
    }
}
