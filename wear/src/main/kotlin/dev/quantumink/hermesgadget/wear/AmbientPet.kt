package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun petOutline(frame: Bitmap): Bitmap {
    require(frame.width <= 192 && frame.height <= 208)
    val width = frame.width
    val height = frame.height
    val pixels = IntArray(width * height)
    frame.getPixels(pixels, 0, width, 0, 0, width, height)
    fun occupied(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height &&
        (pixels[y * width + x] ushr 24) > 16
    val result = IntArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (occupied(x, y) && (
                    !occupied(x - 1, y) || !occupied(x + 1, y) ||
                        !occupied(x, y - 1) || !occupied(x, y + 1)
                    )
            ) {
                result[y * width + x] = -1
            }
        }
    }
    return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
}

/** Fixed-size, sparse monochrome fallback; never mutates or recycles the source portrait. */
internal fun lowBitPortrait(portrait: Bitmap): Bitmap {
    val side = 64
    val scaled = portrait.scale(side, side, false)
    val pixels = IntArray(side * side)
    scaled.getPixels(pixels, 0, side, 0, 0, side, side)
    if (scaled !== portrait) scaled.recycle()
    val thresholds = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    for (y in 0 until side) {
        for (x in 0 until side) {
            val color = pixels[y * side + x]
            val brightness = (
                54 * android.graphics.Color.red(color) +
                    183 * android.graphics.Color.green(color) +
                    19 * android.graphics.Color.blue(color)
                ) /
                256
            val dx = x + 0.5f - side / 2f
            val dy = y + 0.5f - side / 2f
            pixels[y * side + x] = if ((color ushr 24) > 16 &&
                dx * dx + dy * dy <= side * side / 4f &&
                brightness * 4 > thresholds[(y % 4) * 4 + x % 4] * 255
            ) {
                -1
            } else {
                0
            }
        }
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
}

@Composable
fun AmbientPet(
    pet: PetAtlas?,
    minute: Long,
    lowBit: Boolean,
    systemBurnInShift: Boolean,
    portrait: Bitmap? = null
) {
    val frame = pet?.rows?.get("idle")?.firstOrNull()
    val outline = remember(frame) { frame?.let(::petOutline) }
    DisposableEffect(outline) { onDispose { outline?.recycle() } }
    val monochrome = remember(portrait, frame, lowBit) {
        if (frame == null && lowBit) portrait?.let(::lowBitPortrait) else null
    }
    DisposableEffect(monochrome) { onDispose { monochrome?.recycle() } }
    val portraitPaint = remember {
        Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            alpha = 64
            isFilterBitmap = false
        }
    }
    Canvas(
        Modifier.fillMaxSize().background(Color.Black).semantics {
            contentDescription =
                "Quantum Relay for Hermes ambient profile. Wake the watch to interact."
        }
    ) {
        val native = drawContext.canvas.nativeCanvas
        val shiftX = if (systemBurnInShift) 0f else ((minute % 7) - 3).toFloat()
        val shiftY = if (systemBurnInShift) 0f else (((minute / 7) % 7) - 3).toFloat()
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = !lowBit
            isFilterBitmap = false
            strokeWidth = 1f
        }
        native.withTranslation(shiftX, shiftY) {
            val width = size.width * 0.38f
            val height = width * 104f / 96f
            val left = (size.width - width) / 2
            val top = (size.height - height) / 2 - size.height * 0.08f
            if (outline != null) {
                native.drawBitmap(
                    outline,
                    Rect(0, 0, outline.width, outline.height),
                    RectF(left, top, left + width, top + height),
                    paint
                )
            } else if (portrait != null) {
                val static = monochrome ?: portrait
                native.withSave {
                    if (!lowBit) {
                        native.clipPath(
                            Path().apply {
                                addCircle(
                                    left + width / 2,
                                    top + width / 2,
                                    width / 2,
                                    Path.Direction.CW
                                )
                            }
                        )
                    }
                    native.drawBitmap(
                        static,
                        Rect(0, 0, static.width, static.height),
                        RectF(left, top, left + width, top + width),
                        if (lowBit) paint else portraitPaint
                    )
                }
            } else {
                paint.style = Paint.Style.STROKE
                native.drawRect(
                    left + width * 0.23f,
                    top + height * 0.25f,
                    left + width * 0.77f,
                    top + height * 0.73f,
                    paint
                )
                native.drawLine(
                    left + width * 0.5f,
                    top + height * 0.17f,
                    left + width * 0.5f,
                    top + height * 0.25f,
                    paint
                )
            }
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = size.width * 0.065f
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(minute * 60000))
            native.drawText(time, size.width / 2, size.height * 0.78f, paint)
        }
    }
}
