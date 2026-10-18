package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.widgets.WidgetUi
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.QuickAction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delays the real activity's lookup, then drives real review routes while it is in flight. */
class QuickActionTransferTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun editActionInvalidatesTheDelayedTransfer() = delayedTransfer("edit")

    @Test fun backAndSelectingAnotherActionInvalidateTheDelayedTransfer() = delayedTransfer("back")

    @Test fun changingInputsAndRepreviewingCannotReviveTheOldTransfer() = delayedTransfer("inputs")

    private fun identities(): Map<String, String> =
        File(context.filesDir, "phone-conversations").listFiles().orEmpty().associate { file ->
            file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }

    private fun input(value: String) {
        val field = WidgetUi.nodes().single { it.isEditable && it.isVisibleToUser }
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        )
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue(condition())
    }

    private fun delayedTransfer(route: String) {
        assertTrue("Owned emulator only", android.os.Build.MODEL.startsWith("sdk_"))
        val vault = CatalogVault(context)
        assertTrue(
            "Never overwrite existing setup",
            vault.sources().isEmpty() && vault.pages().isEmpty() &&
                ProfileVault(context).profiles().isEmpty()
        )
        assertFalse(RelaySettings.enabled(context))
        val beforeIdentities = identities()
        val store = QuickActionStore(context)
        val previous = store.load()
        val page =
            CatalogPage(
                "n4fix",
                "Synthetic box",
                CatalogProfile("bot", "Synthetic bot", "wss://n4fix.example.invalid/gadget", null),
                CatalogStatus.OFFLINE,
                0
            )
        val action =
            QuickAction(
                UUID.randomUUID().toString(),
                page.key,
                "Gated action",
                "Expanded {{issue}}"
            )
        val other = action.copy(id = UUID.randomUUID().toString(), title = "Other action")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lookupCompleted = CountDownLatch(1)
        var activity: MainActivity? = null
        var navigation: PhoneProfileNavigation? = null
        try {
            vault.saveSources(
                listOf(
                    CatalogSource(
                        "n4fix",
                        "Synthetic box",
                        "https://n4fix.example.invalid/catalog/v1",
                        "synthetic"
                    )
                )
            )
            vault.savePages(listOf(page))
            val repository = CatalogRepository.get(context)
            repository.savedPages()
            store.save(listOf(action, other))
            val host = instrumentation.startActivitySync(
                QuickActionShortcuts.intent(
                    context,
                    action.id
                ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as MainActivity
            activity = host
            WidgetUi.await("Review action")
            val model = ViewModelProvider(host)[PhoneConversation::class.java]
            instrumentation.runOnMainSync {
                val resolved = requireNotNull(repository.currentProfile(page.key))
                model.open(resolved.second, resolved.first)
                model.draft("Keep saved draft")
                model.hide()
                val gated = PhoneProfileNavigation(host.lifecycleScope, { key, _ ->
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Lookup gate timed out" }
                    repository.savedPages()
                    requireNotNull(repository.currentProfile(key)).also {
                        lookupCompleted.countDown()
                    }
                }, { candidate -> repository.pages.value.any { it === candidate } })
                navigation = gated
                MainActivity::class.java.getDeclaredField("navigation\$delegate").apply {
                    isAccessible =
                        true
                }.set(host, lazyOf(gated))
            }
            input("Original")
            WidgetUi.click("Preview prompt")
            WidgetUi.await("Expanded Original")
            WidgetUi.click("Use prompt in Talk/Chat")
            assertTrue("Transfer lookup entered", entered.await(3, TimeUnit.SECONDS))
            val job =
                requireNotNull(
                    PhoneProfileNavigation::class.java.getDeclaredField("job").apply {
                        isAccessible =
                            true
                    }.get(navigation) as? Job
                )
            when (route) {
                "edit" -> {
                    WidgetUi.click("Edit action")
                    WidgetUi.await("Action title")
                }
                "back" -> {
                    WidgetUi.click("Back to quick actions")
                    WidgetUi.await("Create action")
                    WidgetUi.click("Review Other action")
                    WidgetUi.await("Other action")
                }
                "inputs" -> {
                    WidgetUi.click("Change inputs")
                    WidgetUi.await("issue")
                    input("Revised")
                    input("Original")
                    WidgetUi.click("Preview prompt")
                    WidgetUi.await("Expanded Original")
                }
            }
            release.countDown()
            runBlocking { withTimeout(5000) { job.join() } }
            assertTrue(
                "Lookup returned a current valid profile",
                lookupCompleted.await(2, TimeUnit.SECONDS)
            )
            instrumentation.waitForIdleSync()
            assertFalse("Stale $route transfer must not open Talk/Chat", model.state.value.visible)
            assertEquals(
                "Stale $route transfer must retain saved draft",
                "Keep saved draft",
                model.state.value.draft
            )
            when (route) {
                "edit" -> WidgetUi.await("Action title")
                "back" -> WidgetUi.await("Other action")
                "inputs" -> {
                    WidgetUi.await("Expanded Original")
                    WidgetUi.click("Use prompt in Talk/Chat")
                    await {
                        model.state.value.visible &&
                            model.state.value.draft == "Expanded Original"
                    }
                    assertEquals(page.key, model.state.value.page?.key)
                }
            }
            assertEquals(beforeIdentities, identities())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync {
                navigation?.cancel()
                activity?.finish()
            }
            store.save(previous)
            vault.saveSources(emptyList())
            vault.savePages(emptyList())
            CatalogRepository.get(context).savedPages()
            QuickActionShortcuts.sync(context, previous)
        }
    }
}
