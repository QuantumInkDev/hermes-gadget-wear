package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.InboxFailure
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProblem
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.InboxWrite
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncryptedInboxTest {
    @Test fun encryptedRoundtripAndCasPreserveCommittedBytes() {
        val dir = Files.createTempDirectory("inbox-owned").resolve("inbox").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val file = EncryptedInboxFile(dir, { key })
            assertEquals(0, file.load().revision)
            assertTrue(
                file.commit(
                    InboxSnapshot(revision = 1, background = true),
                    0
                ) is InboxWrite.Committed
            )
            val bytes = java.io.File(dir, "inbox.v1").readBytes()
            assertTrue(file.load().background)
            assertTrue(file.commit(InboxSnapshot(revision = 1), 0) is InboxWrite.Stale)
            assertArrayEquals(bytes, java.io.File(dir, "inbox.v1").readBytes())
            java.io.File(dir, "initialized").delete()
            try {
                file.load()
                fail("missing marker must refuse")
            } catch (e: InboxFailure) {
                assertEquals(InboxProblem.MISSING, e.problem)
            }
            assertArrayEquals(bytes, java.io.File(dir, "inbox.v1").readBytes())
        } finally {
            requireNotNull(dir.parentFile).deleteRecursively()
        }
    }

    @Test fun aRecordIsBoundToItsAliasSoTheWatchStoreCannotReadThePhoneOne() {
        val dir = Files.createTempDirectory("inbox-alias").resolve("inbox").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            assertTrue(
                EncryptedInboxFile(dir, { key }).commit(InboxSnapshot(revision = 1), 0) is
                    InboxWrite.Committed
            )
            try {
                EncryptedInboxFile(dir, { key }, alias = "quantumink.relay.watch.inbox.v1").load()
                fail("another alias must not authenticate")
            } catch (e: InboxFailure) {
                assertEquals(InboxProblem.CORRUPT, e.problem)
            }
            assertEquals(1, EncryptedInboxFile(dir, { key }).load().revision)
        } finally {
            requireNotNull(dir.parentFile).deleteRecursively()
        }
    }

    @Test fun wrongKeyAndTamperingNeverResetOrCreateAKeyOverBytes() {
        val dir = Files.createTempDirectory("inbox-key").resolve("inbox").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val file = EncryptedInboxFile(dir, { key })
            assertTrue(file.commit(InboxSnapshot(revision = 1), 0) is InboxWrite.Committed)
            val record = java.io.File(dir, "inbox.v1")
            val before = record.readBytes()
            val missing =
                EncryptedInboxFile(dir, { create ->
                    assertFalse(create)
                    error("missing")
                })
            try {
                missing.load()
                fail("existing key required")
            } catch (e: InboxFailure) {
                assertEquals(InboxProblem.KEY, e.problem)
            }
            assertArrayEquals(before, record.readBytes())
            record.writeBytes(before.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            val broken = record.readBytes()
            assertTrue(file.commit(InboxSnapshot(revision = 2), 1) is InboxWrite.Failed)
            assertArrayEquals(broken, record.readBytes())
        } finally {
            requireNotNull(dir.parentFile).deleteRecursively()
        }
    }

    @Test fun promotionAndDirectorySyncFailuresRemainDistinctFromConfirmedCopies() {
        for (afterMove in listOf(false, true)) {
            val dir = Files.createTempDirectory("inbox-fsync").resolve("inbox").toFile()
            try {
                val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val plain = EncryptedInboxFile(dir, { key })
                assertSame(InboxWrite.Committed, plain.commit(InboxSnapshot(revision = 1), 0))
                val original = java.io.File(dir, "inbox.v1").readBytes()
                val faults = object : RelayFiles {
                    override fun write(file: java.io.File, bytes: ByteArray) =
                        RealRelayFiles.write(file, bytes)
                    override fun move(from: java.io.File, to: java.io.File) {
                        if (!afterMove) error("move refused")
                        RealRelayFiles.move(from, to)
                    }
                    override fun syncDirectory(directory: java.io.File) {
                        error("sync refused")
                    }
                }
                val failed = EncryptedInboxFile(dir, { key }, faults)
                    .commit(InboxSnapshot(revision = 2, background = true), 1) as InboxWrite.Failed
                assertEquals(afterMove, failed.uncertain)
                if (afterMove) {
                    assertEquals(2, plain.load().revision)
                    // Physically promoted, still not a confirmed caller result.
                    assertTrue(plain.load().background)
                } else {
                    assertArrayEquals(original, java.io.File(dir, "inbox.v1").readBytes())
                    val pending = java.io.File(dir, "inbox.pending").readBytes()
                    try {
                        plain.load()
                        fail("pending bytes must refuse, never reset")
                    } catch (
                        e: InboxFailure
                    ) {
                        assertEquals(InboxProblem.STORAGE, e.problem)
                    }
                    assertArrayEquals(pending, java.io.File(dir, "inbox.pending").readBytes())
                }
            } finally {
                requireNotNull(dir.parentFile).deleteRecursively()
            }
        }
    }
}
