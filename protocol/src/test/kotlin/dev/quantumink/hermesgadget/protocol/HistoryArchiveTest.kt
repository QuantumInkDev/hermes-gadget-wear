package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryArchiveTest {
    private val password get() = "café-moonlight-2026".toCharArray()
    private val source = HistoryArchiveSource(
        "00000000-0000-4000-8000-000000000001",
        "00000000-0000-4000-8000-000000000002",
        "Example / Profile"
    )
    private val messages = listOf(
        HistoryArchiveMessage("m1", HistoryArchiveRole.USER, "Private question", 123),
        HistoryArchiveMessage("m2", HistoryArchiveRole.ASSISTANT, "Private answer 🌙", 124)
    )
    private fun conversation(
        id: String = "00000000-0000-4000-8000-000000000003",
        content: List<HistoryArchiveMessage> = messages
    ) = HistoryArchiveConversation(source, id, 2, "Private notes", true, content)
    private fun snapshot() = HistoryArchiveSnapshot(125, listOf(conversation()))
    private fun opened(bytes: ByteArray): HistoryArchiveSnapshot {
        val result = HistoryArchive.decrypt(bytes, password)
        assertTrue(
            "Expected a readable encrypted archive",
            result is HistoryArchiveReadResult.Success
        )
        return (result as HistoryArchiveReadResult.Success).snapshot
    }

    @Test fun selectedTextRoundTripsWithoutPlaintextOrEndpointMaterialInTheEnvelope() {
        val sealed = HistoryArchive.encrypt(snapshot(), password)
        val restored = opened(sealed)
        assertEquals(125L, restored.exportedAtMs)
        val thread = restored.conversations.single()
        assertEquals("Example / Profile", thread.source.label)
        assertEquals(2L, thread.publicRevision)
        assertEquals("Private notes", thread.title)
        assertTrue(thread.bookmarked)
        assertEquals(listOf("m1", "m2"), thread.messages.map { it.id })
        assertEquals("Private answer 🌙", thread.messages[1].text)
        assertFalse(sealed.toString(Charsets.ISO_8859_1).contains("Private"))
        assertFalse(sealed.toString(Charsets.ISO_8859_1).contains("Example"))
        assertFalse(restored.toString().contains("Private"))
        assertFalse(thread.toString().contains("Private"))
    }

    @Test fun independentBinaryFixtureOpensWithTheDocumentedEnvelopeContract() {
        val restored = opened(sealFixture(payload()))
        assertEquals("Private answer 🌙", restored.conversations.single().messages[1].text)
        assertEquals(124L, restored.conversations.single().messages[1].createdAtMs)
    }

    @Test fun randomSaltAndNoncePreventIdenticalExportsFromMatching() {
        val first = HistoryArchive.encrypt(snapshot(), password)
        val second = HistoryArchive.encrypt(snapshot(), password)
        assertFalse(first.contentEquals(second))
        assertEquals(600000, ByteBuffer.wrap(first).getInt(12))
        assertEquals("QRHIST01", first.copyOfRange(0, 8).toString(Charsets.US_ASCII))
        assertEquals("Private question", opened(second).conversations.single().messages[0].text)
    }

    @Test fun wrongPasswordTamperTruncationAndTrailingBytesShareInvalidResult() {
        val sealed = HistoryArchive.encrypt(snapshot(), password)
        assertEquals(
            HistoryArchiveReadResult.Invalid,
            HistoryArchive.decrypt(sealed, "another-long-password".toCharArray())
        )
        val invalid = listOf(
            sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            sealed.copyOf(sealed.size - 1),
            sealed + byteArrayOf(1),
            sealed.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() },
            sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        )
        invalid.forEach {
            assertEquals(
                HistoryArchiveReadResult.Invalid,
                HistoryArchive.decrypt(it, password)
            )
        }
    }

    @Test fun unsupportedAlgorithmsAndHostileHeaderLengthsAreRefused() {
        val sealed = HistoryArchive.encrypt(snapshot(), password)
        val invalid = listOf(
            sealed.copyOf().also { it[0] = 0 },
            sealed.copyOf().also { it[8] = 2 },
            sealed.copyOf().also { it[9] = 2 },
            sealed.copyOf().also { it[10] = 2 },
            sealed.copyOf().also { it[11] = 1 },
            sealed.copyOf().also { ByteBuffer.wrap(it).putInt(12, Int.MAX_VALUE) },
            sealed.copyOf().also { ByteBuffer.wrap(it).putInt(16, -1) },
            sealed.copyOf().also { ByteBuffer.wrap(it).putInt(20, Int.MAX_VALUE) }
        )
        invalid.forEach {
            assertEquals(
                HistoryArchiveReadResult.Invalid,
                HistoryArchive.decrypt(it, password)
            )
        }
    }

    @Test fun authenticatedMalformedPayloadsAreStillRejected() {
        val raw = payload()
        val invalid = listOf(
            raw.copyOf().also { ByteBuffer.wrap(it).putInt(0, 2) },
            raw.copyOf().also { ByteBuffer.wrap(it).putInt(12, Int.MAX_VALUE) },
            raw.copyOf().also { ByteBuffer.wrap(it).putInt(16, -1) },
            raw + byteArrayOf(1),
            raw.copyOf().also { it[20] = 0xff.toByte() }
        )
        invalid.forEach {
            assertEquals(
                HistoryArchiveReadResult.Invalid,
                HistoryArchive.decrypt(sealFixture(it), password)
            )
        }
    }

    @Test fun normalizedPasswordsAreInteroperableAndCallerPasswordIsUntouched() {
        val caller = password
        val before = caller.copyOf()
        val sealed = HistoryArchive.encrypt(snapshot(), caller)
        assertTrue(caller.contentEquals(before))
        assertTrue(
            HistoryArchive.decrypt(sealed, "cafe\u0301-moonlight-2026".toCharArray())
                is HistoryArchiveReadResult.Success
        )
        listOf(
            charArrayOf(),
            "short".toCharArray(),
            CharArray(1025) { 'a' },
            ("invalid-password-" + '\uD800').toCharArray()
        ).forEach {
            refuses { HistoryArchive.encrypt(snapshot(), it) }
        }
    }

    @Test fun callerOwnedCollectionsCannotChangeAReviewedExport() {
        val ownedMessages = messages.toMutableList()
        val threads = mutableListOf(conversation(content = ownedMessages))
        val reviewed = HistoryArchiveSnapshot(125, threads)
        ownedMessages.clear()
        threads.clear()
        assertEquals(
            2,
            opened(HistoryArchive.encrypt(reviewed, password))
                .conversations.single().messages.size
        )
    }

    @Test fun duplicateIdsAndUnsupportedSourceIdentityCannotBeSealed() {
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    listOf(conversation(), conversation())
                ),
                password
            )
        }
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    listOf(conversation(content = listOf(messages[0], messages[0])))
                ),
                password
            )
        }
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    listOf(
                        HistoryArchiveConversation(
                            source.copy(serverId = "not-a-uuid"),
                            conversation().id,
                            2,
                            "Title",
                            false,
                            messages
                        )
                    )
                ),
                password
            )
        }
    }

    @Test fun oversizeCountsAndTextRefuseInsteadOfTruncating() {
        val large = "x".repeat(256 * 1024)
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    listOf(
                        conversation(
                            content = List(33) {
                                HistoryArchiveMessage(
                                    "large-$it",
                                    HistoryArchiveRole.USER,
                                    large,
                                    it.toLong()
                                )
                            }
                        )
                    )
                ),
                password
            )
        }
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    listOf(
                        conversation(
                            content = List(1025) {
                                HistoryArchiveMessage(
                                    "m-$it",
                                    HistoryArchiveRole.USER,
                                    "ok",
                                    it.toLong()
                                )
                            }
                        )
                    )
                ),
                password
            )
        }
        refuses {
            HistoryArchive.encrypt(
                HistoryArchiveSnapshot(
                    125,
                    List(257) {
                        conversation("00000000-0000-4000-8000-" + "%012d".format(it))
                    }
                ),
                password
            )
        }
    }

    @Test fun streamRoundTripDoesNotCloseTheCallersStreamsAndWriteErrorsPropagate() {
        var closed = false
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        HistoryArchive.writeEncrypted(snapshot(), password, output)
        val input = object : ByteArrayInputStream(output.toByteArray()) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        assertTrue(HistoryArchive.read(input, password) is HistoryArchiveReadResult.Success)
        assertFalse(closed)
        val partial = ByteArrayOutputStream()
        val failure = runCatching {
            HistoryArchive.writeEncrypted(
                snapshot(),
                password,
                object : OutputStream() {
                    override fun write(value: Int) {
                        if (partial.size() ==
                            80
                        ) {
                            throw java.io.IOException("owned fixture write failure")
                        }
                        partial.write(value)
                    }
                }
            )
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertFalse(partial.toString(Charsets.ISO_8859_1).contains("Private"))
        assertEquals(
            HistoryArchiveReadResult.Invalid,
            HistoryArchive.decrypt(partial.toByteArray(), password)
        )
    }

    private fun refuses(block: () -> Any?) {
        assertTrue("Expected validation refusal", runCatching(block).exceptionOrNull() is Exception)
    }

    // Independent literal binary layout, not the production encoder.
    private fun payload(): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use {
            fun string(value: String) {
                val bytes = value.toByteArray()
                it.writeInt(bytes.size)
                it.write(bytes)
            }
            it.writeInt(1)
            it.writeLong(125)
            it.writeInt(1)
            string("00000000-0000-4000-8000-000000000001")
            string("00000000-0000-4000-8000-000000000002")
            string("Example / Profile")
            string("00000000-0000-4000-8000-000000000003")
            it.writeLong(2)
            string("Private notes")
            it.writeBoolean(true)
            it.writeInt(2)
            string("m1")
            it.writeByte(0)
            string("Private question")
            it.writeLong(123)
            string("m2")
            it.writeByte(1)
            string("Private answer 🌙")
            it.writeLong(124)
        }
    }.toByteArray()

    private fun sealFixture(payload: ByteArray): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 17).toByte() }
        val header = ByteBuffer.allocate(52).put("QRHIST01".toByteArray())
            .put(byteArrayOf(1, 1, 1, 0)).putInt(600000)
            .putInt(payload.size).putInt(payload.size + 16).put(salt).put(nonce).array()
        val derived = MigrationArchive.deriveKey(password, salt, 600000)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derived, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(header)
            header + cipher.doFinal(payload)
        } finally {
            derived.fill(0)
        }
    }
}
