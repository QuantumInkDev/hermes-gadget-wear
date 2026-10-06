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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.wear.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    private var gadget by mutableStateOf<GadgetService?>(null)
    private var bound = false
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
        setContent {
            MaterialTheme {
                val service = gadget
                if (service == null) {
                    WaitingScreen()
                } else {
                    WatchScreen(
                        service = service,
                        connect = ::requestConnection,
                        startRecording = ::requestRecording,
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
