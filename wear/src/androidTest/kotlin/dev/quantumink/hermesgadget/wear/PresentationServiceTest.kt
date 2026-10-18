package dev.quantumink.hermesgadget.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PresentationServiceTest {
    @Test fun actualServiceRefusesCorruptStoreAndKeepsModeConnectionAndBytes() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "profile-presentation")
        assumeTrue("Never replace a saved choice", !file.exists())
        val bytes = "synthetic corrupt presentation".toByteArray()
        file.writeBytes(bytes)
        val latch = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
                latch.countDown()
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
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(bound.get())
            await { !service.state.value.loading }
            await { service.state.value.presentationNotice.contains("bytes were kept") }
            val catalog =
                CatalogPage(
                    "synthetic-presentation",
                    "Fixture",
                    CatalogProfile("offline", "Same name", null, null),
                    CatalogStatus.OFFLINE,
                    1
                )
            val page = ProfilePages.merge(emptyList(), listOf(catalog)).single()
            instrumentation.runOnMainSync {
                service.applyCatalog(listOf(catalog), page.key)
                service.rememberPage(page.key)
            }
            val connection = service.state.value.connection
            instrumentation.runOnMainSync {
                service.setPresentation(page.key, page.presentationKey(), PresentationMode.PET)
            }
            await { !service.state.value.presentationSaving }
            assertEquals(
                PresentationMode.PORTRAIT,
                service.state.value.presentation[page.presentationKey()]
            )
            assertSame(connection, service.state.value.connection)
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(service.state.value.presentationNotice.contains("bytes were kept"))
        } finally {
            context.unbindService(binding)
            context.stopService(Intent(context, GadgetService::class.java))
            file.delete()
        }
    }
    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Service presentation did not settle")
    }
}
