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
import dev.quantumink.hermesgadget.client.Cue
import dev.quantumink.hermesgadget.client.CuePreferences
import dev.quantumink.hermesgadget.client.OpusSupport
import dev.quantumink.hermesgadget.client.PcmCapture
import dev.quantumink.hermesgadget.client.PcmPlayback
import dev.quantumink.hermesgadget.client.PlaybackOutput
import dev.quantumink.hermesgadget.client.PlaybackRoute
import dev.quantumink.hermesgadget.client.RecordingCues
import dev.quantumink.hermesgadget.client.ReplayRecorder
import dev.quantumink.hermesgadget.client.WalkingPreferences
import dev.quantumink.hermesgadget.client.WalkingReply
import dev.quantumink.hermesgadget.client.WalkingSpeech
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.PetEffect
import dev.quantumink.hermesgadget.protocol.PetManifest
import dev.quantumink.hermesgadget.protocol.RecordingFeedback
import dev.quantumink.hermesgadget.protocol.TransportPath
import dev.quantumink.hermesgadget.protocol.TransportPolicy
import dev.quantumink.hermesgadget.protocol.TransportPreference
import dev.quantumink.hermesgadget.protocol.WatchAction
import dev.quantumink.hermesgadget.protocol.WatchActions
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProfile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
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
    val catalog: List<dev.quantumink.hermesgadget.protocol.CatalogPage> = emptyList(),
    val catalogRefreshing: Boolean = false,
    val catalogNotice: String = "",
    val selectedPage: String = "",
    val artwork: Map<String, PageArtwork> = emptyMap(),
    val artworkIdentities: Map<String, PresentationKey> = emptyMap(),
    val presentationPets: Map<PresentationKey, PetAtlas> = emptyMap(),
    val path: TransportPath = TransportPath.DIRECT,
    val selecting: Boolean = false,
    val transportNotice: String = "",
    val presentation: PresentationChoices = PresentationChoices(),
    val presentationSaving: Boolean = false,
    val presentationNotice: String = "",
    val petEndpoint: String? = null,
    val pet: PetAtlas? = null,
    val petCue: String = "",
    val captureActive: Boolean = false,
    val screenTurnStarted: Long? = null,
    val screenNotice: String = "",
    val speaking: Boolean = false,
    val speechPending: Boolean = false,
    val playbackLevel: Float = 0f,
    val haptic: Long = 0,
    /** Walking mode only: a completed received clip can be replayed / more client speech remains. */
    val replayReady: Boolean = false,
    val moreReady: Boolean = false,
    /** Observed output of the current response playback; never a preference or device identity. */
    val output: PlaybackOutput = PlaybackOutput.UNKNOWN,
    /** Relay mode of the selected profile (off by default); the stock conversation above is independent of it. */
    val relay: RelayUiState = RelayUiState(),
    /** The retained watch inbox across this watch's Relay profiles (null until read). */
    val inbox: InboxSnapshot? = null,
    val inboxStatus: String = ""
) {
    val selectionLocked: Boolean get() = saving || captureActive ||
        connection.conversation.prompt != null ||
        connection.conversation.mode == ConversationMode.LISTENING ||
        connection.conversation.busy
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
    private val drafts = ProfileDrafts()
    private lateinit var catalog: WatchCatalogController
    private val petWorker = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(3),
        { task -> Thread(task, "pet-storage").apply { isDaemon = true } }
    )
    private val presentationArtEpoch = AtomicLong()
    private lateinit var presentationStore: PresentationStore
    private lateinit var petCache: PetCache

    @Volatile private var petManifest: PetManifest? = null
    private lateinit var vault: IdentityVault
    private lateinit var capture: PcmCapture
    private lateinit var recordingCues: RecordingCues
    private var recordingFeedback: RecordingFeedback? = null

    @Volatile private var captureToken: String? = null
    private lateinit var playback: PcmPlayback
    private lateinit var walking: WalkingPreferences

    // Walking mode: the current reply's spoken progress and last received clip, under mediaLock.
    private val replayRecorder = ReplayRecorder()
    private var walkingReply: WalkingReply? = null

    @Volatile private var connection: DirectConnection? = null
    private var relay: WatchRelay? = null

    // Relay mode (explicit per-profile opt-in): the watch's own enrollment of the configured profile and its session.
    private lateinit var relayMode: WatchRelayMode

    @Volatile private var relayWho: EnrollmentIdentity? = null

    @Volatile private var relaySession = 0L
    private var inboxRefreshed = 0L
    private val pendingInbox = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private var speech: WatchSpeech? = null

    @Volatile private var visible = false

    @Volatile private var foreground = false
    private var timerLauncher: ((Intent) -> Boolean)? = null
    private var brightness: ((Float?) -> Boolean)? = null
    private var destroyed = false
    private var savedIdentity: DeviceIdentity? = null

    @Volatile private var pendingSetup: EndpointProfile? = null
    private var rejectedCatalogNotice = ""
    private var deferredCatalog:
        Pair<List<dev.quantumink.hermesgadget.protocol.CatalogPage>, String>? = null

    private fun rosterProfiles(): List<EndpointProfile> {
        val profiles = mutableState.value.profiles
        val pending = pendingSetup ?: return profiles
        return profiles.filterNot { it.endpoint.url == pending.endpoint.url } + pending
    }

    @Volatile private var opusReady = false
    private var advertisedActions: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        capture = PcmCapture(this)
        CuePreferences.initialize(this)
        walking = WalkingPreferences(this)
        recordingCues = RecordingCues(this, watchLightHaptics = true)
        petCache = PetCache(this)
        relayMode = WatchRelayMode(
            this,
            ui = { relayState ->
                mutableState.update { it.copy(relay = relayState) }
                main.post { relayProgress(relayState) }
            },
            inbox = { snapshot, status ->
                mutableState.update { it.copy(inbox = snapshot, inboxStatus = status) }
            }
        )
        presentationStore = PresentationStore(java.io.File(filesDir, "profile-presentation"))
        catalog = WatchCatalogController(
            this,
            petCache,
            allowed = { pages ->
                ProfilePages.merge(rosterProfiles(), pages).size <= 32
            },
            loaded = ::applyCatalog,
            busy = { refreshing, notice ->
                mutableState.update {
                    it.copy(
                        catalogRefreshing = refreshing,
                        catalogNotice = rejectedCatalogNotice.ifBlank { notice }
                    )
                }
            },
            artwork = { artwork ->
                mutableState.update { it.withPublishedArtwork(artwork) }
            }
        )
        playback = PcmPlayback(
            this,
            onInterrupted = { connection?.report("Playback interrupted.") },
            onLevel = { level -> mutableState.update { it.copy(playbackLevel = level) } },
            onFinished = {
                mutableState.update { it.copy(speaking = false, playbackLevel = 0f) }
                main.post { releaseIdleForeground() }
            },
            onOutputChanged = { output -> mutableState.update { it.copy(output = output) } }
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
                // The service outlives ambient/activity composition replacement. Keep the
                // approved roster/session projection until the same selection lock clears.
                if (!watch.selectionLocked) {
                    deferredCatalog?.let { pending ->
                        deferredCatalog = null
                        applyCatalog(pending.first, pending.second)
                    }
                }
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
            val modes = runCatching { presentationStore.read() }
            main.post {
                if (!destroyed) {
                    modes.fold(
                        { saved -> mutableState.update { it.copy(presentation = saved) } },
                        {
                            mutableState.update {
                                it.copy(
                                    presentationNotice =
                                    "Saved presentation needs attention. " +
                                        "Restore the private watch store or contact support; " +
                                        "bytes were kept."
                                )
                            }
                        }
                    )
                }
            }
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
                        mutableState.update { it.copy(profiles = saved.third) }
                        catalog.start()
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
        refreshCatalog(false)
    }

    fun refreshCatalog(force: Boolean = true) {
        if (visible) catalog.refresh(force)
    }

    internal fun applyCatalog(
        pages: List<dev.quantumink.hermesgadget.protocol.CatalogPage>,
        selected: String
    ) {
        if (ProfilePages.merge(rosterProfiles(), pages).size > 32) {
            rejectedCatalogNotice = "At most 32 pages are supported. Saved setup was kept. " +
                "Remove unused pages on the phone and refresh."
            mutableState.update {
                it.copy(
                    loading = false,
                    catalogNotice = rejectedCatalogNotice
                )
            }
            return
        }
        if (mutableState.value.selectionLocked) {
            deferredCatalog = pages.toList() to selected
            return
        }
        deferredCatalog = null
        rejectedCatalogNotice = ""
        mutableState.update {
            it.copy(
                loading = false,
                catalogNotice = "",
                catalog = pages,
                selectedPage = it.selectedPage.ifBlank {
                    selected
                }
            ).let { updated -> updated.withPublishedArtwork(updated.artwork) }
        }
        if (!::vault.isInitialized) return
        storage.execute {
            val profiles = runCatching {
                vault.updateLabels(
                    pages.mapNotNull { page ->
                        page.profile.gadgetUrl?.let { it to page.profile.name }
                    }.toMap()
                )
            }
            main.post {
                if (destroyed) return@post
                profiles.onSuccess { updated ->
                    mutableState.update { watch ->
                        watch.copy(
                            profiles = updated,
                            profile = updated.singleOrNull {
                                it.endpoint.url == watch.profile?.endpoint?.url
                            }
                        )
                    }
                }
            }
        }
    }

    fun setPresentation(pageKey: String, identity: PresentationKey, mode: PresentationMode) {
        val watch = mutableState.value
        if (watch.loading || watch.presentationSaving || watch.selectedPage != pageKey) return
        val page =
            ProfilePages.merge(watch.profiles, watch.catalog).singleOrNull { it.key == pageKey }
                ?: return
        if (page.presentationKey() != identity) return
        mutableState.update { it.copy(presentationSaving = true) }
        storage.execute {
            val saved = runCatching { presentationStore.set(identity, mode) }
            main.post {
                if (destroyed) return@post
                saved.fold(
                    { choices ->
                        mutableState.update {
                            it.copy(
                                presentation = choices,
                                presentationSaving = false,
                                presentationNotice = ""
                            )
                        }
                    },
                    { failure ->
                        mutableState.update {
                            it.copy(
                                presentationSaving = false,
                                presentationNotice = failure.message.orEmpty()
                            )
                        }
                    }
                )
            }
        }
    }

    fun preparePages(keys: List<String>) {
        val watch = mutableState.value
        val pages = ProfilePages.merge(watch.profiles, watch.catalog)
        val prepared = keys.distinct().take(3).mapNotNull { key ->
            pages.singleOrNull {
                it.key ==
                    key
            }
        }
        val request = presentationArtEpoch.incrementAndGet()
        storage.execute {
            if (request != presentationArtEpoch.get()) return@execute
            val pets = prepared.mapNotNull { page ->
                val endpoint = page.approved?.endpoint?.url ?: return@mapNotNull null
                petCache.load(endpoint)?.let { page.presentationKey() to it }
            }.toMap()
            main.post {
                if (!destroyed && request == presentationArtEpoch.get()) {
                    mutableState.update { it.copy(presentationPets = pets) }
                }
            }
        }
        catalog.prepare(prepared)
    }

    fun rememberPage(key: String) {
        mutableState.update { it.copy(selectedPage = key) }
        catalog.rememberPage(key)
    }

    private fun rememberActivePage() {
        val watch = mutableState.value
        val active = ProfilePages.merge(watch.profiles, watch.catalog).singleOrNull {
            it.approved?.endpoint?.url == watch.profile?.endpoint?.url
        }
        if (active != null && watch.selectedPage != active.key) rememberPage(active.key)
    }

    fun hidden() {
        visible = false
        timerLauncher = null
        brightness?.invoke(null)
        brightness = null
        cancelRecording()
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
        val profiles = mutableState.value.profiles.filterNot {
            it.endpoint.url == profile.endpoint.url
        } + profile
        if (ProfilePages.merge(profiles, mutableState.value.catalog).size > 32) {
            mutableState.update {
                it.copy(
                    setupError = "At most 32 pages are supported. " +
                        "Remove unused pages before adding setup."
                )
            }
            return
        }
        pendingSetup = profile
        mutableState.update { it.copy(saving = true, setupError = "") }
        storage.execute {
            val result = runCatching { vault.save(profile) to vault.profiles() }
            main.post {
                if (destroyed) return@post
                result.fold(
                    onSuccess = { saved ->
                        configure(profile, saved.first)
                        mutableState.update { it.copy(saving = false, profiles = saved.second) }
                        pendingSetup = null
                        rememberActivePage()
                        ready()
                    },
                    onFailure = {
                        pendingSetup = null
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
        drafts.remove(url)
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
                        mutableState.update {
                            it.copy(
                                profile = null,
                                profiles = profiles,
                                saving = false,
                                draft = "",
                                pet = null,
                                petEndpoint = null,
                                connection = ConnectionState()
                            )
                        }
                    } else {
                        configure(saved.first, saved.second)
                        mutableState.update { it.copy(saving = false, profiles = profiles) }
                        rememberActivePage()
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
            // Relay is advertised on this socket only when this profile's saved setting is on.
            val who = WatchRelayModel.identity(profile.endpoint, identity)
            val session = relayMode.open(who, profile.endpoint.url, profile.label)
            val relayOn = who != null &&
                runCatching { relayMode.enabledBlocking(who, session) }.getOrDefault(false)
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
                                    },
                                    relaySession = session,
                                    relayOn = relayOn
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
        recordingFeedback?.invalidate()
        captureToken = null
        mutableState.update { it.copy(captureActive = false) }
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
        drafts.update(value)
        mutableState.update { it.copy(draft = value.take(4096)) }
    }

    fun text(value: String, completed: (Boolean) -> Unit) {
        val version = epoch.get()
        val active = connection
        if (visible && mutableState.value.connection.status == ConnectionStatus.PAIRED &&
            mutableState.value.connection.conversation.prompt == null && active != null &&
            beginForeground(false)
        ) {
            beginScreenTurn()
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

    // -- Relay mode (explicit, per profile; every action below is a deliberate user action) -------------------------

    /** Turns Relay on or off for the selected profile. The capability is negotiated per socket, so a live link restarts. */
    fun relayEnable(on: Boolean) {
        // Not a wss profile: the page explains it and offers no switch.
        if (relayWho == null) return
        relayMode.controller.setEnabled(on)
        if (mutableState.value.connection.status !in
            setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)
        ) {
            disconnect()
            connect()
        }
    }

    /** Runs [action] only while the rendered [seen] state still belongs to the configured profile and session. */
    fun relay(seen: RelayUiState, action: (RelayController) -> Unit) {
        val who = relayWho ?: return
        if (seen.identity == who && seen.foregroundSession == relaySession &&
            relayMode.current(who, relaySession)
        ) {
            action(relayMode.controller)
        }
    }

    /** A dictated or typed turn into the selected Relay conversation: stored first, then written; never the stock chat. */
    fun relaySend(seen: RelayUiState, text: String, completed: (Boolean) -> Unit) {
        var posted = false
        relay(seen) { controller ->
            if (!seen.canSend || text.isBlank()) return@relay
            posted = true
            controller.send(text.take(8192)) { accepted -> main.post { completed(accepted) } }
        }
        if (!posted) completed(false)
    }

    fun loadInbox() = relayMode.loadInbox()

    /** Refreshes the selected profile's inbox over its live Relay link; other profiles keep their retained lines. */
    fun refreshInbox() {
        val relayState = mutableState.value.relay
        if (relayState.phase in setOf(RelayPhase.READY, RelayPhase.SELECTED)) {
            relay(relayState) { relayMode.refreshInbox() }
        } else {
            relayMode.loadInbox()
        }
    }

    /** Quiet private notifications for this profile's Needs input / Finished changes (off until the user turns them on). */
    fun inboxAlerts(profile: InboxProfile, on: Boolean) {
        relayMode.controller.configureInbox(profile.copy(completionAlerts = on, inputAlerts = on))
        relayMode.loadInbox()
    }

    /**
     * Opens an inbox line deliberately: resolves it locally, selects the watch profile that owns it (same endpoint and
     * this watch's own enrollment) and continues that exact conversation once its Relay link is ready.
     */
    fun openInbox(handle: String, opened: (Boolean) -> Unit) {
        val controller = relayMode.controller
        storage.execute {
            val route = runCatching { controller.inboxRouteBlocking(handle) }.getOrNull()
            val owner = route?.let { found ->
                runCatching {
                    WatchRelayModel.owner(
                        found.namespace,
                        vault.profiles().map { profile ->
                            profile to runCatching {
                                WatchRelayModel.identity(
                                    profile.endpoint,
                                    vault.identity(profile.endpoint)
                                )
                            }.getOrNull()
                        }
                    )
                }.getOrNull()
            }
            main.post {
                if (destroyed) return@post
                if (route?.canonicalConversation == null || owner == null) {
                    opened(false)
                    return@post
                }
                controller.markInboxRead(handle)
                pendingInbox.set(handle)
                opened(true)
                if (mutableState.value.profile?.endpoint?.url == owner.endpoint.url) {
                    relayProgress(mutableState.value.relay)
                    if (mutableState.value.connection.status in
                        setOf(ConnectionStatus.OFFLINE, ConnectionStatus.ERROR)
                    ) {
                        connect()
                    }
                } else {
                    selectProfile(owner.endpoint.url) { connect() }
                }
            }
        }
    }

    /** Main thread. Continues a pending inbox line and refreshes the inbox once per ready Relay session. */
    private fun relayProgress(seen: RelayUiState) {
        if (destroyed || seen.phase !in setOf(RelayPhase.READY, RelayPhase.SELECTED)) return
        val who = relayWho ?: return
        if (seen.identity != who || seen.foregroundSession != relaySession) return
        if (inboxRefreshed != relaySession) {
            inboxRefreshed = relaySession
            relayMode.refreshInbox()
        }
        pendingInbox.getAndSet(null)?.let { relayMode.controller.continueInbox(it) }
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
                mutableState.update {
                    it.copy(
                        screenTurnStarted = null,
                        screenNotice = if (it.screenNotice ==
                            SCREEN_LIMIT_NOTICE
                        ) {
                            ""
                        } else {
                            it.screenNotice
                        }
                    )
                }
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
        if (mutableState.value.connection.conversation.mode ==
            ConversationMode.LISTENING
        ) {
            return
        }
        if (beginForeground(true)) {
            beginScreenTurn()
            connection?.startRecording()
        }
    }

    private fun beginScreenTurn() {
        mutableState.update {
            it.copy(screenTurnStarted = android.os.SystemClock.elapsedRealtime(), screenNotice = "")
        }
    }

    fun screenLeaseExpired(started: Long?) {
        mutableState.update {
            if (started != null && it.screenTurnStarted == started &&
                it.conversationInProgress && it.screenNotice.isEmpty()
            ) {
                it.copy(
                    screenNotice = SCREEN_LIMIT_NOTICE
                )
            } else {
                it
            }
        }
    }

    fun finishRecording() {
        if (!capture.finish()) connection?.finishRecording()
    }
    fun cancelRecording() {
        synchronized(mediaLock) {
            captureToken = null
            mutableState.update { it.copy(captureActive = false) }
            capture.stop()
        }
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
        deferredCatalog = null
        closeConnection()
        drafts.clear()
        catalog.reset()
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
        speechNode: String? = null,
        relaySession: Long? = null,
        relayOn: Boolean = false
    ) {
        closeConnection()
        relay = pipe
        val who = WatchRelayModel.identity(profile.endpoint, identity)
        val session = relaySession?.takeIf { relayMode.current(who, it) }
            ?: relayMode.open(who, profile.endpoint.url, profile.label)
        val withRelay = relayOn && who != null && session == relaySession
        relayWho = who
        this.relaySession = session
        val speechEngine = speechNode?.let { WatchSpeech(this, profile.endpoint, it, opusReady) }
        speech = speechEngine
        val path = if (pipe == null) TransportPath.DIRECT else TransportPath.RELAY
        savedIdentity = identity
        val version = epoch.incrementAndGet()
        recordingFeedback?.invalidate()
        captureToken = null
        mutableState.update { it.copy(captureActive = false) }
        val feedback = RecordingFeedback { finished ->
            if (version == epoch.get() && visible) {
                recordingCues.play(if (finished) Cue.FINISH else Cue.CANCEL)
            }
        }
        recordingFeedback = feedback
        val actions = availableActions()
        advertisedActions = actions
        val sameEndpoint = mutableState.value.profile?.endpoint?.url == profile.endpoint.url
        petManifest = null
        if (!sameEndpoint) {
            mutableState.update {
                it.copy(pet = null, petEndpoint = null, petCue = "")
            }
        }
        storage.execute {
            val cached = petCache.load(profile.endpoint.url)
            if (cached != null) {
                main.post {
                    if (!destroyed && version == epoch.get()) {
                        mutableState.update { it.withPresentationPet(profile.endpoint.url, cached) }
                    }
                }
            }
        }
        val caps = WatchActions.capabilities().toMutableMap()
        if (speech != null) caps["tts"] = JsonPrimitive("client")
        caps["pet"] = buildJsonObject {
            put("asset_channel", 4)
            put("expression_overlays", 1)
        }
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
                                        it.withPresentationPet(profile.endpoint.url, atlas)
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
                    if (state.conversation.mode == ConversationMode.THINKING &&
                        mutableState.value.connection.conversation.mode != ConversationMode.THINKING
                    ) {
                        synchronized(mediaLock) { clearWalking() }
                    }
                    if (state.status == ConnectionStatus.PAIRED &&
                        mutableState.value.connection.status != ConnectionStatus.PAIRED
                    ) {
                        petCue("waving", version)
                    }
                    mutableState.update {
                        if (version == epoch.get()) {
                            val interrupted = it.connection.conversation.busy &&
                                state.status in
                                setOf(
                                    ConnectionStatus.OFFLINE,
                                    ConnectionStatus.ERROR,
                                    ConnectionStatus.RETRYING
                                )
                            it.copy(
                                connection = state,
                                screenNotice = if (interrupted) {
                                    "Connection ended before the reply completed. Reconnect and try again."
                                } else if (state.conversation.reply !=
                                    it.connection.conversation.reply
                                ) {
                                    ""
                                } else {
                                    it.screenNotice
                                }
                            )
                        } else {
                            it
                        }
                    }
                    if (TransportPolicy.canFallBack(profile.transport, path, state)) {
                        main.post {
                            if (!destroyed && version == epoch.get()) {
                                configure(
                                    profile,
                                    identity,
                                    previousReply = state.conversation.reply,
                                    notice = getString(R.string.relay_fallback),
                                    relaySession = session,
                                    relayOn = withRelay
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
                        if ((visible || foreground) && speechEngine != null) {
                            val part = synchronized(mediaLock) {
                                if (version != epoch.get()) return
                                if (walking.enabled.value) {
                                    // Walking speaks a short extract; the full reply stays on screen.
                                    val reply = WalkingReply(
                                        effect.id,
                                        effect.voice,
                                        WalkingSpeech.parts(effect.text)
                                    )
                                    walkingReply = reply
                                    reply.next().also { publishWalking() }
                                        ?.let { Triple(it.first, it.second, reply.voice) }
                                } else {
                                    walkingReply = null
                                    Triple(effect.id, effect.text, effect.voice)
                                }
                            }
                            if (part == null) {
                                connection?.report(getString(R.string.walking_nothing))
                            } else {
                                speakClient(
                                    version,
                                    speechEngine,
                                    part.first,
                                    part.second,
                                    part.third
                                )
                            }
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
                                    feedback.begin(effect.token)
                                    captureToken = effect.token
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
                                        onFinished = {
                                            if (version ==
                                                epoch.get()
                                            ) {
                                                current.finishRecording(effect.token)
                                            }
                                        },
                                        onStarted = {
                                            synchronized(mediaLock) {
                                                if (version == epoch.get() && visible &&
                                                    captureToken == effect.token &&
                                                    !mutableState.value.captureActive
                                                ) {
                                                    mutableState.update {
                                                        it.copy(captureActive = true)
                                                    }
                                                    recordingCues.play(Cue.START)
                                                }
                                            }
                                        },
                                        onStopped = {
                                            synchronized(mediaLock) {
                                                if (version == epoch.get()) {
                                                    if (captureToken == effect.token) {
                                                        captureToken = null
                                                        mutableState.update {
                                                            it.copy(captureActive = false)
                                                        }
                                                    }
                                                    feedback.stopped(effect.token)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            is ConversationEffect.RecordingOutcome ->
                                feedback.outcome(effect.token, effect.finished)
                            ConversationEffect.CaptureStop -> {
                                captureToken = null
                                mutableState.update { it.copy(captureActive = false) }
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
                                startPlayback(effect.rate, effect.opusHeader)
                                mutableState.update { it.copy(speaking = true) }
                            }
                            is ConversationEffect.PlaybackData -> {
                                // Server voice keeps arriving after Stop, so Replay can use it.
                                replayRecorder.offer(effect.bytes)
                                playback.offer(effect.bytes)
                            }
                            ConversationEffect.PlaybackFinish -> {
                                replayRecorder.finish()
                                publishWalking()
                                playback.finish()
                            }
                            ConversationEffect.PlaybackStop -> {
                                mutableState.update { it.copy(speechPending = false) }
                                playback.stop()
                            }
                            is ConversationEffect.PetCue -> petCue(effect.mode, version)
                            is ConversationEffect.Action -> main.post {
                                if (version == epoch.get()) executeAction(effect, generation)
                            }
                            is ConversationEffect.Haptic -> if (effect !=
                                ConversationEffect.Haptic.LISTEN_START &&
                                effect != ConversationEffect.Haptic.LISTEN_END
                            ) {
                                mutableState.update {
                                    if (version ==
                                        epoch.get()
                                    ) {
                                        it.copy(haptic = it.haptic + 1)
                                    } else {
                                        it
                                    }
                                }
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
            previousReply = previousReply,
            relay = if (withRelay) {
                relayMode.optIn { version == epoch.get() && relayMode.current(who, session) }
            } else {
                null
            }
        )
        if (who != null) relayMode.controller.attachTransport(connection?.relayTransport)
        mutableState.update {
            it.copy(
                profile = profile,
                draft = drafts.select(profile.endpoint.url),
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

    /** Never called under [mediaLock]: the engine delivers callbacks that take it. */
    private fun speakClient(
        version: Long,
        engine: WatchSpeech,
        id: String,
        text: String,
        voice: String
    ) {
        synchronized(mediaLock) {
            if (version != epoch.get()) return
            mutableState.update { it.copy(speechPending = true) }
        }
        engine.speak(
            id,
            text,
            voice,
            { header ->
                synchronized(mediaLock) {
                    if (version == epoch.get()) {
                        startPlayback(16000, header)
                        mutableState.update {
                            it.copy(speaking = true, speechPending = false)
                        }
                    }
                }
            },
            {
                synchronized(mediaLock) {
                    if (version == epoch.get()) {
                        replayRecorder.offer(it)
                        playback.offer(it)
                    }
                }
            },
            {
                synchronized(mediaLock) {
                    if (version == epoch.get()) {
                        mutableState.update { it.copy(speechPending = false) }
                        replayRecorder.finish()
                        publishWalking()
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

    /** Caller holds [mediaLock]. Walking prefers earbuds, else the watch speaker. */
    private fun startPlayback(rate: Int, header: ByteArray?) {
        val walkingOn = walking.enabled.value
        if (walkingOn) replayRecorder.start(rate, header) else replayRecorder.clear()
        playback.route = if (walkingOn) {
            PlaybackRoute.HEADPHONES_THEN_SPEAKER
        } else {
            PlaybackRoute.SYSTEM
        }
        playback.start(rate, header)
        publishWalking()
    }

    private fun publishWalking() {
        val on = walking.enabled.value
        val replayReady = on && replayRecorder.clip != null
        val moreReady = on && walkingReply?.hasMore == true
        mutableState.update { it.copy(replayReady = replayReady, moreReady = moreReady) }
    }

    private fun clearWalking() {
        replayRecorder.clear()
        walkingReply = null
        publishWalking()
    }

    /** Stops the current response audio only; the agent turn and its text are unchanged. */
    fun stopSpeech() {
        speech?.stop()
        synchronized(mediaLock) {
            mutableState.update { it.copy(speechPending = false) }
            playback.stop()
        }
    }

    /** Walking mode: replays the last received clip exactly, with no synthesis or agent turn. */
    fun replay() {
        val watch = mutableState.value
        if (!visible || !walking.enabled.value || watch.captureActive ||
            watch.connection.conversation.mode == ConversationMode.LISTENING
        ) {
            return
        }
        speech?.stop()
        synchronized(mediaLock) {
            val clip = replayRecorder.clip ?: return
            playback.route = PlaybackRoute.HEADPHONES_THEN_SPEAKER
            playback.replay(clip)
            mutableState.update { it.copy(speaking = true, speechPending = false) }
        }
    }

    /** Walking mode: deliberately speaks the next part of the same client-speech reply. */
    fun tellMore() {
        val watch = mutableState.value
        val engine = speech ?: return
        if (!visible || !walking.enabled.value || !watch.connection.clientSpeech ||
            watch.captureActive || watch.connection.conversation.busy
        ) {
            return
        }
        val version = epoch.get()
        val (part, voice) = synchronized(mediaLock) {
            val reply = walkingReply ?: return
            val part = reply.next() ?: return
            publishWalking()
            part to reply.voice
        }
        speakClient(version, engine, part.first, part.second, voice)
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
        recordingFeedback?.invalidate()
        captureToken = null
        mutableState.update { it.copy(captureActive = false) }
        speech?.close()
        speech = null
        textEditing = false
        synchronized(mediaLock) {
            mutableState.update { it.copy(speechPending = false) }
            capture.stop()
            playback.stop()
            clearWalking()
        }
        val relayed = connection?.relayTransport
        relayed?.invalidatePending()
        connection?.close()
        connection = null
        if (relayed != null) relayMode.controller.attachTransport(null)
        relay?.close()
        relay = null
        endForeground()
        brightness?.invoke(null)
    }

    override fun onDestroy() {
        destroyed = true
        catalog.close()
        closeConnection()
        relayMode.close()
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
