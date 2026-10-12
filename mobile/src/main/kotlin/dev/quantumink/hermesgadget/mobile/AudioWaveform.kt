package dev.quantumink.hermesgadget.mobile

import android.animation.ValueAnimator
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A live amplitude envelope, never a recording or fabricated moving signal. */
@Composable
internal fun AudioWaveform(level: Float, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val static = !ValueAnimator.areAnimatorsEnabled() ||
        context.getSystemService(PowerManager::class.java).isPowerSaveMode
    val bounded = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
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
            val count = 31
            repeat(count) { index ->
                val x = (index + .5f) * size.width / count
                val envelope = 1f - kotlin.math.abs(index - count / 2f) / count
                val height = (size.height * bounded * envelope).coerceAtLeast(2.dp.toPx())
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
