package dev.quantumink.hermesgadget.wear

import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.delay

internal const val SCREEN_LIMIT_NOTICE = "Still active. Screen limit reached; reopen to check."

internal val WatchState.conversationInProgress: Boolean
    get() = connection.status == ConnectionStatus.PAIRED &&
        (
            connection.conversation.mode == ConversationMode.LISTENING ||
                connection.conversation.busy || speechPending || speaking
            )

/** Elapsed realtime originates at an explicit user turn, never at a status/meter update. */
internal object ConversationScreenLease {
    const val LIMIT_MILLIS = 120000L
    fun remaining(started: Long?, now: Long): Long = if (started ==
        null
    ) {
        0
    } else {
        (LIMIT_MILLIS - (now - started).coerceAtLeast(0)).coerceAtLeast(0)
    }
}

@Composable
internal fun KeepConversationVisible(
    window: Window,
    started: Long?,
    active: Boolean,
    ambient: Boolean,
    expired: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val onExpired by rememberUpdatedState(expired)
    DisposableEffect(window, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(window, started, active, ambient, foreground) {
        val remaining = ConversationScreenLease.remaining(started, SystemClock.elapsedRealtime())
        if (foreground && !ambient && active && remaining > 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                delay(remaining)
                onExpired()
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (foreground && !ambient && active && started != null && remaining == 0L) onExpired()
        }
    }
}
