package dev.quantumink.hermesgadget.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material3.MaterialTheme

/** Owns only the artwork's gesture; scrolling retains all pre-hold pointer movement. */
@Composable
internal fun Modifier.recordingPortrait(
    enabled: Boolean,
    listening: Boolean,
    start: () -> Unit,
    finish: () -> Unit,
    cancel: () -> Unit
): Modifier {
    val currentStart by rememberUpdatedState(start)
    val currentFinish by rememberUpdatedState(finish)
    val currentCancel by rememberUpdatedState(cancel)
    val currentListening by rememberUpdatedState(listening)
    val action =
        stringResource(if (listening) R.string.finish_recording else R.string.start_recording)
    return semantics {
        if (enabled) {
            role = Role.Button
            contentDescription = action
            onClick(action) {
                if (currentListening) currentFinish() else currentStart()
                true
            }
        }
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val distance = 40.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            var held = down
            val aborted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull true
                    if (!change.pressed || change.isConsumed ||
                        event.changes.count { it.pressed } != 1 ||
                        (change.position - down.position).getDistance() >
                        viewConfiguration.touchSlop
                    ) {
                        return@withTimeoutOrNull true
                    }
                    held = change
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    if (final.changes.any { it.isConsumed }) return@withTimeoutOrNull true
                }
            }
            if (aborted == true || currentListening) return@awaitEachGesture
            held.consume()
            var resolved = false
            currentStart()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                    if (event.changes.count { it.pressed } > 1) break
                    if (!change.pressed) {
                        if (!resolved) {
                            resolved = true
                            if (change.isConsumed) currentCancel() else currentFinish()
                        }
                        change.consume()
                        break
                    }
                    if (!resolved && change.position.y - down.position.y > distance) {
                        resolved = true
                        currentCancel()
                    }
                    change.consume()
                }
            } finally {
                if (!resolved) currentCancel()
            }
        }
    }
}

@Composable
internal fun RecordingRings(active: Boolean, selected: Boolean, level: Float) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        fun update() {
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            motion = !context.getSystemService(PowerManager::class.java).isPowerSaveMode &&
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) >
                0f
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = update()
        }
        val settings = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            settings
        )
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(settings)
        }
    }
    val visible = active && selected && foreground
    val flowing = visible && motion
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(flowing) {
        phase = 0f
        if (flowing) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { phase = ((it - start) % 1800000000L) / 1800000000f }
        }
    }
    if (visible) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier.fillMaxSize().semantics {
                stateDescription =
                    if (flowing) "Recording, outward rings" else "Recording, static level"
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val edge = size.minDimension / 2
                val outside = edge + 8.dp.toPx()
                if (flowing) {
                    repeat(3) { ring ->
                        val progress = (phase + ring / 3f) % 1f
                        drawCircle(
                            accent.copy(alpha = (1f - progress) * 0.8f),
                            edge + (1f + progress * 7f).dp.toPx(),
                            style = Stroke(1.5.dp.toPx())
                        )
                    }
                } else {
                    drawCircle(
                        accent,
                        outside,
                        style = Stroke((1.5f + level.coerceIn(0f, 1f)).dp.toPx())
                    )
                }
            }
        }
    }
}
