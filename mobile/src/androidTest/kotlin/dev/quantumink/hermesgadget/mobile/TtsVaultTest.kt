package dev.quantumink.hermesgadget.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TtsVaultTest {
    @Test fun sixtyFourEndpointsAreIndependentAndOverflowDoesNotChangeAnyBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "speech-capacity-" + suffix).apply { mkdirs() }
        val alias = "hermesgadget.test.speech." + suffix
        val vault = TtsVault(context, directory, alias)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            // Create the key before unrelated files enter the separate capacity fixture.
            vault.save(
                "shared",
                SpeechSettings(SpeechMode.SHARED, "syntheticShared", "voiceShared")
            )
            File(directory, "f".repeat(64)).writeText("unrelated")
            File(directory, "e".repeat(64) + ".sealed.tmp").writeText("unrelated")
            File(directory, "d".repeat(64) + ".sealed").mkdirs()
            repeat(64) { index ->
                val id = index.toString(16).padStart(64, '0')
                vault.save(id, SpeechSettings(SpeechMode.PROFILE, "synthetic$index", "voice$index"))
            }
            assertEquals(64, vault.endpoints().size)
            repeat(64) { index ->
                assertEquals(
                    "synthetic$index",
                    vault.selected(index.toString(16).padStart(64, '0')).key
                )
            }
            val before = directory.listFiles().orEmpty().filter { it.isFile }
                .associate { it.name to it.readBytes().toList() }
            assertThrows(IllegalArgumentException::class.java) {
                vault.save("c".repeat(64), SpeechSettings(SpeechMode.PROFILE, "overflow", ""))
            }
            assertEquals(
                before,
                directory.listFiles().orEmpty().filter { it.isFile }
                    .associate { it.name to it.readBytes().toList() }
            )
            vault.save("0".repeat(64), SpeechSettings(SpeechMode.SERVER, "synthetic0", ""))
            assertEquals(SpeechMode.SERVER, vault.selected("0".repeat(64)).mode)
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }

    @Test fun corruptRecordsCannotBeOverwrittenAndMissingKeyPreservesAllBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "speech-corrupt-" + suffix)
        val alias = "hermesgadget.test.speech." + suffix
        val vault = TtsVault(context, directory, alias)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val id = "a".repeat(64)
        try {
            vault.save(id, SpeechSettings(SpeechMode.PROFILE, "synthetic", ""))
            val file = File(directory, "$id.sealed")
            file.writeBytes(
                file.readBytes().apply {
                    this[lastIndex] =
                        (this[lastIndex].toInt() xor 1).toByte()
                }
            )
            val corrupted = file.readBytes().toList()
            assertTrue(
                runCatching {
                    vault.save(id, SpeechSettings(SpeechMode.SERVER, "", ""))
                }.isFailure
            )
            assertEquals(corrupted, file.readBytes().toList())
            keys.deleteEntry(alias)
            assertTrue(runCatching { vault.read(id) }.isFailure)
            assertTrue(
                runCatching {
                    vault.save("b".repeat(64), SpeechSettings(SpeechMode.SERVER, "", ""))
                }.isFailure
            )
            assertEquals(corrupted, file.readBytes().toList())
            assertFalse(keys.containsAlias(alias))
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }

    @Test fun backupAndPendingBytesSurviveCorruptionAndConcurrentCapacityIsBounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "speech-backup-" + suffix)
        val alias = "hermesgadget.test.speech." + suffix
        val vault = TtsVault(context, directory, alias)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val id = "a".repeat(64)
        try {
            vault.save(id, SpeechSettings(SpeechMode.PROFILE, "synthetic", "voice"))
            val base = File(directory, "$id.sealed")
            val validBytes = base.readBytes()
            val backup = File(directory, "$id.sealed.bak")
            assertTrue(base.renameTo(backup))
            File(directory, "$id.sealed.new").writeBytes(byteArrayOf(7, 8, 9))
            assertEquals(listOf(id), vault.endpoints())
            assertEquals("synthetic", vault.read(id).key)
            assertFalse(base.exists())
            backup.writeBytes(byteArrayOf(1, 2, 3))
            val before = directory.listFiles().orEmpty().associate {
                it.name to
                    it.readBytes().toList()
            }
            assertTrue(runCatching { vault.read(id) }.isFailure)
            assertTrue(
                runCatching {
                    vault.save(id, SpeechSettings(SpeechMode.SERVER, "", ""))
                }.isFailure
            )
            assertEquals(
                before,
                directory.listFiles().orEmpty().associate {
                    it.name to
                        it.readBytes().toList()
                }
            )
            backup.writeBytes(validBytes)
            repeat(62) { index ->
                vault.save(
                    index.toString(16).padStart(64, '0'),
                    SpeechSettings(SpeechMode.SERVER, "", "")
                )
            }
            val gate = java.util.concurrent.CountDownLatch(1)
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val threads = listOf("b".repeat(64), "c".repeat(64)).map { endpoint ->
                Thread {
                    gate.await()
                    if (runCatching {
                            TtsVault(
                                context,
                                directory,
                                alias
                            ).save(endpoint, SpeechSettings(SpeechMode.SERVER, "", ""))
                        }.isSuccess
                    ) {
                        writes.incrementAndGet()
                    }
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach {
                it.join(4000)
                assertFalse(it.isAlive)
            }
            assertEquals(1, writes.get())
            assertEquals(64, vault.endpoints().size)
            assertEquals(validBytes.toList(), backup.readBytes().toList())
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }

    @Test fun encryptsSharedAndIndependentKeysDetectsTamperingAndNeverRegeneratesLostVault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "speech-test-" + suffix)
        val alias = "hermesgadget.test.speech." + suffix
        val vault = TtsVault(context, directory, alias)
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        val shared = UUID.randomUUID().toString()
        val own = UUID.randomUUID().toString()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            vault.save("shared", SpeechSettings(SpeechMode.SHARED, shared, "syntheticSharedVoice"))
            vault.save(first, SpeechSettings(SpeechMode.SHARED, "", "syntheticOverride"))
            vault.save(second, SpeechSettings(SpeechMode.PROFILE, own, ""))
            assertEquals(shared, vault.selected(first).key)
            assertEquals(own, vault.selected(second).key)
            assertEquals("syntheticOverride", vault.selected(first).voice)
            assertEquals("syntheticSharedVoice", vault.selected(second).voice)
            assertEquals("", vault.selected("c".repeat(64)).key)
            assertTrue(
                directory.listFiles().orEmpty().all {
                    !it.readBytes().toString(Charsets.ISO_8859_1).contains(shared) &&
                        !it.readBytes().toString(Charsets.ISO_8859_1).contains(own)
                }
            )
            val record = File(directory, second + ".sealed")
            record.writeBytes(
                record.readBytes().apply {
                    this[lastIndex] =
                        (this[lastIndex].toInt() xor 1).toByte()
                }
            )
            assertTrue(runCatching { vault.read(second) }.isFailure)
            assertTrue(
                runCatching {
                    vault.save(second, SpeechSettings(SpeechMode.PROFILE, own, ""))
                }.isFailure
            )
            keys.deleteEntry(alias)
            assertThrows(IllegalStateException::class.java) { vault.read(first) }
            assertFalse(keys.containsAlias(alias))
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
