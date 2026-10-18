package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.widgets.WidgetUi
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Prompt
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Genuine native route entry, with fresh synthetic setup and identity-preservation guards. */
class SpeechSettingsNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun phone(activity: MainActivity) =
        ViewModelProvider(activity)[PhoneConversation::class.java]

    @Suppress("UNCHECKED_CAST")
    private fun target(activity: MainActivity): SpeechSettingsTarget? = (
        MainActivity::class.java.getDeclaredField("speechTarget\$delegate").apply {
            isAccessible = true
        }.get(activity) as androidx.compose.runtime.State<SpeechSettingsTarget?>
        ).value
    private fun request(activity: MainActivity, page: CatalogPage) {
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod(
                "requestSpeech",
                CatalogPage::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }.invoke(activity, page, null)
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue("Speech route did not settle", condition())
    }
    private fun identities(): Map<String, List<Byte>> =
        File(context.filesDir, "phone-conversations").listFiles().orEmpty().associate {
            it.name to
                it.readBytes().toList()
        }
    private fun fixture(test: (MainActivity, CatalogRepository) -> Unit) {
        assertTrue("Genuine owned emulator only", isAndroidEmulator())
        val vault = CatalogVault(context)
        assertTrue(
            "Never overwrite an existing setup",
            vault.sources().isEmpty() && vault.pages().isEmpty() &&
                ProfileVault(context).profiles().isEmpty()
        )
        assertFalse(RelaySettings.enabled(context))
        val before = identities()
        val sources = listOf("one", "two").map {
            CatalogSource(
                it,
                "Synthetic $it box",
                "https://$it.example.invalid/catalog/v1",
                "synthetic"
            )
        }
        vault.saveSources(sources)
        vault.savePages(
            sources.map {
                CatalogPage(
                    it.id,
                    it.label,
                    CatalogProfile(
                        "bot",
                        "Same named bot",
                        "wss://${it.id}.example.invalid/gadget",
                        null
                    ),
                    CatalogStatus.OFFLINE,
                    1
                )
            }
        )
        val repository = CatalogRepository.get(context)
        repository.savedPages()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            WidgetUi.await("Open Same named bot Talk and Chat")
            test(activity, repository)
            assertEquals(before, identities())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            vault.saveSourceOrder(emptyList())
            repository.savedPages()
            assertEquals(before, identities())
        }
    }

    @Test fun nativeDetailUsesExactEndpointAcrossRecreation() = fixture {
            activity,
            repository
        ->
        // The first same-name card belongs to source one; use its real native detail control.
        WidgetUi.click("Options for Same named bot")
        WidgetUi.click("Endpoint details")
        WidgetUi.click("Speech providers")
        await { target(activity) != null }
        assertEquals("one:bot", target(activity)!!.page.key)
        assertEquals("wss://one.example.invalid/gadget", target(activity)!!.endpoint.url)
        assertNull(phone(activity).state.value.profile)
        WidgetUi.await("Speech providers for phone and watch")
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        WidgetUi.await("Open Same named bot Talk and Chat")
        val second = repository.pages.value.single { it.key == "two:bot" }
        request(activity, second)
        await { target(activity)?.page?.key == "two:bot" }
        assertEquals("wss://two.example.invalid/gadget", target(activity)!!.endpoint.url)
        instrumentation.runOnMainSync { activity.recreate() }
        await {
            resumedActivity()?.let { target(it)?.page?.key == "two:bot" } == true
        }
        val recreated = resumedActivity()!!
        assertEquals("wss://two.example.invalid/gadget", target(recreated)!!.endpoint.url)
        assertNull(phone(recreated).state.value.profile)
        instrumentation.runOnMainSync { recreated.finish() }
    }

    @Test fun staleMountedPageAndLockedTurnCannotOpenOrAlterTheConversation() = fixture {
            activity,
            repository
        ->
        val stale = repository.pages.value.first()
        repository.savedPages()
        request(activity, stale)
        SystemClock.sleep(250)
        assertNull(target(activity))
        WidgetUi.click("Open Same named bot Talk and Chat")
        val model = phone(activity)
        await { model.state.value.visible }
        val current = model.state.value.page!!
        instrumentation.runOnMainSync { model.draft("Synthetic unsent draft") }
        @Suppress("UNCHECKED_CAST")
        val mutable = PhoneConversation::class.java.getDeclaredField("mutable").apply {
            isAccessible =
                true
        }
            .get(model) as MutableStateFlow<PhoneConversationState>
        val base = model.state.value
        val variants = listOf(
            base.copy(sending = true),
            base.copy(speaking = true),
            base.copy(preparingSpeech = true),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(
                        prompt = Prompt(
                            "approval",
                            "Synthetic approval",
                            "Keep pending",
                            0,
                            Long.MAX_VALUE
                        )
                    )
                )
            )
        )
        variants.forEach { locked ->
            instrumentation.runOnMainSync { mutable.value = locked }
            request(activity, current)
            SystemClock.sleep(100)
            assertNull(target(activity))
            assertSame(locked, model.state.value)
            assertEquals("Synthetic unsent draft", model.state.value.draft)
            WidgetUi.click("Settings")
            assertSame(locked, model.state.value)
        }
        instrumentation.runOnMainSync { mutable.value = base }
        request(activity, current)
        await { target(activity) != null }
        assertEquals("Synthetic unsent draft", model.state.value.draft)
        assertFalse(model.state.value.visible)
    }

    @Test fun delayedEntryCannotOpenAfterNavigationOrReplaceTheNextSelectedProfile() = fixture {
            activity,
            repository
        ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val first = repository.pages.value.first()
        val second = repository.pages.value.last()
        val gated = PhoneProfileNavigation(activity.lifecycleScope, { key, _ ->
            if (key == first.key) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                completed.countDown()
            }
            repository.currentProfile(key)
        }, { page -> repository.pages.value.any { it === page } })
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredField("speechNavigation\$delegate").apply {
                isAccessible = true
            }.set(activity, lazyOf(gated))
        }
        try {
            request(activity, first)
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            request(activity, second)
            await { target(activity)?.page === second }
            release.countDown()
            assertTrue(completed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertSame(second, target(activity)!!.page)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            WidgetUi.await("Open Same named bot Talk and Chat")
            assertNull(target(activity))
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { gated.cancel() }
        }
    }

    private fun resumedActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return activity
    }
}
