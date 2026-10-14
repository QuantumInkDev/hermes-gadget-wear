package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.TilePins
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class ProfileTileEntry(
    val key: String?,
    val label: String,
    val status: String,
    val pixels: ByteArray? = null
) {
    val available: Boolean get() = key != null && status != "Unavailable"
    val resourceId: String? get() = pixels?.let { "portrait-${digest(it)}" }
}

/** Reads only local normalized PNGs; no animation cache, transport, or service. */
class ProfileTileSnapshot(
    val version: String,
    val focus: ProfileTileEntry,
    val grid: List<ProfileTileEntry>
) {
    companion object {
        const val IMAGE_SIZE = 96
        fun build(
            pins: TilePins,
            pages: List<ProfilePage>,
            portraits: PortraitCache
        ): ProfileTileSnapshot {
            val entries = (listOf(pins.focus) + pins.grid).distinct().associateWith { key ->
                val page = pages.singleOrNull { it.key == key }
                val status = when {
                    key == null -> ""
                    page == null -> "Unavailable"
                    page.status != CatalogStatus.AVAILABLE -> "Offline"
                    page.approved == null -> "Review"
                    else -> "Open"
                }
                val bitmap = key?.let(portraits::load)
                val pixels = bitmap?.let {
                    try {
                        circle(it)
                    } finally {
                        it.recycle()
                    }
                }
                ProfileTileEntry(
                    key,
                    page?.label?.take(64) ?: if (key ==
                        null
                    ) {
                        "Set up"
                    } else {
                        "Unavailable"
                    },
                    status,
                    pixels
                )
            }
            val focus = entries.getValue(pins.focus)
            val grid = (pins.grid + List(4 - pins.grid.size) { null }).map {
                entries[it]
                    ?: ProfileTileEntry(null, "Set up", "")
            }
            val signature = buildString {
                append(pins.revision)
                for (entry in listOf(focus) + grid) {
                    for (value in listOf(
                        entry.key.orEmpty(),
                        entry.label,
                        entry.status,
                        entry.resourceId.orEmpty()
                    )) {
                        append('|').append(value.length).append(':').append(value)
                    }
                }
            }
            return ProfileTileSnapshot(digest(signature.toByteArray()), focus, grid)
        }
        private fun circle(source: Bitmap): ByteArray {
            val output = createBitmap(IMAGE_SIZE, IMAGE_SIZE)
            try {
                val canvas = Canvas(output)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawCircle(IMAGE_SIZE / 2f, IMAGE_SIZE / 2f, IMAGE_SIZE / 2f, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(source, null, Rect(0, 0, IMAGE_SIZE, IMAGE_SIZE), paint)
                return ByteBuffer.allocate(
                    IMAGE_SIZE * IMAGE_SIZE * 4
                ).also(output::copyPixelsToBuffer).array()
            } finally {
                output.recycle()
            }
        }
        fun read(context: Context): ProfileTileSnapshot {
            val pins = TilePinStore(context).read()
            val profiles = runCatching {
                IdentityVault(context).profiles()
            }.getOrDefault(emptyList())
            val catalog = runCatching {
                WatchCatalogVault(context).pages()
            }.getOrDefault(emptyList())
            return build(
                pins,
                ProfilePages.merge(profiles, catalog),
                PortraitCache(File(context.filesDir, "portrait-cache"))
            )
        }
    }
}

private fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
