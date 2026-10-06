package dev.quantumink.hermesgadget.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.edit
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.quantumink.hermesgadget.protocol.DuplexPump
import dev.quantumink.hermesgadget.protocol.RelayForwarder
import dev.quantumink.hermesgadget.protocol.RelayProtocol
import dev.quantumink.hermesgadget.protocol.RelayStreams
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RelaySettings {
    private const val FILE = "relay-settings"
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()
    val sessions = ConcurrentHashMap<ChannelClient.Channel, () -> Unit>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", value)
        }
        if (!value) stop()
    }

    fun report(value: String) {
        mutableStatus.value = value
    }
    fun stop() {
        sessions.values.toList().forEach { it() }
    }
}

class RelayStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RelaySettings.stop()
    }
}

/** The Play Services listener receives only same-package/same-signature wearable channels. */
class RelayService : WearableListenerService() {
    private val slots = Semaphore(2)
    private val streams = ConcurrentHashMap<ChannelClient.Channel, RelayStreams>()
    private val pumps = ConcurrentHashMap<ChannelClient.Channel, DuplexPump>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
    private val clock = Executors.newSingleThreadScheduledExecutor()
    private val foreground = AtomicBoolean(false)
    private val owned = ConcurrentHashMap<ChannelClient.Channel, () -> Unit>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                "relay",
                getString(R.string.relay_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (streams.isEmpty() && pumps.isEmpty() && slots.availablePermits() == 2) stopSelf()
        return START_NOT_STICKY
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        if (channel.path != RelayProtocol.CHANNEL_PATH || !RelaySettings.enabled(this) ||
            !slots.tryAcquire()
        ) {
            client.close(channel)
            return
        }
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                streams.remove(channel)?.close()
                pumps.remove(channel)?.close()
                RelaySettings.sessions.remove(channel)
                owned.remove(channel)
                client.close(channel)
                slots.release()
                if (slots.availablePermits() == 2) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground.set(false)
                    stopSelf()
                }
            }
        }
        RelaySettings.sessions[channel] = release
        owned[channel] = release
        if (!beginForeground()) {
            release()
            return
        }
        try {
            workers.execute {
                val deadline = clock.schedule({ release() }, 15, TimeUnit.SECONDS)
                try {
                    val input = Tasks.await(client.getInputStream(channel), 5, TimeUnit.SECONDS)
                    val output = Tasks.await(
                        client.getOutputStream(channel),
                        5,
                        TimeUnit.SECONDS
                    )
                    val pipe = RelayStreams(input, output, { client.close(channel) })
                    streams[channel] = pipe
                    check(!released.get())
                    val pump = RelayForwarder.accept(
                        pipe,
                        {
                            RelaySettings.enabled(this) &&
                                (
                                    Build.VERSION.SDK_INT < 37 ||
                                        checkSelfPermission(
                                            Manifest.permission.ACCESS_LOCAL_NETWORK
                                        ) ==
                                        PackageManager.PERMISSION_GRANTED
                                    )
                        },
                        release
                    )
                    pumps[channel] = pump
                    if (released.get()) {
                        pump.close()
                    } else {
                        RelaySettings.report(
                            getString(R.string.relay_active)
                        )
                    }
                } catch (_: Exception) {
                    RelaySettings.report(getString(R.string.relay_failed))
                    release()
                } finally {
                    deadline.cancel(false)
                }
            }
        } catch (_: Exception) {
            release()
        }
    }

    override fun onChannelClosed(
        channel: ChannelClient.Channel,
        closeReason: Int,
        appSpecificErrorCode: Int
    ) {
        RelaySettings.sessions[channel]?.invoke()
        if (owned.isEmpty()) RelaySettings.report(getString(R.string.relay_idle))
    }

    @Synchronized private fun beginForeground(): Boolean = try {
        if (!foreground.get()) {
            startForegroundService(Intent(this, RelayService::class.java))
            val stop = PendingIntent.getBroadcast(
                this,
                0,
                Intent(this, RelayStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val open = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            startForeground(
                1,
                Notification.Builder(this, "relay").setSmallIcon(R.drawable.ic_gadget)
                    .setContentTitle(getString(R.string.relay_active))
                    .setContentText(getString(R.string.relay_notification))
                    .setContentIntent(
                        open
                    ).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.stop_relay),
                            stop
                        ).build()
                    )
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            foreground.set(true)
        }
        true
    } catch (_: Exception) {
        RelaySettings.report(getString(R.string.relay_background_denied))
        false
    }

    override fun onDestroy() {
        owned.values.toList().forEach { it() }
        pumps.values.forEach { it.close() }
        workers.shutdownNow()
        clock.shutdownNow()
        super.onDestroy()
    }
}
