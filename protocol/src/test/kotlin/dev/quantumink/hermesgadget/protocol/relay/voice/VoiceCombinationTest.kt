package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.ConversationId
import dev.quantumink.hermesgadget.protocol.relay.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed `(state, reason, provider)` combinations. Two kinds of proof: (1) every combination the producer's own tables name is ACCEPTED (the
 * accounting suite's `OUTCOMES` map and the §8.1a/§6.4 mappings), and (2) an exhaustive sweep of the whole product space in which every accepted
 * combination satisfies the documented rules and every named impossible one is refused.
 */
class VoiceCombinationTest {
    private val capture = CaptureId.of("01a14d67-a300-7000-9f3a-17469850b711")!!
    private val conversation = ConversationId.of("fc25a8ad-dc63-4ed7-bc95-2066ab0172c2")!!

    private fun result(
        state: CaptureState,
        reason: CaptureFailure? = null,
        provider: VoiceProvider = VoiceProvider.NOT_STARTED,
        source: TranscriptSource? = null,
        transcript: String? = null,
        sealed: Long? = 1_000L
    ): CaptureResult? = try {
        CaptureResult(
            capture,
            conversation,
            state,
            reason,
            transcript,
            if (transcript != null) 2_000L else null,
            source,
            provider,
            sealed
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    @Test fun everyOutcomeTheProducerMapsIsAccepted() {
        // relay_voice test_relay_voice_accounting.OUTCOMES + _pre_seal + the crash matrix (design 10.2) + discard/expiry/revocation
        val table = listOf(
            result(
                CaptureState.TRANSCRIBED,
                null,
                VoiceProvider.COMPLETED,
                TranscriptSource.CONFIGURED,
                "t"
            ),
            result(
                CaptureState.TRANSCRIBED,
                null,
                VoiceProvider.FAILED_AFTER_SEND,
                TranscriptSource.LOCAL_FALLBACK,
                "t"
            ),
            result(
                CaptureState.TRANSCRIBED,
                null,
                VoiceProvider.NOT_STARTED,
                TranscriptSource.CONFIGURED,
                "t"
            ),
            result(CaptureState.EMPTY, null, VoiceProvider.COMPLETED, TranscriptSource.CONFIGURED),
            result(
                CaptureState.TOO_LONG,
                null,
                VoiceProvider.COMPLETED,
                TranscriptSource.CONFIGURED
            ),
            result(
                CaptureState.FAILED,
                CaptureFailure.PROVIDER_FAILED,
                VoiceProvider.FAILED_AFTER_SEND
            ),
            result(CaptureState.FAILED, CaptureFailure.STT_UNAVAILABLE, VoiceProvider.NOT_STARTED),
            result(CaptureState.FAILED, CaptureFailure.INTERRUPTED, VoiceProvider.NOT_STARTED),
            result(CaptureState.FAILED, CaptureFailure.AUDIO_DECODE, VoiceProvider.NOT_STARTED),
            result(CaptureState.UNKNOWN, null, VoiceProvider.UNKNOWN),
            result(CaptureState.QUEUED),
            result(CaptureState.PREPARING),
            result(CaptureState.TRANSCRIBING, null, VoiceProvider.IN_FLIGHT),
            result(CaptureState.DISCARDED, null, VoiceProvider.NOT_STARTED),
            result(CaptureState.DISCARDED, null, VoiceProvider.IN_FLIGHT),
            result(CaptureState.DISCARDED, null, VoiceProvider.COMPLETED),
            result(
                CaptureState.EXPIRED,
                null,
                VoiceProvider.COMPLETED,
                TranscriptSource.CONFIGURED
            ),
            result(CaptureState.REVOKED, null, VoiceProvider.COMPLETED),
            result(CaptureState.REVOKED, null, VoiceProvider.NOT_STARTED)
        )
        table.forEachIndexed { index, value -> assertTrue("row $index", value != null) }
        // pre-seal finish verdicts and the pre-seal discard: no row, nothing sent, no source
        for (reason in CaptureFailure.entries.filter { it.preSeal }) {
            assertTrue(
                reason.toString(),
                result(CaptureState.FAILED, reason, VoiceProvider.NOT_STARTED, sealed = null) !=
                    null
            )
        }
        assertTrue(
            result(CaptureState.DISCARDED, null, VoiceProvider.NOT_STARTED, sealed = null) != null
        )
    }

    @Test fun theImpossibleCombinationsAreRefused() {
        val refused = mapOf(
            "reason on a non-failed state" to result(CaptureState.QUEUED, CaptureFailure.SEQUENCE),
            "failed without a reason" to result(CaptureState.FAILED),
            "transcript on a queued state" to result(CaptureState.QUEUED, transcript = "t"),
            "transcribed without a transcript" to
                result(
                    CaptureState.TRANSCRIBED,
                    null,
                    VoiceProvider.COMPLETED,
                    TranscriptSource.CONFIGURED
                ),
            "transcribed without a source" to
                result(CaptureState.TRANSCRIBED, null, VoiceProvider.COMPLETED, null, "t"),
            "queued with a send in flight" to
                result(CaptureState.QUEUED, provider = VoiceProvider.IN_FLIGHT),
            "preparing already billed" to
                result(CaptureState.PREPARING, provider = VoiceProvider.COMPLETED),
            "transcribing not in flight" to
                result(CaptureState.TRANSCRIBING, provider = VoiceProvider.NOT_STARTED),
            "unknown outcome claimed not started" to
                result(CaptureState.UNKNOWN, provider = VoiceProvider.NOT_STARTED),
            "in flight on a terminal answer" to
                result(CaptureState.EMPTY, provider = VoiceProvider.IN_FLIGHT),
            "pre-seal reason on a sealed row" to
                result(CaptureState.FAILED, CaptureFailure.SEQUENCE, VoiceProvider.NOT_STARTED),
            "post-seal reason with no row" to
                result(
                    CaptureState.FAILED,
                    CaptureFailure.PROVIDER_FAILED,
                    VoiceProvider.NOT_STARTED,
                    sealed = null
                ),
            "pre-seal answer that was sent" to
                result(
                    CaptureState.FAILED,
                    CaptureFailure.SEQUENCE,
                    VoiceProvider.COMPLETED,
                    sealed = null
                ),
            "pre-seal queued" to result(CaptureState.QUEUED, sealed = null),
            "decode failure after a send" to
                result(CaptureState.FAILED, CaptureFailure.AUDIO_DECODE, VoiceProvider.COMPLETED),
            "interrupted after a send" to
                result(CaptureState.FAILED, CaptureFailure.INTERRUPTED, VoiceProvider.UNKNOWN),
            "negative seal time" to result(CaptureState.QUEUED, sealed = -1L)
        )
        for ((name, value) in refused) assertTrue(name, value == null)
    }

    @Test fun theWholeProductSpaceOnlyAcceptsWhatTheRulesAllow() {
        var accepted = 0
        var total = 0
        for (state in CaptureState.entries) {
            for (reason in listOf<CaptureFailure?>(null) +
                CaptureFailure.entries) {
                for (provider in VoiceProvider.entries) {
                    for (source in listOf<TranscriptSource?>(null) + TranscriptSource.entries) {
                        for (sealed in listOf<Long?>(null, 5L)) {
                            for (text in listOf<String?>(null, "t")) {
                                total++
                                val value =
                                    result(state, reason, provider, source, text, sealed)
                                        ?: continue
                                accepted++
                                assertEquals(
                                    value.toString(),
                                    state == CaptureState.FAILED,
                                    reason != null
                                )
                                assertEquals(
                                    value.toString(),
                                    state == CaptureState.TRANSCRIBED,
                                    text != null
                                )
                                if (state ==
                                    CaptureState.TRANSCRIBED
                                ) {
                                    assertTrue(
                                        value.toString(),
                                        source != null
                                    )
                                }
                                if (state == CaptureState.QUEUED ||
                                    state == CaptureState.PREPARING
                                ) {
                                    assertEquals(VoiceProvider.NOT_STARTED, provider)
                                }
                                if (state ==
                                    CaptureState.TRANSCRIBING
                                ) {
                                    assertEquals(VoiceProvider.IN_FLIGHT, provider)
                                }
                                if (state ==
                                    CaptureState.UNKNOWN
                                ) {
                                    assertEquals(VoiceProvider.UNKNOWN, provider)
                                }
                                if (provider ==
                                    VoiceProvider.IN_FLIGHT
                                ) {
                                    assertTrue(
                                        value.toString(),
                                        state == CaptureState.TRANSCRIBING ||
                                            state == CaptureState.DISCARDED
                                    )
                                }
                                if (sealed == null) {
                                    assertTrue(
                                        value.toString(),
                                        state == CaptureState.FAILED ||
                                            state == CaptureState.DISCARDED
                                    )
                                    assertEquals(VoiceProvider.NOT_STARTED, provider)
                                    assertEquals(null, source)
                                    assertTrue(value.toString(), reason == null || reason.preSeal)
                                } else {
                                    assertTrue(value.toString(), reason == null || !reason.preSeal)
                                }
                                if (reason == CaptureFailure.AUDIO_DECODE ||
                                    reason == CaptureFailure.INTERRUPTED
                                ) {
                                    assertEquals(VoiceProvider.NOT_STARTED, provider)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(11 * 11 * 5 * 3 * 2 * 2, total)
        assertTrue("accepted $accepted of $total", accepted in 40..400)
    }

    private val speech = SpeechId.of("01a14d67-a300-7000-bdbb-1b2c33245e9b")!!
    private val message = MessageId.of("6e87dff2-3cf6-5fb9-91b4-11756446af6a")!!
    private val source = SpeechSource(SourceDigest.of("a".repeat(64))!!, 41, 41, false)
    private val range = SpeechRange(0, 41, 2, false, true)
    private val chunk = listOf(SpeechChunk(0, 41, "x".repeat(41)))

    private fun speech(
        state: SpeechState,
        reason: SpeechReason? = null,
        provider: VoiceProvider = VoiceProvider.NOT_STARTED,
        stream: Int? = null,
        withSource: Boolean = false,
        chunks: List<SpeechChunk>? = null,
        voice: String? = null
    ): SpeechResult? = try {
        SpeechResult(
            speech,
            message,
            state,
            reason,
            stream?.let { VoiceStream.of(it) },
            if (withSource) source else null,
            if (withSource) range else null,
            chunks,
            voice,
            provider
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    @Test fun everySpeechOutcomeTheProducerMapsIsAccepted() {
        // wire.TTS_WIRE: (state, reason, provider) the bridge can emit, plus the unrecorded states and a client-mode text
        val table = listOf(
            speech(SpeechState.PREPARING, withSource = true, stream = 1),
            speech(
                SpeechState.PREPARING,
                provider = VoiceProvider.IN_FLIGHT,
                withSource = true,
                stream = 1
            ),
            speech(SpeechState.PREPARING),
            speech(
                SpeechState.STREAMING,
                provider = VoiceProvider.COMPLETED,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.COMPLETED,
                provider = VoiceProvider.COMPLETED,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.COMPLETED,
                provider = VoiceProvider.NOT_STARTED,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.STOPPED,
                provider = VoiceProvider.IN_FLIGHT,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.STOPPED,
                provider = VoiceProvider.COMPLETED,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.STOPPED,
                provider = VoiceProvider.UNKNOWN,
                stream = 3,
                withSource = true
            ),
            speech(SpeechState.ABORTED, SpeechReason.REVOKED, VoiceProvider.COMPLETED, 3, true),
            speech(SpeechState.ABORTED, SpeechReason.STALE, VoiceProvider.COMPLETED, 3, true),
            speech(
                SpeechState.ABORTED,
                SpeechReason.DISCONNECTED,
                VoiceProvider.NOT_STARTED,
                3,
                true
            ),
            speech(SpeechState.ABORTED, SpeechReason.TOO_LONG, VoiceProvider.COMPLETED, 3, true),
            speech(
                SpeechState.ABORTED,
                SpeechReason.ENCODE_FAILED,
                VoiceProvider.COMPLETED,
                3,
                true
            ),
            speech(
                SpeechState.FAILED,
                SpeechReason.TTS_UNAVAILABLE,
                VoiceProvider.NOT_STARTED,
                3,
                true
            ),
            speech(
                SpeechState.FAILED,
                SpeechReason.PROVIDER_FAILED,
                VoiceProvider.FAILED_AFTER_SEND,
                3,
                true
            ),
            speech(
                SpeechState.UNKNOWN,
                provider = VoiceProvider.UNKNOWN,
                stream = 3,
                withSource = true
            ),
            speech(
                SpeechState.TEXT,
                withSource = true,
                chunks = chunk,
                voice = "pNInz6obpgDQGcFmaJgB"
            ),
            speech(SpeechState.TEXT, withSource = true, chunks = chunk),
            speech(SpeechState.OFF),
            speech(SpeechState.NO_FINAL),
            speech(SpeechState.NOT_SPEAKABLE),
            speech(SpeechState.EXHAUSTED),
            speech(SpeechState.UNAVAILABLE, SpeechReason.BEYOND_INDEX),
            speech(SpeechState.UNAVAILABLE, SpeechReason.SOURCE_REFUSED)
        )
        table.forEachIndexed { index, value -> assertTrue("row $index", value != null) }
    }

    @Test fun theImpossibleSpeechCombinationsAreRefused() {
        val refused = mapOf(
            "reason on a completed speech" to
                speech(SpeechState.COMPLETED, SpeechReason.STALE, VoiceProvider.COMPLETED, 3, true),
            "aborted without a reason" to
                speech(SpeechState.ABORTED, null, VoiceProvider.COMPLETED, 3, true),
            "a failed reason on an aborted state" to
                speech(
                    SpeechState.ABORTED,
                    SpeechReason.TTS_UNAVAILABLE,
                    VoiceProvider.NOT_STARTED,
                    3,
                    true
                ),
            "an unavailable reason on a failed state" to
                speech(
                    SpeechState.FAILED,
                    SpeechReason.BEYOND_INDEX,
                    VoiceProvider.NOT_STARTED,
                    3,
                    true
                ),
            "unavailable without a reason" to speech(SpeechState.UNAVAILABLE),
            "chunks on a server state" to
                speech(SpeechState.PREPARING, chunks = chunk, withSource = true),
            "text without chunks" to speech(SpeechState.TEXT, withSource = true),
            "text without a range" to speech(SpeechState.TEXT, chunks = chunk),
            "text with a stream" to
                speech(SpeechState.TEXT, withSource = true, chunks = chunk, stream = 1),
            "text that was billed" to
                speech(
                    SpeechState.TEXT,
                    provider = VoiceProvider.COMPLETED,
                    withSource = true,
                    chunks = chunk
                ),
            "a voice hint on a server state" to
                speech(SpeechState.PREPARING, voice = "v", withSource = true),
            "an empty voice hint" to
                speech(SpeechState.TEXT, withSource = true, chunks = chunk, voice = ""),
            "a non-printable voice hint" to
                speech(SpeechState.TEXT, withSource = true, chunks = chunk, voice = "a\u0000b"),
            "an unrecorded state with a stream" to speech(SpeechState.OFF, stream = 1),
            "an unrecorded state with a range" to speech(SpeechState.EXHAUSTED, withSource = true),
            "an unrecorded state that was billed" to
                speech(SpeechState.NO_FINAL, provider = VoiceProvider.UNKNOWN),
            "streaming without a stream" to
                speech(
                    SpeechState.STREAMING,
                    provider = VoiceProvider.COMPLETED,
                    withSource = true
                ),
            "completed that is still in flight" to
                speech(
                    SpeechState.COMPLETED,
                    provider = VoiceProvider.IN_FLIGHT,
                    stream = 1,
                    withSource = true
                ),
            "unknown claimed not started" to
                speech(
                    SpeechState.UNKNOWN,
                    provider = VoiceProvider.NOT_STARTED,
                    stream = 1,
                    withSource = true
                ),
            "tts unavailable after a send" to
                speech(
                    SpeechState.FAILED,
                    SpeechReason.TTS_UNAVAILABLE,
                    VoiceProvider.COMPLETED,
                    3,
                    true
                ),
            "audio that was never produced" to
                speech(
                    SpeechState.STREAMING,
                    provider = VoiceProvider.FAILED_AFTER_SEND,
                    stream = 1,
                    withSource = true
                )
        )
        for ((name, value) in refused) assertTrue(name, value == null)
    }

    private fun checkSpeech(
        state: SpeechState,
        reason: SpeechReason?,
        provider: VoiceProvider,
        stream: Int?,
        withSource: Boolean,
        chunks: List<SpeechChunk>?,
        voice: String?
    ): Boolean {
        val value =
            speech(state, reason, provider, stream, withSource, chunks, voice) ?: return false
        val describe = value.toString()
        val reasonStates = setOf(SpeechState.ABORTED, SpeechState.FAILED, SpeechState.UNAVAILABLE)
        assertEquals(describe, state in reasonStates, reason != null)
        if (reason != null) assertEquals(describe, reason.state, state)
        assertEquals(describe, state == SpeechState.TEXT, chunks != null)
        if (voice != null) assertEquals(describe, SpeechState.TEXT, state)
        if (state.unrecorded) {
            assertTrue(describe, stream == null && !withSource && chunks == null)
            assertEquals(describe, VoiceProvider.NOT_STARTED, provider)
        }
        if (state == SpeechState.TEXT) {
            assertTrue(describe, withSource && stream == null)
            assertEquals(describe, VoiceProvider.NOT_STARTED, provider)
        }
        if (state == SpeechState.UNKNOWN) assertEquals(VoiceProvider.UNKNOWN, provider)
        if (state == SpeechState.STREAMING) assertTrue(describe, stream != null)
        val audio = state == SpeechState.STREAMING || state == SpeechState.COMPLETED
        if (audio) {
            assertTrue(
                describe,
                provider == VoiceProvider.COMPLETED || provider == VoiceProvider.NOT_STARTED
            )
        }
        if (provider == VoiceProvider.IN_FLIGHT) {
            assertTrue(describe, state == SpeechState.PREPARING || state == SpeechState.STOPPED)
        }
        if (reason == SpeechReason.TTS_UNAVAILABLE) {
            assertEquals(VoiceProvider.NOT_STARTED, provider)
        }
        return true
    }

    @Test fun theWholeSpeechProductSpaceOnlyAcceptsWhatTheRulesAllow() {
        var accepted = 0
        var total = 0
        val reasons = listOf<SpeechReason?>(null) + SpeechReason.entries
        val streams = listOf<Int?>(null, 1)
        val chunkOptions = listOf<List<SpeechChunk>?>(null, chunk)
        val voices = listOf<String?>(null, "v")
        for (state in SpeechState.entries) {
            for (reason in reasons) {
                for (provider in VoiceProvider.entries) {
                    for (stream in streams) {
                        for (withSource in listOf(false, true)) {
                            for (chunks in chunkOptions) {
                                for (voice in voices) {
                                    total++
                                    if (checkSpeech(
                                            state,
                                            reason,
                                            provider,
                                            stream,
                                            withSource,
                                            chunks,
                                            voice
                                        )
                                    ) {
                                        accepted++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(13 * 10 * 5 * 2 * 2 * 2 * 2, total)
        assertTrue("accepted $accepted of $total", accepted in 60..1_200)
    }

    @Test fun sourceAndRangeComeTogetherAndStayInsideTheProcessedPrefix() {
        assertTrue(speech(SpeechState.PREPARING, withSource = true) != null)
        // range inside the speakable end, done exactly at it
        assertTrue(runCatching { SpeechRange(5, 5, 0, false, false) }.isFailure)
        assertTrue(runCatching { SpeechRange(-1, 5, 0, false, false) }.isFailure)
        val limited = SpeechSource(SourceDigest.of("b".repeat(64))!!, 262_000, 262_000, true)
        assertTrue(
            runCatching {
                SpeechSource(SourceDigest.of("b".repeat(64))!!, 10, 11, false)
            }.isFailure
        )
        val bad = listOf(
            { SpeechRange(0, 100_000, 1, false, true) },
            { SpeechRange(0, 262_000, 1, false, false) },
            { SpeechRange(0, 261_999, 1, false, true) },
            { SpeechRange(0, 262_001, 1, false, true) }
        )
        for (build in bad) {
            assertTrue(
                runCatching {
                    SpeechResult(
                        speech, message, SpeechState.PREPARING, null, null, limited,
                        build(), null, null, VoiceProvider.NOT_STARTED
                    )
                }.isFailure
            )
        }
        val atTheEnd =
            SpeechResult(
                speech, message, SpeechState.PREPARING, null, null, limited,
                SpeechRange(
                    261_000,
                    262_000,
                    1,
                    false,
                    true
                ),
                null, null, VoiceProvider.NOT_STARTED
            )
        assertTrue(atTheEnd.range!!.done && atTheEnd.source!!.limited)
        // a source without a range, or a range without a source, is not a truthful answer
        assertTrue(
            runCatching {
                SpeechResult(
                    speech, message, SpeechState.PREPARING, null, null, limited, null,
                    null, null, VoiceProvider.NOT_STARTED
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                SpeechResult(
                    speech, message, SpeechState.PREPARING, null, null, null, range, null,
                    null, VoiceProvider.NOT_STARTED
                )
            }.isFailure
        )
    }

    @Test fun chunksTileTheRangeExactlyAndAreBounded() {
        fun text(vararg spans: Triple<Int, Int, String>) = speech(
            SpeechState.TEXT,
            withSource = true,
            chunks = spans.map {
                SpeechChunk(it.first, it.second, it.third)
            }
        )
        assertTrue(text(Triple(0, 41, "x".repeat(41))) != null)
        assertTrue(text(Triple(0, 20, "x".repeat(20)), Triple(20, 41, "x".repeat(21))) != null)
        assertTrue(
            "gap",
            text(Triple(0, 20, "x".repeat(20)), Triple(21, 41, "x".repeat(20))) == null
        )
        assertTrue(
            "overlap",
            text(Triple(0, 21, "x".repeat(21)), Triple(20, 41, "x".repeat(21))) == null
        )
        assertTrue("short of the range", text(Triple(0, 40, "x".repeat(40))) == null)
        assertTrue("not starting at the range", text(Triple(1, 41, "x".repeat(40))) == null)
        assertTrue(runCatching { SpeechChunk(0, 451, "x".repeat(451)) }.isFailure)
        assertTrue(runCatching { SpeechChunk(0, 3, "ab") }.isFailure)
        assertTrue(
            "text must count code points, not UTF-16 units",
            runCatching {
                SpeechChunk(0, 1, "😀")
            }.isSuccess
        )
        assertTrue(runCatching { SpeechChunk(0, 2, "😀") }.isFailure)
        assertTrue(
            "a lone surrogate is not text",
            runCatching {
                SpeechChunk(0, 1, "\uD83D")
            }.isFailure
        )
        assertFalse(SpeechChunk(0, 1, "😀").toString().contains("\uD83D"))
    }
}
