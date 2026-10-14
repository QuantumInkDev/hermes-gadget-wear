package dev.quantumink.hermesgadget.mobile.widgets

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.CatalogRepository
import dev.quantumink.hermesgadget.mobile.CatalogVault
import dev.quantumink.hermesgadget.mobile.ProfileVault
import dev.quantumink.hermesgadget.mobile.RelaySettings
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomeWidgetEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun open(id: Int): HomeWidgetConfigActivity = instrumentation.startActivitySync(
        HomeWidgets.editIntent(context, id)
    ) as HomeWidgetConfigActivity
    private fun finished(activity: HomeWidgetConfigActivity) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!activity.isFinishing && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue(activity.isFinishing)
    }
    private fun fixture(block: (WidgetHostFixture) -> Unit) {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val vault = CatalogVault(context)
        assumeTrue(
            "Never overwrite existing catalog setup",
            vault.sources().isEmpty() && vault.pages().isEmpty() &&
                ProfileVault(context).profiles().isEmpty()
        )
        val enabled = RelaySettings.enabled(context)
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
        try {
            block(host)
        } finally {
            host.close()
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            vault.saveSourceOrder(emptyList())
            CatalogRepository.get(context).savedPages()
            RelaySettings.setEnabled(context, enabled)
        }
    }

    @Test fun chooseReorderAndCancelKeepInstancesAndSameNamedBotsSeparate() = fixture { host ->
        val first = host.bind(HomeWidgetPreset.FOUR)
        val second = host.bind(HomeWidgetPreset.FOCUS)
        val store = HomeWidgets.store(context)
        val other = store.save(
            second,
            WidgetConfig(HomeWidgetPreset.FOCUS, listOf("studio:orbit")),
            0
        )
        var activity: HomeWidgetConfigActivity? = null
        try {
            activity = open(first)
            WidgetUi.await("Choose bots")
            WidgetUi.click("Edit widget slot 1")
            WidgetUi.click("Choose Orbit · Studio")
            WidgetUi.click("Edit widget slot 2")
            WidgetUi.click("Choose Orbit · Lab")
            WidgetUi.click("Edit widget slot 2")
            WidgetUi.click("Move earlier")
            WidgetUi.click("Dark")
            WidgetUi.click("Save widget")
            finished(activity)
            val saved = requireNotNull(store.config(first))
            assertEquals(listOf("lab:orbit", "studio:orbit", null, null), saved.slots)
            assertEquals(WidgetAppearance.DARK, saved.appearance)
            assertEquals(other, store.config(second))
            activity = open(first)
            WidgetUi.click("Edit widget slot 1")
            WidgetUi.click("Remove bot")
            WidgetUi.click("Cancel")
            finished(activity)
            assertEquals(saved, store.config(first))
            assertEquals(other, store.config(second))
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            store.delete(first)
            store.delete(second)
        }
    }

    @Test fun invalidHostIdCannotCreateConfigurationOrInitializePairing() {
        val id = Int.MAX_VALUE
        val activity = open(id)
        try {
            WidgetUi.await("Widget unavailable")
            assertNull(HomeWidgets.store(context).config(id))
            WidgetUi.click("Close")
            finished(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
