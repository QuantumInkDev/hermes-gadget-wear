package dev.quantumink.hermesgadget.mobile

import android.animation.ValueAnimator
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A live amplitude envelope, never a recording or fabricated moving signal. */
@Composable
internal fun AudioWaveform(level: Float, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val static = !ValueAnimator.areAnimatorsEnabled() ||
        context.getSystemService(PowerManager::class.java).isPowerSaveMode
    val currentLevel by rememberUpdatedState(level)
    val history = remember(label) { AudioLevelHistory(24) }
    var levels by remember(label) { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(label, static) {
        history.clear()
        levels = emptyList()
        if (!static) {
            while (true) {
                history.add(currentLevel)
                levels = history.snapshot()
                delay(50)
            }
        }
    }
    val color = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.outline
    Canvas(
        modifier.fillMaxWidth().height(32.dp).semantics {
            contentDescription = if (static) "$label · meter paused" else "$label · audio level"
        }
    ) {
        val center = size.height / 2
        if (static) {
            // A stationary baseline avoids ongoing visual motion under the system's policy.
            drawLine(
                quiet,
                Offset(0f, center),
                Offset(size.width, center),
                2.dp.toPx(),
                StrokeCap.Round
            )
        } else {
            val count = 24
            repeat(count) { index ->
                val x = (index + .5f) * size.width / count
                val sample = levels.getOrNull(index - (count - levels.size)) ?: 0f
                val height = (size.height * sample).coerceAtLeast(2.dp.toPx())
                drawLine(
                    color,
                    Offset(x, center - height / 2),
                    Offset(x, center + height / 2),
                    3.dp.toPx(),
                    StrokeCap.Round
                )
            }
        }
    }
}
