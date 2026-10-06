package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.PetStates
import kotlinx.coroutines.delay

@Composable
fun PetView(watch: WatchState, compact: Boolean = false) {
    val conversation = watch.connection.conversation
    val mode = when {
        conversation.prompt != null -> "waiting"
        watch.connection.status == ConnectionStatus.ERROR -> "failed"
        watch.connection.status != ConnectionStatus.PAIRED -> "sleeping"
        conversation.mode == ConversationMode.LISTENING -> "listening"
        watch.speaking -> "talking"
        watch.petCue.isNotEmpty() -> watch.petCue
        conversation.mode == ConversationMode.THINKING -> "running"
        conversation.transcript.isNotEmpty() &&
            conversation.mode != ConversationMode.READY -> "review"
        else -> "idle"
    }
    val atlas = watch.pet
    val row = PetStates.row(mode, atlas?.rows?.keys.orEmpty())
    val frames = atlas?.rows?.get(row)
    var frame by remember(atlas, mode, row) { mutableIntStateOf(0) }
    var mouth by remember(atlas) { mutableIntStateOf(0) }
    LaunchedEffect(watch.playbackLevel, mode) {
        mouth = if (mode == "talking") PetStates.mouth(watch.playbackLevel, mouth) else 0
    }
    LaunchedEffect(atlas, mode, row) {
        val count = frames?.size ?: 1
        if (mode !in setOf("sleeping", "talking") && count > 1) {
            while (true) {
                delay(((atlas?.loopMs ?: 1100) / count).toLong())
                frame =
                    (frame + 1) % count
            }
        }
    }
    val bitmap = frames?.get(
        if (row ==
            "talking"
        ) {
            mouth.coerceAtMost(frames.lastIndex)
        } else {
            frame.coerceAtMost(frames.lastIndex)
        }
    )
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(
        Modifier.size(if (compact) 48.dp else 96.dp, if (compact) 52.dp else 104.dp).semantics {
            contentDescription =
                "${atlas?.name ?: "Sample pet"}: $mode"
        }
    ) {
        val bob = if (mode == "talking" &&
            row != "talking"
        ) {
            watch.playbackLevel.coerceIn(0f, 0.5f) * 6.dp.toPx()
        } else {
            0f
        }
        if (image != null) {
            drawImage(
                image,
                dstOffset = IntOffset(0, -bob.toInt()),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None
            )
        } else {
            // Original procedural sample: a small amber robot, independent of Hermes pet art.
            val amber = Color(0xFFFFBA66)
            val left = size.width * 0.23f
            val top = size.height * 0.25f - bob
            drawRect(amber, Offset(left, top), Size(size.width * 0.54f, size.height * 0.48f))
            val eyeY = top + size.height * 0.15f
            for (eye in listOf(0.36f, 0.64f)) {
                if (mode ==
                    "sleeping"
                ) {
                    drawLine(
                        Color.Black,
                        Offset(size.width * eye - 4.dp.toPx(), eyeY),
                        Offset(size.width * eye + 4.dp.toPx(), eyeY),
                        2.dp.toPx()
                    )
                } else {
                    drawRect(
                        Color.Black,
                        Offset(size.width * eye - 3.dp.toPx(), eyeY),
                        Size(6.dp.toPx(), 6.dp.toPx())
                    )
                }
            }
            drawRect(
                Color.Black,
                Offset(size.width * 0.43f, top + size.height * 0.32f),
                Size(size.width * 0.14f, (2 + mouth * 3).dp.toPx())
            )
            drawLine(
                amber,
                Offset(size.width * 0.5f, top),
                Offset(
                    size.width * 0.5f,
                    top - 8.dp.toPx()
                ),
                3.dp.toPx()
            )
            drawCircle(
                amber,
                3.dp.toPx(),
                Offset(size.width * 0.5f, top - 10.dp.toPx()),
                style = Stroke(2.dp.toPx())
            )
        }
    }
}
