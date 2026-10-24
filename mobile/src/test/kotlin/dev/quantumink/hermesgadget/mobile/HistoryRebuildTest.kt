package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.relay.session.EnrollmentRecord
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryAnchor
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryFailure
import dev.quantumink.hermesgadget.protocol.relay.session.HistoryProblem
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fresh software-key files only. These fixtures do not restore original installed/quarantined data. */
class HistoryRebuildTest {
    @get:Rule val temp = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val url = "wss://example.invalid/gadget"
    private val scope =
        HistoryScope(
            EnrollmentRecord.endpointKey(url),
            url,
            "phone:hg-public-label",
            "00000000-0000-4000-8000-000000000002",
            7
        )
    private val id = "00000000-0000-4000-8000-000000000003"
    private fun cache(dir: File) = EncryptedHistoryCache(dir, { key }, { now })
    private fun message(n: Int) =
        HistoryMessage("m$n", HistoryRole.USER, "unique-text-$n", 100L - n)
    private fun thread(vararg n: Int, complete: Boolean = false) =
        HistoryThread(id, 1, "Canonical", false, n.map(::message), complete)
    private fun refuses(problem: HistoryProblem, action: () -> Any?) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected $problem, got $failure", failure is HistoryFailure)
        assertEquals(problem, (failure as HistoryFailure).problem)
    }

    @Test fun laterListingCannotBorrowAnUnreadPredecessorFromAnOlderCompleteTail() {
        val store = cache(temp.newFolder())
        store.put(scope, thread(1, 2, 3, complete = true), 0, HistoryAnchor.Beginning, "A")
        val b = store.put(scope, thread(1), 1, HistoryAnchor.Beginning, "B")
        assertFalse(b.threads.single().complete)
        refuses(HistoryProblem.MISSING_ANCHOR) {
            store.put(scope, thread(3, complete = true), b.revision, HistoryAnchor.After("m2"), "B")
        }
        val overlap = store.put(scope, thread(1), b.revision, HistoryAnchor.Beginning, "B")
        assertEquals(b.revision, overlap.revision)
        val traversed = store.put(
            scope,
            thread(2, 3, complete = true),
            b.revision,
            HistoryAnchor.After("m1"),
            "B"
        )
        assertTrue(traversed.threads.single().complete)
        assertEquals(listOf("m1", "m2", "m3"), traversed.threads.single().messages.map { it.id })
    }

    @Test fun prunePrecedesAppendAndRetainedSuffixRequiresExactReviewedRebuild() {
        val dir = temp.newFolder()
        val store = cache(dir)
        store.put(scope, thread(1), 0, HistoryAnchor.Beginning)
        now += 89 * DAY
        store.put(scope, thread(2), 1, HistoryAnchor.After("m1"))
        now += DAY
        refuses(HistoryProblem.REBUILD_REQUIRED) {
            store.put(scope, thread(3), 2, HistoryAnchor.After("m2"))
        }
        val suffix = cache(dir).read(scope).threads.single()
        assertEquals(listOf("m2"), suffix.messages.map { it.id })
        assertTrue(suffix.rebuildRequired)
        assertFalse(suffix.complete)
        val review = store.review(scope, id)
        store.erase(review, scope)
        val revision = store.read(scope).revision
        val rebuilt = store.put(
            scope,
            thread(1, 2, 3, complete = true),
            revision,
            HistoryAnchor.Beginning,
            "reviewed-rebuild"
        )
        assertTrue(rebuilt.threads.single().complete)
    }

    @Test fun backwardClockCannotCreateAnInteriorReceiptGapAndExpiredPrefixNeverRevives() {
        val dir = temp.newFolder()
        val store = cache(dir)
        store.put(scope, thread(1), 0, HistoryAnchor.Beginning)
        val start = now
        now += 89 * DAY
        store.put(scope, thread(2), 1, HistoryAnchor.After("m1"))
        now = start + DAY
        store.put(scope, thread(3), 2, HistoryAnchor.After("m2"))
        now = start + 90 * DAY
        val retained = store.read(scope)
        assertEquals(listOf("m2", "m3"), retained.threads.single().messages.map { it.id })
        now = start
        assertEquals(
            listOf("m2", "m3"),
            cache(dir).read(scope).threads.single().messages.map {
                it.id
            }
        )
        refuses(HistoryProblem.REBUILD_REQUIRED) {
            store.put(scope, thread(1, 2, 3), retained.revision, HistoryAnchor.Beginning)
        }
    }

    private fun rewrite(dir: File, edit: (ByteArray) -> Unit) {
        val record = File(dir, "history.v1")
        val bytes = record.readBytes()
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
        decrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
        val plain = decrypt.doFinal(bytes.copyOfRange(14, bytes.size))
        try {
            edit(plain)
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
            encrypt.init(Cipher.ENCRYPT_MODE, key)
            encrypt.updateAAD("quantum-relay|phone-history|v1".toByteArray())
            record.writeBytes(byteArrayOf(1, 12) + encrypt.iv + encrypt.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    @Test fun anAuthenticatedSyntheticInteriorAgeGapIsMarkedIncompleteAndCannotBeReplayed() {
        val dir = temp.newFolder()
        val store = cache(dir)
        store.put(scope, thread(1, 2, 3, complete = true), 0, HistoryAnchor.Beginning)
        // Deliberate owned record fixture, not a claimed reachable new-writer clock schedule.
        rewrite(dir) { plain ->
            val needle = "unique-text-2".toByteArray()
            val at = plain.indices.first { start ->
                start + needle.size <= plain.size &&
                    needle.indices.all { plain[start + it] == needle[it] }
            }
            ByteBuffer.wrap(plain).putLong(at + needle.size + 8, now - 91 * DAY)
        }
        val view = store.read(scope)
        assertEquals(listOf("m1", "m3"), view.threads.single().messages.map { it.id })
        assertTrue(view.threads.single().rebuildRequired)
        assertFalse(view.threads.single().complete)
        refuses(HistoryProblem.REBUILD_REQUIRED) {
            store.put(scope, thread(1, 2, 3), view.revision, HistoryAnchor.Beginning)
        }
    }

    @Test fun legacyUnboundBytesNeedAnExactGlobalReviewAndAStaleReviewCannotEraseNewBytes() {
        val dir = temp.newFolder()
        val store = cache(dir)
        store.put(scope, thread(1), 0, HistoryAnchor.Beginning)
        val stale = store.review(scope, id)
        store.put(scope, thread(2), 1, HistoryAnchor.After("m1"))
        val current = File(dir, "history.v1").readBytes()
        refuses(HistoryProblem.STALE_REVIEW) { store.erase(stale, scope) }
        assertArrayEquals(current, File(dir, "history.v1").readBytes())
        rewrite(dir) { ByteBuffer.wrap(it).putInt(0, 2) }
        val old = File(dir, "history.v1").readBytes()
        refuses(HistoryProblem.LEGACY_UNBOUND) { store.read(scope) }
        val global = store.review(scope, id)
        assertTrue(global.allProfiles)
        assertEquals(null, global.namespace)
        assertEquals(null, global.conversationId)
        assertEquals(2, global.version)
        assertEquals(2L, global.revision)
        assertArrayEquals(old, File(dir, "history.v1").readBytes())
        store.erase(global, scope)
        assertTrue(store.read(scope).threads.isEmpty())
    }

    @Test fun fullPruneKnowledgeIsBoundedAndNeverSilentlyEvictedToAcceptANewIdentity() {
        val dir = temp.newFolder()
        val store = cache(dir)
        var revision = 0L
        for (n in 0 until 256) {
            val entry = HistoryThread("conversation-$n", 1, "Title", false, listOf(message(1)))
            revision = store.put(scope, entry, revision, HistoryAnchor.Beginning).revision
        }
        now += 91 * DAY
        revision = store.read(scope).revision
        assertTrue(store.read(scope).threads.isEmpty())
        refuses(HistoryProblem.CAPACITY) {
            store.put(scope, thread(2), revision, HistoryAnchor.Beginning)
        }
        val reopened = cache(dir)
        refuses(HistoryProblem.REBUILD_REQUIRED) {
            reopened.put(
                scope,
                HistoryThread("conversation-0", 1, "Title", false, listOf(message(1))),
                revision,
                HistoryAnchor.Beginning
            )
        }
        val days = reopened.setRetention(scope, 365, revision)
        refuses(HistoryProblem.REBUILD_REQUIRED) {
            reopened.put(
                scope,
                HistoryThread("conversation-0", 1, "Title", false, listOf(message(1))),
                days.revision,
                HistoryAnchor.Beginning
            )
        }
    }

    @Test fun failureAfterAtomicPromotionIsUncertainAndTheSamePageDoesNotRenewItsAge() {
        val dir = temp.newFolder()
        val store =
            EncryptedHistoryCache(dir, {
                key
            }, { now }, { throw java.io.IOException("owned post-promotion cut") })
        refuses(HistoryProblem.WRITE_UNCERTAIN) {
            store.put(scope, thread(1), 0, HistoryAnchor.Beginning)
        }
        val reopened = cache(dir)
        assertEquals(1L, reopened.read(scope).revision)
        now += 89 * DAY
        assertEquals(1L, reopened.put(scope, thread(1), 1, HistoryAnchor.Beginning).revision)
        now += DAY
        assertTrue(reopened.read(scope).threads.isEmpty())
    }

    @Test fun extendingACompleteListingWithAnIncompletePageWithdrawsCompleteness() {
        val store = cache(temp.newFolder())
        store.put(scope, thread(1, complete = true), 0, HistoryAnchor.Beginning, "A")
        val view = store.put(scope, thread(2), 1, HistoryAnchor.After("m1"), "A")
        assertFalse(view.threads.single().complete)
        val done = store.put(
            scope,
            thread(2, complete = true),
            view.revision,
            HistoryAnchor.After("m1"),
            "A"
        )
        assertTrue(done.threads.single().complete)
    }

    @Test fun actualPublicEnrollmentMappingSeparatesEndpointsLabelsProfilesAndReviewEpochs() {
        fun record(
            endpoint: String,
            label: String = "phone:hg-public-label",
            epoch: Long = 7,
            profile: String = scope.profileId
        ) = EnrollmentRecord.fresh(
            endpoint,
            label
        ).copy(
            pinnedProfileId = dev.quantumink.hermesgadget.protocol.relay.ProfileId.of(profile),
            reviewEpoch = epoch
        )
        val first = record(url)
        assertEquals(scope, HistoryScope.from(first))
        assertEquals(null, HistoryScope.from(EnrollmentRecord.fresh(url, "phone:hg-public-label")))
        val namespaces = listOf(
            first,
            record("wss://other.invalid/gadget"),
            record(url, "phone:other"),
            record(url, epoch = 8),
            record(url, profile = "00000000-0000-4000-8000-000000000009")
        )
            .map { requireNotNull(HistoryScope.from(it)) }
        assertEquals(namespaces.size, namespaces.toSet().size)
        val store = cache(temp.newFolder())
        for ((index, namespace) in namespaces.withIndex()) {
            store.put(namespace, thread(1), index.toLong(), HistoryAnchor.Beginning)
        }
        for (namespace in namespaces) assertEquals(1, store.read(namespace).threads.size)
    }

    companion object {
        private const val DAY = 86_400_000L
    }
}
