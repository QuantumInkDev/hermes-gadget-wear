package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneInboxIdentityTest {
    @Test fun missingKnownMarkerAndAtomicRecoveryBytesAreRefusedWithoutMutation() {
        val dir = Files.createTempDirectory("inbox-identity").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val id = "ab".repeat(32)
        fun write(name: String, bytes: ByteArray) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD((name + "|phone-v1").toByteArray())
            File(dir, name).writeBytes(byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(bytes))
        }
        try {
            val identity = DeviceIdentity.generate()
            write("identity-$id", identity.enrollmentKey().toByteArray())
            val before = File(dir, "identity-$id").readBytes()
            assertSame(PhoneIdentityLookup.Refused, PhoneIdentityLookup.read(dir, id) { key })
            assertEquals(listOf("identity-$id"), dir.listFiles()!!.map { it.name })
            assertArrayEquals(before, File(dir, "identity-$id").readBytes())
            write("known-$id", byteArrayOf(1))
            val found = PhoneIdentityLookup.read(dir, id) { key } as PhoneIdentityLookup.Known
            assertEquals(identity.deviceId, found.identity.deviceId)
            File(dir, "identity-$id.bak").writeBytes(before)
            assertSame(PhoneIdentityLookup.Refused, PhoneIdentityLookup.read(dir, id) { key })
            assertArrayEquals(before, File(dir, "identity-$id.bak").readBytes())
            assertArrayEquals(before, File(dir, "identity-$id").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun setupRecoveryCompletesBeforeAnyBackgroundIdentityRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val dir = Files.createTempDirectory("inbox-barrier").resolve("absent").toFile()
        SetupTransferGate.resetForProcessFixture {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val thread = Thread {
            assertSame(
                PhoneIdentityLookup.Absent,
                PhoneIdentityLookup.read(dir, "ab".repeat(32)) {
                    error("No key lookup for an absent enrollment")
                }
            )
            done.countDown()
        }
        try {
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(done.await(30, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            thread.join(5000)
            requireNotNull(dir.parentFile).deleteRecursively()
            SetupTransferGate.resetForProcessFixture(null)
            SetupTransferGate.ensureRecovered()
        }
    }
}
