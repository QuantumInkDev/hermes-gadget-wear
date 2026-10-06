package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import android.graphics.Paint
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

@Composable
fun AmbientPet(pet: PetAtlas?, minute: Long, lowBit: Boolean, systemBurnInShift: Boolean) {
    val frame = pet?.rows?.get("idle")?.firstOrNull()
    val outline = remember(frame) { frame?.let(::petOutline) }
    DisposableEffect(outline) { onDispose { outline?.recycle() } }
    Canvas(
        Modifier.fillMaxSize().background(Color.Black).semantics {
            contentDescription = "Hermes ambient pet. Wake the watch to interact."
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
