package dev.quantumink.hermesgadget.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnostics
import dev.quantumink.hermesgadget.protocol.ConnectionStage
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.DiagnosticTransport

/** Non-exported synthetic process/recreation host. No enrollment, server, microphone or provider. */
class DiagnosticsTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recorder = ConnectionDiagnostics().apply {
            reset(DiagnosticTransport.DIRECT, 0)
            connection(ConnectionStage.READY, 1)
        }
        val state = PhoneConversationState(
            visible = true,
            connection = ConnectionState(
                status = ConnectionStatus.PAIRED,
                diagnostics = recorder.snapshot(2)
            )
        )
        setContent {
            PhoneAppearance(dev.quantumink.hermesgadget.client.Appearance.DARK) {
                fun forbidden(): Nothing =
                    error("Diagnostics fixture cannot invoke conversation actions")
                ConversationContent(
                    state, null, { forbidden() }, { forbidden() }, { forbidden() }, { forbidden() },
                    {
                        forbidden()
                    }, { forbidden() }, { forbidden() }, { forbidden() }, { forbidden() },
                    { forbidden() }, { _, _ -> forbidden() }
                )
            }
        }
    }
}
