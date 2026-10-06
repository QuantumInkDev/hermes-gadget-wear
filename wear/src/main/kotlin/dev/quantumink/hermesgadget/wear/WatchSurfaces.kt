package dev.quantumink.hermesgadget.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.flow.MutableStateFlow

class SurfaceSnapshot(
    val label: String = "Hermes",
    val status: String = "Offline",
    val reply: String = ""
) {
    override fun toString(): String = "SurfaceSnapshot(redacted)"
}

object WatchSurfaces {
    val current = MutableStateFlow(SurfaceSnapshot())
    private var lastUpdate = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    @Synchronized fun publish(context: Context, watch: WatchState, force: Boolean = false) {
        val conversation = watch.connection.conversation
        val status = when {
            watch.connection.status == ConnectionStatus.ERROR -> "Error"
            watch.connection.status == ConnectionStatus.PAIRING -> "Pair"
            watch.connection.status != ConnectionStatus.PAIRED -> "Offline"
            conversation.prompt != null -> "Approve"
            conversation.mode == ConversationMode.LISTENING -> "Listen"
            watch.speaking -> "Talking"
            conversation.busy -> "Working"
            else -> "Ready"
        }
        val next = SurfaceSnapshot(
            watch.profile?.label ?: "Hermes",
            status,
            conversation.reply.replace(Regex("\\s+"), " ").take(120)
        )
        val previous = current.value
        current.value = next
        val now = android.os.SystemClock.elapsedRealtime()
        val changed = previous.status != next.status || previous.reply != next.reply ||
            previous.label != next.label
        if (!force && !changed) return
        if (force || now - lastUpdate >= 2000) {
            pending?.let(handler::removeCallbacks)
            pending = null
            refresh(context.applicationContext)
        } else if (pending == null) {
            val application = context.applicationContext
            pending = Runnable {
                synchronized(this) {
                    pending = null
                    refresh(application)
                }
            }
            handler.postDelayed(requireNotNull(pending), 2000 - (now - lastUpdate))
        }
    }
    private fun refresh(context: Context) {
        lastUpdate = android.os.SystemClock.elapsedRealtime()
        runCatching { TileService.getUpdater(context).requestUpdate(GadgetTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, GadgetComplication::class.java)
            ).requestUpdateAll()
        }
    }
    fun read(context: Context): SurfaceSnapshot {
        val live = current.value
        if (live.status != "Offline" || live.reply.isNotEmpty()) return live
        return runCatching { IdentityVault(context).surface() }.getOrNull()?.let {
            SurfaceSnapshot(it.first, "Offline", it.second)
        } ?: live
    }
    fun launch(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or
            PendingIntent.FLAG_UPDATE_CURRENT
    )
}
