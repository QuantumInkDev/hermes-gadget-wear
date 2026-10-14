package dev.quantumink.hermesgadget.mobile

import android.Manifest
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
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
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetsPage
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

    private var talkRequest: Job? = null

    override fun onStop() {
        talkRequest?.cancel()
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
            conversation.notice(
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
        setContent {
            val appearance = remember { AppearancePreferences(this@MainActivity) }
            val mode by appearance.mode.collectAsStateWithLifecycle()
            PhoneAppearance(mode) {
                val phone by conversation.state.collectAsStateWithLifecycle()
                val status by RelaySettings.status.collectAsStateWithLifecycle()
                var destination by androidx.compose.runtime.saveable.rememberSaveable {
                    mutableStateOf("profiles")
                }
                androidx.compose.runtime.LaunchedEffect(started, destination) {
                    if (destination == "conversation" && started && !phone.visible) {
                        phone.profile?.let { conversation.open(it, phone.page) }
                            ?: run { destination = "profiles" }
                    }
                }
                val directoryState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val directoryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
                fun navigate(value: String) {
                    talkRequest?.cancel()
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
                        when (destination) {
                            "profiles" -> directoryState.SaveableStateProvider("profiles") {
                                DirectoryPage(
                                    this@MainActivity,
                                    listState = directoryScroll,
                                    onTalk = { page ->
                                        talkRequest?.cancel()
                                        talkRequest = lifecycleScope.launch {
                                            val repository = CatalogRepository.get(
                                                this@MainActivity
                                            )
                                            val resolved = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    repository.currentProfile(page.key)
                                                }.getOrNull()
                                            }
                                            if (resolved != null && started &&
                                                lifecycle.currentState.isAtLeast(
                                                    androidx.lifecycle.Lifecycle.State.RESUMED
                                                ) &&
                                                destination == "profiles" &&
                                                repository.pages.value.any { it === resolved.first }
                                            ) {
                                                conversation.open(resolved.second, resolved.first)
                                                destination = "conversation"
                                            }
                                        }
                                    },
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
