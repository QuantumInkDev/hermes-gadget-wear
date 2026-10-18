@file:Suppress("DEPRECATION")

package dev.quantumink.hermesgadget.mobile

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnostics
import dev.quantumink.hermesgadget.protocol.ConnectionDiagnosticsReport
import dev.quantumink.hermesgadget.protocol.ConnectionStage
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConnectionStatus
import dev.quantumink.hermesgadget.protocol.ConversationMode
import dev.quantumink.hermesgadget.protocol.ConversationState
import dev.quantumink.hermesgadget.protocol.DiagnosticTransport
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.FailureCategory
import dev.quantumink.hermesgadget.protocol.RequestStage
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHealthTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val namespace = "n8-health-" + UUID.randomUUID()
    private val diagnostics = ConnectionDiagnostics().apply {
        reset(DiagnosticTransport.DIRECT, 0)
        connection(ConnectionStage.READY, 10)
    }
    private fun state(
        connectionStage: ConnectionStage = ConnectionStage.READY,
        request: RequestStage = RequestStage.IDLE,
        failure: FailureCategory = FailureCategory.NONE
    ): PhoneConversationState {
        diagnostics.reset(DiagnosticTransport.DIRECT, 0)
        diagnostics.connection(connectionStage, 1, failure)
        if (request != RequestStage.IDLE) diagnostics.startRequest(request, 1000)
        return PhoneConversationState(
            visible = true,
            profile = EndpointProfile(
                Endpoint.parse("wss://canary.example.invalid/gadget"),
                "CANARY_PROFILE",
                "CANARY_CREDENTIAL"
            ),
            draft = "CANARY_DRAFT",
            error = "CANARY_EXCEPTION /private/canary",
            connection = ConnectionState(
                status = when (connectionStage) {
                    ConnectionStage.OFFLINE -> ConnectionStatus.OFFLINE
                    ConnectionStage.PAIRING -> ConnectionStatus.PAIRING
                    ConnectionStage.RETRYING -> ConnectionStatus.RETRYING
                    ConnectionStage.FAILED -> ConnectionStatus.ERROR
                    else -> ConnectionStatus.PAIRED
                },
                pairingCode = "CANARY_PAIRING",
                pairingCommand = "CANARY_PAIR_COMMAND",
                error = "CANARY_ERROR",
                conversation = ConversationState(
                    transcript = "CANARY_TRANSCRIPT",
                    reply = "CANARY_REPLY",
                    status = "CANARY_SERVER_STATUS",
                    mode = if (request ==
                        RequestStage.WAITING
                    ) {
                        ConversationMode.THINKING
                    } else {
                        ConversationMode.READY
                    },
                    busy = request == RequestStage.WAITING
                ),
                diagnostics = diagnostics.snapshot(46000)
            )
        )
    }

    @Test fun busyPreviewDoesNotEnableAnAppSwitchThatWouldStopTheForegroundRequest() {
        NativePhoneFixture().use { fixture ->
            val state = state(request = RequestStage.WAITING)
            show(fixture, state)
            open(fixture)
            fixture.scrollTo("Preview safe report")
            fixture.click("Preview safe report")
            fixture.scrollTo("Share report")
            var action = requireNotNull(fixture.find("Share report"))
            while (!action.isClickable) action = requireNotNull(action.parent)
            assertFalse(
                "Sharing while busy would trigger MainActivity.onStop/hide",
                action.isEnabled
            )
            fixture.scrollTo("Copy report")
            fixture.click("Copy report")
            assertClipboard(state)
        }
    }

    @Test fun actualDetailPreviewCopyAndChooserContainExactlyTheFrozenEncoderOutput() {
        NativePhoneFixture().use { fixture ->
            val current = mutableStateOf(state())
            fixture.show { modifier ->
                Content(current.value, modifier)
            }
            open(fixture)
            fixture.scrollTo("Preview safe report")
            fixture.click("Preview safe report")
            val selected = current.value.connection.diagnostics
            val expected = ConnectionDiagnosticsReport.encode(selected)
            fixture.instrumentation.runOnMainSync {
                current.value =
                    state(connectionStage = ConnectionStage.FAILED, failure = FailureCategory.TLS)
            }
            fixture.scrollTo("Copy report")
            fixture.click("Copy report")
            assertClipboard(
                current.value.copy(
                    connection = current.value.connection.copy(diagnostics = selected)
                )
            )
            val monitor = object : Instrumentation.ActivityMonitor() {
                var chooser: Intent? = null
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_CHOOSER) return null
                    chooser = intent
                    return Instrumentation.ActivityResult(0, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                fixture.scrollTo("Share report")
                fixture.click("Share report")
                val chooser = requireNotNull(monitor.chooser)
                val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("text/plain", send.type)
                assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals(setOf(Intent.EXTRA_TEXT), send.extras?.keySet())
                assertEquals(null, send.clipData)
                assertEquals(0, send.flags)
                assertEquals(null, send.data)
                File(
                    instrumentation.targetContext.cacheDir,
                    namespace + ".json"
                ).writeText(expected)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            fixture.scrollTo("Share report")
            fixture.click("Share report")
            var chooserForeground = false
            repeat(120) {
                if (!chooserForeground) {
                    chooserForeground = instrumentation.uiAutomation.rootInActiveWindow
                        ?.packageName?.toString() in setOf("android", "com.android.intentresolver")
                    if (!chooserForeground) SystemClock.sleep(50)
                }
            }
            assertTrue(
                "Android's chooser must be in the foreground, not the clipboard overlay",
                chooserForeground
            )
            SystemClock.sleep(300)
            capture(fixture, "android-sharesheet")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            fixture.await { fixture.find("Close") != null }
        }
    }

    @Test fun newSessionDiscardsAnOldOwnersOpenPreview() {
        NativePhoneFixture().use { fixture ->
            val current = mutableStateOf(state())
            fixture.show { modifier -> Content(current.value, modifier) }
            open(fixture)
            fixture.scrollTo("Preview safe report")
            fixture.click("Preview safe report")
            fixture.instrumentation.runOnMainSync {
                current.value =
                    current.value.copy(sessionVersion = current.value.sessionVersion + 1)
            }
            fixture.await { fixture.find("Connection details", description = true) != null }
            open(fixture)
            fixture.scrollTo("Preview safe report")
            assertEquals(null, fixture.find("Copy report"))
        }
    }

    @Test fun allThreeThemesExposeOfflinePairingWaitingRetryTlsAndEmptyWithoutActionCallbacks() {
        NativePhoneFixture().use { fixture ->
            for (appearance in Appearance.entries) {
                for ((stage, request, failure) in listOf(
                    Triple(ConnectionStage.OFFLINE, RequestStage.IDLE, FailureCategory.NONE),
                    Triple(ConnectionStage.PAIRING, RequestStage.IDLE, FailureCategory.NONE),
                    Triple(ConnectionStage.READY, RequestStage.WAITING, FailureCategory.NONE),
                    Triple(
                        ConnectionStage.RETRYING,
                        RequestStage.IDLE,
                        FailureCategory.REACHABILITY
                    ),
                    Triple(ConnectionStage.FAILED, RequestStage.IDLE, FailureCategory.TLS)
                )) {
                    val state = state(stage, request, failure)
                    fixture.show(mode = appearance) { modifier -> Content(state, modifier) }
                    open(fixture)
                    assertNotNull(
                        fixture.find(
                            "This phone only. Watch diagnostics are displayed on the watch."
                        )
                    )
                    fixture.scrollTo(
                        "Connection: " +
                            dev.quantumink.hermesgadget.client.ConnectionHealth.connection(
                                state.connection.diagnostics
                            )
                    )
                    fixture.scrollTo(
                        "Failure category: " + when (failure) {
                            FailureCategory.TLS -> "TLS security"
                            FailureCategory.REACHABILITY -> "Reachability"
                            else -> "None observed"
                        }
                    )
                    capture(fixture, "${appearance.name}-${stage.name}")
                    fixture.click("Close")
                }
                fixture.show(mode = appearance) { modifier ->
                    Content(PhoneConversationState(), modifier)
                }
                open(fixture)
                fixture.scrollTo("Route: Not observed")
                fixture.click("Close")
            }
        }
    }

    private fun show(fixture: NativePhoneFixture, state: PhoneConversationState) {
        fixture.show { modifier -> Content(state, modifier) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(state: PhoneConversationState, modifier: androidx.compose.ui.Modifier) {
        fun forbidden(): Nothing =
            error("Opening/exporting diagnostics must not invoke a conversation action")
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalDensity.current.density,
                InstrumentationRegistry.getArguments().getString("n8_font_scale")?.toFloat() ?: 1f
            )
        ) {
            ConversationContent(
                state, null, { forbidden() }, { forbidden() }, { forbidden() }, { forbidden() },
                { forbidden() }, { forbidden() }, { forbidden() }, { forbidden() }, { forbidden() },
                { forbidden() }, { _, _ -> forbidden() }, modifier
            )
        }
    }

    private fun open(fixture: NativePhoneFixture) {
        fixture.click("Connection details", description = true)
        fixture.click("Connection health")
        fixture.await {
            fixture.find("This phone only. Watch diagnostics are displayed on the watch.") !=
                null
        }
        assertEquals(null, fixture.find("Copy report"))
    }

    private fun assertClipboard(state: PhoneConversationState) {
        var copied: String? = null
        instrumentation.runOnMainSync {
            copied = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(ConnectionDiagnosticsReport.encode(state.connection.diagnostics), copied)
        assertTrue(
            requireNotNull(copied).toByteArray().size <= ConnectionDiagnosticsReport.MAX_BYTES
        )
        for (canary in listOf("CANARY", "canary.example.invalid", "/private/", "wss://")) {
            assertFalse(requireNotNull(copied).contains(canary))
        }
    }

    private fun capture(fixture: NativePhoneFixture, name: String) {
        SystemClock.sleep(100)
        val directory = File(instrumentation.targetContext.cacheDir, namespace).apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
