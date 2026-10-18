@file:Suppress("DEPRECATION")

package dev.quantumink.hermesgadget.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.client.ConnectionHealth
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual conversation footer, local typed synthetic state; never bind/start a service. */
class ConnectionHealthTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val namespace = "n8-health-" + UUID.randomUUID()
    private fun snapshot(stage: ConnectionStage, request: RequestStage, failure: FailureCategory) =
        ConnectionDiagnostics().apply {
            reset(DiagnosticTransport.PHONE_RELAY, 0)
            connection(stage, 1, failure)
            if (request != RequestStage.IDLE) startRequest(request, 1000)
        }.snapshot(46000)

    @Test fun actualFooterKeepsArtworkClearAndJumpFinalInAllThemesAtLargeType() {
        checkEmulator()
        val activity = start()
        val portrait = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val profile =
            EndpointProfile(
                Endpoint.parse("wss://canary.example.invalid/gadget"),
                "Synthetic profile",
                "CANARY_TOKEN"
            )
        val page = ProfilePage("synthetic:n8", profile.label, "Synthetic source", profile, null)
        val state = mutableStateOf(
            WatchState(
                profile = profile,
                profiles = listOf(profile),
                loading = false,
                connection = ConnectionState(
                    status = ConnectionStatus.PAIRED,
                    conversation = ConversationState(reply = "CANARY_REPLY"),
                    diagnostics = snapshot(
                        ConnectionStage.READY,
                        RequestStage.IDLE,
                        FailureCategory.NONE
                    )
                ),
                artwork = mapOf(page.key to PageArtwork(portrait, null)),
                artworkIdentities = mapOf(page.key to page.presentationKey())
            )
        )
        val unusedService = GadgetService()
        val theme = mutableStateOf(Appearance.LIGHT)
        var jumped = 0
        val scroll = TransformingLazyColumnState()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, 1.3f)
                    ) {
                        WatchAppearance(theme.value) {
                            ConversationPage(
                                state.value, unusedService,
                                {
                                    error("Diagnostics must not connect")
                                }, { error("Diagnostics must not record") },
                                {
                                    error("Diagnostics must not open setup")
                                }, { error("Diagnostics must not type") },
                                {
                                    error("Diagnostics must not change profiles")
                                }, { error("Diagnostics must not change appearance") },
                                page, scroll
                            ) { Secondary("Jump to profile", { jumped++ }) }
                        }
                    }
                }
            }
            for (appearance in Appearance.entries) {
                instrumentation.runOnMainSync { theme.value = appearance }
                top()
                await { nodes().any { it.text?.toString() == page.label } }
                assertTrue(nodes().none { it.text?.toString() == "Connection health" })
                capture("${appearance.name}-portrait")
                scrollTo("Connection health")
                click("Connection health")
                scrollTo("This watch only. No report is transferred to the phone.")
                capture("${appearance.name}-health")
                scrollTo("Route: Phone relay")
                scrollTo("Enrollment: Paired for this connection")
                scrollTo("Native playback: Idle")
                scrollTo("View local report")
                click("View local report")
                val expected = ConnectionDiagnosticsReport.encode(
                    state.value.connection.diagnostics
                )
                scrollTo("Local report (frozen)")
                capture("${appearance.name}-local-report")
                for (part in expected.chunked(40)) scrollTo(part)
                scrollTo("Jump to profile")
                click("Jump to profile")
                assertEquals(appearance.ordinal + 1, jumped)
                assertEquals(
                    scroll.layoutInfo.totalItemsCount - 1,
                    scroll.layoutInfo.visibleItems.last().index
                )
                capture("${appearance.name}-final-jump")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                scrollTo("Connection health")
                assertTrue(!activity.isFinishing)
            }
            // Reusing a catalog page key must not retain another endpoint's report preview.
            click("Connection health")
            scrollTo("View local report")
            click("View local report")
            instrumentation.runOnMainSync {
                state.value = state.value.copy(
                    profile = EndpointProfile(
                        Endpoint.parse("wss://new-owner.example.invalid/gadget"),
                        profile.label,
                        ""
                    )
                )
            }
            scrollTo("Connection health")
            assertTrue(nodes().none { it.text?.toString() == "Local report (frozen)" })
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent {}
                activity.finish()
            }
            portrait.recycle()
        }
    }

    @Test fun localStatesAndCanonicalReportRemainReadableWithoutAnyTransferOrPlaybackClaims() {
        checkEmulator()
        val activity = start()
        try {
            for ((stage, request, failure) in listOf(
                Triple(ConnectionStage.OFFLINE, RequestStage.IDLE, FailureCategory.NONE),
                Triple(ConnectionStage.PAIRING, RequestStage.IDLE, FailureCategory.NONE),
                Triple(ConnectionStage.READY, RequestStage.WAITING, FailureCategory.NONE),
                Triple(ConnectionStage.RETRYING, RequestStage.IDLE, FailureCategory.REACHABILITY),
                Triple(ConnectionStage.FAILED, RequestStage.IDLE, FailureCategory.TLS)
            )) {
                val snapshot = snapshot(stage, request, failure)
                instrumentation.runOnMainSync {
                    activity.setContent {
                        WatchAppearance(Appearance.MOONLIGHT) {
                            RoundPage {
                                connectionHealthItems(snapshot, false, true, true, {}, null, {})
                                item { Secondary("Jump to profile", {}) }
                            }
                        }
                    }
                }
                scrollTo("Connection: " + ConnectionHealth.connection(snapshot))
                scrollTo("Request: " + ConnectionHealth.request(snapshot))
                scrollTo(
                    ConnectionHealth.details(snapshot).first {
                        it.startsWith("Failure category:")
                    }
                )
                capture("state-${stage.name}")
                scrollTo("Native playback: Preparing voice")
                scrollTo("Jump to profile")
            }
            instrumentation.runOnMainSync {
                activity.setContent {
                    WatchAppearance(Appearance.MOONLIGHT) {
                        RoundPage {
                            connectionHealthItems(
                                ConnectionState().diagnostics,
                                false,
                                false,
                                true,
                                {},
                                null,
                                {}
                            )
                        }
                    }
                }
            }
            scrollTo("Route: Not observed")
            scrollTo("Request: No active request")
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent {}
                activity.finish()
            }
        }
    }

    private fun checkEmulator() {
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(
            "1",
            instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    it
                ).bufferedReader().use { reader ->
                    reader.readText().trim()
                }
            }
        )
    }
    private fun start() = instrumentation.startActivitySync(
        Intent(context, AppearanceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as AppearanceTestActivity
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { walk(node.getChild(it)) }
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(text: String) {
        await { nodes().any { it.text?.toString() == text } }
        var node = nodes().first { it.text?.toString() == text }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }
    private fun scrollTo(text: String) {
        var forward = true
        repeat(120) {
            if (nodes().any { it.text?.toString() == text }) return
            val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            val node = nodes().firstOrNull {
                it.isScrollable &&
                    it.actionList.any { a -> a.id == down || a.id == up }
            }
            if (node == null) {
                SystemClock.sleep(60)
                return@repeat
            }
            if (node.actionList.none { it.id == if (forward) down else up }) forward = !forward
            // Overlapping touch scrolls avoid skipping short footer items on round viewports.
            val bounds = Rect().also(node::getBoundsInScreen)
            val distance = bounds.height() * .18f
            val middle = bounds.exactCenterY()
            val start = middle + if (forward) distance else -distance
            val end = middle - if (forward) distance else -distance
            val now = SystemClock.uptimeMillis()
            fun pointer(action: Int, y: Float) {
                val event = android.view.MotionEvent.obtain(
                    now,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    y,
                    0
                )
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try {
                    instrumentation.sendPointerSync(event)
                } finally {
                    event.recycle()
                }
            }
            pointer(android.view.MotionEvent.ACTION_DOWN, start)
            repeat(4) { step ->
                SystemClock.sleep(16)
                pointer(
                    android.view.MotionEvent.ACTION_MOVE,
                    start + (end - start) * (step + 1) / 4
                )
            }
            SystemClock.sleep(120)
            pointer(android.view.MotionEvent.ACTION_MOVE, end)
            pointer(android.view.MotionEvent.ACTION_UP, end)
            SystemClock.sleep(80)
        }
        error("Missing local diagnostics control $text")
    }
    private fun top() {
        repeat(80) {
            val node = nodes().firstOrNull { it.isScrollable } ?: return@repeat
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            SystemClock.sleep(50)
        }
    }
    private fun await(check: () -> Boolean) {
        repeat(60) {
            if (check()) return
            SystemClock.sleep(80)
        }
        error("Watch diagnostics did not settle")
    }
    private fun capture(name: String) {
        val directory = File(context.cacheDir, namespace).apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
