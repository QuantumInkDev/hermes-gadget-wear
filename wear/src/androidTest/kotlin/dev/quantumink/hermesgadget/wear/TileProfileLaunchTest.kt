package dev.quantumink.hermesgadget.wear

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TileProfileLaunchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profile =
        EndpointProfile(Endpoint.parse("ws://127.0.0.1:9/gadget", true, true), "Fixture", "")
    private val key = ProfilePages.merge(listOf(profile), emptyList()).single().key
    private fun intent(value: String) =
        Intent(context, MainActivity::class.java).putExtra(TileProfileLaunch.EXTRA_PAGE_KEY, value)

    @Test fun waitsForBindingRosterAndAllExistingSelectionLocksThenSelectsExactKey() {
        val launch = TileProfileLaunch()
        var selected: String? = null
        launch.receive(intent(key))
        launch.reconcile(WatchState(loading = true)) { selected = it }
        assertNull(selected)
        val base = WatchState(loading = false, profiles = listOf(profile))
        val locks = listOf(
            base.copy(saving = true),
            base.copy(selecting = true),
            base.copy(connection = ConnectionState(conversation = ConversationState(busy = true))),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(mode = ConversationMode.LISTENING)
                )
            ),
            base.copy(
                connection = ConnectionState(
                    conversation = ConversationState(prompt = Prompt("a", "a", "a", 0, null))
                )
            )
        )
        for (watch in locks) {
            launch.reconcile(watch) { selected = it }
            assertNull(selected)
            assertEquals(key, launch.pendingKey)
            assertTrue(launch.notice.contains("Fixture"))
        }
        launch.reconcile(base) { selected = it }
        assertEquals(key, selected)
        assertNull(launch.pendingKey)
        assertEquals("", launch.notice)
        launch.receive(intent("fixture:removed"))
        launch.reconcile(base) { fail("Unknown stable key must never redirect") }
        assertTrue(launch.unavailable)
    }

    @Test fun invalidExtraIsRejectedAndAnOrdinaryWarmIntentCannotReplayACompletedRequest() {
        val launch = TileProfileLaunch()
        launch.receive(intent("wss://localhost?token=secret"))
        assertTrue(launch.unavailable)
        assertNull(launch.pendingKey)
        launch.receive(intent(key))
        launch.reconcile(WatchState(loading = false, profiles = listOf(profile))) {
            assertEquals(key, it)
        }
        launch.receive(Intent(context, MainActivity::class.java))
        launch.reconcile(WatchState(loading = false, profiles = listOf(profile))) {
            fail("Replayed")
        }
    }
}
