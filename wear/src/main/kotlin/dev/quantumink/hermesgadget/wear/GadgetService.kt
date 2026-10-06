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
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.PetEffect
import dev.quantumink.hermesgadget.protocol.PetManifest
import dev.quantumink.hermesgadget.protocol.TransportPath
import dev.quantumink.hermesgadget.protocol.TransportPolicy
import dev.quantumink.hermesgadget.protocol.TransportPreference
import dev.quantumink.hermesgadget.protocol.WatchAction
import dev.quantumink.hermesgadget.protocol.WatchActions
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WatchState(
    val profile: EndpointProfile? = null,
    val profiles: List<EndpointProfile> = emptyList(),
    val imports: List<EndpointProfile> = emptyList(),
    val importing: Boolean = false,
    val connection: ConnectionState = ConnectionState(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val setupError: String = "",
    val draft: String = "",
    val path: TransportPath = TransportPath.DIRECT,
    val selecting: Boolean = false,
    val transportNotice: String = "",
    val pet: PetAtlas? = null,
    val petCue: String = "",
    val speaking: Boolean = false,
    val speechPending: Boolean = false,
    val playbackLevel: Float = 0f,
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
    private val surfaceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var lastSavedSurface: Triple<String, String, String>? = null

    @Volatile private var textEditing = false
    private val storage = Executors.newSingleThreadExecutor()
    private val transportWorker = Executors.newSingleThreadExecutor()
    private val selectionEpoch = AtomicLong()
    private val epoch = AtomicLong()
    private val mediaLock = Any()
    private val petWorker = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(3),
        { task -> Thread(task, "pet-storage").apply { isDaemon = true } }
    )
    private lateinit var petCache: PetCache

    @Volatile private var petManifest: PetManifest? = null
    private lateinit var vault: IdentityVault
    private lateinit var capture: PcmCapture
    private lateinit var playback: PcmPlayback

    @Volatile private var connection: DirectConnection? = null
    private var relay: WatchRelay? = null
    private var speech: WatchSpeech? = null

    @Volatile private var visible = false

    @Volatile private var foreground = false
    private var timerLauncher: ((Intent) -> Boolean)? = null
    private var brightness: ((Float?) -> Boolean)? = null
    private var destroyed = false
    private var savedIdentity: DeviceIdentity? = null

    @Volatile private var opusReady = false
    private var advertisedActions: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        capture = PcmCapture(this)
        petCache = PetCache(this)
        playback = PcmPlayback(
            this,
            onInterrupted = { connection?.report("Playback interrupted.") },
            onLevel = { level -> mutableState.update { it.copy(playbackLevel = level) } },
            onFinished = {
                mutableState.update { it.copy(speaking = false, playbackLevel = 0f) }
                main.post { releaseIdleForeground() }
            }
        )
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
        surfaceScope.launch {
            mutableState.collect { watch ->
                WatchSurfaces.publish(this@GadgetService, watch)
                val profile = watch.profile
                if (profile != null && !watch.connection.conversation.busy &&
                    watch.connection.conversation.reply.isNotEmpty()
                ) {
                    val snapshot = Triple(
                        profile.endpoint.url,
                        profile.label,
                        watch.connection.conversation.reply.take(120)
                    )
                    if (snapshot != lastSavedSurface && !watch.loading && !watch.saving) {
                        lastSavedSurface = snapshot
                        val version = epoch.get()
                        storage.execute {
                            if (version == epoch.get() && ::vault.isInitialized) {
                                runCatching {
                                    vault.saveSurface(
                                        profile.endpoint,
                                        profile.label,
                                        snapshot.third
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        storage.execute {
            opusReady = OpusSupport.probe()
            val restored = runCatching {
                vault = IdentityVault(this)
                val profile = vault.profile()
                Triple(profile, profile?.let { vault.identity(it.endpoint) }, vault.profiles())
            }
            main.post {
                if (destroyed) return@post
                restored.fold(
                    onSuccess = { saved ->
                        if (saved.first !=
                            null
                        ) {
                            configure(saved.first!!, requireNotNull(saved.second))
                        }
                        mutableState.update { it.copy(loading = false, profiles = saved.third) }
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
        if (!foreground && !textEditing) disconnect()
    }

    fun save(
        url: String,
        label: String,
        token: String,
        privateNetwork: Boolean,
        accepted: Boolean,
        transport: TransportPreference = TransportPreference.AUTO,
        ready: () -> Unit
    ) {
        if (mutableState.value.saving || mutableState.value.loading) return
        val profile = try {
            EndpointProfile(
                Endpoint.parse(url, privateNetwork, accepted),
                label.trim(),
                token.trim(),
                transport
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
            val result = runCatching { vault.save(profile) to vault.profiles() }
            main.post {
                if (destroyed) return@post
                result.fold(
                    onSuccess = { saved ->
                        configure(profile, saved.first)
                        mutableState.update { it.copy(saving = false, profiles = saved.second) }
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

    fun selectProfile(url: String, ready: () -> Unit) = changeProfiles({ vault.select(url) }, ready)

    fun removeProfile(url: String, ready: () -> Unit) = changeProfiles({
        val saved = vault.remove(url)
        petCache.remove(url)
        saved
    }, ready)

    private fun changeProfiles(
        operation: () -> Pair<EndpointProfile, DeviceIdentity>?,
        ready: () -> Unit
    ) {
        if (mutableState.value.loading || mutableState.value.saving) return
        closeConnection()
        mutableState.update { it.copy(saving = true, setupError = "") }
        storage.execute {
            val result = runCatching { operation() to vault.profiles() }
            main.post {
                if (destroyed) return@post
                result.fold({ (saved, profiles) ->
                    if (saved == null) {
                        savedIdentity = null
                        mutableState.value = WatchState(loading = false, profiles = profiles)
                    } else {
                        configure(saved.first, saved.second)
                        mutableState.update { it.copy(saving = false, profiles = profiles) }
                    }
                    ready()
                }, {
                    mutableState.update {
                        it.copy(
                            saving = false,
                            connection = ConnectionState(),
                            setupError = getString(R.string.storage_failed)
                        )
                    }
                })
            }
        }
    }

    fun importProfiles() {
        if (mutableState.value.importing) return
        mutableState.update { it.copy(importing = true, setupError = "", imports = emptyList()) }
        transportWorker.execute {
            val result = runCatching {
                val node = requireNotNull(WatchRelay.reachablePhone(this))
                val uri = android.net.Uri.Builder().scheme("wear").authority(node)
                    .path(dev.quantumink.hermesgadget.protocol.EndpointProfiles.SETUP_PATH).build()
                val item = com.google.android.gms.tasks.Tasks.await(
                    com.google.android.gms.wearable.Wearable.getDataClient(this).getDataItem(uri),
                    5,
                    java.util.concurrent.TimeUnit.SECONDS
                ) ?: error("No phone setup")
                val text = com.google.android.gms.wearable.DataMapItem.fromDataItem(item)
                    .dataMap.getString("profiles").orEmpty()
                dev.quantumink.hermesgadget.protocol.EndpointProfiles.decode(text)
            }
            main.post {
                if (destroyed) return@post
                result.fold({ profiles ->
                    mutableState.update { it.copy(importing = false, imports = profiles) }
                }, {
                    mutableState.update {
                        it.copy(
                            importing = false,
                            setupError = getString(R.string.import_failed)
                        )
                    }
                })
            }
        }
    }

    fun connect() {
        val profile = mutableState.value.profile ?: return
        if (mutableState.value.selecting || mutableState.value.connection.status in
            setOf(ConnectionStatus.CONNECTING, ConnectionStatus.PAIRING, ConnectionStatus.PAIRED)
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= 37 && profile.endpoint.privateNetwork &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            connection?.report(getString(R.string.local_permission_needed))
            return
        }
        val identity = savedIdentity ?: return
        val request = selectionEpoch.incrementAndGet()
        mutableState.update {
            it.copy(
                selecting = true,
                connection = it.connection.copy(status = ConnectionStatus.CONNECTING)
            )
        }
        transportWorker.execute {
            val node = if (profile.endpoint.isTls &&
                profile.transport != TransportPreference.DIRECT
            ) {
                WatchRelay.reachablePhone(this)
            } else {
                null
            }
            val selected = runCatching {
                TransportPolicy.select(
                    profile.endpoint,
                    profile.transport,
                    node != null
                )
            }
            val byok =
                selected.getOrNull() == TransportPath.RELAY && node != null &&
                    WatchSpeech.ready(this, profile.endpoint, node)
            main.post {
                if (destroyed || request != selectionEpoch.get()) return@post
                selected.fold(
                    { path ->
                        val created = runCatching {
                            if (path ==
                                TransportPath.RELAY
                            ) {
                                WatchRelay(this, profile.endpoint, requireNotNull(node))
                            } else {
                                null
                            }
                        }
                        created.fold(
                            { pipe ->
                                val reply = mutableState.value.connection.conversation.reply
                                configure(
                                    profile,
                                    identity,
                                    pipe,
                                    reply,
                                    speechNode = node?.takeIf {
                                        byok
                                    }
                                )
                                connection?.connect()
                            },
                            {
                                mutableState.update {
                                    it.copy(
                                        selecting = false,
                                        connection = it.connection.copy(
                                            status = ConnectionStatus.ERROR,
                                            error = getString(R.string.relay_unavailable)
                                        )
                                    )
                                }
                            }
                        )
                    },
                    { failure ->
                        mutableState.update {
                            it.copy(
                                selecting = false,
                                connection = it.connection.copy(
                                    status = ConnectionStatus.ERROR,
                                    error = failure.message.orEmpty()
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    fun disconnect() {
        epoch.incrementAndGet()
        selectionEpoch.incrementAndGet()
        mutableState.update {
            it.copy(
                selecting = false,
                connection = it.connection.copy(status = ConnectionStatus.OFFLINE)
            )
        }
        speech?.stop()
        capture.stop()
        playback.stop()
        connection?.disconnect()
        endForeground()
        brightness?.invoke(null)
    }

    fun updateDraft(value: String) {
        mutableState.update { it.copy(draft = value.take(4096)) }
    }

    fun text(value: String, completed: (Boolean) -> Unit) {
        val version = epoch.get()
        val active = connection
        if (visible && mutableState.value.connection.status == ConnectionStatus.PAIRED &&
            mutableState.value.connection.conversation.prompt == null && active != null &&
            beginForeground(false)
        ) {
            active.text(value).whenComplete { accepted, failure ->
                main.post {
                    releaseIdleForeground()
                    completed(
                        version == epoch.get() && active === connection &&
                            failure == null && accepted == true
                    )
                }
            }
        } else {
            completed(false)
        }
    }

    fun prepareTextInput(): Boolean {
        textEditing = visible && mutableState.value.connection.status == ConnectionStatus.PAIRED
        return textEditing
    }
    fun finishTextInput() {
        textEditing = false
    }
    fun enterAmbient() {
        cancelRecording()
        brightness?.invoke(null)
        releaseIdleForeground()
    }

    private fun releaseIdleForeground() {
        main.postDelayed({
            if (destroyed || !foreground) return@postDelayed
            val watch = mutableState.value
            if (!watch.connection.conversation.busy && !watch.speaking && !watch.speechPending) {
                endForeground()
                if (!visible) disconnect()
            }
        }, 750)
    }

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
        if (!capture.finish()) connection?.finishRecording()
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
            val result = runCatching {
                IdentityVault(this).reset()
                petCache.reset()
            }
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

    private var cueSerial = 0L
    private fun petCue(mode: String, version: Long) {
        main.post {
            if (version != epoch.get()) return@post
            val serial = ++cueSerial
            mutableState.update { it.copy(petCue = mode) }
            main.postDelayed({
                if (version == epoch.get() &&
                    mutableState.value.petCue == mode && serial == cueSerial
                ) {
                    mutableState.update { it.copy(petCue = "") }
                }
            }, 1100)
        }
    }

    private fun configure(
        profile: EndpointProfile,
        identity: DeviceIdentity,
        pipe: WatchRelay? = null,
        previousReply: String = "",
        notice: String = "",
        speechNode: String? = null
    ) {
        closeConnection()
        relay = pipe
        val speechEngine = speechNode?.let { WatchSpeech(this, profile.endpoint, it, opusReady) }
        speech = speechEngine
        val path = if (pipe == null) TransportPath.DIRECT else TransportPath.RELAY
        savedIdentity = identity
        val version = epoch.incrementAndGet()
        val actions = availableActions()
        advertisedActions = actions
        val sameEndpoint = mutableState.value.profile?.endpoint?.url == profile.endpoint.url
        petManifest = null
        if (!sameEndpoint) mutableState.update { it.copy(pet = null, petCue = "") }
        storage.execute {
            val cached = petCache.load(profile.endpoint.url)
            if (cached != null && version == epoch.get()) {
                mutableState.update {
                    if (version == epoch.get()) it.copy(pet = cached) else it
                }
            }
        }
        val caps = WatchActions.capabilities().toMutableMap()
        if (speech != null) caps["tts"] = JsonPrimitive("client")
        caps["pet"] = buildJsonObject { put("asset_channel", 4) }
        if (opusReady) {
            val formats = JsonArray(listOf(JsonPrimitive("opus"), JsonPrimitive("pcm16")))
            listOf("mic", "speaker").forEach { name ->
                (caps[name] as? JsonObject)?.let {
                    caps[name] =
                        JsonObject(it + ("formats" to formats))
                }
            }
        }
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
                override fun pet(effect: PetEffect) {
                    if (version != epoch.get()) return
                    if (effect is PetEffect.Manifest) petManifest = effect.value
                    val manifest = petManifest ?: return
                    runCatching {
                        petWorker.execute {
                            if (version != epoch.get() || petManifest !== manifest) return@execute
                            val atlas = runCatching {
                                when (effect) {
                                    is PetEffect.Manifest -> petCache.saveManifest(
                                        profile.endpoint.url,
                                        manifest
                                    )
                                    is PetEffect.Asset -> petCache.saveAsset(
                                        profile.endpoint.url,
                                        manifest,
                                        effect.sheet,
                                        effect.bytes
                                    )
                                }
                            }.getOrNull()
                            if (atlas != null && version == epoch.get() &&
                                petManifest === manifest
                            ) {
                                mutableState.update {
                                    if (version == epoch.get() &&
                                        petManifest === manifest
                                    ) {
                                        it.copy(pet = atlas)
                                    } else {
                                        it
                                    }
                                }
                            }
                        }
                    }
                }
                override fun stateChanged(state: ConnectionState) {
                    if (version != epoch.get()) return
                    if (state.status == ConnectionStatus.PAIRED &&
                        mutableState.value.connection.status != ConnectionStatus.PAIRED
                    ) {
                        petCue("waving", version)
                    }
                    mutableState.update {
                        if (version == epoch.get()) it.copy(connection = state) else it
                    }
                    if (TransportPolicy.canFallBack(profile.transport, path, state)) {
                        main.post {
                            if (!destroyed && version == epoch.get()) {
                                configure(
                                    profile,
                                    identity,
                                    previousReply = state.conversation.reply,
                                    notice = getString(R.string.relay_fallback)
                                )
                                connection?.connect()
                            }
                        }
                    }
                    main.post { if (version == epoch.get()) releaseIdleForeground() }
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
                    if (effect == ConversationEffect.PlaybackStop) {
                        speechEngine?.stop()
                    }
                    if (effect is ConversationEffect.ClientSpeak) {
                        if (visible || foreground) {
                            synchronized(mediaLock) {
                                if (version != epoch.get()) return
                                mutableState.update { it.copy(speechPending = true) }
                            }
                            speechEngine?.speak(
                                effect.id,
                                effect.text,
                                effect.voice,
                                { header ->
                                    synchronized(mediaLock) {
                                        if (version == epoch.get()) {
                                            playback.start(16000, header)
                                            mutableState.update {
                                                it.copy(speaking = true, speechPending = false)
                                            }
                                        }
                                    }
                                },
                                {
                                    synchronized(mediaLock) {
                                        if (version == epoch.get()) playback.offer(it)
                                    }
                                },
                                {
                                    synchronized(mediaLock) {
                                        if (version == epoch.get()) {
                                            mutableState.update { it.copy(speechPending = false) }
                                            playback.finish()
                                        }
                                    }
                                },
                                {
                                    synchronized(mediaLock) {
                                        if (version == epoch.get()) {
                                            mutableState.update { it.copy(speechPending = false) }
                                            playback.stop()
                                            connection?.report(
                                                getString(R.string.client_speech_failed)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        return
                    }
                    synchronized(mediaLock) {
                        if (version != epoch.get()) return
                        when (effect) {
                            is ConversationEffect.CaptureStart -> {
                                if (!visible || !foreground) {
                                    connection?.cancelRecording()
                                } else {
                                    val current = connection ?: return
                                    capture.start(
                                        { bytes ->
                                            if (effect.format ==
                                                "opus"
                                            ) {
                                                current.capturedOpus(effect.token, bytes)
                                            } else {
                                                current.captured(effect.token, bytes)
                                            }
                                        },
                                        {
                                            current.captureFailed(
                                                effect.token,
                                                getString(R.string.capture_failed)
                                            )
                                        },
                                        format = effect.format,
                                        onHeader = { current.codecHeader(effect.token, it) },
                                        onLevel = { current.captureLevel(effect.token, it) },
                                        onFinished = { current.finishRecording(effect.token) }
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
                                playback.start(effect.rate, effect.opusHeader)
                                mutableState.update { it.copy(speaking = true) }
                            }
                            is ConversationEffect.PlaybackData -> playback.offer(effect.bytes)
                            ConversationEffect.PlaybackFinish -> playback.finish()
                            ConversationEffect.PlaybackStop -> {
                                mutableState.update { it.copy(speechPending = false) }
                                playback.stop()
                            }
                            is ConversationEffect.PetCue -> petCue(effect.mode, version)
                            is ConversationEffect.Action -> main.post {
                                if (version == epoch.get()) executeAction(effect, generation)
                            }
                            is ConversationEffect.Haptic -> mutableState.update {
                                if (version == epoch.get()) it.copy(haptic = it.haptic + 1) else it
                            }
                            else -> Unit
                        }
                    }
                }
            },
            accessToken = profile.accessToken.takeIf { it.isNotEmpty() },
            availableActions = actions,
            sensors = battery(),
            capabilities = JsonObject(caps),
            httpClient = pipe?.client,
            connectionProxy = pipe?.bridge?.proxy ?: Proxy.NO_PROXY,
            previousReply = previousReply
        )
        mutableState.update {
            it.copy(
                profile = profile,
                draft = if (sameEndpoint) it.draft else "",
                connection = ConnectionState(
                    conversation = ConversationState(reply = previousReply)
                ),
                setupError = "",
                selecting = false,
                path = path,
                transportNotice = notice
            )
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
        selectionEpoch.incrementAndGet()
        epoch.incrementAndGet()
        speech?.close()
        speech = null
        textEditing = false
        synchronized(mediaLock) {
            mutableState.update { it.copy(speechPending = false) }
            capture.stop()
            playback.stop()
        }
        connection?.close()
        connection = null
        relay?.close()
        relay = null
        endForeground()
        brightness?.invoke(null)
    }

    override fun onDestroy() {
        destroyed = true
        closeConnection()
        WatchSurfaces.publish(
            this,
            mutableState.value.copy(
                connection = mutableState.value.connection.copy(status = ConnectionStatus.OFFLINE)
            ),
            force = true
        )
        surfaceScope.cancel()
        main.removeCallbacksAndMessages(null)
        storage.shutdownNow()
        petWorker.shutdownNow()
        transportWorker.shutdownNow()
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
