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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
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
        enabled = RelaySettings.enabled(this)
        setContent {
            MaterialTheme {
                val status by RelaySettings.status.collectAsStateWithLifecycle()
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF11170F))
                        .verticalScroll(
                            rememberScrollState()
                        ).padding(horizontal = 28.dp, vertical = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        stringResource(R.string.gadget_title),
                        color = Color(0xFFB6ED93),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        stringResource(R.string.setup_title),
                        color = Color(0xFFF0F3EB),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(stringResource(R.string.relay_tls_hint), color = Color(0xFFBEC7B7))
                    Text(stringResource(R.string.relay_vpn_hint), color = Color(0xFFBEC7B7))
                    Button(onClick = {
                        if (enabled) {
                            RelaySettings.setEnabled(this@MainActivity, false)
                            enabled = false
                        } else {
                            requestRelay()
                        }
                    }) {
                        Text(
                            stringResource(
                                if (enabled) R.string.disable_relay else R.string.enable_relay
                            )
                        )
                    }
                    Button(onClick = ::associateWatch) {
                        Text(stringResource(R.string.associate_watch))
                    }
                    Text(stringResource(R.string.association_hint), color = Color(0xFFBEC7B7))
                    Button(onClick = {
                        RelaySettings.stop()
                    }) { Text(stringResource(R.string.stop_relay)) }
                    Text(
                        status.ifBlank {
                            getString(R.string.relay_idle)
                        },
                        color = Color(0xFFB6ED93)
                    )
                    Text(stringResource(R.string.privacy_note), color = Color(0xFFBEC7B7))
                }
            }
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
            .setSingleDevice(true).build()
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
