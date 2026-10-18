package dev.quantumink.hermesgadget.mobile

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsSessionTest {
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private val empty get() = SpeechSettings(SpeechMode.SERVER, "", "")

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val records = Collections.synchronizedMap(mutableMapOf<String, SpeechSettings>())
        val allowed = AtomicBoolean(true)
        val writes = AtomicInteger()
        val published = AtomicInteger()
        val checks = AtomicInteger()
        var beforeRead: (String) -> Unit = {}
        var beforePublish: () -> Unit = {}
        var beforeQuota: () -> Unit = {}
        val session = SpeechSettingsSession(scope, { id ->
            beforeRead(id)
            records[id] ?: SpeechSettings(SpeechMode.SERVER, "", "")
        }, { id, settings ->
            writes.incrementAndGet()
            records[id] = settings
        }, {
            beforePublish()
            published.incrementAndGet()
        }, { key ->
            assertTrue(key.startsWith("synthetic"))
            checks.incrementAndGet()
            beforeQuota()
            42L
        }, { allowed.get() })
        fun ready() = await { session.state.value.loaded && !session.state.value.busy }
        override fun close() {
            session.close()
            scope.cancel()
        }
    }

    @Test fun savingSharedNeverOptsProfilesInAndEachSavedModeRemainsIndependent() {
        Fixture().use { f ->
            f.session.open(first)
            f.ready()
            assertEquals(SpeechMode.SERVER, f.session.state.value.mode)
            f.session.saveShared("syntheticShared", "sharedVoice")
            f.ready()
            assertEquals(SpeechMode.SERVER, f.session.state.value.mode)
            assertFalse(f.session.state.value.ready)
            assertFalse(f.records.containsKey(first))
            f.session.saveProfile(SpeechMode.SHARED, "", "override")
            f.ready()
            assertTrue(f.session.state.value.ready)
            f.session.open(second)
            f.ready()
            assertEquals(SpeechMode.SERVER, f.session.state.value.mode)
            f.session.saveProfile(SpeechMode.PROFILE, "syntheticOwn", "ownVoice")
            f.ready()
            assertEquals(SpeechMode.SHARED, f.records[first]!!.mode)
            assertEquals("syntheticOwn", f.records[second]!!.key)
            f.session.deleteShared()
            f.ready()
            assertEquals(SpeechMode.PROFILE, f.session.state.value.mode)
            assertTrue(f.session.state.value.ready)
            f.session.open(first)
            f.ready()
            assertEquals(SpeechMode.SHARED, f.session.state.value.mode)
            assertFalse(f.session.state.value.ready)
            assertEquals(0, f.checks.get())
        }
    }

    @Test fun explicitQuotaDoesNotWritePublishChangeModesOrClearEdits() {
        Fixture().use { f ->
            f.records[first] = SpeechSettings(SpeechMode.PROFILE, "syntheticOwn", "voice")
            f.session.open(first)
            f.ready()
            val before = f.session.state.value
            assertEquals(0, f.checks.get())
            f.session.checkQuota(false)
            f.ready()
            assertEquals(42L, f.session.state.value.remaining)
            assertEquals(SpeechNotice.QUOTA, f.session.state.value.notice)
            assertEquals(before.profileVersion, f.session.state.value.profileVersion)
            assertEquals(before.sharedVersion, f.session.state.value.sharedVersion)
            assertEquals(before.mode, f.session.state.value.mode)
            assertEquals(1, f.checks.get())
            assertEquals(0, f.writes.get())
            assertEquals(0, f.published.get())
            f.records[first] = empty
            f.session.checkQuota(false)
            f.ready()
            assertEquals(SpeechNotice.MISSING_KEY, f.session.state.value.notice)
            assertEquals(1, f.checks.get())
        }
    }

    @Test fun delayedLoadCannotReplaceNewEndpointAndCloseCannotReviveIt() {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.records[first] = SpeechSettings(SpeechMode.PROFILE, "syntheticOld", "oldVoice")
            f.beforeRead =
                { id ->
                    if (id ==
                        first
                    ) {
                        entered.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    }
                }
            try {
                f.session.open(first)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.session.open(second)
                f.ready()
                release.countDown()
                Thread.sleep(100)
                assertEquals(second, f.session.state.value.endpoint)
                assertEquals(SpeechMode.SERVER, f.session.state.value.mode)
                assertEquals("", f.session.state.value.voice)
                f.session.close()
                f.session.saveShared("syntheticNo", "")
                assertEquals(0, f.writes.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun delayedSaveReadCannotWriteAfterProfileReplacementOrLock() {
        listOf(false, true).forEach { lock ->
            Fixture().use { f ->
                f.session.open(first)
                f.ready()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                f.beforeRead =
                    { id ->
                        if (id ==
                            first
                        ) {
                            entered.countDown()
                            release.await(3, TimeUnit.SECONDS)
                        }
                    }
                try {
                    f.session.saveProfile(SpeechMode.PROFILE, "syntheticStale", "staleVoice")
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    if (lock) f.allowed.set(false) else f.session.open(second)
                    release.countDown()
                    if (!lock) f.ready()
                    Thread.sleep(100)
                    assertEquals(0, f.writes.get())
                    assertEquals(0, f.published.get())
                    assertFalse(f.records.containsKey(first))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test fun lateQuotaAndPostSaveSyncCannotReplaceNewProfileOrReviveAnOldNotice() {
        listOf(false, true).forEach { save ->
            Fixture().use { f ->
                f.records[first] = SpeechSettings(SpeechMode.PROFILE, "syntheticOld", "voice")
                f.session.open(first)
                f.ready()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val pause = {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    Unit
                }
                if (save) f.beforePublish = pause else f.beforeQuota = pause
                try {
                    if (save) {
                        f.session.saveProfile(
                            SpeechMode.SHARED,
                            "",
                            ""
                        )
                    } else {
                        f.session.checkQuota(false)
                    }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    f.session.open(second)
                    f.ready()
                    release.countDown()
                    Thread.sleep(100)
                    assertEquals(second, f.session.state.value.endpoint)
                    assertEquals(SpeechNotice.LOADED, f.session.state.value.notice)
                    assertEquals(SpeechMode.SERVER, f.session.state.value.mode)
                    assertEquals(null, f.session.state.value.remaining)
                    assertFalse(f.records.containsKey(second))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test fun failedReadCannotBecomeAnOverwriteAndFailedSyncRetainsSuccessfulLocalSave() {
        Fixture().use { f ->
            f.beforeRead = { if (it == first) error("synthetic corruption") }
            f.session.open(first)
            await { !f.session.state.value.busy }
            assertEquals(SpeechNotice.FAILED, f.session.state.value.notice)
            assertFalse(f.session.state.value.loaded)
            f.session.saveProfile(SpeechMode.SERVER, "", "")
            assertEquals(0, f.writes.get())
            f.beforeRead = {}
            f.session.open(first)
            f.ready()
            f.beforePublish = { error("synthetic channel unavailable") }
            f.session.saveProfile(SpeechMode.PROFILE, "syntheticOwn", "voice")
            f.ready()
            assertEquals("syntheticOwn", f.records[first]!!.key)
            assertEquals(SpeechNotice.SYNC_PENDING, f.session.state.value.notice)
            f.session.saveProfile(SpeechMode.SERVER, "", "")
            f.ready()
            assertEquals("syntheticOwn", f.records[first]!!.key)
            f.session.deleteProfile()
            f.ready()
            assertEquals("", f.records[first]!!.key)
            assertEquals(SpeechMode.SERVER, f.records[first]!!.mode)
        }
    }

    @Test fun explicitQuotaUsesOnlyTheLocalTlsSubscriptionRoute() {
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(
            certificate
        ).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(
            certificate.certificate
        ).build()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setBody(
                    """{"character_count":8,"character_limit":50}"""
                )
            )
            val client = okhttp3.OkHttpClient.Builder().sslSocketFactory(
                clientTls.sslSocketFactory(),
                clientTls.trustManager
            ).build()
            ElevenLabsApi(server.url("/"), client).use { api ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val records = Collections.synchronizedMap(mutableMapOf<String, SpeechSettings>())
                records[first] = SpeechSettings(SpeechMode.PROFILE, "syntheticTlsKey", "voice")
                val session =
                    SpeechSettingsSession(scope, { records[it] ?: empty }, { id, saved ->
                        records[id] =
                            saved
                    }, {}, { api.quota(it).remaining })
                try {
                    session.open(first)
                    await { session.state.value.loaded && !session.state.value.busy }
                    assertEquals(0, server.requestCount)
                    session.saveProfile(SpeechMode.PROFILE, "", "changedVoice")
                    await { !session.state.value.busy }
                    assertEquals(0, server.requestCount)
                    session.checkQuota(false)
                    await { !session.state.value.busy }
                    assertEquals(42L, session.state.value.remaining)
                    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("GET", request.method)
                    assertEquals("/v1/user/subscription", request.path)
                    assertEquals("syntheticTlsKey", request.getHeader("xi-api-key"))
                    assertEquals(1, server.requestCount)
                } finally {
                    session.close()
                    scope.cancel()
                }
            }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private fun await(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!condition() && System.nanoTime() < until) Thread.sleep(10)
            assertTrue("Settings operation did not settle", condition())
        }
    }
}
