package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.widgets.WidgetUi
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Prompt
import dev.quantumink.hermesgadget.protocol.QuickAction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fresh owned emulator only: no server connection, enrollment, microphone or send. */
class QuickActionLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun phone(activity: MainActivity) =
        ViewModelProvider(activity)[PhoneConversation::class.java]

    @Suppress("UNCHECKED_CAST")
    private fun mutable(activity: MainActivity): MutableStateFlow<PhoneConversationState> =
        PhoneConversation::class.java.getDeclaredField("mutable").apply {
            isAccessible = true
        }.get(phone(activity)) as MutableStateFlow<PhoneConversationState>
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue(condition())
    }
    private fun request(activity: MainActivity, id: String) {
        assertFalse(activity.isFinishing)
        context.startActivity(QuickActionShortcuts.intent(context, id))
        instrumentation.waitForIdleSync()
    }

    @Test fun coldWarmLatestDeletedAndLockedLaunchesNeverEnrollOrSend() {
        assertTrue("Owned emulator only", isAndroidEmulator())
        val vault = CatalogVault(context)
        assertTrue(
            "Never overwrite existing catalog",
            vault.sources().isEmpty() && vault.pages().isEmpty() &&
                ProfileVault(context).profiles().isEmpty()
        )
        assertFalse(RelaySettings.enabled(context))
        val store = QuickActionStore(context)
        val previous = store.load()
        val identityDirectory = File(context.filesDir, "phone-conversations")
        val identities = identityDirectory.listFiles().orEmpty().associate {
            it.name to
                it.readBytes().toList()
        }
        val sources = listOf("one", "two").map {
            CatalogSource(it, "Same box", "https://$it.example.invalid/catalog/v1", "synthetic")
        }
        val pages = sources.map {
            CatalogPage(
                it.id,
                it.label,
                CatalogProfile("bot", "Same name", "wss://${it.id}.example.invalid/gadget", null),
                CatalogStatus.OFFLINE,
                0
            )
        }
        val one = QuickAction(UUID.randomUUID().toString(), "one:bot", "One review", "First prompt")
        val two =
            QuickAction(UUID.randomUUID().toString(), "two:bot", "Two review", "Second prompt")
        vault.saveSources(sources)
        vault.savePages(pages)
        CatalogRepository.get(context).savedPages()
        store.save(listOf(one, two))
        var activity: MainActivity? = null
        try {
            activity =
                instrumentation.startActivitySync(
                    QuickActionShortcuts.intent(
                        context,
                        one.id
                    ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ) as MainActivity
            val host = activity
            WidgetUi.await("Review action")
            assertFalse(phone(host).state.value.visible)
            WidgetUi.click("Preview prompt")
            WidgetUi.await("First prompt")
            assertFalse(phone(host).state.value.visible)
            WidgetUi.click("Use prompt in Talk/Chat")
            await { phone(host).state.value.page?.key == "one:bot" }
            assertEquals("First prompt", phone(host).state.value.draft)
            assertEquals(
                dev.quantumink.hermesgadget.protocol.ConnectionStatus.OFFLINE,
                phone(host).state.value.connection.status
            )
            val original = phone(host).state.value
            val approval = original.copy(
                connection = ConnectionState(
                    conversation = ConversationState(
                        prompt = Prompt("synthetic", "Approval", "Retain", 0, null)
                    )
                )
            )
            instrumentation.runOnMainSync { mutable(host).value = approval }
            request(host, two.id)
            WidgetUi.await("Open action review")
            assertEquals("one:bot", phone(host).state.value.page?.key)
            assertNotNull(phone(host).state.value.connection.conversation.prompt)
            var open = requireNotNull(WidgetUi.find("Open action review"))
            while (!open.isClickable && open.parent != null) open = open.parent
            assertFalse(open.isEnabled)
            instrumentation.runOnMainSync { mutable(host).value = original }
            store.save(listOf(one, two.copy(template = "Latest prompt")))
            WidgetUi.click("Open action review")
            WidgetUi.await("Two review")
            WidgetUi.click("Preview prompt")
            WidgetUi.await("Latest prompt")
            WidgetUi.click("Use prompt in Talk/Chat")
            await { phone(host).state.value.page?.key == "two:bot" }
            assertEquals("Latest prompt", phone(host).state.value.draft)
            // A removed profile cannot select its same-named sibling.
            vault.savePages(listOf(pages[0]))
            CatalogRepository.get(context).savedPages()
            request(host, two.id)
            WidgetUi.await("This profile is missing or withdrawn. Choose a profile in Edit action.")
            assertFalse(phone(host).state.value.visible)
            store.save(listOf(one))
            request(host, two.id)
            WidgetUi.await("This action is missing or was removed. Nothing was sent.")
            assertFalse(phone(host).state.value.visible)
            assertEquals(
                identities,
                identityDirectory.listFiles().orEmpty().associate {
                    it.name to
                        it.readBytes().toList()
                }
            )
            val manager = context.getSystemService(ShortcutManager::class.java)
            assertNull(QuickActionShortcuts.sync(context, listOf(one)))
            val dynamic = manager.dynamicShortcuts.filter { it.id.startsWith("quick-action:") }
            assertEquals(listOf("quick-action:" + one.id), dynamic.map { it.id })
            assertEquals(
                one.id,
                QuickActionShortcuts.parse(requireNotNull(dynamic.single().intent))
            )
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            store.save(previous)
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            CatalogRepository.get(context).savedPages()
            QuickActionShortcuts.sync(context, previous)
        }
    }
}
