package dev.quantumink.hermesgadget.mobile

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetUi
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSettingsLayoutTest {
    @Test fun largeFontVoiceLabelNeverOverlapsSelectorAcrossStatesAndThemes() {
        assertTrue("Owned emulator only", isAndroidEmulator())
        val measurements = JSONArray()
        val overlaps = mutableListOf<String>()
        Appearance.entries.forEach { appearance ->
            listOf(false, true).forEach { filled ->
                listOf(false, true).forEach { focused ->
                    NativePhoneFixture().use { fixture ->
                        assertTrue(fixture.context.resources.configuration.screenWidthDp <= 320)
                        assertTrue(fixture.context.resources.configuration.fontScale >= 1.7f)
                        val directory = File(fixture.context.filesDir, "layout-vault")
                        val alias = "test.speech.layout." + UUID.randomUUID()
                        val vault = TtsVault(fixture.context, directory, alias)
                        val endpoint = Endpoint.parse("wss://layout.example.invalid/gadget")
                        val id = ClientSpeech.endpointId(endpoint)
                        val page = CatalogPage(
                            "layout",
                            "Synthetic box",
                            CatalogProfile("voice", "Synthetic voice profile", endpoint.url, null),
                            CatalogStatus.OFFLINE,
                            1
                        )
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                        val session = SpeechSettingsSession(scope, vault::read, vault::save, {}, {
                            error("No quota action is authorized")
                        })
                        val fillState = if (filled) "filled" else "empty"
                        val focusState = if (focused) "focused" else "unfocused"
                        val state = "$fillState-$focusState"
                        try {
                            vault.save(
                                id,
                                SpeechSettings(
                                    SpeechMode.PROFILE,
                                    "syntheticLayout",
                                    if (filled) "syntheticVoice" else ""
                                )
                            )
                            fixture.show(appearance, "settings", scroll = true) {
                                SpeechSettingsPage(
                                    fixture.context,
                                    SpeechSettingsTarget(page, endpoint),
                                    providedSession = session
                                ) {}
                            }
                            fixture.await {
                                session.state.value.loaded && !session.state.value.busy
                            }
                            val label = fixture.context.getString(R.string.speech_profile_voice)
                            fixture.scrollTo(label)
                            if (focused) {
                                val field = WidgetUi.nodes().first {
                                    it.isEditable && !it.isPassword &&
                                        it.isVisibleToUser
                                }
                                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                SystemClock.sleep(700)
                                if (fixture.instrumentation.uiAutomation.windows.any {
                                        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                                            it.root?.isVisibleToUser == true
                                    }
                                ) {
                                    fixture.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                                }
                                fixture.await {
                                    fixture.instrumentation.uiAutomation.windows.none {
                                        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                                            it.root?.isVisibleToUser == true
                                    }
                                }
                            }
                            var row = JSONObject()
                            fixture.instrumentation.runOnMainSync {
                                val nodes = nodes(fixture)
                                val selectorText = nodes.single {
                                    text(it) ==
                                        "Voice source: Mode: per-profile BYOK key"
                                }
                                val selector = generateSequence(selectorText) { it.parent }
                                    .first { it.config.contains(SemanticsActions.OnClick) }
                                val voiceLabel = nodes.single { text(it) == label }
                                val selectorBounds = bounds(selector)
                                val labelBounds = bounds(voiceLabel)
                                val field = generateSequence(voiceLabel) { it.parent }
                                    .first { it.config.contains(SemanticsProperties.EditableText) }
                                assertTrue(
                                    "Focus state was not applied",
                                    field.config.getOrNull(SemanticsProperties.Focused) == focused
                                )
                                row = JSONObject().put("theme", appearance.name).put("state", state)
                                    .put(
                                        "selectorBottom",
                                        selectorBounds.bottom
                                    ).put("labelTop", labelBounds.top)
                                    .put("labelBottom", labelBounds.bottom).put(
                                        "overlapPx",
                                        (
                                            selectorBounds.bottom -
                                                labelBounds.top
                                            ).coerceAtLeast(0f)
                                    )
                                if (labelBounds.top <
                                    selectorBounds.bottom
                                ) {
                                    overlaps +=
                                        "$appearance/$state: $labelBounds overlaps $selectorBounds"
                                }
                                // Align the selector in the viewport for comparable native captures.
                                val scroll = nodes.first {
                                    it.config.contains(SemanticsActions.ScrollBy)
                                }
                                val dy =
                                    selectorBounds.top - scroll.boundsInRoot.top -
                                        8 * fixture.context.resources.displayMetrics.density
                                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
                            }
                            SystemClock.sleep(450)
                            measurements.put(row)
                            fixture.capture("fix1-voice-${appearance.name}-$state")
                        } finally {
                            session.close()
                            scope.cancel()
                            directory.deleteRecursively()
                            KeyStore.getInstance("AndroidKeyStore").apply {
                                load(null)
                                deleteEntry(alias)
                            }
                        }
                    }
                }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.cacheDir, "task2-fix1-layout").apply { mkdirs() }
            .resolve("geometry.json").writeText(measurements.toString(2))
        assertTrue(overlaps.joinToString("\n"), overlaps.isEmpty())
    }

    private fun bounds(node: SemanticsNode): Rect = Rect(node.positionInRoot, node.size.toSize())
    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }.orEmpty()
    private fun nodes(fixture: NativePhoneFixture): List<SemanticsNode> {
        val activity = NativePhoneFixture::class.java.getDeclaredField("activity").apply {
            isAccessible = true
        }.get(fixture) as AppearanceTestActivity
        fun find(view: View): View? {
            if (view.javaClass.name ==
                "androidx.compose.ui.platform.AndroidComposeView"
            ) {
                return view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    find(
                        view.getChildAt(index)
                    )?.let {
                        return it
                    }
                }
            }
            return null
        }
        val view = requireNotNull(find(activity.window.decorView))
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
        return owner.getAllSemanticsNodes(mergingEnabled = false)
    }
}
