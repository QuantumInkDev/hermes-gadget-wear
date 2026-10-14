package dev.quantumink.hermesgadget.mobile.widgets

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.CatalogRepository
import dev.quantumink.hermesgadget.mobile.CatalogVault
import dev.quantumink.hermesgadget.mobile.MainActivity
import dev.quantumink.hermesgadget.mobile.PhoneConversation
import dev.quantumink.hermesgadget.mobile.ProfileVault
import dev.quantumink.hermesgadget.mobile.RelaySettings
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomeWidgetLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun phone(activity: MainActivity) =
        ViewModelProvider(activity)[PhoneConversation::class.java]
    private fun awaitKey(activity: MainActivity, key: String) {
        val until = SystemClock.uptimeMillis() + 5000
        while (phone(activity).state.value.page?.key != key &&
            SystemClock.uptimeMillis() < until
        ) {
            SystemClock.sleep(30)
        }
        assertEquals(key, phone(activity).state.value.page?.key)
        instrumentation.waitForIdleSync()
    }
    private fun identities(): Map<String, String> {
        val files = File(context.filesDir, "phone-conversations").listFiles().orEmpty()
        return files.filter { it.isFile }.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            file.name to digest.joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    @Test fun coldWarmSameProfileAndStaleShortcutKeepExactBotDraftsAndIdentities() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val vault = CatalogVault(context)
        assumeTrue(
            "Never overwrite existing catalog setup",
            vault.sources().isEmpty() && vault.pages().isEmpty() &&
                ProfileVault(context).profiles().isEmpty()
        )
        val enabled = RelaySettings.enabled(context)
        val before = identities()
        RelaySettings.setEnabled(context, false)
        val sources =
            listOf(
                CatalogSource(
                    "studio",
                    "Studio",
                    "https://studio.example.invalid/catalog/v1",
                    "synthetic"
                ),
                CatalogSource("lab", "Lab", "https://lab.example.invalid/catalog/v1", "synthetic")
            )
        vault.saveSources(sources)
        vault.savePages(
            sources.map {
                CatalogPage(
                    it.id,
                    it.label,
                    CatalogProfile("orbit", "Orbit", "wss://${it.id}.example.invalid/gadget", null),
                    CatalogStatus.OFFLINE,
                    1
                )
            }
        )
        CatalogRepository.get(context).savedPages()
        val host = WidgetHostFixture(context)
        val id = host.bind(HomeWidgetPreset.FOUR)
        val store = HomeWidgets.store(context)
        val config = store.save(
            id,
            WidgetConfig(HomeWidgetPreset.FOUR, listOf("studio:orbit", "lab:orbit", null, null)),
            0
        )
        var activity: MainActivity? = null
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val studio = HomeWidgets.profilePendingIntent(
                context,
                id,
                0,
                "studio:orbit",
                config.revision
            )
            val lab = HomeWidgets.profilePendingIntent(context, id, 1, "lab:orbit", config.revision)
            studio.send()
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as MainActivity
            awaitKey(activity, "studio:orbit")
            instrumentation.runOnMainSync { phone(activity).draft("Keep Studio draft") }
            lab.send()
            awaitKey(activity, "lab:orbit")
            assertEquals("", phone(activity).state.value.draft)
            studio.send()
            awaitKey(activity, "studio:orbit")
            assertEquals("Keep Studio draft", phone(activity).state.value.draft)
            val version = phone(activity).state.value.sessionVersion
            studio.send()
            SystemClock.sleep(500)
            instrumentation.waitForIdleSync()
            assertEquals(version, phone(activity).state.value.sessionVersion)
            store.save(
                id,
                config.copy(slots = listOf(null, "lab:orbit", null, null)),
                config.revision
            )
            lab.send()
            WidgetUi.await("Bot shortcut unavailable")
            assertEquals("studio:orbit", phone(activity).state.value.page?.key)
            assertEquals(before, identities())
            assertTrue(
                activity.window.attributes.flags and
                    android.view.WindowManager.LayoutParams.FLAG_SECURE !=
                    0
            )
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.removeMonitor(monitor)
            host.close()
            store.delete(id)
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            vault.saveSourceOrder(emptyList())
            CatalogRepository.get(context).savedPages()
            RelaySettings.setEnabled(context, enabled)
        }
    }
}
