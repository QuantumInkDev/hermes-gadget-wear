package dev.quantumink.hermesgadget.mobile

import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val scope = HistoryScope(
        "box:personal",
        "wss://example.invalid/gadget",
        "00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002",
        "a".repeat(64)
    )
    private val conversation = "00000000-0000-4000-8000-000000000003"
    private fun cache(directory: File, secret: SecretKey = key) =
        EncryptedHistoryCache(directory, { secret }, { now })
    private fun thread(
        revision: Long = 1,
        title: String = "Moonlight notes",
        messages: List<HistoryMessage> = listOf(
            HistoryMessage("m1", HistoryRole.USER, "My private question", 123),
            HistoryMessage("m2", HistoryRole.ASSISTANT, "A private answer 🌙", 124)
        )
    ) = HistoryThread(conversation, revision, title, false, messages)

    // Missing persistence, plaintext writes or missing scope filtering break this behavior.
    @Test fun reopeningKeepsTextEncryptedAndSearchesOnlyTheExactScope() {
        val directory = temporary.newFolder()
        val saved = cache(directory).put(scope, thread(), 0)
        assertEquals(1L, saved.revision)
        assertEquals(90, saved.retentionDays)
        val reopened = cache(directory)
        assertEquals("A private answer 🌙", reopened.read(scope).threads.single().messages[1].text)
        assertEquals(conversation, reopened.search(scope, "PRIVATE ANSWER").single().id)
        assertTrue(reopened.search(scope.copy(profileKey = "box:business"), "private").isEmpty())
        directory.walkTopDown().filter(File::isFile).forEach {
            val disk = it.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains("private"))
            assertFalse(disk.contains("example.invalid"))
        }
        assertFalse(saved.toString().contains("private"))
        assertFalse(thread().toString().contains("Moonlight"))
    }

    @Test fun everyIdentityComponentSeparatesTheCache() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val others = listOf(
            scope.copy(profileKey = "another"),
            scope.copy(endpointUrl = "wss://another.invalid/gadget"),
            scope.copy(serverId = "00000000-0000-4000-8000-000000000004"),
            scope.copy(profileId = "00000000-0000-4000-8000-000000000004"),
            scope.copy(enrollmentTag = "b".repeat(64))
        )
        others.forEach { assertTrue(store.read(it).threads.isEmpty()) }
        store.put(others[0], thread(title = "Other profile"), 1)
        assertEquals("Moonlight notes", store.read(scope).threads.single().title)
    }

    @Test fun stableIdsMergeOverlappingPagesButEqualTextWithDifferentIdsIsKept() {
        val store = cache(temporary.newFolder())
        store.put(scope, thread(), 0)
        val merged = store.put(
            scope,
            thread(
                messages = listOf(
                    HistoryMessage("m2", HistoryRole.ASSISTANT, "A private answer 🌙", 124),
                    HistoryMessage("m3", HistoryRole.ASSISTANT, "A private answer 🌙", 125)
                )
            ),
            1,
            afterMessageId = "m1"
        )
        assertEquals(listOf("m1", "m2", "m3"), merged.threads.single().messages.map { it.id })
        assertEquals(merged.revision, store.put(scope, thread(), merged.revision).revision)
    }

    @Test fun canonicalListOrderSurvivesEqualTimesClockRollbackAndReopen() {
        val directory = temporary.newFolder()
        val messages = listOf(
            HistoryMessage("z-user", HistoryRole.USER, "Question", 123),
            HistoryMessage("a-answer", HistoryRole.ASSISTANT, "Answer", 123),
            HistoryMessage("earlier-clock", HistoryRole.USER, "Next question", 100)
        )
        cache(directory).put(scope, thread(messages = messages), 0)
        assertEquals(messages, cache(directory).read(scope).threads.single().messages)
    }

    @Test fun equalTimePrefixReplayAndExtensionKeepTheCanonicalSequence() {
        val store = cache(temporary.newFolder())
        val question = HistoryMessage("z-user", HistoryRole.USER, "Question", 123)
        val answer = HistoryMessage("a-answer", HistoryRole.ASSISTANT, "Answer", 123)
        store.put(scope, thread(messages = listOf(question)), 0)
        val extended = store.put(scope, thread(messages = listOf(question, answer)), 1)
        assertEquals(listOf(question, answer), extended.threads.single().messages)
        assertEquals(
            extended.revision,
            store.put(scope, thread(messages = listOf(question)), 2).revision
        )
    }

    @Test fun reorderedOrUnanchoredPagesNeverReplaceTheCommittedSequence() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val before = File(directory, "history.v1").readBytes()
        refuses { store.put(scope, thread(messages = thread().messages.reversed()), 1) }
        refuses {
            store.put(
                scope,
                thread(
                    messages = listOf(
                        HistoryMessage("new", HistoryRole.USER, "Unplaced page", 125)
                    )
                ),
                1
            )
        }
        assertTrue(before.contentEquals(File(directory, "history.v1").readBytes()))
        assertEquals(1L, store.read(scope).revision)
    }

    @Test fun anchoredPagesCanExtendOrReplayButNeverGuessAnUnknownPosition() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        val user = HistoryMessage("z", HistoryRole.USER, "Question", 123)
        val answer = HistoryMessage("a", HistoryRole.ASSISTANT, "Answer", 100)
        val next = HistoryMessage("next", HistoryRole.USER, "Next", 100)
        store.put(scope, thread(messages = listOf(user)), 0)
        val extended = store.put(scope, thread(messages = listOf(answer, next)), 1, "z")
        assertEquals(listOf(user, answer, next), extended.threads.single().messages)
        assertEquals(2L, store.put(scope, thread(messages = listOf(answer)), 2, "z").revision)
        val before = File(directory, "history.v1").readBytes()
        refuses { store.put(scope, thread(messages = listOf(next)), 2, "unknown") }
        refuses { store.put(scope, thread(messages = listOf(next)), 2, "z") }
        assertTrue(before.contentEquals(File(directory, "history.v1").readBytes()))
        assertEquals(
            listOf(user, answer, next),
            cache(directory).read(scope).threads.single().messages
        )
    }

    @Test fun conflictingIdsOrStaleMetadataNeverOverwriteSavedBytes() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val before = File(directory, "history.v1").readBytes()
        refuses {
            store.put(
                scope,
                thread(
                    messages = listOf(
                        HistoryMessage("m1", HistoryRole.USER, "replaced content", 123)
                    )
                ),
                1
            )
        }
        refuses { store.put(scope, thread(revision = 0), 1) }
        refuses { store.put(scope, thread(title = "same revision, wrong title"), 1) }
        assertTrue(before.contentEquals(File(directory, "history.v1").readBytes()))
        val updated = store.put(scope, thread(revision = 2, title = "Reviewed name"), 1)
        assertEquals("Reviewed name", updated.threads.single().title)
    }

    @Test fun purgeFencesLateWritersAndDoesNotDeleteOtherProfiles() {
        val store = cache(temporary.newFolder())
        store.put(scope, thread(), 0)
        val other = scope.copy(profileKey = "other")
        store.put(other, thread(), 1)
        val purged = store.purge(scope, conversation, 2)
        assertTrue(purged.threads.isEmpty())
        refuses { store.put(scope, thread(), 2) }
        assertTrue(store.read(scope).threads.isEmpty())
        assertEquals(conversation, store.read(other).threads.single().id)
    }

    @Test fun purgingAnEmptyCacheStillFencesAnAlreadyPendingPage() {
        val store = cache(temporary.newFolder())
        val before = store.read(scope)
        val purged = store.purge(scope, conversation, before.revision)
        refuses { store.put(scope, thread(), before.revision) }
        assertTrue(purged.revision > before.revision)
        assertTrue(store.read(scope).threads.isEmpty())
    }

    @Test fun retryingAnEarlierProfileDoesNotChangeTheLocalRevision() {
        val store = cache(temporary.newFolder())
        store.put(scope, thread(), 0)
        store.put(scope.copy(profileKey = "second"), thread(), 1)
        assertEquals(2L, store.put(scope, thread(), 2).revision)
    }

    @Test fun retentionExpiresAtTheBoundaryAndDuplicatesDoNotRenewIt() {
        val store = cache(temporary.newFolder())
        store.put(scope, thread(), 0)
        now += 89 * DAY
        val unchanged = store.put(scope, thread(), 1)
        assertEquals(1L, unchanged.revision)
        now += DAY - 1
        assertEquals(2, store.read(scope).threads.single().messages.size)
        now++
        assertTrue(store.read(scope).threads.isEmpty())
        now -= 90 * DAY
        assertTrue(store.read(scope).threads.isEmpty())
    }

    @Test fun newMessagesSurviveWhenOlderMessagesExpire() {
        val store = cache(temporary.newFolder())
        store.put(scope, thread(), 0)
        now += 89 * DAY
        store.put(
            scope,
            thread(
                messages = listOf(
                    HistoryMessage("m3", HistoryRole.USER, "Recent thought", 125)
                )
            ),
            1,
            afterMessageId = "m2"
        )
        now += DAY
        assertEquals(listOf("m3"), store.read(scope).threads.single().messages.map { it.id })
    }

    @Test fun changingRetentionPersistsAndZeroDisablesStorage() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        now += 8 * DAY
        val changed = store.setRetention(scope, 7, 1)
        assertEquals(7, changed.retentionDays)
        assertTrue(changed.threads.isEmpty())
        val disabled = store.setRetention(scope, 0, changed.revision)
        assertTrue(store.put(scope, thread(), disabled.revision).threads.isEmpty())
        assertEquals(0, cache(directory).read(scope).retentionDays)
    }

    @Test fun missingKeyRecordAndTamperingFailClosedWithoutReplacingEvidence() {
        val directory = temporary.newFolder()
        cache(directory).put(scope, thread(), 0)
        val file = File(directory, "history.v1")
        val original = file.readBytes()
        val missing = EncryptedHistoryCache(directory, { error("missing key") }, { now })
        refuses { missing.read(scope) }
        assertTrue(original.contentEquals(file.readBytes()))
        file.writeBytes(
            original.copyOf().also {
                it[it.lastIndex] =
                    (it.last().toInt() xor 1).toByte()
            }
        )
        val damaged = file.readBytes()
        refuses { cache(directory).put(scope, thread(), 1) }
        assertTrue(damaged.contentEquals(file.readBytes()))
        file.delete()
        refuses { cache(directory).put(scope, thread(), 0) }
        assertFalse(file.exists())
    }

    @Test fun wrongKeyUnsupportedVersionAndOversizedRecordAreRejected() {
        val directory = temporary.newFolder()
        cache(directory).put(scope, thread(), 0)
        val file = File(directory, "history.v1")
        val original = file.readBytes()
        val wrong = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        refuses { cache(directory, wrong).read(scope) }
        file.writeBytes(original.copyOf().also { it[0] = 2 })
        refuses { cache(directory).read(scope) }
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 31) }
        refuses { cache(directory).read(scope) }
    }

    @Test fun stagingFailurePreservesTheCommittedRecordAndItsRevision() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val before = File(directory, "history.v1").readBytes()
        File(directory, "history.pending").mkdir()
        refuses { store.put(scope, thread(revision = 2, title = "New name"), 1) }
        assertTrue(before.contentEquals(File(directory, "history.v1").readBytes()))
        assertEquals(1L, cache(directory).read(scope).revision)
        assertEquals("Moonlight notes", cache(directory).read(scope).threads.single().title)
    }

    @Test fun missingInitializationRecordDoesNotPromoteOrOverwriteAnOrphanStage() {
        val directory = temporary.newFolder()
        val orphan = File(directory, "history.pending").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        refuses { cache(directory).put(scope, thread(), 0) }
        assertEquals(listOf<Byte>(7, 8, 9), orphan.readBytes().toList())
    }

    @Test fun largeMessagesAreRefusedWithoutTruncatingExistingText() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        refuses {
            store.put(
                scope,
                thread(
                    messages = listOf(
                        HistoryMessage("large", HistoryRole.USER, "🌙".repeat(65_537), 125)
                    )
                ),
                1
            )
        }
        assertEquals("My private question", store.read(scope).threads.single().messages[0].text)
    }

    @Test fun sharedLocalRevisionSerializesDifferentStoreInstances() {
        val directory = temporary.newFolder()
        cache(directory).put(scope, thread(), 0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes = pool.invokeAll(
                listOf("first", "second").map { title ->
                    Callable {
                        runCatching { cache(directory).put(scope, thread(2, title), 1) }.isSuccess
                    }
                }
            ).map { it.get() }
            assertEquals(1, outcomes.count { it })
            assertEquals(2L, cache(directory).read(scope).revision)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun callerOwnedListsCannotChangeCachedMessages() {
        val messages = mutableListOf(HistoryMessage("m1", HistoryRole.USER, "Original", 123))
        val entry = thread(messages = messages)
        messages.clear()
        val store = cache(temporary.newFolder())
        store.put(scope, entry, 0)
        val returned = store.read(scope).threads.single().messages as MutableList<HistoryMessage>
        try {
            returned.clear()
        } catch (_: UnsupportedOperationException) {
            // An immutable view and a mutable defensive copy both preserve the cache.
        }
        assertEquals("Original", store.read(scope).threads.single().messages.single().text)
    }

    @Test fun invalidIdentityOrTextIsRejectedBeforeAnyRecordIsWritten() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        refuses { store.put(scope.copy(endpointUrl = "ws://example.invalid/gadget"), thread(), 0) }
        refuses { store.put(scope.copy(enrollmentTag = "not a fingerprint"), thread(), 0) }
        refuses {
            store.put(
                scope,
                thread(
                    messages = listOf(
                        HistoryMessage("m1", HistoryRole.USER, "invalid \uD800", 123)
                    )
                ),
                0
            )
        }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }

    @Test fun totalByteCapacityIsRefusedWithoutDiscardingTheCommittedRecord() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val before = File(directory, "history.v1").readBytes()
        val large = "x".repeat(256 * 1024)
        refuses {
            store.put(
                scope,
                thread(
                    messages = List(33) {
                        HistoryMessage("large-$it", HistoryRole.USER, large, 200L + it)
                    }
                ),
                1,
                afterMessageId = "m2"
            )
        }
        assertTrue(before.contentEquals(File(directory, "history.v1").readBytes()))
        assertEquals(
            "My private question",
            store.read(scope).threads.single().messages.first().text
        )
    }

    @Test fun mergingCannotExceedThePerConversationMessageLimit() {
        val store = cache(temporary.newFolder())
        store.put(
            scope,
            thread(
                messages = List(1024) {
                    HistoryMessage("id-$it", HistoryRole.USER, "Thought $it", it.toLong())
                }
            ),
            0
        )
        refuses {
            store.put(
                scope,
                thread(
                    messages = listOf(
                        HistoryMessage("one-too-many", HistoryRole.USER, "New thought", 2000)
                    )
                ),
                1,
                afterMessageId = "id-1023"
            )
        }
        assertEquals(1024, store.read(scope).threads.single().messages.size)
        assertEquals(1L, store.read(scope).revision)
    }

    @Test fun legacyTimestampSortedSchemaIsRefusedWithoutReplacingItsBytes() {
        val directory = temporary.newFolder()
        cache(directory).put(scope, thread(), 0)
        val record = File(directory, "history.v1")
        val original = record.readBytes()
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, original.copyOfRange(2, 14)))
        decrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
        val legacy = decrypt.doFinal(original.copyOfRange(14, original.size))
        try {
            ByteBuffer.wrap(legacy).putInt(0, 1)
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
            encrypt.init(Cipher.ENCRYPT_MODE, key)
            encrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
            val sealed = byteArrayOf(1, 12) + encrypt.iv + encrypt.doFinal(legacy)
            record.writeBytes(sealed)
            refuses { cache(directory).read(scope) }
            assertTrue(sealed.contentEquals(record.readBytes()))
        } finally {
            legacy.fill(0)
        }
    }

    @Test fun authenticatedButInvalidBinaryDocumentsNeverBecomeReadableHistory() {
        val directory = temporary.newFolder()
        val store = cache(directory)
        store.put(scope, thread(), 0)
        val record = File(directory, "history.v1")
        val original = record.readBytes()
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, original.copyOfRange(2, 14)))
        decrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
        val plain = decrypt.doFinal(original.copyOfRange(14, original.size))
        val invalid = listOf(
            plain.copyOf().also { ByteBuffer.wrap(it).putInt(0, 3) },
            plain.copyOf().also { ByteBuffer.wrap(it).putInt(16, Int.MAX_VALUE) },
            plain.copyOf().also { ByteBuffer.wrap(it).putInt(20, -1) },
            plain + byteArrayOf(1),
            plain.copyOf().also {
                val needle = "My private question".toByteArray()
                val offset = it.indices.first { start ->
                    start + needle.size <= it.size && needle.indices.all { index ->
                        it[start + index] == needle[index]
                    }
                }
                it[offset] = 0xff.toByte()
            }
        )
        try {
            invalid.forEach { payload ->
                val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
                encrypt.init(Cipher.ENCRYPT_MODE, key)
                encrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
                val sealed = byteArrayOf(1, 12) + encrypt.iv + encrypt.doFinal(payload)
                record.writeBytes(sealed)
                refuses { store.read(scope) }
                assertTrue(sealed.contentEquals(record.readBytes()))
            }
        } finally {
            plain.fill(0)
            invalid.forEach { it.fill(0) }
            record.writeBytes(original)
        }
        assertEquals(
            "My private question",
            store.read(scope).threads.single().messages.first().text
        )
    }

    private fun refuses(block: () -> Any?) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected safe refusal", failure is Exception)
    }

    companion object {
        private const val DAY = 86_400_000L
    }
}
