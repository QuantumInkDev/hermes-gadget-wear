package dev.quantumink.hermesgadget.mobile

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetLaunch
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetStore
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgets
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetsPage
import dev.quantumink.hermesgadget.mobile.widgets.LaunchDecision
import dev.quantumink.hermesgadget.mobile.widgets.WidgetLaunchNotice
import dev.quantumink.hermesgadget.mobile.widgets.WidgetLaunchRequest
import dev.quantumink.hermesgadget.mobile.widgets.decideLaunch
import dev.quantumink.hermesgadget.mobile.widgets.phoneSelectionLocked
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val catalogNetwork = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (RelaySettings.enabled(this@MainActivity)) {
                runCatching { CatalogRepository.get(this@MainActivity).refresh() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        getSystemService(android.net.ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(catalogNetwork)
    }

    private var destination by mutableStateOf("profiles")
    private var incomingWidget: WidgetLaunchRequest? = null
    private var pendingWidget by mutableStateOf<WidgetLaunchRequest?>(null)
    private var widgetUnavailable by mutableStateOf(false)
    private var widgetEditId by mutableStateOf<Int?>(null)
    private val navigation by lazy {
        PhoneProfileNavigation(lifecycleScope, { key, widget ->
            if (widget != null && !HomeWidgetLaunch.valid(this, widget)) {
                null
            } else {
                val repository = CatalogRepository.get(this)
                repository.savedPages()
                repository.currentProfile(key)?.takeIf {
                    it.first.status != CatalogStatus.WITHDRAWN
                }
            }
        }, { page -> CatalogRepository.get(this).pages.value.any { it === page } })
    }

    override fun onStop() {
        navigation.cancel()
        started = false
        conversation.hide()
        getSystemService(android.net.ConnectivityManager::class.java)
            .unregisterNetworkCallback(catalogNetwork)
        super.onStop()
    }
    private var enabled by mutableStateOf(false)
    private val conversation by lazy {
        androidx.lifecycle.ViewModelProvider(this)[PhoneConversation::class.java]
    }
    private var started by mutableStateOf(false)
    private var pendingPhoneUrl: String? = null
    private val phoneNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requested = pendingPhoneUrl
            pendingPhoneUrl = null
            if (conversation.state.value.visible &&
                conversation.state.value.profile?.endpoint?.url == requested
            ) {
                if (granted) {
                    conversation.connect()
                } else {
                    conversation.notice(
                        "Local network permission is needed for LAN/tailnet access."
                    )
                }
            }
        }
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            conversation.recordingUnavailable(
                if (granted) {
                    "Microphone ready. Hold to talk again."
                } else {
                    "Microphone permission was declined."
                }
            )
        }
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            enableRelay()
        }
    private val association =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            RelaySettings.report(
                getString(
                    if (it.resultCode ==
                        RESULT_OK
                    ) {
                        R.string.association_ready
                    } else {
                        R.string.association_cancelled
                    }
                )
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enabled = RelaySettings.enabled(this)
        destination = savedInstanceState?.getString("phone_destination")?.takeIf {
            it in
                setOf(
                    "profiles", "conversation", "watch", "settings", "tiles", "boxes",
                    "manual", "speech", "appearance", "home-widgets"
                )
        } ?: "profiles"
        if (savedInstanceState == null) {
            receiveWidget(intent)
        } else {
            pendingWidget = HomeWidgetLaunch.restore(savedInstanceState.getBundle("pending_widget"))
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                consumeWidget()
            }
        }
        setContent {
            val appearance = remember { AppearancePreferences(this@MainActivity) }
            val mode by appearance.mode.collectAsStateWithLifecycle()
            PhoneAppearance(mode) {
                val phone by conversation.state.collectAsStateWithLifecycle()
                val status by RelaySettings.status.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(started, destination) {
                    if (destination == "conversation" && started && !phone.visible) {
                        phone.profile?.let { conversation.open(it, phone.page) }
                            ?: run { destination = "profiles" }
                    }
                }
                val directoryState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val directoryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
                fun navigate(value: String) {
                    navigation.cancel()
                    pendingWidget = null
                    widgetUnavailable = false
                    widgetEditId = null
                    if (destination == "conversation") conversation.hide()
                    destination = value
                }
                androidx.activity.compose.BackHandler(
                    destination != "profiles"
                ) {
                    navigate(
                        when (destination) {
                            "conversation", "watch", "settings" -> "profiles"
                            "tiles" -> "watch"
                            else -> "settings"
                        }
                    )
                }
                PhoneScaffold(
                    topBar = {
                        androidx.compose.foundation.layout.Box(Modifier.statusBarsPadding()) {
                            if (destination != "conversation") PhoneBrandHeader()
                        }
                    },
                    bottomBar = {
                        if (WindowInsets.ime.getBottom(LocalDensity.current) ==
                            0
                        ) {
                            PhoneNavigationBar(
                                if (destination ==
                                    "tiles"
                                ) {
                                    "watch"
                                } else {
                                    destination
                                },
                                ::navigate
                            )
                        }
                    }
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(
                            padding
                        ).consumeWindowInsets(padding).imePadding()
                    ) {
                        WidgetLaunchNotice(
                            pendingWidget != null,
                            widgetUnavailable,
                            phoneSelectionLocked(phone),
                            widgetEditId != null,
                            { pendingWidget?.let { requestProfile(it.key, it) } },
                            {
                                widgetEditId?.let {
                                    startActivity(HomeWidgets.editIntent(this@MainActivity, it))
                                }
                            },
                            {
                                pendingWidget = null
                                widgetUnavailable = false
                                widgetEditId = null
                                navigation.cancel()
                            }
                        )
                        when (destination) {
                            "profiles" -> directoryState.SaveableStateProvider("profiles") {
                                DirectoryPage(
                                    this@MainActivity,
                                    listState = directoryScroll,
                                    onTalk = { page -> requestProfile(page.key, null) },
                                    onBoxes = { navigate("boxes") },
                                    onManual = { navigate("manual") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            "conversation" -> ConversationPage(
                                conversation,
                                phone.page,
                                ::connectPhone,
                                ::recordPhone,
                                { navigate("profiles") },
                                Modifier.weight(1f)
                            )
                            else -> Column(
                                Modifier.weight(1f).verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                when (destination) {
                                    "boxes" -> CatalogPage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "manual" -> ProfilesPage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "speech" -> SpeechSettingsPage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "appearance" -> AppearancePage(this@MainActivity) {
                                        navigate("settings")
                                    }
                                    "tiles" -> ProfileTilesPage { navigate("watch") }
                                    "home-widgets" -> HomeWidgetsPage {
                                        navigate("settings")
                                    }
                                    "settings" -> PhoneSettingsPage(this@MainActivity, ::navigate)
                                    else -> WatchDashboard(
                                        enabled,
                                        status,
                                        ::requestRelay,
                                        {
                                            RelaySettings.setEnabled(this@MainActivity, false)
                                            enabled = false
                                        },
                                        ::associateWatch,
                                        { RelaySettings.stop() },
                                        { navigate("tiles") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveWidget(intent)
        window.decorView.post { consumeWidget() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phone_destination", destination)
        pendingWidget?.let { outState.putBundle("pending_widget", HomeWidgetLaunch.bundle(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun receiveWidget(value: Intent) {
        if (value.action != HomeWidgets.ACTION_OPEN) return
        navigation.cancel()
        incomingWidget = HomeWidgetLaunch.parse(value)
        if (incomingWidget == null) {
            pendingWidget = null
            widgetUnavailable = true
            widgetEditId = null
        }
    }

    private fun consumeWidget() {
        if (!started ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            return
        }
        val request = incomingWidget ?: return
        incomingWidget = null
        requestProfile(request.key, request)
    }

    private fun requestProfile(key: String, widget: WidgetLaunchRequest?) {
        val requestedFrom = destination
        val widgetVersion = HomeWidgetStore.version()
        pendingWidget = null
        widgetUnavailable = false
        widgetEditId = null
        navigation.request(key, widget, {
            started && destination == requestedFrom &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }) { candidate ->
            val resolved = candidate.takeIf {
                widget == null || widgetVersion == HomeWidgetStore.version()
            }
            val state = conversation.state.value
            val decision = if (widget == null) {
                if (resolved == null) LaunchDecision.UNAVAILABLE else LaunchDecision.OPEN
            } else {
                decideLaunch(
                    key,
                    state.page?.key.takeIf {
                        state.visible && resolved != null &&
                            state.profile?.endpoint?.url == resolved.second.endpoint.url &&
                            state.profile.accessToken == resolved.second.accessToken
                    },
                    phoneSelectionLocked(state),
                    resolved != null
                )
            }
            when (decision) {
                LaunchDecision.IGNORE -> destination = "conversation"
                LaunchDecision.DEFER -> pendingWidget = widget
                LaunchDecision.UNAVAILABLE -> if (widget != null) {
                    widgetUnavailable = true
                    widgetEditId = widget.widgetId.takeIf { HomeWidgets.preset(this, it) != null }
                }
                LaunchDecision.OPEN -> resolved?.let {
                    conversation.open(it.second, it.first)
                    destination = "conversation"
                }
            }
        }
    }

    private fun connectPhone() {
        val profile = conversation.state.value.profile ?: return
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingPhoneUrl = profile.endpoint.url
            phoneNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            conversation.connect()
        }
    }

    private fun recordPhone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            conversation.recordingUnavailable("Allow microphone access, then hold to talk again.")
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            conversation.startRecording()
        }
    }

    private fun requestRelay() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
        if (needed.isEmpty()) enableRelay() else permissions.launch(needed.toTypedArray())
    }

    private fun enableRelay() {
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            RelaySettings.report(getString(R.string.local_permission_needed))
            return
        }
        RelaySettings.setEnabled(this, true)
        enabled = true
        RelaySettings.report(getString(R.string.relay_idle))
    }

    @Suppress("DEPRECATION")
    private fun associateWatch() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            RelaySettings.report(getString(R.string.association_unavailable))
            return
        }
        val request = AssociationRequest.Builder().addDeviceFilter(
            BluetoothDeviceFilter.Builder().build()
        )
            .setSingleDevice(false).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                association.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            @Deprecated("Platform callback for Android 10–12")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                association.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }
            override fun onFailure(error: CharSequence?) {
                RelaySettings.report(getString(R.string.association_unavailable))
            }
        }
        runCatching {
            val manager = getSystemService(CompanionDeviceManager::class.java)
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, mainExecutor, callback)
            } else {
                manager.associate(request, callback, null)
            }
        }.onFailure { RelaySettings.report(getString(R.string.association_unavailable)) }
    }
}
