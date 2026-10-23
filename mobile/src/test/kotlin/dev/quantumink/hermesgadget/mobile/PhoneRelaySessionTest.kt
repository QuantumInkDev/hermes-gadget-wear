package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production-used ViewModel adapter + real controller/file store. No Android constructor, Keystore or UI execution. */
class PhoneRelaySessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val a = EnrollmentIdentity("wss://a.example.invalid/gadget", "phone:enrollment-a")
    private val b = EnrollmentIdentity("wss://b.example.invalid/gadget", "phone:enrollment-b")
    private class Queue : RelayScheduler {
        val jobs = ArrayDeque<Runnable>()
        override fun post(task: Runnable) {
            jobs.add(task)
        }
        override fun postDelayed(delayMs: Long, task: Runnable) = Cancelable { }
        fun run() {
            while (jobs.isNotEmpty()) jobs.removeFirst().run()
        }
    }
    private class Immediate : RelayScheduler {
        override fun post(task: Runnable) = task.run()
        override fun postDelayed(delayMs: Long, task: Runnable) = Cancelable { }
    }
    private fun adapter(
        scheduler: RelayScheduler = Immediate(),
        seen: MutableList<RelayUiState> = ArrayList()
    ): Pair<PhoneRelaySession, EncryptedRelayStore> {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = EncryptedRelayStore(File(temp.root, "state"), { key })
        val adapter = PhoneRelaySession({ listener ->
            RelayController(store, scheduler, {
                5_000_000_000L
            }, SecureRequestIdEntropy(), listener = listener)
        }) { seen.add(it) }
        return adapter to store
    }

    @Test fun aEnableThenBOpenConnectNeverEnablesB() {
        val (adapter, store) = adapter()
        adapter.open(a.endpointUrl, 1)
        adapter.enable(true)
        adapter.hide()
        adapter.open(b.endpointUrl, 2)
        assertFalse(adapter.prepare(b.endpointUrl, 2, b))
        val records = (store.load() as? StoreLoad.Loaded)?.document?.section?.enrollments.orEmpty()
        assertTrue(records.none { it.endpointUrl == b.endpointUrl && it.enabled })
    }

    @Test fun aControllerSettingCannotMutateAWhileAnUnpreparedBIsDisplayed() {
        val (adapter, store) = adapter()
        adapter.open(a.endpointUrl, 1)
        adapter.prepare(a.endpointUrl, 1, a)
        adapter.enable(true)
        adapter.hide()
        adapter.open(b.endpointUrl, 2)
        adapter.enable(false)
        val aRecord = (store.load() as StoreLoad.Loaded).document.section.enrollments.single()
        assertTrue("B's control must never disable A", aRecord.enabled)
    }

    @Test fun oldPreparationAfterReplacementCannotEnableOrPublishA() {
        val seen = ArrayList<RelayUiState>()
        val (adapter, store) = adapter(seen = seen)
        adapter.open(a.endpointUrl, 1)
        adapter.enable(true)
        adapter.hide()
        adapter.open(b.endpointUrl, 2)
        seen.clear()
        assertFalse(adapter.prepare(a.endpointUrl, 1, a))
        assertTrue(seen.isEmpty())
        assertEquals(StoreLoad.Empty, store.load())
    }

    @Test fun queuedOldControllerUiIsSuppressedAfterReplacement() {
        val queue = Queue()
        val seen = ArrayList<RelayUiState>()
        val (adapter, _) = adapter(queue, seen)
        adapter.open(a.endpointUrl, 1)
        // Explicitly queue work on the actual controller, as an earlier identity read would.
        adapter.controller.prepare(a)
        adapter.controller.setEnabled(true)
        adapter.hide()
        adapter.open(b.endpointUrl, 2)
        queue.run()
        assertTrue(seen.isEmpty())
    }

    @Test fun currentPendingOptInUsesOnlyTheResolvedOwnEnrollment() {
        val (adapter, store) = adapter()
        adapter.open(a.endpointUrl, 1)
        adapter.enable(true)
        assertTrue(adapter.prepare(a.endpointUrl, 1, a))
        assertEquals(
            a.enrollmentLabel,
            (store.load() as StoreLoad.Loaded).document.section.enrollments.single().enrollmentKey
        )
        val other = EnrollmentIdentity(a.endpointUrl, "phone:other-enrollment")
        assertFalse(adapter.prepare(a.endpointUrl, 1, other))
    }

    @Test fun oldSettingsAndActionsAreRejectedAfterBHasBeenPrepared() {
        val (adapter, store) = adapter()
        adapter.open(a.endpointUrl, 1)
        adapter.prepare(a.endpointUrl, 1, a)
        adapter.enable(true)
        adapter.hide()
        adapter.open(b.endpointUrl, 2)
        adapter.prepare(b.endpointUrl, 2, b)
        adapter.enable(true, a.endpointUrl, 1)
        adapter.perform(a.endpointUrl, 1) { it.setEnabled(false) }
        val records = (store.load() as StoreLoad.Loaded).document.section.enrollments
        assertTrue(records.single { it.endpointUrl == a.endpointUrl }.enabled)
        assertTrue(records.none { it.endpointUrl == b.endpointUrl && it.enabled })
    }
}
