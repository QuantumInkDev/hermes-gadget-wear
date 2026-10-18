package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test
    fun wrongTurnTranscriptAndStatusCannotMakeProgress() {
        val conversation = Conversation()
        conversation.setPaired(true)
        conversation.sendText("synthetic request", 0)
        conversation.receive(message("turn.start", "current"), 1)
        conversation.receive(message("transcript", "wrong", "hostile synthetic secret"), 2)
        conversation.receive(message("status", "wrong", "hostile synthetic secret"), 3)
        assertEquals("", conversation.state.transcript)
        assertEquals("", conversation.state.status)
    }

    @Test
    fun waitingWarningUsesObservedProgressAndNeverCancelsOrReplaysAnOldReply() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        val conversation =
            Conversation(previousReply = "old synthetic secret", diagnostics = diagnostics)
        conversation.setPaired(true)
        assertEquals(RequestStage.IDLE, diagnostics.snapshot(0).requestStage)
        conversation.receive(message("reply", "old", "old synthetic secret"), 1)
        assertEquals(RequestStage.IDLE, diagnostics.snapshot(1).requestStage)
        conversation.sendText("new synthetic secret", 10)
        conversation.receive(message("turn.start", "current"), 20)
        assertEquals(TurnCorrelation.OBSERVED, diagnostics.snapshot(20).turnCorrelation)
        assertFalse(diagnostics.snapshot(30019).stalled)
        assertTrue(conversation.tick(30020).isEmpty())
        assertTrue(diagnostics.snapshot(30020).stalled)
        assertTrue(conversation.busy)
        conversation.receive(message("turn.start", "current"), 30021)
        assertTrue(diagnostics.snapshot(30021).stalled)
        conversation.receive(message("status", "wrong", "hostile synthetic secret"), 30021)
        assertTrue(diagnostics.snapshot(30021).stalled)
        conversation.receive(message("status", "current", "hostile synthetic secret"), 30022)
        assertFalse(diagnostics.snapshot(30022).stalled)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(30022).requestStage)
        conversation.receive(message("transcript", "current", "hostile synthetic secret"), 30023)
        assertEquals(RequestStage.TRANSCRIBED, diagnostics.snapshot(30023).requestStage)
        conversation.receive(
            Message.create(
                "prompt",
                buildJsonObject {
                    put("id", "synthetic-prompt")
                    put("turn", "wrong")
                    put("title", "hostile synthetic secret")
                    put("text", "hostile synthetic secret")
                }
            ),
            30023
        )
        assertEquals(null, conversation.state.prompt)
        conversation.receive(message("reply.delta", "current", "hostile synthetic secret"), 30024)
        assertEquals(RequestStage.RECEIVING, diagnostics.snapshot(30024).requestStage)
        conversation.receive(message("turn.end", "current"), 30025)
        assertEquals(RequestStage.COMPLETE, diagnostics.snapshot(30025).requestStage)
        val completedElapsed = diagnostics.snapshot(30025).elapsedMillis
        assertEquals(completedElapsed, diagnostics.snapshot(99999).elapsedMillis)
        conversation.sendText("second synthetic request", 100000)
        conversation.receive(message("turn.start", "current"), 100001)
        conversation.receive(message("reply", "current", "replayed synthetic secret"), 100002)
        conversation.receive(message("turn.end", "current"), 100003)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(100003).requestStage)
        assertFalse(
            ConnectionDiagnosticsReport.encode(diagnostics.snapshot(100004)).contains("secret")
        )
    }

    @Test
    fun ringCoalescesTicksAndPacketsClampsTimeAndResetsWithoutNegativeDurations() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.PHONE_RELAY, 100)
        diagnostics.startRequest(RequestStage.RECORDING, 100)
        diagnostics.request(RequestStage.UPLOADING, 101)
        repeat(1000) {
            diagnostics.request(RequestStage.UPLOADING, 102L + it)
            diagnostics.snapshot(102L + it)
        }
        assertEquals(3, diagnostics.snapshot(1102).events.size)
        repeat(200) {
            diagnostics.connection(
                if (it % 2 ==
                    0
                ) {
                    ConnectionStage.AUTHENTICATING
                } else {
                    ConnectionStage.SOCKET_OPEN
                },
                1200L + it
            )
        }
        val bounded = diagnostics.snapshot(2000)
        assertEquals(96, bounded.events.size)
        assertEquals(107, bounded.omittedEvents)
        repeat(100) {
            diagnostics.connection(
                if (it % 2 ==
                    0
                ) {
                    ConnectionStage.AUTHENTICATING
                } else {
                    ConnectionStage.SOCKET_OPEN
                },
                -10
            )
            diagnostics.request(
                if (it % 2 ==
                    0
                ) {
                    RequestStage.WAITING
                } else {
                    RequestStage.TRANSCRIBED
                },
                -10
            )
        }
        assertEquals(bounded.events, diagnostics.snapshot(-100).events)
        assertTrue(diagnostics.snapshot(Long.MAX_VALUE).elapsedMillis <= 604800000)
        assertTrue(
            diagnostics.snapshot(Long.MIN_VALUE).events.all {
                it.elapsedMillis in
                    0..604800000
            }
        )
        try {
            (bounded.events as MutableList<DiagnosticEvent>).clear()
            throw AssertionError("Snapshot events must be immutable")
        } catch (_: UnsupportedOperationException) {
            // The immutable published list cannot alter either snapshot or tracker.
        }
        diagnostics.reset(DiagnosticTransport.DIRECT, 9000)
        val reset = diagnostics.snapshot(9000)
        assertEquals(RequestStage.IDLE, reset.requestStage)
        assertEquals(0, reset.omittedEvents)
        assertEquals(1, reset.events.size)
        assertEquals(0L, reset.elapsedMillis)
        assertFalse(reset.stalled)
    }

    @Test
    fun reportUsesSchemaOneKeepsNewestEventsAndHasAHardByteBudget() {
        val events = List(256) {
            DiagnosticEvent(
                1000000L * (it + 1),
                ConnectionStage.AUTHENTICATING,
                RequestStage.TRANSCRIBED,
                FailureCategory.ADDRESS_POLICY,
                DiagnosticObservation.LOCAL_SUBMISSION
            )
        }
        val snapshot = ConnectionDiagnosticsSnapshot(
            elapsedMillis = Long.MAX_VALUE,
            lastProgressAgeMillis = -99,
            events = events,
            omittedEvents = 7
        )
        val report = ConnectionDiagnosticsReport.encode(snapshot)
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 16384)
        val decoded = Json.parseToJsonElement(report).jsonObject
        assertEquals("1", decoded.getValue("schema").jsonPrimitive.content)
        assertEquals("604800000", decoded.getValue("elapsedMillis").jsonPrimitive.content)
        assertEquals("0", decoded.getValue("lastProgressAgeMillis").jsonPrimitive.content)
        assertEquals("167", decoded.getValue("omittedEvents").jsonPrimitive.content)
        val selected = decoded.getValue("events").jsonArray
        assertEquals(96, snapshot.events.size)
        assertEquals(96, selected.size)
        assertEquals(
            "161000000",
            selected.first().jsonObject.getValue("elapsedMillis").jsonPrimitive.content
        )
        assertEquals(
            "256000000",
            selected.last().jsonObject.getValue("elapsedMillis").jsonPrimitive.content
        )
        assertFalse(snapshot.toString().contains("secret"))
        assertFalse(
            ConnectionState(error = "hostile synthetic secret").toString().contains("secret")
        )
    }

    @Test
    fun recordingUploadAndReceivedAudioAreObservedSeparatelyFromTextAndNativePlayback() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        val conversation = Conversation(diagnostics = diagnostics)
        conversation.setPaired(true)
        val capture = conversation.startRecording(
            1
        ).filterIsInstance<ConversationEffect.CaptureStart>().single()
        assertEquals(RequestStage.RECORDING, diagnostics.snapshot(1).requestStage)
        conversation.capture("wrong", ByteArray(640), 2)
        assertEquals(RequestStage.RECORDING, diagnostics.snapshot(2).requestStage)
        repeat(13) { conversation.capture(capture.token, ByteArray(640), 3L + it) }
        assertEquals(RequestStage.UPLOADING, diagnostics.snapshot(15).requestStage)
        conversation.finishRecording(16, capture.token)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(16).requestStage)
        conversation.receive(message("turn.start", "audio-turn"), 17)
        conversation.receive(
            Message.create(
                "audio.start",
                buildJsonObject {
                    put("turn", "audio-turn")
                    put("rate", 16000)
                    put("stream", 1)
                }
            ),
            18
        )
        val audio = diagnostics.snapshot(18)
        assertEquals(RequestStage.RECEIVING, audio.requestStage)
        assertEquals(DiagnosticAudioStage.RECEIVING, audio.audioStage)
        val count = audio.events.size
        repeat(20) {
            conversation.binary(
                BinaryFrame(BinaryFrame.AUDIO, 1, it, ByteArray(640)),
                19L + it
            )
        }
        assertEquals(count, diagnostics.snapshot(39).events.size)
        conversation.receive(Message.create("audio.end", buildJsonObject { put("stream", 1) }), 40)
        assertEquals(DiagnosticAudioStage.COMPLETE, diagnostics.snapshot(40).audioStage)
        conversation.cancel(41)
        assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(41).requestStage)
        assertFalse(diagnostics.snapshot(99999).stalled)
    }

    @Test
    fun stockUntaggedTranscriptAndPromptAreObservedButCancellationIsNotSuccess() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        val conversation = Conversation(diagnostics = diagnostics)
        conversation.setPaired(true)
        conversation.sendText("synthetic request", 1)
        conversation.receive(
            Message.create(
                "transcript",
                buildJsonObject {
                    put("text", "hostile synthetic secret")
                }
            ),
            2
        )
        assertEquals(RequestStage.TRANSCRIBED, diagnostics.snapshot(2).requestStage)
        conversation.receive(
            Message.create(
                "prompt",
                buildJsonObject {
                    put("id", "synthetic-prompt")
                    put("title", "hostile synthetic secret")
                    put("text", "hostile synthetic secret")
                }
            ),
            3
        )
        assertEquals(
            DiagnosticObservation.PROMPT,
            diagnostics.snapshot(3).events.last().observation
        )
        conversation.receive(message("turn.start", "current"), 4)
        conversation.receive(
            Message.create(
                "turn.end",
                buildJsonObject {
                    put("turn", "current")
                    put("outcome", "cancelled")
                }
            ),
            5
        )
        assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(5).requestStage)
        assertFalse(ConnectionDiagnosticsReport.encode(diagnostics.snapshot(6)).contains("secret"))
    }

    @Test
    fun promptStopsLocalCaptureTelemetryBeforeAndAfterPacketsWithoutResurrection() {
        for (hasPacket in listOf(false, true)) {
            for (answer in listOf(false, true)) {
                val diagnostics = ConnectionDiagnostics()
                diagnostics.reset(DiagnosticTransport.DIRECT, 0)
                val conversation = Conversation(diagnostics = diagnostics)
                conversation.setPaired(true)
                val capture = conversation.startRecording(1)
                    .filterIsInstance<ConversationEffect.CaptureStart>().single()
                if (hasPacket) conversation.capture(capture.token, ByteArray(640), 2)
                val stopped = conversation.receive(
                    Message.create(
                        "prompt",
                        buildJsonObject {
                            put("id", "capture-prompt")
                            put("title", "synthetic secret")
                            put("text", "synthetic secret")
                        }
                    ),
                    3
                )
                assertTrue(stopped.contains(ConversationEffect.CaptureStop))
                assertEquals(
                    listOf("audio.cancel"),
                    stopped.filterIsInstance<ConversationEffect.Send>()
                        .map { it.message.type }
                )
                assertEquals(
                    listOf(ConversationEffect.RecordingOutcome(capture.token, false)),
                    stopped.filterIsInstance<ConversationEffect.RecordingOutcome>()
                )
                assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(3).requestStage)
                assertFalse(diagnostics.requestActive)
                assertEquals(2L, diagnostics.snapshot(60000).elapsedMillis)
                assertTrue(conversation.finishRecording(60001, capture.token).isEmpty())
                assertTrue(conversation.cancelRecording(60002).isEmpty())
                val promptEffects = if (answer) {
                    conversation.answer("capture-prompt", true, 60003)
                } else {
                    conversation.receive(
                        Message.create(
                            "prompt.close",
                            buildJsonObject {
                                put("id", "capture-prompt")
                            }
                        ),
                        60003
                    )
                }
                assertEquals(
                    if (answer) listOf("prompt.reply") else emptyList<String>(),
                    promptEffects.filterIsInstance<ConversationEffect.Send>().map {
                        it.message.type
                    }
                )
                assertTrue(
                    promptEffects.filterIsInstance<ConversationEffect.RecordingOutcome>().isEmpty()
                )
                assertFalse(promptEffects.any { it is ConversationEffect.CaptureStart })
                assertEquals(null, conversation.state.prompt)
                assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(90000).requestStage)
                assertEquals(2L, diagnostics.snapshot(90000).elapsedMillis)
                assertFalse(diagnostics.snapshot(90000).stalled)
                assertFalse(
                    ConnectionDiagnosticsReport.encode(
                        diagnostics.snapshot(90000)
                    ).contains("secret")
                )
            }
        }
    }

    @Test
    fun evictedTurnReplayCannotProgressOrCompleteANewRequest() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        val conversation = Conversation(diagnostics = diagnostics)
        conversation.setPaired(true)
        repeat(129) {
            conversation.receive(message("turn.start", "retired-$it"), it * 2L)
            conversation.receive(message("turn.end", "retired-$it"), it * 2L + 1)
        }
        val history = Conversation::class.java.getDeclaredField("retiredTurns")
            .apply { isAccessible = true }.get(conversation) as Set<*>
        assertEquals(128, history.size)
        conversation.sendText("current synthetic request", 1000)
        assertEquals(TurnCorrelation.HISTORY_LIMIT, diagnostics.snapshot(1000).turnCorrelation)
        assertTrue(diagnostics.snapshot(31000).stalled)
        conversation.receive(message("turn.start", "retired-0"), 31001)
        conversation.receive(message("status", "retired-0", "synthetic replay secret"), 31002)
        conversation.receive(message("transcript", "retired-0", "synthetic replay secret"), 31003)
        conversation.receive(message("reply", "retired-0", "synthetic replay secret"), 31004)
        conversation.receive(message("turn.end", "retired-0"), 31005)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(31005).requestStage)
        assertTrue(diagnostics.snapshot(31005).stalled)
        assertTrue(diagnostics.requestActive)
        assertEquals("synthetic replay secret", conversation.state.reply)
        val report = ConnectionDiagnosticsReport.encode(diagnostics.snapshot(31005))
        assertEquals(
            "HISTORY_LIMIT",
            Json.parseToJsonElement(report).jsonObject
                .getValue("turnCorrelation").jsonPrimitive.content
        )
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("retired-"))
        assertEquals(128, history.size)
        // Fresh and unsolicited server output remains functional, but cannot prove which
        // pending local request it belongs to after exact-history loss.
        conversation.cancel(32000)
        assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(32000).requestStage)
        conversation.receive(message("turn.start", "fresh-unsolicited"), 32001)
        conversation.receive(message("reply", "fresh-unsolicited", "fresh readable reply"), 32002)
        conversation.receive(message("turn.end", "fresh-unsolicited"), 32003)
        assertEquals("fresh readable reply", conversation.state.reply)
        assertEquals(RequestStage.CANCELLED, diagnostics.snapshot(32003).requestStage)
        assertEquals(TurnCorrelation.HISTORY_LIMIT, diagnostics.snapshot(32003).turnCorrelation)
        assertFalse(diagnostics.requestActive)
        assertEquals(128, history.size)
        conversation.disconnected(33000)
        conversation.setPaired(true)
        conversation.sendText("after reconnect", 34000)
        assertEquals(TurnCorrelation.HISTORY_LIMIT, diagnostics.snapshot(34000).turnCorrelation)
        conversation.receive(message("turn.start", "fresh-after-reconnect"), 34001)
        conversation.receive(
            message("reply", "fresh-after-reconnect", "readable after reconnect"),
            34002
        )
        conversation.receive(message("turn.end", "fresh-after-reconnect"), 34003)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(34003).requestStage)
        assertEquals("readable after reconnect", conversation.state.reply)
        assertTrue(conversation.tick(64000).isEmpty())
        assertTrue(diagnostics.snapshot(64000).stalled)
        assertTrue(diagnostics.requestActive)
    }

    @Test
    fun replacingALiveTurnAtTheHistoryBoundaryExposesUncertaintyImmediately() {
        val diagnostics = ConnectionDiagnostics()
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        val conversation = Conversation(diagnostics = diagnostics)
        conversation.setPaired(true)
        repeat(128) {
            conversation.receive(message("turn.start", "boundary-$it"), it * 2L)
            conversation.receive(message("turn.end", "boundary-$it"), it * 2L + 1)
        }
        conversation.receive(message("turn.start", "live-boundary"), 300)
        assertEquals(TurnCorrelation.OBSERVED, diagnostics.snapshot(300).turnCorrelation)
        conversation.sendText("replacement synthetic request", 1000)
        assertEquals(TurnCorrelation.HISTORY_LIMIT, diagnostics.snapshot(1000).turnCorrelation)
        assertEquals(RequestStage.WAITING, diagnostics.snapshot(1000).requestStage)
        assertTrue(diagnostics.snapshot(31000).stalled)
    }

    private fun message(type: String, turn: String, text: String? = null) = Message.create(
        type,
        buildJsonObject {
            put("turn", turn)
            if (text != null) put("text", text)
        }
    )
}
