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
class WatchPetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext

    @Test
    fun petDeliveryCacheAndLegacyFallbackThroughActualService() {
        val endpoint = InstrumentationRegistry.getArguments()
            .getString("syntheticEndpoint").orEmpty()
        assumeTrue("Supply a synthetic localhost devserver", endpoint.startsWith("ws://localhost:"))
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
            waitUntil { service.state.value.pet != null }
            val atlas = requireNotNull(service.state.value.pet)
            assertEquals("Sample robot", atlas.name)
            assertEquals(6, atlas.rows.getValue("idle").size)
            assertEquals(4, atlas.rows.getValue("waving").size)
            assertEquals(96, atlas.rows.getValue("idle").first().width)
            assertEquals(104, atlas.rows.getValue("idle").first().height)
            assertEquals(
                "review",
                dev.quantumink.hermesgadget.protocol.PetStates.row("listening", atlas.rows.keys)
            )
            assertEquals(
                "idle",
                dev.quantumink.hermesgadget.protocol.PetStates.row("talking", atlas.rows.keys)
            )
            val restored = PetCache(context).load(endpoint)
            assertEquals(6, restored?.rows?.get("idle")?.size)
            assertEquals(null, PetCache(context).load(endpoint + "-different"))
            instrumentation.runOnMainSync { service.disconnect() }
            waitUntil { service.state.value.connection.status == ConnectionStatus.OFFLINE }
            assertTrue(service.state.value.pet != null)
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
