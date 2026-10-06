package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.AlarmClock
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.WatchAction
import dev.quantumink.hermesgadget.protocol.WatchActions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WatchState(
    val profile: EndpointProfile? = null,
    val connection: ConnectionState = ConnectionState(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val setupError: String = "",
    val haptic: Long = 0
) {
    override fun toString(): String = "WatchState(content=redacted)"
}

class GadgetService : Service() {
    inner class LocalBinder : Binder() {
        val service: GadgetService get() = this@GadgetService
    }
    private val binder = LocalBinder()
    private val mutableState = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = mutableState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val storage = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private lateinit var vault: IdentityVault
    private lateinit var capture: PcmCapture
    private lateinit var playback: PcmPlayback

    @Volatile private var connection: DirectConnection? = null

    @Volatile private var visible = false

    @Volatile private var foreground = false
    private var timerLauncher: ((Intent) -> Boolean)? = null
    private var brightness: ((Float?) -> Boolean)? = null
    private var destroyed = false
    private var savedIdentity: DeviceIdentity? = null
    private var advertisedActions: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        capture = PcmCapture(this)
        playback = PcmPlayback(this) { connection?.report("Playback interrupted.") }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.conversation_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                getString(R.string.alert_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        storage.execute {
            val restored = runCatching {
                vault = IdentityVault(this)
                val profile = vault.profile()
                profile?.let { it to vault.identity(it.endpoint) }
            }
            main.post {
                if (destroyed) return@post
                restored.fold(
                    onSuccess = { saved ->
                        if (saved != null) configure(saved.first, saved.second)
                        mutableState.update { it.copy(loading = false) }
                    },
                    onFailure = {
                        mutableState.update {
                            it.copy(
                                loading = false,
                                setupError = getString(R.string.storage_failed)
                            )
                        }
                    }
                )
            }
        }
        main.postDelayed(
            object : Runnable {
                override fun run() {
                    if (destroyed) return
                    connection?.updateSensors(battery())
                    main.postDelayed(this, 60000)
                }
            },
            60000
        )
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) disconnect()
        return START_NOT_STICKY
    }

    fun attach(timer: (Intent) -> Boolean, screenBrightness: (Float?) -> Boolean) {
        visible = true
        timerLauncher = timer
        brightness = screenBrightness
    }

    fun hidden() {
        visible = false
        timerLauncher = null
        brightness?.invoke(null)
        brightness = null
        capture.stop()
        connection?.cancelRecording()
        if (!foreground) disconnect()
    }

    fun save(
        url: String,
        label: String,
        token: String,
        privateNetwork: Boolean,
        accepted: Boolean,
        ready: () -> Unit
    ) {
        if (mutableState.value.saving || mutableState.value.loading) return
        val profile = try {
            EndpointProfile(
                Endpoint.parse(url, privateNetwork, accepted),
                label.trim(),
                token.trim()
            )
        } catch (failure: IllegalArgumentException) {
            mutableState.update {
                it.copy(
                    setupError =
                    failure.message ?: getString(R.string.invalid_setup)
                )
            }
            return
        }
        mutableState.update { it.copy(saving = true, setupError = "") }
        storage.execute {
            val result = runCatching { vault.save(profile) }
            main.post {
                if (destroyed) return@post
                result.fold(
                    onSuccess = { identity ->
                        configure(profile, identity)
                        mutableState.update { it.copy(saving = false) }
                        ready()
                    },
                    onFailure = {
                        mutableState.update {
                            it.copy(saving = false, setupError = getString(R.string.storage_failed))
                        }
                    }
                )
            }
        }
    }

    fun connect() {
        val profile = mutableState.value.profile ?: return
        if (Build.VERSION.SDK_INT >= 37 && profile.endpoint.privateNetwork &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            connection?.report(getString(R.string.local_permission_needed))
            return
        }
        if (availableActions() != advertisedActions) {
            savedIdentity?.let { configure(profile, it) }
        }
        connection?.connect()
    }

    fun disconnect() {
        capture.stop()
        playback.stop()
        connection?.disconnect()
        endForeground()
        brightness?.invoke(null)
    }

    fun text(value: String) {
        if (visible && mutableState.value.connection.status == ConnectionStatus.PAIRED &&
            mutableState.value.connection.conversation.prompt == null && beginForeground(false)
        ) {
            connection?.text(value)
        }
    }

    fun prepareTextInput(): Boolean = visible &&
        mutableState.value.connection.status == ConnectionStatus.PAIRED && beginForeground(false)

    fun startRecording() {
        if (!visible || mutableState.value.connection.status != ConnectionStatus.PAIRED ||
            mutableState.value.connection.conversation.prompt != null
        ) {
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            connection?.report(getString(R.string.microphone_permission_needed))
            return
        }
        if (beginForeground(true)) connection?.startRecording()
    }

    fun finishRecording() {
        connection?.finishRecording()
    }
    fun cancelRecording() {
        capture.stop()
        connection?.cancelRecording()
    }
    fun cancel(newSession: Boolean = false) {
        connection?.cancel(newSession)
    }
    fun answer(id: String, yes: Boolean) {
        connection?.answer(id, yes)
    }
    fun dismissDisplay() {
        connection?.dismissDisplay()
    }
    fun report(value: String) {
        connection?.report(value)
    }

    fun reset() {
        closeConnection()
        mutableState.update { it.copy(saving = true) }
        storage.execute {
            val result = runCatching { IdentityVault(this).reset() }
            main.post {
                if (destroyed) return@post
                mutableState.value = if (result.isSuccess) {
                    vault = IdentityVault(this)
                    WatchState(loading = false)
                } else {
                    mutableState.value.copy(
                        saving = false,
                        setupError = getString(R.string.storage_failed)
                    )
                }
            }
        }
    }

    private fun configure(profile: EndpointProfile, identity: DeviceIdentity) {
        closeConnection()
        savedIdentity = identity
        val version = epoch.incrementAndGet()
        val actions = availableActions()
        advertisedActions = actions
        val caps = WatchActions.capabilities().toMutableMap()
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) caps.remove("mic")
        if (!packageManager.hasSystemFeature(
                PackageManager.FEATURE_AUDIO_OUTPUT
            )
        ) {
            caps.remove("speaker")
        }
        connection = DirectConnection(
            profile.endpoint,
            identity,
            object : ConnectionObserver {
                override fun stateChanged(state: ConnectionState) {
                    if (version != epoch.get()) return
                    mutableState.update {
                        if (version == epoch.get()) it.copy(connection = state) else it
                    }
                    if (state.status in setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)) {
                        main.post {
                            if (version == epoch.get()) {
                                endForeground()
                                brightness?.invoke(null)
                            }
                        }
                    }
                }
                override fun effect(effect: ConversationEffect, generation: Long) {
                    if (version != epoch.get()) return
                    when (effect) {
                        is ConversationEffect.CaptureStart -> {
                            if (!visible || !foreground) {
                                connection?.cancelRecording()
                            } else {
                                val current = connection ?: return
                                capture.start(
                                    { bytes -> current.captured(effect.token, bytes) },
                                    {
                                        current.cancelRecording()
                                        current.report(getString(R.string.capture_failed))
                                    }
                                )
                            }
                        }
                        ConversationEffect.CaptureStop -> {
                            capture.stop()
                            main.post {
                                if (version == epoch.get() &&
                                    foreground
                                ) {
                                    beginForeground(false)
                                }
                            }
                        }
                        is ConversationEffect.PlaybackStart -> if (visible ||
                            foreground
                        ) {
                            playback.start(effect.rate)
                        }
                        is ConversationEffect.PlaybackData -> playback.offer(effect.bytes)
                        ConversationEffect.PlaybackFinish -> playback.finish()
                        ConversationEffect.PlaybackStop -> playback.stop()
                        is ConversationEffect.Action -> main.post {
                            if (version == epoch.get()) executeAction(effect, generation)
                        }
                        is ConversationEffect.Haptic -> mutableState.update {
                            if (version == epoch.get()) it.copy(haptic = it.haptic + 1) else it
                        }
                        else -> Unit
                    }
                }
            },
            accessToken = profile.accessToken.takeIf { it.isNotEmpty() },
            availableActions = actions,
            sensors = battery(),
            capabilities = JsonObject(caps)
        )
        mutableState.update {
            it.copy(profile = profile, connection = ConnectionState(), setupError = "")
        }
    }

    private fun availableActions(): Set<String> = buildSet {
        if (getSystemService(
                VibratorManager::class.java
            ).defaultVibrator.hasVibrator()
        ) {
            add("watch.vibrate")
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        ) {
            add("notification.show")
        }
        if (Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(packageManager) !=
            null
        ) {
            add("timer.start")
        }
        add("screen.brightness")
    }

    private fun executeAction(effect: ConversationEffect.Action, generation: Long) {
        val current = connection ?: return
        val result = runCatching {
            when (val action = effect.action) {
                is WatchAction.Vibrate -> {
                    getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                        VibrationEffect.createOneShot(
                            action.durationMs.toLong(),
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                    buildJsonObject { put("vibrated", true) }
                }
                is WatchAction.Notification -> {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        error("Notification permission is unavailable.")
                    }
                    val manager = getSystemService(NotificationManager::class.java)
                    check(manager.areNotificationsEnabled())
                    manager.notify(
                        ALERT_ID,
                        Notification.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_gadget)
                            .setContentTitle(action.title).setContentText(action.text)
                            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
                            .setContentIntent(openApp()).build()
                    )
                    buildJsonObject { put("shown", true) }
                }
                is WatchAction.Timer -> {
                    check(
                        visible && timerLauncher?.invoke(
                            Intent(
                                AlarmClock.ACTION_SET_TIMER
                            ).putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        ) == true
                    )
                    buildJsonObject { put("requested", true) }
                }
                is WatchAction.Brightness -> {
                    check(visible && brightness?.invoke(action.level) == true)
                    buildJsonObject { put("applied", true) }
                }
            }
        }
        result.fold(
            { current.actionResult(generation, effect.id, it) },
            {
                current.actionResult(
                    generation,
                    effect.id,
                    null,
                    getString(R.string.action_unavailable)
                )
            }
        )
    }

    private fun beginForeground(microphone: Boolean): Boolean {
        return try {
            if (!foreground && !visible) return false
            if (!foreground) startService(Intent(this, GadgetService::class.java))
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, GadgetService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_gadget)
                .setContentTitle(getString(R.string.conversation_notification))
                .setContentText(
                    getString(
                        if (microphone) {
                            R.string.recording_notification
                        } else {
                            R.string.reply_notification
                        }
                    )
                )
                .setContentIntent(
                    openApp()
                ).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(
                    Notification.Action.Builder(null, getString(R.string.disconnect), stop).build()
                ).build()
            startForeground(ONGOING_ID, notification, type)
            foreground = true
            true
        } catch (_: Exception) {
            if (!foreground) stopSelf()
            connection?.report(getString(R.string.foreground_failed))
            false
        }
    }

    private fun endForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun battery(): JsonObject {
        val percentage = getSystemService(
            BatteryManager::class.java
        ).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return buildJsonObject { if (percentage in 0..100) put("battery_pct", percentage) }
    }

    private fun closeConnection() {
        epoch.incrementAndGet()
        capture.stop()
        playback.stop()
        connection?.close()
        connection = null
        endForeground()
        brightness?.invoke(null)
    }

    override fun onDestroy() {
        destroyed = true
        closeConnection()
        main.removeCallbacksAndMessages(null)
        storage.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "conversation"
        private const val ALERT_CHANNEL = "agent-alerts"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val STOP = "dev.quantumink.hermesgadget.STOP"
    }
}
