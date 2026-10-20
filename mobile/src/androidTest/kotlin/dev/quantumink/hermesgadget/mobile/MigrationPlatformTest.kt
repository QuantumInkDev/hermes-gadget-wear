package dev.quantumink.hermesgadget.mobile

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.MigrationReadResult
import dev.quantumink.hermesgadget.protocol.MigrationSnapshot
import dev.quantumink.hermesgadget.protocol.MigrationTheme
import dev.quantumink.hermesgadget.protocol.MigrationWidget
import dev.quantumink.hermesgadget.protocol.MigrationWidgetPreset
import java.io.File
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure format behavior that depends on the Android runtime (libcore, Conscrypt, ICU). */
class MigrationPlatformTest {
    @Test fun boundedWriterInteroperatesWithAndroidAndOwnedReaderAuthenticates() {
        val passphrase = "synthetic passphrase!".toCharArray()
        val snapshot = MigrationSnapshot(appearance = MigrationTheme.MOONLIGHT)
        val output = java.io.ByteArrayOutputStream()
        MigrationArchive.writeEncrypted(snapshot, passphrase, emptyMap(), output)
        val sealed = output.toByteArray()
        (MigrationArchive.decrypt(sealed, passphrase) as MigrationReadResult.Success).use {
            assertEquals(snapshot, it.snapshot)
        }
        val owned = sealed.copyOf()
        (MigrationArchive.decryptOwned(owned, passphrase) as MigrationReadResult.Success).use {
            assertEquals(snapshot, it.snapshot)
        }
        assertTrue(owned.all { it == 0.toByte() })
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertSame(MigrationReadResult.Invalid, MigrationArchive.decryptOwned(sealed, passphrase))
        assertTrue(sealed.all { it == 0.toByte() })
    }

    @Test fun stagedReaderAcceptsNativeAndJvmSealedFilesAndRejectsTamper() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "staged-interop-${System.nanoTime()}"
            )
        try {
            val fixtures = jvmFixtures + mapOf(
                "android-sealed-api29.hgmigr" to "Ångström ☾ 月光 moonlight",
                "android-sealed-api37.hgmigr" to "Ångström ☾ 月光 moonlight"
            )
            for ((name, text) in fixtures) {
                val phrase = text.toCharArray()
                val bytes = asset(name)
                val expected = MigrationArchive.decrypt(
                    bytes,
                    phrase
                ) as MigrationReadResult.Success
                expected.use { old ->
                    val result = MigrationArchive.readStaged(
                        bytes.inputStream(),
                        phrase,
                        root
                    ) as dev.quantumink.hermesgadget.protocol.MigrationStagedReadResult.Success
                    result.use { opened ->
                        assertEquals(old.snapshot, opened.snapshot)
                        old.artwork.forEach { (hash, art) ->
                            val actual = opened.artwork(hash)
                            org.junit.Assert.assertArrayEquals(art, actual)
                            actual.fill(0)
                        }
                    }
                }
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                assertEquals(
                    dev.quantumink.hermesgadget.protocol.MigrationStagedReadResult.Invalid,
                    MigrationArchive.readStaged(bytes.inputStream(), phrase, root)
                )
                assertTrue(root.listFiles().orEmpty().isEmpty())
                phrase.fill(' ')
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private class ExposingList<T>(private val items: Array<Any?>) : java.util.AbstractList<T>() {
        override val size: Int get() = items.size

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): T = items[index] as T
        override fun toArray(): Array<Any?> = items
    }

    @Test fun widgetSlotsDoNotAliasAnArrayExposedByTheCallerCollection() {
        val backing = arrayOf<Any?>("box:one", null, null, null)
        val widget = MigrationWidget(
            "00000000-0000-4000-8000-000000000001",
            MigrationWidgetPreset.FOUR,
            ExposingList(backing)
        )
        backing[0] = "box:two"
        backing[1] = "box:two"
        assertEquals(listOf("box:one", null, null, null), widget.slots)
    }

    @Test fun snapshotListsDoNotAliasAnArrayExposedByTheCallerCollection() {
        val backing = arrayOf<Any?>("manual")
        val snapshot = MigrationSnapshot(sourceOrder = ExposingList(backing))
        backing[0] = "elsewhere"
        assertEquals(listOf("manual"), snapshot.sourceOrder)
        assertEquals(snapshot, MigrationSnapshot())
    }

    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).use { it.readBytes() }

    /** Sealed by JDK 17 (SunJCE); each passphrase here is the other Unicode normalization form. */
    private val jvmFixtures = mapOf(
        "jvm-sealed-v1.hgmigr" to
            "Ångström ☾ 月光 moonlight",
        "jvm-sealed-latin-nfc.hgmigr" to "pässwörd 月光 moon",
        "jvm-sealed-hangul-nfd.hgmigr" to
            "한국어 비밀번호 문구 key"
    )

    private fun checkInterop(snapshot: MigrationSnapshot, artwork: Map<String, ByteArray>) {
        assertEquals("wss://interop.example.invalid/gadget", snapshot.endpoints.single().url)
        assertEquals("https://catalog.example.invalid/hermes", snapshot.catalogs.single().url)
        assertEquals(MigrationTheme.MOONLIGHT, snapshot.appearance)
        assertEquals(listOf("box", "manual"), snapshot.sourceOrder)
        assertEquals("box:bot", snapshot.widgets.single().slots[0])
        assertEquals(snapshot.artwork.map { it.sha256 }.toSet(), artwork.keys)
        artwork.forEach { (hash, bytes) -> assertEquals(hash, sha256Hex(bytes)) }
    }

    @Test fun jvmSealedArchivesOpenOnAndroidWithTheOtherNormalizationForm() {
        // Guard the literals: an editor that silently normalizes source text would void this test.
        jvmFixtures.forEach { (name, passphrase) ->
            val form = if (name.endsWith("nfd.hgmigr")) Normalizer.Form.NFC else Normalizer.Form.NFD
            assertEquals(passphrase, Normalizer.normalize(passphrase, form))
            val opposite = if (form ==
                Normalizer.Form.NFC
            ) {
                Normalizer.Form.NFD
            } else {
                Normalizer.Form.NFC
            }
            assertNotEquals(passphrase, Normalizer.normalize(passphrase, opposite))
        }
        jvmFixtures.forEach { (name, passphrase) ->
            val started = SystemClock.elapsedRealtime()
            val result = MigrationArchive.decrypt(asset(name), passphrase.toCharArray())
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i("MigrationPlatformTest", "api=${Build.VERSION.SDK_INT} $name decrypt ms=$elapsed")
            (result as MigrationReadResult.Success).use { checkInterop(it.snapshot, it.artwork) }
            assertSame(
                MigrationReadResult.Invalid,
                MigrationArchive.decrypt(asset(name), (passphrase + "!").toCharArray())
            )
        }
    }

    @Test fun icuNormalizationMatchesTheJvmFormsUsedForTheVectors() {
        assertEquals(
            "Ångström",
            Normalizer.normalize("Ångström", Normalizer.Form.NFC)
        )
        assertEquals(
            "한국어",
            Normalizer.normalize("한국어", Normalizer.Form.NFC)
        )
        // NFC keeps compatibility characters; only NFKC would fold U+FB01 to "fi".
        assertEquals("ﬁ", Normalizer.normalize("ﬁ", Normalizer.Form.NFC))
    }

    @Test fun platformPbkdf2AndGcmMatchTheIndependentVector() {
        val salt = ByteArray(16) { it.toByte() }
        val started = SystemClock.elapsedRealtime()
        val spec = PBEKeySpec("synthetic passphrase!".toCharArray(), salt, 600000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(
            "MigrationPlatformTest",
            "api=${Build.VERSION.SDK_INT} pbkdf2 600k ms=$elapsed provider=" +
                factory.provider.name +
                " mac=" + Mac.getInstance("HmacSHA256").provider.name +
                " gcm=" + Cipher.getInstance("AES/GCM/NoPadding").provider.name
        )
        // Independent Python hashlib.pbkdf2_hmac SHA256 vector: ASCII synthetic passphrase!,
        // salt bytes(range(16)), 600000 iterations, 32 output bytes.
        assertEquals(
            "b6ef34f61bbe5d5d0014644fad33374e5fad47880bc948417dda225bc3216938",
            key.joinToString("") { "%02x".format(it) }
        )
    }

    /** Conscrypt GCM + libcore ZipOutputStream output, written for the JVM interop check. */
    @Test fun androidSealedArchiveRoundTripsAndIsKeptForTheJvmCheck() {
        val (name, passphrase) = jvmFixtures.entries.first()
        val source = MigrationArchive.decrypt(asset(name), passphrase.toCharArray())
            as MigrationReadResult.Success
        val sealed = source.use {
            MigrationArchive.encrypt(it.snapshot, passphrase.toCharArray(), it.artwork)
        }
        assertNotEquals(asset(name).toList(), sealed.toList())
        (
            MigrationArchive.decrypt(
                sealed.copyOf(),
                passphrase.toCharArray()
            ) as MigrationReadResult.Success
            )
            .use { checkInterop(it.snapshot, it.artwork) }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(target.filesDir, "interop").apply { mkdirs() }
        File(output, "android-sealed-api${Build.VERSION.SDK_INT}.hgmigr").writeBytes(sealed)
        assertTrue(sealed.size > MigrationArchive.KDF_ITERATIONS / 1000)
    }
}
