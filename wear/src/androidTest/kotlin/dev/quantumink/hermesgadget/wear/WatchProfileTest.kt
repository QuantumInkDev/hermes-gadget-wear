package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic emulator gate; never replaces an existing endpoint. */
@RunWith(AndroidJUnit4::class)
class WatchProfileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test
    fun independentProfileSwitchCancelsRecordingAndClearsOldReply() {
        val endpoint = InstrumentationRegistry.getArguments()
            .getString("syntheticEndpoint").orEmpty()
        assumeTrue("Supply a synthetic localhost devserver", endpoint.startsWith("ws://localhost:"))
        val second = InstrumentationRegistry.getArguments()
            .getString("syntheticSecondEndpoint").orEmpty()
        assumeTrue(
            "Supply a second synthetic endpoint",
            second.startsWith("ws://localhost:") &&
                second != endpoint
        )
        assumeTrue(
            "Do not overwrite an existing saved setup",
            File(context.filesDir, "gadget-vault").listFiles().isNullOrEmpty()
        )
        automation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS
        )
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 37) {
            automation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val connected = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            context.bindService(
                Intent(context, GadgetService::class.java),
                binding,
                Context.BIND_AUTO_CREATE
            )
        )
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(bound.get())
            waitUntil { !service.state.value.loading }
            instrumentation.runOnMainSync {
                service.save(endpoint, "Synthetic pet test", "", true, true) { service.connect() }
            }
            waitUntil { service.state.value.connection.status == ConnectionStatus.PAIRED }
            val vault = IdentityVault(context)
            val firstProfile = requireNotNull(vault.profile())
            val firstKey = vault.identity(firstProfile.endpoint).deviceId
            instrumentation.runOnMainSync {
                service.text("Original first reply") {}
            }
            waitUntil {
                service.state.value.connection.conversation.reply ==
                    "You said: Original first reply"
            }
            instrumentation.runOnMainSync { service.startRecording() }
            waitUntil {
                service.state.value.connection.conversation.mode ==
                    dev.quantumink.hermesgadget.protocol.ConversationMode.LISTENING
            }
            instrumentation.runOnMainSync {
                service.save(second, "Synthetic second", "", true, true) { service.connect() }
            }
            waitUntil {
                service.state.value.profile?.endpoint?.url == second &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.connection.conversation.reply)
            assertEquals(
                dev.quantumink.hermesgadget.protocol.ConversationMode.READY,
                service.state.value.connection.conversation.mode
            )
            assertEquals(false, service.state.value.speaking)
            val secondProfile = requireNotNull(vault.profile())
            val secondKey = vault.identity(secondProfile.endpoint).deviceId
            org.junit.Assert.assertNotEquals(firstKey, secondKey)
            assertEquals(2, service.state.value.profiles.size)
            instrumentation.runOnMainSync { service.text("Original second reply") {} }
            waitUntil {
                service.state.value.connection.conversation.reply ==
                    "You said: Original second reply"
            }
            instrumentation.runOnMainSync {
                service.selectProfile(endpoint) { service.connect() }
            }
            waitUntil {
                service.state.value.profile?.endpoint?.url == endpoint &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.connection.conversation.reply)
            assertEquals(firstKey, vault.identity(firstProfile.endpoint).deviceId)
            instrumentation.runOnMainSync { service.removeProfile(endpoint) { service.connect() } }
            waitUntil {
                service.state.value.profile?.endpoint?.url == second &&
                    service.state.value.profiles.size == 1 &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertEquals("", service.state.value.connection.conversation.reply)
            assertEquals(firstKey, vault.identity(firstProfile.endpoint).deviceId)
            assertEquals(secondKey, vault.identity(secondProfile.endpoint).deviceId)
        } finally {
            bound.get()?.let { service ->
                instrumentation.runOnMainSync { service.disconnect() }
            }
            context.unbindService(binding)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        error("Watch state did not reach the expected condition")
    }
}
