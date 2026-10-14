package dev.quantumink.hermesgadget.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TileActivityLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun coldWarmUnknownAndDeferredLaunchesUseActualActivityServiceAndStockServers() {
        val urls = listOf("syntheticEndpoint", "syntheticSecondEndpoint", "syntheticThirdEndpoint")
            .map { InstrumentationRegistry.getArguments().getString(it).orEmpty() }
        assumeTrue(
            Build.MODEL.startsWith("sdk_") && urls.all {
                it.startsWith("ws://localhost:")
            } &&
                urls.distinct().size == 3
        )
        assumeTrue(File(context.filesDir, "gadget-vault").listFiles().isNullOrEmpty())
        val vault = IdentityVault(context)
        val pages = urls.mapIndexed { index, url ->
            val endpoint = Endpoint.parse(url, true, true)
            vault.save(EndpointProfile(endpoint, "Tile agent ${index + 1}", ""))
            CatalogPage(
                "manual",
                "Synthetic box",
                CatalogProfile(
                    ClientSpeech.endpointId(endpoint),
                    "Tile agent ${index + 1}",
                    null,
                    null
                ),
                CatalogStatus.AVAILABLE,
                1
            )
        }
        val unreviewed =
            CatalogPage(
                "fixture",
                "Synthetic box",
                CatalogProfile("review", "Review me", null, null),
                CatalogStatus.OFFLINE,
                1
            )
        WatchCatalogVault(context).apply {
            savePages(pages + unreviewed)
            select(pages.first().key)
        }
        for (permission in listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        if (Build.VERSION.SDK_INT >=
            37
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        }
        assertProvidersHaveNoConversationSideEffects(pages)
        val activity = instrumentation.startActivitySync(intent(unreviewed.key)) as MainActivity
        val ready = CountDownLatch(1)
        val bound = AtomicReference<GadgetService>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.set((binder as GadgetService.LocalBinder).service)
                ready.countDown()
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
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val service = bound.get()
            waitUntil { !service.state.value.loading }
            waitUntil { service.state.value.selectedPage == unreviewed.key }
            SystemClock.sleep(700)
            assertEquals(ConnectionStatus.OFFLINE, service.state.value.connection.status)
            assertFalse(service.state.value.connection.conversation.busy)
            assertNotEquals(
                ConversationMode.LISTENING,
                service.state.value.connection.conversation.mode
            )
            context.startActivity(intent(pages[1].key))
            waitUntil {
                service.state.value.selectedPage == pages[1].key &&
                    service.state.value.profile?.endpoint?.url == urls[1] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            assertFalse(activity.isDestroyed)
            assertEquals(urls[1], service.state.value.profile!!.endpoint.url)
            instrumentation.runOnMainSync {
                service.updateDraft("Keep this draft")
                service.startRecording()
            }
            waitUntil {
                service.state.value.connection.conversation.mode ==
                    ConversationMode.LISTENING
            }
            context.startActivity(intent(pages[2].key))
            waitUntil { hasText("Open Tile agent 3 after the current turn") }
            assertEquals(pages[1].key, service.state.value.selectedPage)
            assertEquals("Keep this draft", service.state.value.draft)
            instrumentation.runOnMainSync { service.cancelRecording() }
            waitUntil {
                service.state.value.selectedPage == pages[2].key &&
                    service.state.value.profile?.endpoint?.url == urls[2] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            instrumentation.runOnMainSync { service.text("Synthetic carousel approval") {} }
            waitUntil { service.state.value.connection.conversation.prompt != null }
            context.startActivity(intent(pages[0].key))
            waitUntil { hasText("Open Tile agent 1 after the current turn") }
            assertEquals(pages[2].key, service.state.value.selectedPage)
            SystemClock.sleep(650)
            val prompt = requireNotNull(service.state.value.connection.conversation.prompt)
            instrumentation.runOnMainSync { service.answer(prompt.id, false) }
            waitUntil { service.state.value.connection.conversation.prompt == null }
            assertTrue(service.state.value.connection.conversation.busy)
            assertEquals(pages[2].key, service.state.value.selectedPage)
            instrumentation.runOnMainSync { service.cancel() }
            waitUntil {
                service.state.value.selectedPage == pages[0].key &&
                    service.state.value.profile?.endpoint?.url == urls[0] &&
                    service.state.value.connection.status == ConnectionStatus.PAIRED
            }
            val identities = vault.profiles().size
            context.startActivity(intent("fixture:removed"))
            waitUntil { hasText("Profile unavailable") }
            assertEquals(pages[0].key, service.state.value.selectedPage)
            assertEquals(identities, vault.profiles().size)
            assertNotEquals(
                ConversationMode.LISTENING,
                service.state.value.connection.conversation.mode
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                bound.get()?.disconnect()
            }
            context.unbindService(binding)
        }
    }

    @Suppress("DEPRECATION") // Android returns our own process services.
    private fun assertProvidersHaveNoConversationSideEffects(pages: List<CatalogPage>) {
        TilePinStore(context).editLocal(pages[1].key, pages.map { it.key } + listOf(null))
        fun files() = File(context.filesDir, "gadget-vault").listFiles().orEmpty().associate {
            it.name to
                it.readBytes().toList()
        }
        val before = files()
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val recordings = audio.activeRecordingConfigurations
        val providers = listOf(NativeTile(false), NativeTile(true))
        try {
            for (provider in providers) {
                val tile = provider.tileRequest().get(5, TimeUnit.SECONDS)
                val resources = provider.resourcesRequest(
                    tile.resourcesVersion
                ).get(5, TimeUnit.SECONDS)
                assertEquals(tile.resourcesVersion, resources.version)
            }
            assertEquals(before, files())
            assertEquals(recordings, audio.activeRecordingConfigurations)
            assertTrue(
                context.getSystemService(
                    android.app.ActivityManager::class.java
                ).getRunningServices(100).none {
                    it.service.className ==
                        GadgetService::class.java.name
                }
            )
        } finally {
            providers.forEach { it.onDestroy() }
        }
    }
    private inner class NativeTile(override val grid: Boolean) : GadgetTileService() {
        init {
            attachBaseContext(context)
        }
        fun tileRequest() =
            onTileRequest(androidx.wear.tiles.RequestBuilders.TileRequest.Builder().build())
        fun resourcesRequest(version: String) = onTileResourcesRequest(
            androidx.wear.tiles.RequestBuilders.ResourcesRequest.Builder().setVersion(
                version
            ).build()
        )
    }
    private fun intent(key: String) = Intent(
        context,
        MainActivity::class.java
    ).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    ).putExtra(TileProfileLaunch.EXTRA_PAGE_KEY, key)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(75)
        assertTrue("Native tile launch condition timed out", condition())
    }
    private fun hasText(text: String): Boolean {
        fun find(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == text ||
            (0 until node.childCount).any { node.getChild(it)?.let(::find) == true }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find) == true
    }
}
