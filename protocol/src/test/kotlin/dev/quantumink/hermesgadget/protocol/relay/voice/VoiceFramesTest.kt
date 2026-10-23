package dev.quantumink.hermesgadget.protocol.relay.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel-5 frames in BOTH directions. Real captured streams (uplink PCM and Opus from the registered hub, downlink PCM/Opus/ABORT from the registered
 * Speech) are re-parsed and re-assembled byte for byte; every CONSTRUCTED negative below is labelled as such and exercises the real validators.
 */
class VoiceFramesTest {
    private fun parsed(raw: ByteArray, direction: VoiceDirection): VoiceFrame {
        val result = VoiceFrame.parse(raw, direction)
        assertTrue(result.toString(), result is VoiceParse.Parsed)
        return (result as VoiceParse.Parsed).frame
    }

    private fun refusal(raw: ByteArray, direction: VoiceDirection): FrameRefusal {
        val result = VoiceFrame.parse(raw, direction)
        assertTrue(result.toString(), result is VoiceParse.Refused)
        return (result as VoiceParse.Refused).reason
    }

    private val stream = VoiceStream.of(1)!!
    private val head = "4f707573486561640101380180 3e0000000000".replace(" ", "").let {
        VoiceVectors.hex(it)
    }
    private val packet20 = byteArrayOf(0x48, 5, 6, 7, 8, 9, 10, 11, 12, 13)

    private fun frame(
        kind: VoiceFrameKind,
        seq: Int,
        body: ByteArray,
        streamId: Int = 1
    ): VoiceFrame = VoiceFrame.create(kind, streamId, seq, body)!!

    // -- real captured UPLINK streams ------------------------------------------------------------------------------------------

    private fun uplinkGroup(file: String, case: String): List<ByteArray> =
        VoiceVectors.binary(file).filter {
            it["case"]!!.jsonPrimitive.content == case &&
                "raw_hex" in it
        }.map { VoiceVectors.hex(it["raw_hex"]!!.jsonPrimitive.content) }

    private fun finishParams(id: String): JsonObject = VoiceVectors.byId(id).params

    @Test fun capturedPcmUplinkParsesAndTheAssemblyReproducesEveryByteAndTheFinishCounts() {
        for ((case, finishId) in listOf(
            "uplink-pcm/stream" to "uplink-pcm/finish-ok-queued",
            "uplink-pcm-floor/stream" to "uplink-pcm-floor/finish-ok-queued"
        )) {
            val raws = uplinkGroup("v4-input-uplink-pcm.json", case)
            assertTrue(raws.size >= 13)
            val params = finishParams(finishId)
            var capture = UplinkCapture.start(
                VoiceStream.of(params["stream"]!!.jsonPrimitive.int)!!,
                CaptureFormat.PCM16
            )
            raws.forEachIndexed { index, raw ->
                val frame = parsed(raw, VoiceDirection.UPLINK)
                assertEquals(VoiceFrameKind.UPLINK_AUDIO, frame.kind)
                assertEquals(index, frame.sequence)
                val step = capture.pcm(frame.body)
                assertNull(step.refusal)
                assertArrayEquals("$case frame $index", raw, step.frame!!.encode())
                capture = step.capture
            }
            val counts = capture.counts()!!
            assertEquals(params["frames"]!!.jsonPrimitive.int, counts.frames)
            assertEquals(params["duration_ms"]!!.jsonPrimitive.int, counts.durationMs)
            assertFalse(counts.tooShort)
        }
        // the floor case is the stock formula: 8,010 bytes -> 250 ms, never rounded up
        val floor = finishParams("uplink-pcm-floor/finish-ok-queued")
        assertEquals(250, floor["duration_ms"]!!.jsonPrimitive.int)
        assertEquals(
            260,
            finishParams("uplink-pcm/finish-ok-queued")["duration_ms"]!!.jsonPrimitive.int
        )
    }

    @Test fun capturedOpusUplinkFromTheRealEncoderParsesAndReassembles() {
        val raws = uplinkGroup("v4-input-uplink-opus.json", "uplink-opus/stream")
        assertTrue(raws.size > 10)
        val params = finishParams("uplink-opus/finish-ok-queued")
        var capture = UplinkCapture.start(
            VoiceStream.of(params["stream"]!!.jsonPrimitive.int)!!,
            CaptureFormat.OPUS
        )
        raws.forEachIndexed { index, raw ->
            val frame = parsed(raw, VoiceDirection.UPLINK)
            assertEquals(index, frame.sequence)
            val step = if (index == 0) {
                assertEquals(VoiceFrameKind.UPLINK_OPUS_HEADER, frame.kind)
                capture.header(frame.body)
            } else {
                assertEquals(VoiceFrameKind.UPLINK_AUDIO, frame.kind)
                capture.opus(frame.body)
            }
            assertNull("frame $index ${step.refusal}", step.refusal)
            assertArrayEquals(raw, step.frame!!.encode())
            capture = step.capture
        }
        val counts = capture.counts()!!
        assertEquals(params["frames"]!!.jsonPrimitive.int, counts.frames)
        assertEquals(params["duration_ms"]!!.jsonPrimitive.int, counts.durationMs)
        assertEquals(counts.frames * 20, counts.durationMs)
    }

    // -- real captured DOWNLINK streams ----------------------------------------------------------------------------------------

    private class Downlink(val label: String, val format: CaptureFormat, val raws: List<ByteArray>)

    private fun f1Raw(frame: JsonObject): ByteArray {
        val body = VoiceVectors.hex(frame["body_hex"]!!.jsonPrimitive.content)
        val seq = frame["seq"]!!.jsonPrimitive.int
        return byteArrayOf(
            5,
            frame["stream"]!!.jsonPrimitive.int.toByte(),
            seq.toByte(),
            (
                seq ushr
                    8
                ).toByte(),
            frame["kind"]!!.jsonPrimitive.int.toByte()
        ) +
            body
    }

    private fun downlinkStreams(): List<Downlink> {
        val out = ArrayList<Downlink>()
        for ((file, format) in listOf(
            "v3-downlink-pcm-frames.json" to CaptureFormat.PCM16,
            "v3-downlink-opus-frames.json" to CaptureFormat.OPUS,
            "v3-downlink-abort-frame.json" to CaptureFormat.PCM16
        )) {
            out.add(
                Downlink(
                    "F1 $file",
                    format,
                    VoiceVectors.json(file)["frames"]!!.jsonArray.map {
                        f1Raw(it.jsonObject)
                    }
                )
            )
        }
        for (file in VoiceVectors.v4Files.filter { it.startsWith("v4-output") }) {
            for (group in VoiceVectors.binary(file).filter {
                it["direction"]?.jsonPrimitive?.content ==
                    "downlink" &&
                    "frames" in it
            }) {
                val id = group["case"]!!.jsonPrimitive.content
                val raws = group["frames"]!!.jsonArray.map {
                    VoiceVectors.hex(it.jsonObject["raw_hex"]!!.jsonPrimitive.content)
                }
                // the negotiated format is visible in the stream itself: only an Opus clip has the 0x11 header
                val format = if (raws.any {
                        it[4].toInt() ==
                            VoiceFrameKind.DOWNLINK_OPUS_HEADER.code
                    }
                ) {
                    CaptureFormat.OPUS
                } else {
                    CaptureFormat.PCM16
                }
                out.add(Downlink("$file::$id", format, raws))
            }
        }
        return out
    }

    @Test fun everyCapturedDownlinkStreamIsAValidStreamEndingExactlyAsRecorded() {
        val streams = downlinkStreams()
        assertTrue("only ${streams.size}", streams.size >= 12)
        var completed = 0
        var aborted = 0
        for (captured in streams) {
            val frames = captured.raws.map { parsed(it, VoiceDirection.DOWNLINK) }
            assertTrue(captured.label, frames.isNotEmpty())
            // The registered socket reuses a small stream number for each speech of a scenario, so one group can hold several clips back to back;
            // each clip ends at its terminator and is read by a fresh stream (the client opens one per SpeechResult).
            var reader = DownlinkStream.open(
                VoiceStream.of(frames.first().stream)!!,
                captured.format
            )
            var terminal: DownlinkEvent? = null
            for ((index, frame) in frames.withIndex()) {
                val step = reader.accept(frame)
                reader = step.stream
                val event = step.event
                assertFalse(
                    "${captured.label} frame $index $event",
                    event is DownlinkEvent.Violation || event is DownlinkEvent.Ignored
                )
                if (event is DownlinkEvent.Completed || event is DownlinkEvent.Aborted) {
                    terminal = event
                    if (event is DownlinkEvent.Completed) {
                        completed++
                        assertEquals(captured.label, frame.endCount, event.audioFrames.toLong())
                    } else {
                        aborted++
                        assertEquals(
                            captured.label,
                            frame.abortCode,
                            (event as DownlinkEvent.Aborted).code
                        )
                    }
                    assertTrue(reader.closed)
                    reader = DownlinkStream.open(VoiceStream.of(frame.stream)!!, captured.format)
                }
            }
            assertNotNull(captured.label, terminal)
            assertTrue(
                "${captured.label}: the last frame is a terminator",
                frames.last().kind.code >= VoiceFrameKind.DOWNLINK_END.code
            )
        }
        assertTrue("completed $completed aborted $aborted", completed >= 4 && aborted >= 8)
    }

    @Test fun capturedAbortCodesMatchTheirRecordedReasons() {
        fun codeOf(label: String): AbortCode {
            val captured = downlinkStreams().first { it.label.endsWith(label) }
            val last = parsed(captured.raws.last(), VoiceDirection.DOWNLINK)
            assertEquals(VoiceFrameKind.DOWNLINK_ABORT, last.kind)
            return last.abortCode!!
        }
        assertEquals(AbortCode.TOO_LONG, codeOf("v3-downlink-abort-frame.json"))
        assertEquals(AbortCode.REVOKED, codeOf("speak/ok-revoked-first-answer"))
        assertEquals(AbortCode.STALE, codeOf("speak/ok-stale-epoch-first-answer"))
        assertEquals(AbortCode.STALE, codeOf("speak/ok-stale-rebind-first-answer"))
        assertEquals(AbortCode.ENCODE_FAILED, codeOf("speak/ok-encode-failed-first-answer"))
        assertEquals(AbortCode.PROVIDER_FAILED, codeOf("speak/ok-tts-unavailable-first-answer"))
        assertEquals(AbortCode.STOPPED, codeOf("stop/ok-stopped-mid-stream"))
        assertEquals(AbortCode.STOPPED, codeOf("stop/ok-stopped-provider-in-flight"))
    }

    @Test fun theCapturedOpusClipHasTheHeaderAtZeroPacketsFromOneAndEndAtTheNextSequence() {
        val opus = downlinkStreams().first { it.label.contains("opus-frames") }
        val frames = opus.raws.map { parsed(it, VoiceDirection.DOWNLINK) }
        assertEquals(VoiceFrameKind.DOWNLINK_OPUS_HEADER, frames.first().kind)
        assertEquals(0, frames.first().sequence)
        assertEquals(
            (1..frames.size - 2).toList(),
            frames.subList(1, frames.size - 1).map {
                it.sequence
            }
        )
        assertEquals(frames.size - 1, frames.last().sequence)
        assertEquals((frames.size - 2).toLong(), frames.last().endCount)
    }

    // -- CONSTRUCTED negatives (labelled): every one runs the real validator -------------------------------------------------------

    private fun run(format: CaptureFormat, vararg frames: VoiceFrame): List<DownlinkEvent> {
        var reader = DownlinkStream.open(stream, format)
        return frames.map {
            val step = reader.accept(it)
            reader = step.stream
            step.event
        }
    }

    private fun pcm(seq: Int, size: Int = 1_280) = frame(
        VoiceFrameKind.DOWNLINK_AUDIO,
        seq,
        ByteArray(size) {
            1
        }
    )

    private fun end(seq: Int, count: Long) = frame(
        VoiceFrameKind.DOWNLINK_END,
        seq,
        byteArrayOf(
            (count ushr 24).toByte(),
            (count ushr 16).toByte(),
            (count ushr 8).toByte(),
            count.toByte()
        )
    )

    private fun abort(seq: Int, code: Int) =
        frame(VoiceFrameKind.DOWNLINK_ABORT, seq, byteArrayOf(code.toByte()))

    private fun violation(event: DownlinkEvent): StreamViolation =
        (event as DownlinkEvent.Violation).reason

    @Test fun constructedSequenceGapDuplicateAndReorderCloseTheStream() {
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), pcm(2)).last())
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), pcm(0)).last())
        )
        assertEquals(StreamViolation.SEQUENCE, violation(run(CaptureFormat.PCM16, pcm(1)).last()))
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), pcm(1), pcm(1)).last())
        )
        // after a violation nothing is repaired: later frames are ignored as closed
        val events = run(CaptureFormat.PCM16, pcm(0), pcm(2), pcm(1), end(2, 2))
        assertEquals(StreamViolation.SEQUENCE, violation(events[1]))
        assertEquals(FrameIgnored.CLOSED, (events[2] as DownlinkEvent.Ignored).why)
        assertEquals(FrameIgnored.CLOSED, (events[3] as DownlinkEvent.Ignored).why)
    }

    @Test fun constructedOpusHeaderRulesAreStrict() {
        val header = frame(VoiceFrameKind.DOWNLINK_OPUS_HEADER, 0, head)
        val audio = { seq: Int -> frame(VoiceFrameKind.DOWNLINK_AUDIO, seq, packet20) }
        assertEquals(
            StreamViolation.HEADER_REQUIRED,
            violation(run(CaptureFormat.OPUS, audio(0)).last())
        )
        assertEquals(
            StreamViolation.HEADER_REQUIRED,
            violation(run(CaptureFormat.OPUS, audio(1)).last())
        )
        assertEquals(
            StreamViolation.HEADER_REPEATED,
            violation(
                run(
                    CaptureFormat.OPUS,
                    header,
                    frame(VoiceFrameKind.DOWNLINK_OPUS_HEADER, 1, head)
                ).last()
            )
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(
                run(CaptureFormat.OPUS, frame(VoiceFrameKind.DOWNLINK_OPUS_HEADER, 1, head)).last()
            )
        )
        assertEquals(
            StreamViolation.HEADER_ON_PCM,
            violation(run(CaptureFormat.PCM16, header).last())
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.OPUS, header, audio(0)).last())
        ) // the first packet is 1, not 0
        // a header with no audio before END is not a clip the producer ever sends
        assertEquals(
            StreamViolation.HEADER_WITHOUT_AUDIO,
            violation(run(CaptureFormat.OPUS, header, end(1, 0)).last())
        )
        val ok = run(CaptureFormat.OPUS, header, audio(1), audio(2), end(3, 2))
        assertEquals(2, (ok.last() as DownlinkEvent.Completed).audioFrames)
    }

    @Test fun constructedAudioPayloadBoundsAndOpusPacketRules() {
        assertNull(
            "no frame carries more than the larger payload bound",
            VoiceFrame.create(VoiceFrameKind.DOWNLINK_AUDIO, 1, 0, ByteArray(1_282))
        )
        assertEquals(
            StreamViolation.AUDIO_SIZE,
            violation(run(CaptureFormat.PCM16, pcm(0, 1_279)).last())
        )
        assertEquals(
            StreamViolation.AUDIO_SIZE,
            violation(run(CaptureFormat.PCM16, pcm(0, 3)).last())
        )
        assertEquals(
            StreamViolation.AUDIO_SIZE,
            violation(run(CaptureFormat.PCM16, pcm(0, 1)).last())
        )
        assertTrue(
            run(CaptureFormat.PCM16, pcm(0, 2), pcm(1, 1_280)).none {
                it is DownlinkEvent.Violation
            }
        )
        val header = frame(VoiceFrameKind.DOWNLINK_OPUS_HEADER, 0, head)
        assertEquals(
            StreamViolation.OPUS_PACKET,
            violation(
                run(
                    CaptureFormat.OPUS,
                    header,
                    frame(VoiceFrameKind.DOWNLINK_AUDIO, 1, byteArrayOf(0x18, 1, 2))
                ).last()
            )
        ) // 60 ms
        assertEquals(
            StreamViolation.OPUS_PACKET,
            violation(
                run(
                    CaptureFormat.OPUS,
                    header,
                    frame(VoiceFrameKind.DOWNLINK_AUDIO, 1, byteArrayOf(0x4b, 0))
                ).last()
            )
        ) // code 3, 0 frames
        // 1,276 bytes is a legal PCM frame size but never an Opus packet: the Opus stream refuses it, the 1,275-byte packet passes
        assertEquals(
            StreamViolation.OPUS_PACKET,
            violation(
                run(
                    CaptureFormat.OPUS,
                    header,
                    frame(
                        VoiceFrameKind.DOWNLINK_AUDIO,
                        1,
                        ByteArray(1_276) {
                            0x48
                        }
                    )
                ).last()
            )
        )
        assertTrue(
            run(
                CaptureFormat.OPUS,
                header,
                frame(
                    VoiceFrameKind.DOWNLINK_AUDIO,
                    1,
                    ByteArray(1_275) {
                        0x48
                    }
                )
            ).none { it is DownlinkEvent.Violation }
        )
    }

    @Test fun constructedEndAndAbortRules() {
        assertEquals(
            StreamViolation.END_COUNT,
            violation(run(CaptureFormat.PCM16, pcm(0), end(1, 2)).last())
        )
        assertEquals(
            StreamViolation.END_COUNT,
            violation(run(CaptureFormat.PCM16, pcm(0), end(1, 0)).last())
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), end(2, 1)).last())
        )
        // END is a 32-bit big-endian count: 256 is 00 00 01 00, never 01 00
        val count256 = (0 until 256).map { pcm(it, 2) }.toTypedArray()
        val ok = run(CaptureFormat.PCM16, *count256, end(256, 256))
        assertEquals(256, (ok.last() as DownlinkEvent.Completed).audioFrames)
        assertEquals(256L, end(256, 256).endCount)
        assertEquals(
            4_294_967_295L,
            frame(
                VoiceFrameKind.DOWNLINK_END,
                0,
                ByteArray(4) {
                    -1
                }
            ).endCount
        )
        // silent: END alone at sequence 0 with count 0
        val silent = run(CaptureFormat.PCM16, end(0, 0)).last() as DownlinkEvent.Completed
        assertTrue(silent.silent)
        // ABORT: any point, the next sequence, a known code
        assertEquals(
            AbortCode.STOPPED,
            (run(CaptureFormat.PCM16, abort(0, 1)).last() as DownlinkEvent.Aborted).code
        )
        assertEquals(
            AbortCode.STALE,
            (
                run(
                    CaptureFormat.PCM16,
                    pcm(0),
                    pcm(1),
                    abort(2, 3)
                ).last() as DownlinkEvent.Aborted
                ).code
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), abort(0, 1)).last())
        )
        assertEquals(
            StreamViolation.SEQUENCE,
            violation(run(CaptureFormat.PCM16, pcm(0), abort(5, 1)).last())
        )
        // after the terminator the stream is retired: nothing more is read
        val after = run(CaptureFormat.PCM16, pcm(0), end(1, 1), pcm(2), abort(2, 1))
        assertEquals(FrameIgnored.CLOSED, (after[2] as DownlinkEvent.Ignored).why)
        assertEquals(FrameIgnored.CLOSED, (after[3] as DownlinkEvent.Ignored).why)
    }

    @Test fun framesOfOtherStreamsAreIgnoredAndNeverTouchTheState() {
        var reader = DownlinkStream.open(VoiceStream.of(3)!!, CaptureFormat.PCM16)
        for (other in listOf(0, 1, 2, 4, 255)) {
            val step = reader.accept(
                frame(VoiceFrameKind.DOWNLINK_AUDIO, 99, ByteArray(2), streamId = other)
            )
            assertEquals(FrameIgnored.OTHER_STREAM, (step.event as DownlinkEvent.Ignored).why)
            reader = step.stream
        }
        assertEquals(0, reader.audioFrames)
        assertFalse(reader.closed)
        assertTrue(
            reader.accept(
                frame(VoiceFrameKind.DOWNLINK_AUDIO, 0, ByteArray(2), streamId = 3)
            ).event is DownlinkEvent.Audio
        )
    }

    @Test fun theHardFifteenThousandAudioFrameBoundIncludesOpusPrimingAndNeverTrims() {
        // PCM: 15,000 frames of 2 bytes, then END(15000) is legal; the 15,001st AUDIO frame is a violation
        var reader = DownlinkStream.open(stream, CaptureFormat.PCM16)
        for (seq in 0 until 15_000) reader = reader.accept(pcm(seq, 2)).stream
        assertEquals(15_000, reader.audioFrames)
        val over = reader.accept(pcm(15_000, 2))
        assertEquals(StreamViolation.TOO_MANY_FRAMES, violation(over.event))
        val fine = reader.accept(end(15_000, 15_000)).event as DownlinkEvent.Completed
        assertEquals(15_000, fine.audioFrames)
        // Opus: header (seq 0, not AUDIO) + 15,000 packets (seq 1..15,000) + END at seq 15,001 is legal; packet 15,001 is not
        var opus = DownlinkStream.open(stream, CaptureFormat.OPUS)
        opus = opus.accept(frame(VoiceFrameKind.DOWNLINK_OPUS_HEADER, 0, head)).stream
        for (seq in 1..15_000) {
            opus =
                opus.accept(frame(VoiceFrameKind.DOWNLINK_AUDIO, seq, packet20)).stream
        }
        assertEquals(15_000, opus.audioFrames)
        assertEquals(300_000L, opus.durationMs)
        assertEquals(
            StreamViolation.TOO_MANY_FRAMES,
            violation(opus.accept(frame(VoiceFrameKind.DOWNLINK_AUDIO, 15_001, packet20)).event)
        )
        val last = opus.accept(end(15_001, 15_000)).event as DownlinkEvent.Completed
        assertEquals(15_000, last.audioFrames)
        assertEquals(300_000L, last.durationMs)
        // 15,000 frames is not a way past the byte cap either: PCM past 9,600,000 bytes is its own violation
        var big = DownlinkStream.open(stream, CaptureFormat.PCM16)
        for (seq in 0 until 7_500) big = big.accept(pcm(seq)).stream
        assertEquals(9_600_000, big.audioBytes)
        assertEquals(300_000L, big.durationMs)
        assertEquals(StreamViolation.TOO_MANY_BYTES, violation(big.accept(pcm(7_500, 2)).event))
    }

    // -- raw frame parser -------------------------------------------------------------------------------------------------------------

    @Test fun theParserRefusesWrongDirectionUnknownKindAndMalformedBodies() {
        val downlinkAudio = frame(VoiceFrameKind.DOWNLINK_AUDIO, 0, ByteArray(2)).encode()
        val uplinkAudio = frame(VoiceFrameKind.UPLINK_AUDIO, 0, ByteArray(2)).encode()
        assertEquals(FrameRefusal.WRONG_DIRECTION, refusal(downlinkAudio, VoiceDirection.UPLINK))
        assertEquals(FrameRefusal.WRONG_DIRECTION, refusal(uplinkAudio, VoiceDirection.DOWNLINK))
        assertEquals(
            FrameRefusal.UNKNOWN_KIND,
            refusal(byteArrayOf(5, 1, 0, 0, 0x7f, 1), VoiceDirection.DOWNLINK)
        )
        assertEquals(
            FrameRefusal.UNKNOWN_KIND,
            refusal(byteArrayOf(5, 1, 0, 0, 0x00, 1), VoiceDirection.UPLINK)
        )
        assertEquals(FrameRefusal.NO_KIND, refusal(byteArrayOf(5, 1, 0, 0), VoiceDirection.UPLINK))
        assertEquals(
            FrameRefusal.BAD_AUDIO_SIZE,
            refusal(byteArrayOf(5, 1, 0, 0, 0x02), VoiceDirection.UPLINK)
        )
        assertEquals(
            FrameRefusal.BAD_AUDIO_SIZE,
            refusal(byteArrayOf(5, 1, 0, 0, 0x02) + ByteArray(1_281), VoiceDirection.UPLINK)
        )
        for (size in listOf(0, 3, 5)) {
            assertEquals(
                FrameRefusal.BAD_END_BODY,
                refusal(byteArrayOf(5, 1, 0, 0, 0x13) + ByteArray(size), VoiceDirection.DOWNLINK)
            )
        }
        for (body in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(7), byteArrayOf(1, 1))) {
            assertEquals(
                FrameRefusal.BAD_ABORT_BODY,
                refusal(byteArrayOf(5, 1, 0, 0, 0x14) + body, VoiceDirection.DOWNLINK)
            )
        }
        for (code in 1..6) assertNotNull(AbortCode.of(code))
        assertNull(AbortCode.of(0))
        assertNull(AbortCode.of(7))
        assertEquals(
            FrameRefusal.OVERSIZE,
            refusal(byteArrayOf(5, 1, 0, 0, 0x02) + ByteArray(1 shl 20), VoiceDirection.UPLINK)
        )
    }

    @Test fun theOpusHeaderIsTheStrictMonoOpusHeadOfTheProducer() {
        val good = head
        assertEquals(19, good.size)
        assertNotNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, good))
        fun mutated(index: Int, value: Int) = good.copyOf().also { it[index] = value.toByte() }
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, good.copyOf(18)))
        assertNull(
            VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, good + byteArrayOf(0))
        )
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, mutated(0, 'X'.code)))
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, mutated(8, 2)))
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, mutated(9, 2)))
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_OPUS_HEADER, 1, 0, mutated(18, 1)))
        assertEquals(
            FrameRefusal.BAD_OPUS_HEADER,
            refusal(byteArrayOf(5, 1, 0, 0, 0x01) + good.copyOf(18), VoiceDirection.UPLINK)
        )
        assertEquals(
            FrameRefusal.BAD_OPUS_HEADER,
            refusal(byteArrayOf(5, 1, 0, 0, 0x11) + mutated(9, 2), VoiceDirection.DOWNLINK)
        )
    }

    @Test fun framesOwnTheirBytesAndTheHeaderIsLittleEndianWithoutWrap() {
        val body = ByteArray(4) { 9 }
        val frame = frame(VoiceFrameKind.UPLINK_AUDIO, 0x1234, body)
        body[0] = 0
        assertEquals(9, frame.body[0].toInt())
        frame.body[1] = 0
        assertEquals(9, frame.body[1].toInt())
        val raw = frame.encode()
        assertArrayEquals(byteArrayOf(5, 1, 0x34, 0x12, 0x02, 9, 9, 9, 9), raw)
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_AUDIO, 1, 65_536, ByteArray(2)))
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_AUDIO, 1, -1, ByteArray(2)))
        assertNull(VoiceFrame.create(VoiceFrameKind.UPLINK_AUDIO, 256, 0, ByteArray(2)))
        assertFalse(frame.toString().contains("9, 9"))
        assertEquals(frame, parsed(raw, VoiceDirection.UPLINK))
    }

    // -- uplink assembly caps ---------------------------------------------------------------------------------------------------------

    @Test fun constructedUplinkCapsRefuseBeforeAFrameExists() {
        var pcm = UplinkCapture.start(stream, CaptureFormat.PCM16)
        repeat(3_000) { pcm = pcm.pcm(ByteArray(640)).capture }
        assertEquals(60_000, pcm.durationMs)
        assertEquals(1_920_000, pcm.bytes)
        val over = pcm.pcm(ByteArray(2))
        assertEquals(UplinkRefusal.TOO_MANY_FRAMES, over.refusal)
        assertNull(over.frame)
        assertEquals(pcm.frames, over.capture.frames)
        for (bad in listOf(
            0,
            1,
            3,
            641,
            642,
            1_280
        )) {
            assertEquals(
                bad.toString(),
                UplinkRefusal.PCM_SIZE,
                UplinkCapture.start(stream, CaptureFormat.PCM16).pcm(ByteArray(bad)).refusal
            )
        }
        assertEquals(
            UplinkRefusal.WRONG_KIND_FOR_FORMAT,
            UplinkCapture.start(stream, CaptureFormat.PCM16).header(head).refusal
        )
        assertEquals(
            UplinkRefusal.WRONG_KIND_FOR_FORMAT,
            UplinkCapture.start(stream, CaptureFormat.OPUS).pcm(ByteArray(640)).refusal
        )

        var opus = UplinkCapture.start(stream, CaptureFormat.OPUS)
        assertEquals(UplinkRefusal.HEADER_MISSING, opus.opus(packet20).refusal)
        assertEquals(UplinkRefusal.HEADER_INVALID, opus.header(ByteArray(19)).refusal)
        opus = opus.header(head).capture
        assertEquals(UplinkRefusal.WRONG_KIND_FOR_FORMAT, opus.header(head).refusal)
        assertEquals(UplinkRefusal.OPUS_PACKET, opus.opus(byteArrayOf(0x18, 0, 0)).refusal)
        assertEquals(UplinkRefusal.OPUS_PACKET, opus.opus(ByteArray(0)).refusal)
        assertEquals(UplinkRefusal.OPUS_PACKET, opus.opus(ByteArray(1_276) { 0x48 }).refusal)
        val big = ByteArray(1_275) { 0x48 }
        var filled = opus
        var count = 0
        while (filled.bytes + big.size <= 1_000_000) {
            filled = filled.opus(big).capture
            count++
        }
        assertEquals(784, count)
        assertEquals(UplinkRefusal.TOO_MANY_BYTES, filled.opus(big).refusal)
        assertEquals(count * 20, filled.counts()!!.durationMs)
    }

    @Test fun finishCountsFollowTheStockFormulaAndAShortRecordingIsNotFinished() {
        var pcm = UplinkCapture.start(stream, CaptureFormat.PCM16)
        assertNull(pcm.counts())
        repeat(12) { pcm = pcm.pcm(ByteArray(640)).capture }
        pcm = pcm.pcm(ByteArray(330)).capture
        // floor(8,010 / 32), never ceil
        assertEquals(FinishCounts(13, 250).durationMs, pcm.counts()!!.durationMs)
        assertFalse(pcm.counts()!!.tooShort)
        var short = UplinkCapture.start(stream, CaptureFormat.PCM16)
        repeat(12) { short = short.pcm(ByteArray(640)).capture }
        short = short.pcm(ByteArray(318)).capture // 7,998 bytes -> 249 ms
        assertEquals(249, short.counts()!!.durationMs)
        assertTrue(short.counts()!!.tooShort)
        // no header, no packets
        assertNull(UplinkCapture.start(stream, CaptureFormat.OPUS).counts())
    }
}
