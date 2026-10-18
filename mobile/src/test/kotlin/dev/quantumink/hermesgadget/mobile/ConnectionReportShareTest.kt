package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.ConnectionDiagnostics
import dev.quantumink.hermesgadget.protocol.ConnectionStage
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DiagnosticTransport
import dev.quantumink.hermesgadget.protocol.Prompt
import dev.quantumink.hermesgadget.protocol.RequestStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReportShareTest {
    private val idle = PhoneConversationState(visible = true)

    @Test fun shareNeverInterruptsRecordingWaitingPairingPromptOrNativeVoice() {
        val conversation = idle.connection.conversation
        for (state in listOf(
            idle.copy(sending = true),
            idle.copy(speaking = true),
            idle.copy(preparingSpeech = true),
            idle.copy(connection = idle.connection.copy(status = ConnectionStatus.CONNECTING)),
            idle.copy(connection = idle.connection.copy(status = ConnectionStatus.PAIRING)),
            idle.copy(
                connection = idle.connection.copy(conversation = conversation.copy(busy = true))
            ),
            idle.copy(
                connection = idle.connection.copy(
                    conversation = conversation.copy(mode = ConversationMode.LISTENING)
                )
            ),
            idle.copy(
                connection = idle.connection.copy(
                    conversation = conversation.copy(prompt = Prompt("synthetic", "", "", 0, null))
                )
            )
        )) {
            assertFalse(connectionReportShareAllowed(state))
        }
        for (request in listOf(
            RequestStage.RECORDING,
            RequestStage.UPLOADING,
            RequestStage.WAITING,
            RequestStage.TRANSCRIBED,
            RequestStage.RECEIVING
        )) {
            val recorder = ConnectionDiagnostics().apply {
                reset(DiagnosticTransport.DIRECT, 0)
                connection(ConnectionStage.READY, 1)
                startRequest(request, 2)
            }
            assertFalse(
                connectionReportShareAllowed(
                    idle.copy(connection = ConnectionState(diagnostics = recorder.snapshot(3)))
                )
            )
        }
    }

    @Test fun explicitShareIsAvailableWhenIdleOfflineRetryingOrTlsHasFailed() {
        for (status in listOf(
            ConnectionStatus.OFFLINE,
            ConnectionStatus.ERROR,
            ConnectionStatus.RETRYING,
            ConnectionStatus.PAIRED
        )) {
            assertTrue(
                connectionReportShareAllowed(
                    idle.copy(connection = idle.connection.copy(status = status))
                )
            )
        }
    }
}
