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
            vault.save(second, SpeechSettings(SpeechMode.PROFILE, own, ""))
            keys.deleteEntry(alias)
            assertThrows(IllegalStateException::class.java) { vault.read(first) }
            assertFalse(keys.containsAlias(alias))
        } finally {
            directory.deleteRecursively()
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
        }
    }
}
