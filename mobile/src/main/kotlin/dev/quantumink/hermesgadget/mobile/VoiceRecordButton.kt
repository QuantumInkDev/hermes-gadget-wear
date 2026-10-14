package dev.quantumink.hermesgadget.mobile

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceRecordButton(
    owner: Pair<Long, String?>,
    gesture: VoiceMessageGesture,
    enabled: Boolean,
    recording: Boolean,
    record: () -> Unit,
    finish: () -> Unit,
    cancel: () -> Unit,
    holding: (Boolean) -> Unit
) {
    val currentOwner by rememberUpdatedState(owner)
    val ready by rememberUpdatedState(enabled)
    val active by rememberUpdatedState(recording)
    val start by rememberUpdatedState(record)
    val send by rememberUpdatedState(finish)
    val discard by rememberUpdatedState(cancel)
    val hold by rememberUpdatedState(holding)
    fun terminate(outcome: VoiceMessageOutcome?) {
        if (outcome == null || currentOwner != owner) return
        hold(false)
        if (outcome == VoiceMessageOutcome.SEND) send() else discard()
    }
    fun accessibleAction(): Boolean {
        if (!ready) return false
        if (active) {
            val outcome = gesture.end(SystemClock.uptimeMillis()) ?: return false
            hold(false)
            if (outcome == VoiceMessageOutcome.SEND) send() else discard()
        } else {
            if (!gesture.begin(SystemClock.uptimeMillis() - 250)) return false
            hold(true)
            start()
        }
        return true
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            terminate(gesture.cancel())
            hold(false)
        }
    }
    DisposableEffect(gesture) { onDispose { terminate(gesture.cancel()) } }
    Box(
        Modifier.size(56.dp)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                CircleShape
            )
            .semantics {
                role = Role.Button
                contentDescription =
                    if (recording) "Finish and send recording" else "Hold to record"
                if (!enabled) disabled()
                onClick(if (recording) "Finish and send" else "Start recording") {
                    accessibleAction()
                }
                if (recording) {
                    customActions = listOf(
                        CustomAccessibilityAction("Cancel recording") {
                            terminate(gesture.cancel())
                            true
                        }
                    )
                }
            }
            .onKeyEvent {
                if ((it.key == Key.Enter || it.key == Key.Spacebar) &&
                    it.type == KeyEventType.KeyUp
                ) {
                    accessibleAction()
                } else {
                    false
                }
            }
            .focusable(enabled)
            .pointerInput(gesture) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!ready || active || currentOwner != owner ||
                        !gesture.begin(SystemClock.uptimeMillis())
                    ) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    hold(true)
                    start()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed ||
                                event.changes.any { it.id != down.id && it.pressed }
                            ) {
                                terminate(gesture.cancel())
                                break
                            }
                            if (gesture.move(
                                    down.position.x - change.position.x,
                                    change.position.y - down.position.y
                                )
                            ) {
                                terminate(gesture.cancel())
                            }
                            change.consume()
                            if (!change.pressed) {
                                terminate(gesture.end(SystemClock.uptimeMillis()))
                                break
                            }
                        }
                    } finally {
                        terminate(gesture.cancel())
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_voice_mic),
            null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
