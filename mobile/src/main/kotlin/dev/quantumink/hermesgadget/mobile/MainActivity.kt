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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quantumink.hermesgadget.client.CuePreferences

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

    override fun onStop() {
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
            MaterialTheme(colorScheme = darkColorScheme()) {
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
                fun navigate(value: String) {
                    if (destination == "conversation") conversation.hide()
                    destination = value
                }
                androidx.activity.compose.BackHandler(
                    destination !in setOf("profiles", "watch", "settings")
                ) {
                    navigate(if (destination == "conversation") "profiles" else "settings")
                }
                androidx.compose.material3.Scaffold(
                    containerColor = Color(0xFF11170F),
                    bottomBar = {
                        androidx.compose.material3.NavigationBar {
                            listOf(
                                "profiles" to "Profiles",
                                "watch" to "Watch",
                                "settings" to "Settings"
                            )
                                .forEach { (route, label) ->
                                    NavigationBarItem(
                                        selected = destination == route,
                                        onClick = { navigate(route) },
                                        icon = { Text(label.take(1)) },
                                        label = { Text(label) }
                                    )
                                }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        Text(
                            stringResource(R.string.app_name),
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFB6ED93)
                        )
                        when (destination) {
                            "profiles" -> DirectoryPage(
                                this@MainActivity,
                                onTalk = { page ->
                                    runCatching {
                                        CatalogRepository.get(this@MainActivity).profileFor(page)
                                    }
                                        .getOrNull()?.let {
                                            conversation.open(it, page)
                                            destination = "conversation"
                                        }
                                },
                                onBoxes = { navigate("boxes") },
                                onManual = { navigate("manual") },
                                modifier = Modifier.weight(1f)
                            )
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
                                    "settings" -> PhoneSettingsPage(this@MainActivity, ::navigate)
                                    else -> {
                                        Text(
                                            "Watch relay",
                                            style = MaterialTheme.typography.headlineMedium
                                        )
                                        Text(stringResource(R.string.relay_tls_hint))
                                        Text(stringResource(R.string.relay_vpn_hint))
                                        Button({
                                            if (enabled) {
                                                RelaySettings.setEnabled(this@MainActivity, false)
                                                enabled =
                                                    false
                                            } else {
                                                requestRelay()
                                            }
                                        }) {
                                            Text(
                                                stringResource(
                                                    if (enabled) {
                                                        R.string.disable_relay
                                                    } else {
                                                        R.string.enable_relay
                                                    }
                                                )
                                            )
                                        }
                                        Button(::associateWatch) {
                                            Text(stringResource(R.string.associate_watch))
                                        }
                                        Text(stringResource(R.string.association_hint))
                                        Button({
                                            RelaySettings.stop()
                                        }) { Text(stringResource(R.string.stop_relay)) }
                                        Text(status.ifBlank { getString(R.string.relay_idle) })
                                        Text(stringResource(R.string.privacy_note))
                                    }
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
