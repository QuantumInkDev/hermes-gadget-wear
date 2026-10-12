package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import dev.quantumink.hermesgadget.client.AppearancePreferences
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private var gadget by mutableStateOf<GadgetService?>(null)
    private var bound = false
    private val navigation = WatchNavigation()
    private val tileLaunch = TileProfileLaunch()
    private val networkPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { gadget?.connect() }
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gadget?.report(
            getString(if (granted) R.string.microphone_ready else R.string.microphone_denied)
        )
    }
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val current = (service as GadgetService.LocalBinder).service
            gadget = current
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) attach(current)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            gadget = null
        }
    }

    private fun attach(current: GadgetService) {
        current.attach(
            timer = { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            },
            screenBrightness = { level ->
                window.attributes = window.attributes.apply {
                    screenBrightness =
                        level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                true
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchIntent = savedInstanceState?.getString("pendingTileKey")?.let {
            Intent().putExtra(TileProfileLaunch.EXTRA_PAGE_KEY, it)
        } ?: intent
        tileLaunch.receive(launchIntent)
        intent.removeExtra(TileProfileLaunch.EXTRA_PAGE_KEY)
        setContent {
            val ambientManager = rememberAmbientModeManager()
            CompositionLocalProvider(LocalAmbientModeManager provides ambientManager) {
                val appearance = remember { AppearancePreferences(this@MainActivity) }
                val mode by appearance.mode.collectAsStateWithLifecycle()
                WatchAppearance(mode) {
                    val ambient = ambientManager.currentAmbientMode as? AmbientMode.Ambient
                    var minute by remember {
                        mutableLongStateOf(System.currentTimeMillis() / 60000)
                    }
                    ambientManager.AmbientTickEffect { minute = System.currentTimeMillis() / 60000 }
                    LaunchedEffect(ambient != null, gadget) {
                        if (ambient != null) {
                            gadget?.enterAmbient()
                            minute = System.currentTimeMillis() / 60000
                        }
                    }
                    val service = gadget
                    val watch = service?.state?.collectAsStateWithLifecycle()?.value
                    KeepConversationVisible(
                        window,
                        watch?.screenTurnStarted,
                        watch?.conversationInProgress == true,
                        ambient != null
                    ) { service?.screenLeaseExpired(watch?.screenTurnStarted) }
                    LaunchedEffect(service, watch, tileLaunch.pendingKey) {
                        if (service != null && watch != null) reconcileTile(service)
                    }
                    if (ambient != null) {
                        val artwork = if (service == null) {
                            null to null
                        } else {
                            val pictures = remember(service) {
                                service.state.map { watch ->
                                    val page = ProfilePages.merge(watch.profiles, watch.catalog)
                                        .singleOrNull { it.key == watch.selectedPage }
                                    val artwork = page?.let { watch.artwork[it.key] }
                                    val pet = if (page?.approved?.endpoint?.url ==
                                        watch.profile?.endpoint?.url &&
                                        page?.approved != null
                                    ) {
                                        watch.pet ?: artwork?.pet
                                    } else {
                                        artwork?.pet
                                    }
                                    pet to artwork?.portrait
                                }.distinctUntilChanged()
                            }
                            val artwork by pictures.collectAsStateWithLifecycle(
                                initialValue =
                                null to null
                            )
                            artwork
                        }
                        AmbientPet(
                            artwork.first,
                            minute,
                            ambient.isLowBitAmbientSupported,
                            ambient.isBurnInProtectionRequired,
                            artwork.second
                        )
                    } else if (service == null ||
                        (tileLaunch.pendingKey != null && !tileLaunch.deferred)
                    ) {
                        WaitingScreen()
                    } else if (tileLaunch.unavailable) {
                        RoundPage {
                            item { Heading("Profile unavailable") }
                            item {
                                Body(
                                    "This tile's profile was removed or is not in the local roster."
                                )
                            }
                            item {
                                Secondary("Choose a profile", {
                                    tileLaunch.clear()
                                    navigation.route.value = "home"
                                    navigation.pickerOpen.value = true
                                })
                            }
                        }
                    } else {
                        if (tileLaunch.notice.isNotEmpty()) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = tileLaunch::dismissNotice
                            ) {
                                RoundPage {
                                    item { Heading("Profile waiting") }
                                    item { Body(tileLaunch.notice) }
                                    item {
                                        Secondary(
                                            "Continue current turn",
                                            tileLaunch::dismissNotice
                                        )
                                    }
                                    item { Secondary("Cancel switch", tileLaunch::clear) }
                                }
                            }
                        }
                        WatchScreen(
                            service = service,
                            connect = ::requestConnection,
                            startRecording = ::requestRecording,
                            navigation = navigation,
                            permissions = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", packageName, null))
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    private fun reconcileTile(service: GadgetService) {
        tileLaunch.reconcile(service.state.value) { key ->
            service.rememberPage(key)
            navigation.page.value = key
            navigation.route.value = "home"
            navigation.pickerOpen.value = false
            navigation.pinKey.value = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tileLaunch.receive(intent)
        intent.removeExtra(TileProfileLaunch.EXTRA_PAGE_KEY)
        setIntent(intent)
        gadget?.let(::reconcileTile)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val pending = tileLaunch.pendingKey ?: if (tileLaunch.unavailable) "" else null
        pending?.let { outState.putString("pendingTileKey", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bound = bindService(Intent(this, GadgetService::class.java), binding, BIND_AUTO_CREATE)
        } else {
            gadget?.let(::attach)
        }
    }

    override fun onStop() {
        gadget?.hidden()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) unbindService(binding)
        bound = false
        gadget = null
        super.onDestroy()
    }

    private fun requestConnection() {
        val profile = gadget?.state?.value?.profile ?: return
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 37 && profile.endpoint.privateNetwork &&
                checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            gadget?.connect()
        } else {
            networkPermissions.launch(
                permissions.toTypedArray()
            )
        }
    }

    private fun requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            gadget?.startRecording()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
