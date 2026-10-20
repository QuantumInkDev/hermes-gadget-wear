package dev.quantumink.hermesgadget.protocol

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationStagingTest {
    private val phrase = "Ångström ☾ 月光 moonlight".toCharArray()
    private fun fixture() = requireNotNull(
        javaClass.getResourceAsStream("/migration/android-sealed-api29.hgmigr")
    ).use {
        it.readBytes()
    }
    private fun <T> directory(block: (File) -> T): T {
        val root = Files.createTempDirectory("migration-stage-test").toFile()
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun stagedReaderMatchesNativeAndJcaWithPartialReadsAndCleansItsEncryptedScratch(): Unit =
        directory { root ->
            val envelope = fixture()
            val original = MigrationArchive.decrypt(envelope, phrase) as MigrationReadResult.Success
            original.use { expected ->
                val input = object : FilterInputStream(envelope.inputStream()) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        super.read(bytes, offset, minOf(length, 17))
                }
                val result = MigrationArchive.readStaged(
                    input,
                    phrase,
                    root
                ) as MigrationStagedReadResult.Success
                result.use {
                    assertEquals(expected.snapshot, it.snapshot)
                    expected.artwork.forEach { (hash, bytes) ->
                        val actual = it.artwork(hash)
                        assertArrayEquals(bytes, actual)
                        actual.fill(0)
                    }
                    val all = root.walkTopDown().filter {
                        it.isFile
                    }.flatMap { it.readBytes().asSequence() }.toList().toByteArray()
                    assertFalse(
                        all.toString(Charsets.ISO_8859_1).contains("interop.example.invalid")
                    )
                }
                assertTrue(root.listFiles().orEmpty().isEmpty())
                assertThrows(IllegalStateException::class.java) {
                    result.artwork(expected.snapshot.artwork.first().sha256)
                }
            }
        }

    @Test fun tamperWrongPhraseAndLengthFailuresDeleteScratch(): Unit = directory { root ->
        val original = fixture()
        val malformed = listOf(0, 8, 12, 16, 20, 24, 40, 52, original.lastIndex).map { at ->
            original.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
        } +
            listOf(
                original.copyOf(30),
                original.copyOf(original.size - 1),
                original + byteArrayOf(0)
            )
        malformed.forEach {
            assertEquals(
                MigrationStagedReadResult.Invalid,
                MigrationArchive.readStaged(it.inputStream(), phrase, root)
            )
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
        assertEquals(
            MigrationStagedReadResult.Invalid,
            MigrationArchive.readStaged(
                original.inputStream(),
                "wrong synthetic passphrase".toCharArray(),
                root
            )
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun stageRandomAccessCrossesBlocksAuthenticatesStorageAndClosesOnFailure(): Unit =
        directory { root ->
            val stage = MigrationPrivateStage.create(root)
            val plain = ByteArray(180_013) { (it * 37).toByte() }
            stage.append(plain, 0, 13)
            stage.append(plain, 13, plain.size - 13)
            stage.seal()
            assertArrayEquals(
                plain.copyOfRange(65_530, 160_010),
                stage.bytes(
                    65_530,
                    160_010 - 65_530
                )
            )
            val file = root.walkTopDown().single { it.isFile }
            val bytes = file.readBytes()
            assertFalse(bytes.copyOfRange(0, plain.size).contentEquals(plain))
            // Corrupt an uncached block. Authentication fails before returning its plaintext.
            bytes[4] = (bytes[4].toInt() xor 1).toByte()
            file.writeBytes(bytes)
            assertThrows(Exception::class.java) { stage.bytes(0, 50) }
            stage.close()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }

    @Test fun interruptedIoAndOrphanedProcessScratchAreCleanedWithoutDeletingActiveStages(): Unit =
        directory { root ->
            val orphan = File(root, "migration-stage-orphan").apply { mkdir() }
            File(orphan, "blocks").writeBytes(byteArrayOf(1, 2, 3))
            val active = MigrationPrivateStage.create(root)
            assertFalse(orphan.exists())
            val other = MigrationPrivateStage.create(root)
            active.append(byteArrayOf(4))
            active.seal()
            assertArrayEquals(byteArrayOf(4), active.bytes(0, 1))
            val input = object : FilterInputStream(fixture().inputStream()) {
                var total = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (total >= 52) throw IOException("synthetic provider failure")
                    return super.read(bytes, offset, length).also { total += it }
                }
            }
            assertEquals(
                MigrationStagedReadResult.Invalid,
                MigrationArchive.readStaged(input, phrase, root)
            )
            active.close()
            other.close()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
}
