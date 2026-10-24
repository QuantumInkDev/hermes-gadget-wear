package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.HistoryArchive
import dev.quantumink.hermesgadget.protocol.HistoryArchiveReadResult
import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneHistoryExporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope =
        HistoryScope(
            EnrollmentRecord.endpointKey("wss://example.invalid/gadget"),
            "wss://example.invalid/gadget",
            "a".repeat(64),
            "00000000-0000-4000-8000-000000000002",
            0L
        )
    private val first = "00000000-0000-4000-8000-000000000003"
    private val second = "00000000-0000-4000-8000-000000000004"
    private val password get() = "a-private-export-password".toCharArray()

    private suspend fun fixture(): PhoneHistoryCache {
        val directory = temporary.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cache =
            PhoneHistoryCache { EncryptedHistoryCache(directory, { key }, { 1_800_000_000_000 }) }
        for ((index, id) in listOf(first, second).withIndex()) {
            cache.put(
                scope,
                HistoryThread(
                    id,
                    1,
                    "Notes $index",
                    index == 0,
                    listOf(
                        HistoryMessage("m$index", HistoryRole.USER, "Private thought $index", 123)
                    ),
                    complete = true
                ),
                index.toLong(),
                HistoryAnchor.Beginning
            )
        }
        return cache
    }

    private fun review(revision: Long = 2, ids: List<String> = listOf(first)) = HistoryExportReview(
        scope,
        revision,
        ids,
        "Example / Profile",
        serverId = "00000000-0000-4000-8000-000000000001"
    )

    @Test fun partialHistoryAndUnboundExportProvenanceAreDistinctTypedRefusals() = runBlocking {
        val cache = fixture()
        val output = ByteArrayOutputStream()
        val unbound = HistoryExportReview(scope, 2, listOf(first), "Fixture")
        val missing = runCatching {
            PhoneHistoryExporter(cache).write(unbound, password, output)
        }.exceptionOrNull()
        assertTrue(missing is HistoryFailure)
        assertEquals(HistoryProblem.UNBOUND_PROVENANCE, (missing as HistoryFailure).problem)
        val partialId = "00000000-0000-4000-8000-000000000005"
        cache.put(
            scope,
            HistoryThread(
                partialId,
                1,
                "Notes",
                false,
                listOf(HistoryMessage("m0", HistoryRole.USER, "Private thought", 123))
            ),
            2,
            HistoryAnchor.Beginning
        )
        val partial = runCatching {
            PhoneHistoryExporter(cache).write(review(3, listOf(partialId)), password, output)
        }.exceptionOrNull()
        assertTrue(partial is HistoryFailure)
        assertEquals(HistoryProblem.REBUILD_REQUIRED, (partial as HistoryFailure).problem)
        assertEquals(0, output.size())
    }

    @Test fun reviewedIdsNeverUseTheCallersBulkArrayConversion() {
        val backing = arrayOf(first, second)
        val ids = object : java.util.AbstractList<String>() {
            override val size: Int get() = backing.size
            override fun get(index: Int): String = backing[index]
            override fun toArray(): Array<Any?> = error("Caller array must never be retained")
        }
        val selected = review(ids = ids)
        backing[0] = second
        assertEquals(listOf(first, second), selected.conversationIds)
    }

    @Test fun cacheDtosNeverUseTheCallersBulkArrayConversion() {
        val messages = object : java.util.AbstractList<HistoryMessage>() {
            override val size: Int get() = 2
            override fun get(index: Int) = HistoryMessage("m$index", HistoryRole.USER, "Text", 123)
            override fun toArray(): Array<Any?> = error("Caller array must never be retained")
        }
        val entry = HistoryThread(first, 1, "Title", false, messages, complete = true)
        assertEquals(listOf("m0", "m1"), entry.messages.map { it.id })
        val threads = object : java.util.AbstractList<HistoryThread>() {
            override val size: Int get() = 2
            override fun get(index: Int) = entry
            override fun toArray(): Array<Any?> = error("Caller array must never be retained")
        }
        assertEquals(2, HistorySnapshot(1, threads = threads).threads.size)
    }

    @Test fun exportedHistoryKeepsCanonicalEqualTimePromptAndReplyOrder() = runBlocking {
        val cache = fixture()
        val third = "00000000-0000-4000-8000-000000000005"
        val messages = listOf(
            HistoryMessage("z-user", HistoryRole.USER, "Question", 123),
            HistoryMessage("a-answer", HistoryRole.ASSISTANT, "Answer", 123)
        )
        cache.put(
            scope,
            HistoryThread(third, 1, "Ordered", false, messages, complete = true),
            2,
            HistoryAnchor.Beginning
        )
        val output = ByteArrayOutputStream()
        PhoneHistoryExporter(cache).write(review(3, listOf(third)), password, output)
        val opened = HistoryArchive.decrypt(output.toByteArray(), password)
            as HistoryArchiveReadResult.Success
        assertEquals(
            listOf("z-user", "a-answer"),
            opened.snapshot.conversations.single().messages.map {
                it.id
            }
        )
    }

    @Test fun cancelledReviewDuringEncodingCannotPublishItsCiphertext() = runBlocking {
        val cache = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val review = review()
        val output = ByteArrayOutputStream()
        val exporter = PhoneHistoryExporter(cache, { snapshot, password ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HistoryArchive.encrypt(snapshot, password)
        })
        val result =
            async(Dispatchers.Default) { runCatching { exporter.write(review, password, output) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            review.cancel()
            release.countDown()
            assertTrue(
                "Cancelled review must not report a completed write",
                result.await().isFailure
            )
            assertEquals(0, output.size())
        } finally {
            release.countDown()
        }
    }

    @Test fun cachePurgeWhileEncodingCannotPublishDeletedHistory() = runBlocking {
        val cache = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val exporter = PhoneHistoryExporter(cache, { snapshot, password ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HistoryArchive.encrypt(snapshot, password)
        })
        val result =
            async(Dispatchers.Default) {
                runCatching { exporter.write(review(), password, output) }
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.purge(scope, null, 2)
            release.countDown()
            assertTrue("A purged snapshot must be reviewed again", result.await().isFailure)
            assertEquals(0, output.size())
        } finally {
            release.countDown()
        }
    }

    @Test fun coroutineCancellationDuringEncodingLeavesOutputEmpty() = runBlocking {
        val cache = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val exporter = PhoneHistoryExporter(cache, { snapshot, password ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HistoryArchive.encrypt(snapshot, password)
        })
        val job = async(Dispatchers.Default) { exporter.write(review(), password, output) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(0, output.size())
        } finally {
            release.countDown()
        }
    }

    @Test fun cancellingDuringAnAcceptedChunkStopsSubsequentCiphertextChunks() = runBlocking {
        val cache = fixture()
        cache.put(
            scope,
            HistoryThread(
                first,
                1,
                "Notes 0",
                true,
                listOf(HistoryMessage("large", HistoryRole.ASSISTANT, "x".repeat(200_000), 124)),
                complete = true
            ),
            2,
            HistoryAnchor.After("m0")
        )
        val review = review(3)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val partial = object : ByteArrayOutputStream() {
            private var firstChunk = true
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (firstChunk) {
                    firstChunk = false
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val result = async(Dispatchers.Default) {
            runCatching {
                PhoneHistoryExporter(cache).write(review, password, partial)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            review.cancel()
            release.countDown()
            assertTrue(result.await().isFailure)
            assertTrue(partial.size() in 1..65536)
            assertEquals(
                HistoryArchiveReadResult.Invalid,
                HistoryArchive.decrypt(partial.toByteArray(), password)
            )
        } finally {
            release.countDown()
        }
    }

    @Test fun onlyReviewedConversationIsExportedWithPublicSourceProvenance() = runBlocking {
        val cache = fixture()
        val caller = Thread.currentThread()
        var encodedOffCaller = false
        var wroteOffCaller = false
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                wroteOffCaller = Thread.currentThread() !== caller
                super.write(bytes, offset, length)
            }
            override fun close() {
                closed = true
                super.close()
            }
        }
        val exporter = PhoneHistoryExporter(cache, { snapshot, password ->
            encodedOffCaller = Thread.currentThread() !== caller
            HistoryArchive.encrypt(snapshot, password)
        })
        val written = exporter.write(review(), password, output)
        val decoded = HistoryArchive.decrypt(output.toByteArray(), password)
            as HistoryArchiveReadResult.Success
        assertEquals(listOf(first), decoded.snapshot.conversations.map { it.id })
        assertEquals(
            "00000000-0000-4000-8000-000000000001",
            decoded.snapshot.conversations.single().source.serverId
        )
        assertEquals(scope.profileId, decoded.snapshot.conversations.single().source.profileId)
        assertEquals(1L, decoded.snapshot.conversations.single().publicRevision)
        assertEquals(
            "Private thought 0",
            decoded.snapshot.conversations.single().messages.single().text
        )
        assertEquals(1, written.conversations)
        assertEquals(1, written.messages)
        assertTrue(encodedOffCaller && wroteOffCaller)
        assertFalse(closed)
        assertEquals(2L, cache.read(scope).revision)
    }

    @Test fun staleRevisionMissingSelectionAndWrongProfileLeaveOutputEmpty() = runBlocking {
        val cache = fixture()
        val exporter = PhoneHistoryExporter(cache)
        val invalid = listOf(
            review(1),
            review(ids = listOf("00000000-0000-4000-8000-000000000005")),
            HistoryExportReview(scope.copy(enrollmentLabel = "other"), 2, listOf(first), "Other")
        )
        invalid.forEach {
            val output = ByteArrayOutputStream()
            assertTrue(runCatching { exporter.write(it, password, output) }.isFailure)
            assertEquals(0, output.size())
        }
    }

    @Test fun asynchronousExportOwnsSelectionPasswordAndCiphertextBuffers() = runBlocking {
        val cache = fixture()
        val ids = mutableListOf(first)
        val review = review(ids = ids)
        val callerPassword = password
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var ownedPassword: CharArray? = null
        var ownedCiphertext: ByteArray? = null
        val output = ByteArrayOutputStream()
        val exporter = PhoneHistoryExporter(cache, { snapshot, password ->
            ownedPassword = password
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            HistoryArchive.encrypt(snapshot, password).also { ownedCiphertext = it }
        })
        val result = async(Dispatchers.Default) { exporter.write(review, callerPassword, output) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ids.clear()
            ids += second
            callerPassword.fill('!')
            release.countDown()
            result.await()
            val opened = HistoryArchive.decrypt(output.toByteArray(), password)
                as HistoryArchiveReadResult.Success
            assertEquals(listOf(first), opened.snapshot.conversations.map { it.id })
            assertTrue(requireNotNull(ownedPassword).all { it == '\u0000' })
            assertTrue(requireNotNull(ownedCiphertext).all { it == 0.toByte() })
        } finally {
            release.countDown()
        }
    }

    @Test fun providerWriteOrFlushFailurePropagatesWithoutClosingCallerStream() = runBlocking {
        val cache = fixture()
        val exporter = PhoneHistoryExporter(cache)
        for (failFlush in listOf(false, true)) {
            val partial = ByteArrayOutputStream()
            var closed = false
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    if (!failFlush &&
                        partial.size() == 80
                    ) {
                        throw java.io.IOException("fixture write")
                    }
                    partial.write(value)
                }
                override fun flush() {
                    if (failFlush) throw java.io.IOException("fixture flush")
                }
                override fun close() {
                    closed = true
                }
            }
            val failure = runCatching {
                exporter.write(review(), password, output)
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertFalse(closed)
            assertFalse(partial.toString(Charsets.ISO_8859_1).contains("Private thought"))
        }
    }
}
