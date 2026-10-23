package dev.quantumink.hermesgadget.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.relay.session.ControllerSection
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.voice.VoiceCaptureLedger
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NATIVE gate source for the Relay state store (NOT executed in the Task3 stage): the real AndroidKeyStore key, the real
 * AtomicFile-class replace on a device file system, a restart, and a deleted key that keeps the bytes. JVM tests cover the
 * pure seams; power-loss durability is a later gate (V7).
 */
class EncryptedRelayStoreNativeTest {
    @Test fun keystoreBackedCommitSurvivesARestartAndADeletedKeyKeepsTheBytesUnreadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "relay-state-" + UUID.randomUUID())
        val alias = "test.relay.state." + UUID.randomUUID()
        val snapshot = (
            RelayStateSnapshot.of(
                TurnLedger.empty(),
                emptyList(),
                emptyList(),
                VoiceCaptureLedger.empty()
            )
                as SnapshotBuild.Built
            ).snapshot
        val document = RelayDocument(ControllerSection.EMPTY, snapshot)
        try {
            val store = EncryptedRelayStore.android(context, directory, alias)
            assertEquals(StoreLoad.Empty, store.load())
            assertTrue(store.commit(document, 0) is StoreCommit.Committed)
            val restarted = EncryptedRelayStore.android(context, directory, alias)
            assertTrue(restarted.load() is StoreLoad.Loaded)
            val bytes = File(directory, "state.v1").readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
            val unreadable = EncryptedRelayStore.android(
                context,
                directory,
                alias
            ).load() as StoreLoad.Unreadable
            assertEquals(StoreProblem.KEY_UNAVAILABLE, unreadable.problem)
            assertEquals(bytes.toList(), File(directory, "state.v1").readBytes().toList())
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
