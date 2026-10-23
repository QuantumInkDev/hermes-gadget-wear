package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentIdentity
import dev.quantumink.hermesgadget.protocol.relay.session.RelayController
import dev.quantumink.hermesgadget.protocol.relay.session.RelayPhase
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayUiState
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.SecureRequestIdEntropy
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The production controller over the production FILE store (software key): enable survives a restart; unreadable bytes stay. */
class RelayControllerOnFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val secret: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val identity = EnrollmentIdentity("wss://relay.example.invalid/gadget", "phone:hg-0011223344556677")

    /** Runs every task at once on the caller: the state machine is under test, not the thread. */
    private class Immediate : RelayScheduler {
        override fun post(task: Runnable) = task.run()
        override fun postDelayed(delayMs: Long, task: Runnable): Cancelable = Cancelable { }
    }

    private fun controller(
        directory: File,
        key: (Boolean) -> SecretKey = { secret },
        states: MutableList<RelayUiState> = ArrayList()
    ) = RelayController(
        EncryptedRelayStore(directory, key),
        Immediate(),
        { 5_000_000_000L },
        SecureRequestIdEntropy()
    ) { states.add(it) }

    @Test fun enablingIsStoredAndSurvivesARestartWithoutAnyConnection() {
        val directory = File(temp.root, "state")
        val first = controller(directory)
        first.prepare(identity)
        assertFalse(first.currentState.enabled)
        first.setEnabled(true)
        assertTrue(first.currentState.enabled)
        assertTrue(File(directory, "state.v1").isFile)
        // A new process: a new controller and a new store object over the same directory.
        val second = controller(directory)
        second.prepare(identity)
        assertTrue(second.currentState.enabled)
        assertEquals(RelayPhase.CONNECTING, second.currentState.phase)
        second.setEnabled(false)
        assertFalse(controller(directory).also { it.prepare(identity) }.currentState.enabled)
    }

    @Test fun unreadableBytesStayUntouchedAndBlockEnabling() {
        val directory = File(temp.root, "state")
        controller(directory).also {
            it.prepare(identity)
            it.setEnabled(true)
        }
        val record = File(directory, "state.v1")
        val damaged = record.readBytes().also { it[40] = (it[40].toInt() xor 1).toByte() }
        record.writeBytes(damaged)
        val states = ArrayList<RelayUiState>()
        val blocked = controller(directory, states = states)
        blocked.prepare(identity)
        assertEquals(RelayPhase.STORAGE_PROBLEM, blocked.currentState.phase)
        assertEquals(StoreProblem.CORRUPT, blocked.currentState.storage)
        blocked.setEnabled(false)
        blocked.setEnabled(true)
        assertArrayEquals(damaged, record.readBytes())
        // A missing key is a different, equally explicit and equally harmless problem.
        record.writeBytes(damaged.also { it[40] = (it[40].toInt() xor 1).toByte() })
        val keyless = controller(directory, key = { error("no key") })
        keyless.prepare(identity)
        assertEquals(StoreProblem.KEY_UNAVAILABLE, keyless.currentState.storage)
        assertEquals(false, keyless.currentState.enabled)
    }

    @Test fun aPlainHttpEnrollmentCannotEnableRelay() {
        val directory = File(temp.root, "state")
        val c = controller(directory)
        c.prepare(EnrollmentIdentity("ws://192.168.1.5/gadget", "phone:hg-0011223344556677"))
        c.setEnabled(true)
        assertFalse(c.currentState.enabled)
        assertFalse(File(directory, "state.v1").exists())
    }
}
