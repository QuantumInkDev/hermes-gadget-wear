package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.ProfileId
import dev.quantumink.hermesgadget.protocol.relay.session.ControllerSection
import dev.quantumink.hermesgadget.protocol.relay.session.EncodeRefusal
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.StoreBounds
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import dev.quantumink.hermesgadget.protocol.relay.state.DeliverySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.ScopeRecordSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.SeenKeySnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.StateLimits
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.voice.CaptureLedgerSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The file, cipher and commit contract of the Relay state store, on the JVM with a software AES key. It proves the pure
 * seams (format, AAD, authenticate-before-parse, bounds, atomic replace, explicit results, bytes kept). It does NOT prove
 * AndroidKeyStore behaviour or native power-loss durability: those stay with the later native gate (V7).
 */
class EncryptedRelayStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val secret: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val profile = ProfileId.of("9e8af651-47eb-411f-b28c-ed292bbfd305")!!

    private fun store(
        directory: File = File(temp.root, "state"),
        key: (Boolean) -> SecretKey = { secret },
        alias: String = EncryptedRelayStore.ALIAS,
        files: RelayFiles = RealRelayFiles
    ) = EncryptedRelayStore(directory, key, alias, files)

    private fun document(marker: String = "one", seen: Int = 0): RelayDocument {
        val record = EnrollmentRecord.fresh("wss://relay.example.invalid/$marker", "phone:hg-0011223344556677").copy(
            enabled = true,
            pinnedProfileId = profile,
            selectedConversationId = ConversationId.of("3ff4ef74-af70-4efc-a3d4-e797b98a4dea")
        )
        val scope = ScopeRecordSnapshot(record.endpointKey, profile.value, record.enrollmentKey, 0)
        val deliveries = if (seen == 0) {
            emptyList()
        } else {
            listOf(
                DeliverySnapshot(
                    scope,
                    null,
                    List(seen) {
                        SeenKeySnapshot("canonical", "3ff4ef74-af70-4efc-a3d4-e797b98a4dea", "00000000-0000-4000-a000-%012d".format(it), 1)
                    },
                    emptyList(),
                    emptyList()
                )
            )
        }
        return RelayDocument(
            ControllerSection(listOf(record)),
            RelayStateSnapshot(2, TurnLedgerSnapshot(emptyList(), emptyList()), emptyList(), deliveries, CaptureLedgerSnapshot.EMPTY)
        )
    }

    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private val record get() = File(temp.root, "state/state.v1")

    @Test fun nothingStoredIsEmptyAndLoadingCreatesNothing() {
        val store = store()
        assertEquals(StoreLoad.Empty, store.load())
        assertFalse(File(temp.root, "state").exists())
        assertEquals(0L, store.currentSequence())
    }

    @Test fun aCommitRoundTripsAndAdvancesTheSequenceWithARandomIvEachTime() {
        val store = store()
        val first = store.commit(document(), 0) as StoreCommit.Committed
        assertEquals(1L, first.sequence)
        val ivFirst = record.readBytes().copyOfRange(2, 14)
        val loaded = store.load() as StoreLoad.Loaded
        assertEquals(1L, loaded.sequence)
        assertEquals(document().section, loaded.document.section)
        val second = store.commit(document("two"), 1) as StoreCommit.Committed
        assertEquals(2L, second.sequence)
        assertNotEquals(ivFirst.toList(), record.readBytes().copyOfRange(2, 14).toList())
        assertEquals(2L, store.currentSequence())
        // No staging file is left behind and the marker records that the store was initialised.
        assertEquals(setOf("state.v1", "initialized"), File(temp.root, "state").list()!!.toSet())
    }

    @Test fun aLateWriterWithAnOldBaseIsStaleAndPublishesNothing() {
        val a = store()
        val b = store()
        assertTrue(a.commit(document("a"), 0) is StoreCommit.Committed)
        val before = digest(record)
        assertEquals(StoreCommit.Stale, b.commit(document("b"), 0))
        assertEquals(before, digest(record))
        assertTrue(b.commit(document("b"), 1) is StoreCommit.Committed)
    }

    @Test fun theFormatIsAuthenticatedBeforeItIsParsedAndBoundToItsAliasAndSequence() {
        val store = store()
        store.commit(document(), 0)
        val bytes = record.readBytes()
        // A flipped ciphertext byte, header sequence or tag byte fails authentication: CORRUPT, bytes untouched.
        for (index in listOf(30, bytes.size - 1, 14 + 3)) {
            val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            record.writeBytes(tampered)
            assertEquals(StoreProblem.CORRUPT, (store.load() as StoreLoad.Unreadable).problem)
            assertArrayEquals(tampered, record.readBytes())
        }
        record.writeBytes(bytes)
        assertTrue(store.load() is StoreLoad.Loaded)
        // The same bytes under another alias do not authenticate.
        val other = store(alias = "quantumink.relay.phone.identity.v1")
        assertEquals(StoreProblem.CORRUPT, (other.load() as StoreLoad.Unreadable).problem)
    }

    @Test fun unsupportedCorruptTruncatedAndOversizeRecordsAreKeptAndNamed() {
        val store = store()
        store.commit(document(), 0)
        val bytes = record.readBytes()
        record.writeBytes(bytes.copyOf().also { it[0] = 2 })
        assertEquals(StoreProblem.UNSUPPORTED_VERSION, (store.load() as StoreLoad.Unreadable).problem)
        record.writeBytes(bytes.copyOf(10))
        assertEquals(StoreProblem.CORRUPT, (store.load() as StoreLoad.Unreadable).problem)
        record.writeBytes(ByteArray(StoreBounds.MAX_FILE_BYTES + 1))
        assertEquals(StoreProblem.TOO_LARGE, (store.load() as StoreLoad.Unreadable).problem)
        // A commit over any of these is refused too: nothing is rewritten.
        val size = record.length()
        assertTrue(store.commit(document("x"), 1) is StoreCommit.Failed)
        assertEquals(size, record.length())
    }

    @Test fun aMissingKeyKeepsTheBytesAndNeverCreatesAKeyOverThem() {
        val store = store()
        store.commit(document(), 0)
        val before = digest(record)
        val creates = ArrayList<Boolean>()
        val keyless = store(key = { create ->
            creates.add(create)
            error("no key")
        })
        assertEquals(StoreProblem.KEY_UNAVAILABLE, (keyless.load() as StoreLoad.Unreadable).problem)
        val failed = keyless.commit(document("y"), 1) as StoreCommit.Failed
        assertEquals(StoreProblem.KEY_UNAVAILABLE, failed.problem)
        assertFalse(failed.uncertain)
        assertEquals(before, digest(record))
        // The key may be asked to CREATE only for a store that never held a byte; here it never was.
        assertTrue(creates.none { it })
        // Another key cannot read it either (and cannot replace it).
        val wrong = store(key = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() })
        assertEquals(StoreProblem.CORRUPT, (wrong.load() as StoreLoad.Unreadable).problem)
        assertEquals(before, digest(record))
    }

    @Test fun aFirstCommitMayCreateTheKeyButOnlyOnAStoreThatNeverHeldAByte() {
        val creates = ArrayList<Boolean>()
        val first = store(key = { create ->
            creates.add(create)
            secret
        })
        assertTrue(first.commit(document(), 0) is StoreCommit.Committed)
        assertTrue(first.commit(document("two"), 1) is StoreCommit.Committed)
        assertEquals(listOf(true, false), creates)
    }

    @Test fun anInitialisedStoreWhoseRecordVanishedIsNotAnEmptyStore() {
        val store = store()
        store.commit(document(), 0)
        assertTrue(record.delete())
        assertEquals(StoreProblem.RECORD_MISSING, (store.load() as StoreLoad.Unreadable).problem)
        val failed = store.commit(document("z"), 0) as StoreCommit.Failed
        assertEquals(StoreProblem.RECORD_MISSING, failed.problem)
        assertFalse(record.exists())
        // Unknown files with no record are not "empty" either.
        val stray = File(temp.root, "other")
        stray.mkdirs()
        File(stray, "unknown.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(StoreProblem.RECORD_MISSING, (store(stray).load() as StoreLoad.Unreadable).problem)
    }

    @Test fun aSymlinkedDirectoryIsStorageUnavailable() {
        val real = File(temp.root, "real").also { it.mkdirs() }
        val link = File(temp.root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (_: Exception) {
            return // the platform cannot create one
        }
        assertEquals(StoreProblem.STORAGE_UNAVAILABLE, (store(link).load() as StoreLoad.Unreadable).problem)
    }

    @Test fun anOverCapOrTooManyDocumentIsRefusedBeforeAnythingIsWritten() {
        val store = store()
        val seen = List(StateLimits.MAX_SEEN_KEYS) {
            SeenKeySnapshot("opaque", "c".repeat(128), "e".repeat(200) + "%056d".format(it), 1)
        }
        val deliveries = (0 until StateLimits.MAX_ENROLLMENTS).map {
            DeliverySnapshot(ScopeRecordSnapshot("k$it", profile.value, "phone:hg-$it", 0), null, seen, emptyList(), emptyList())
        }
        val big = RelayDocument(
            ControllerSection.EMPTY,
            RelayStateSnapshot(2, TurnLedgerSnapshot(emptyList(), emptyList()), emptyList(), deliveries, CaptureLedgerSnapshot.EMPTY)
        )
        val refused = store.commit(big, 0) as StoreCommit.Refused
        assertEquals(EncodeRefusal.STORAGE_CAP, refused.reason)
        assertFalse(File(temp.root, "state").exists())
    }

    @Test fun aFailureBeforeTheReplaceLeavesTheOldRecordUnchangedAndIsCertain() {
        val plain = store()
        plain.commit(document(), 0)
        val before = digest(record)
        val failing = store(
            files = object : RelayFiles {
                override fun move(from: File, to: File) = throw IOException("move refused")
                override fun syncDirectory(directory: File) = RealRelayFiles.syncDirectory(directory)
                override fun write(file: File, bytes: ByteArray) = RealRelayFiles.write(file, bytes)
            }
        )
        val failed = failing.commit(document("n"), 1) as StoreCommit.Failed
        assertFalse(failed.uncertain)
        assertEquals(before, digest(record))
        assertFalse(File(temp.root, "state/state.pending").exists())
        // The write itself failing is certain too.
        val unwritable = store(
            files = object : RelayFiles {
                override fun move(from: File, to: File) = RealRelayFiles.move(from, to)
                override fun syncDirectory(directory: File) = RealRelayFiles.syncDirectory(directory)
                override fun write(file: File, bytes: ByteArray) = throw IOException("disk full")
            }
        )
        val full = unwritable.commit(document("n"), 1) as StoreCommit.Failed
        assertFalse(full.uncertain)
        assertEquals(before, digest(record))
    }

    @Test fun aFailureAfterTheReplaceIsUncertainNeverSuccessAndTheNewRecordIsInPlace() {
        val plain = store()
        plain.commit(document(), 0)
        val failing = store(
            files = object : RelayFiles {
                override fun move(from: File, to: File) = RealRelayFiles.move(from, to)
                override fun syncDirectory(directory: File) = throw IOException("sync refused")
                override fun write(file: File, bytes: ByteArray) = RealRelayFiles.write(file, bytes)
            }
        )
        val failed = failing.commit(document("n"), 1) as StoreCommit.Failed
        assertTrue(failed.uncertain)
        assertEquals(2L, store().currentSequence())
        assertEquals("n", (store().load() as StoreLoad.Loaded).document.section.enrollments.single().endpointUrl.substringAfterLast('/'))
    }

    @Test fun aLargeButLegalDocumentCommitsWithinTheCap() {
        val store = store()
        val result = store.commit(document(seen = StateLimits.MAX_SEEN_KEYS), 0)
        assertTrue(result.toString(), result is StoreCommit.Committed)
        assertTrue(record.length() > 300_000)
        assertTrue(record.length() <= StoreBounds.MAX_FILE_BYTES)
        val loaded = store.load() as StoreLoad.Loaded
        assertEquals(StateLimits.MAX_SEEN_KEYS, loaded.document.snapshot.deliveries.single().seen.size)
    }
}
