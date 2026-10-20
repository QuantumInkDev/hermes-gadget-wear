package dev.quantumink.hermesgadget.mobile

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused native lifecycle/publication UI checks; one method per instrumentation process. */
class SetupTransferFixPresentationTest {
    @Test fun delayedPhotoAndDocumentReadsCannotReturnAfterScreenDisposal() {
        val target = TransferFixture()
        val stores = target.stores()
        val before = target.state(stores)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mounted = mutableStateOf(true)
        val phase = mutableStateOf(0)
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        val completed = AtomicInteger()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    dispatchResult(
                        requestCode,
                        Uri.parse("content://synthetic.invalid/selected-picture")
                    )
                }
            }
        }
        try {
            NativePhoneFixture().use { native ->
                native.show(destination = "settings", scroll = true) {
                    key(phase.value) {
                        if (mounted.value) {
                            CompositionLocalProvider(
                                LocalActivityResultRegistryOwner provides owner
                            ) {
                                SetupTransferPage(native.context, {}, { stores }, pictureReader = {
                                    entered.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                    completed.incrementAndGet()
                                    byteArrayOf(1, 2, 3)
                                })
                            }
                        } else {
                            Text("Other screen")
                        }
                    }
                }
                for ((index, button) in listOf(
                    "Read a code from a photo",
                    "Read a code from a file"
                ).withIndex()) {
                    if (index > 0) {
                        entered = CountDownLatch(1)
                        release = CountDownLatch(1)
                        instrumentation.runOnMainSync {
                            phase.value++
                            mounted.value = true
                        }
                    }
                    native.scrollTo(button)
                    native.click(button)
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    native.await { native.find("Reading the picture…") != null }
                    instrumentation.runOnMainSync { mounted.value = false }
                    native.await { native.find("Other screen") != null }
                    release.countDown()
                    native.await { completed.get() == index + 1 }
                    instrumentation.waitForIdleSync()
                    assertTrue(native.find("Other screen") != null)
                    assertFalse(native.find("No setup code found") != null)
                    assertEquals(before, target.state(stores))
                }
                native.capture("fix1-delayed-provider-disposed")
            }
        } finally {
            release.countDown()
            target.close()
        }
    }

    @Test fun failedSetupPublicationShowsRetryAndRetryKeepsCommittedStores() {
        val source = TransferFixture()
        val target = TransferFixture()
        try {
            val origin = source.seed()
            val stores = target.stores()
            target.setupAction =
                { throw java.util.concurrent.TimeoutException("synthetic timeout") }
            val envelope = SetupExport.build(origin).use {
                MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
            }
            val preview = requireNotNull(openPreview(stores, envelope, PASSPHRASE))
            NativePhoneFixture().use { native ->
                native.show(destination = "settings", scroll = true) {
                    SetupTransferPage(native.context, {
                    }, { stores }, initial = TransferScreen.Preview(preview))
                }
                native.scrollTo("Import selected items")
                native.click("Import selected items")
                native.await { native.find("Configuration imported") != null }
                native.scrollTo("Retry sync")
                assertTrue(native.find("Retry sync") != null)
                native.capture("fix1-publication-retry")
                val committed = target.state(stores)
                target.setupAction = {}
                native.click("Retry sync")
                native.await { native.find("Saved metadata was published.") != null }
                assertEquals(committed, target.state(stores))
                native.capture("fix1-publication-retried")
            }
        } finally {
            source.close()
            target.close()
            SetupImport.pendingPublication = null
        }
    }
}
