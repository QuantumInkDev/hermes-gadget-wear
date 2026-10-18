package dev.quantumink.hermesgadget.client

import dev.quantumink.hermesgadget.protocol.ConnectionDiagnostics
import dev.quantumink.hermesgadget.protocol.ConnectionStage
import dev.quantumink.hermesgadget.protocol.DiagnosticTransport
import dev.quantumink.hermesgadget.protocol.FailureCategory
import dev.quantumink.hermesgadget.protocol.RequestStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHealthTest {
    @Test fun enrollmentIsOnlyConfirmedByCurrentReadyStageAndRoutesAreExplicit() {
        for (route in DiagnosticTransport.entries) {
            for (stage in ConnectionStage.entries) {
                val recorder = ConnectionDiagnostics(route)
                recorder.reset(route, 0)
                recorder.connection(stage, 5)
                val snapshot = recorder.snapshot(10)
                val text = ConnectionHealth.details(snapshot)
                assertEquals(
                    stage == ConnectionStage.READY,
                    text.any {
                        it.contains("Paired for this connection")
                    }
                )
                assertEquals(
                    stage == ConnectionStage.PAIRING,
                    text.any {
                        it.contains("Awaiting approval on your server")
                    }
                )
                assertTrue(
                    ConnectionHealth.summary(
                        snapshot
                    ).contains(ConnectionHealth.connection(snapshot))
                )
                assertFalse(text.any { it.contains("null") })
            }
        }
    }

    @Test fun waitingElapsedIsBoundedAndHistoryLimitDoesNotClaimServerProgressOrPlayback() {
        val recorder = ConnectionDiagnostics()
        recorder.reset(DiagnosticTransport.PHONE_RELAY, 0)
        recorder.connection(ConnectionStage.READY, 1)
        recorder.startRequest(RequestStage.WAITING, 1000)
        recorder.uncertainTurn(2000)
        val waiting = recorder.snapshot(46000)
        assertTrue(ConnectionHealth.summary(waiting).endsWith("45 s elapsed"))
        val details = ConnectionHealth.details(waiting)
        assertTrue(details.contains("Last observed request progress: 45 s ago"))
        assertTrue(details.any { it.contains("progress may be unassigned") })
        assertTrue(details.any { it.contains("server work may continue") })
        recorder.connection(ConnectionStage.RETRYING, 47000, FailureCategory.TLS)
        val retry = recorder.snapshot(Long.MAX_VALUE)
        assertTrue(ConnectionHealth.summary(retry).startsWith("Retrying connection"))
        assertFalse(ConnectionHealth.summary(retry).contains("elapsed"))
        assertTrue(ConnectionHealth.details(retry).contains("Failure category: TLS security"))
        assertEquals("Native playback: Playing", ConnectionHealth.playback(true, false))
        assertEquals("Native playback: Preparing voice", ConnectionHealth.playback(false, true))
        assertEquals("Native playback: Idle", ConnectionHealth.playback(false, false))
        assertTrue(ConnectionHealth.AUDIO_LIMIT.contains("not speaker playback"))
    }

    @Test fun allRequestAndFailureEnumsHaveGenericContentWithoutInferredServerTimings() {
        for (request in RequestStage.entries) {
            for (failure in FailureCategory.entries) {
                val recorder = ConnectionDiagnostics()
                recorder.reset(DiagnosticTransport.DIRECT, 0)
                recorder.connection(ConnectionStage.READY, 1, failure)
                recorder.startRequest(RequestStage.WAITING, 2)
                recorder.request(request, 3)
                val snapshot = recorder.snapshot(30003)
                assertTrue(ConnectionHealth.request(snapshot).isNotBlank())
                assertTrue(
                    ConnectionHealth.details(snapshot).any {
                        it.startsWith("Failure category:")
                    }
                )
                assertFalse(ConnectionHealth.summary(snapshot).contains("model"))
            }
        }
        assertTrue(ConnectionHealth.MEASUREMENT_LIMIT.contains("not durable task acceptance"))
    }
}
