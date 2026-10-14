package dev.quantumink.hermesgadget.mobile.widgets

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.mobile.PhoneProfileNavigation
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProfileNavigationTest {
    @Test fun cancelledLookupCannotReplaceTheNextProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<String?>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val navigation = PhoneProfileNavigation(scope, { key, _ ->
            if (key == "box:first") {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            val page =
                CatalogPage(
                    "box",
                    "Box",
                    CatalogProfile(
                        key.substringAfter(':'),
                        "Bot",
                        "wss://bot.example.invalid/gadget",
                        null
                    ),
                    CatalogStatus.AVAILABLE,
                    1
                )
            page to EndpointProfile(Endpoint.parse("wss://bot.example.invalid/gadget"), "Bot", "")
        }, { true })
        try {
            instrumentation.runOnMainSync {
                navigation.request("box:first", null, { true }) {
                    results +=
                        it?.first?.key
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                navigation.request("box:second", null, { true }) {
                    results +=
                        it?.first?.key
                    completed.countDown()
                }
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            release.countDown()
            instrumentation.waitForIdleSync()
            assertEquals(listOf("box:second"), results)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
