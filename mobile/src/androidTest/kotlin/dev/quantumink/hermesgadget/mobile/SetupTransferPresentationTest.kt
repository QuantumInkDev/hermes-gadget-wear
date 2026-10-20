package dev.quantumink.hermesgadget.mobile

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.key
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetEditor
import dev.quantumink.hermesgadget.mobile.widgets.HomeWidgetPreset
import dev.quantumink.hermesgadget.mobile.widgets.WidgetAppearance
import dev.quantumink.hermesgadget.mobile.widgets.WidgetConfig
import dev.quantumink.hermesgadget.mobile.widgets.WidgetTemplate
import dev.quantumink.hermesgadget.protocol.MigrationArchive
import dev.quantumink.hermesgadget.protocol.SetupEndpoint
import dev.quantumink.hermesgadget.protocol.SetupMetadataCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run one method per instrumentation process, with real system font scale and each app palette. */
class SetupTransferPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mode = Appearance.valueOf(
        InstrumentationRegistry.getArguments().getString("transfer_theme") ?: "DARK"
    )
    private val suffix =
        InstrumentationRegistry.getArguments().getString("transfer_suffix") ?: "normal"

    private fun capture(name: String) {
        // Accessibility can expose the next window before its compositor transition finishes.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.filesDir, "transfer-captures").apply {
            mkdirs()
        }
        directory.resolve("${mode.name.lowercase()}-$suffix-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        directory.resolve("${mode.name.lowercase()}-$suffix-$name.tree.txt").writeText(
            nodes().joinToString("\n") {
                val bounds = Rect().also(it::getBoundsInScreen)
                "${it.packageName} ${it.className} $bounds " +
                    "text=${it.text} description=${it.contentDescription} " +
                    "actions=${it.actionList.map { action -> action.id }}"
            }
        )
    }

    private fun shown(native: NativePhoneFixture, text: String) {
        native.scrollTo(text)
        val node = requireNotNull(native.find(text))
        val bounds = Rect().also(node::getBoundsInScreen)
        val display = instrumentation.targetContext.resources.displayMetrics
        assertTrue(
            "$text bounds=$bounds",
            bounds.width() > 0 && bounds.height() > 0 &&
                bounds.left >= 0 && bounds.right <= display.widthPixels &&
                bounds.bottom <= display.heightPixels
        )
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun all(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node ==
            null
        ) {
            emptyList()
        } else {
            listOf(node) + (0 until node.childCount).flatMap { all(node.getChild(it)) }
        }
        return all(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
    }

    // API36 added tri-state checked; the API29 fixture needs the legacy accessor.
    @Suppress("DEPRECATION")
    private fun checked(node: AccessibilityNodeInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 36) node.checked == 1 else node.isChecked

    private fun waitUntil(check: () -> Boolean) {
        repeat(400) {
            if (check()) return
            SystemClock.sleep(50)
        }
        capture("failure-wait")
        error(
            "Native document flow did not settle: " +
                nodes().mapNotNull { it.text }.joinToString(" | ")
        )
    }

    private fun clickReady(native: NativePhoneFixture, text: String) {
        waitUntil {
            var node = native.find(text)
            while (node != null) {
                if (!node.refresh()) return@waitUntil false
                if (node.isClickable && node.isEnabled) return@waitUntil true
                node = node.parent
            }
            false
        }
        tapText(native, text)
    }

    private fun stableNode(find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var previous: Rect? = null
        var stable = 0
        var target: AccessibilityNodeInfo? = null
        var broughtIntoView = false
        waitUntil {
            val node = find() ?: return@waitUntil false
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled) return@waitUntil false
            if (!broughtIntoView) {
                // A partially exposed semantics node can be reported visible while its action
                // lies below the scroll viewport. Bring the entire target on screen first.
                node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                )
                broughtIntoView = true
                return@waitUntil false
            }
            val current = Rect().also(node::getBoundsInScreen)
            stable = if (current == previous) stable + 1 else 0
            previous = current
            target = node
            stable >= 3 && current.width() > 0 && current.height() > 0
        }
        return requireNotNull(target)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val target = Rect().also(node::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_UP
        )) {
            val event = android.view.MotionEvent.obtain(
                time,
                SystemClock.uptimeMillis(),
                action,
                target.exactCenterX(),
                target.exactCenterY(),
                0
            ).apply {
                source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(50)
        }
    }

    private fun tapText(native: NativePhoneFixture, text: String) =
        tap(stableNode { native.find(text) })

    private fun setField(
        native: NativePhoneFixture,
        text: String,
        label: String? = null,
        document: Boolean = false
    ) {
        val field = stableNode {
            if (document) {
                nodes().filter {
                    it.isEditable && it.packageName?.toString()?.contains("documentsui") == true
                }.singleOrNull()
            } else {
                // Select the labelled field, never the first/last currently visible editor:
                // a label may enter the viewport before its editor is fully exposed.
                // A screen-start relocation may finish after its content first appears.
                // Reacquire the observed label after scrolling, instead of waiting on a
                // field that the parent scroll has just moved outside the viewport.
                native.scrollTo(requireNotNull(label))
                var node: AccessibilityNodeInfo? = nodes().firstOrNull {
                    it.text?.toString() == label
                }
                while (node != null && !node.isEditable) node = node.parent
                node
            }
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
            )
        )
        waitUntil {
            field.refresh() && (
                field.text?.toString() == text ||
                    (field.isPassword && field.text?.length == text.length)
                )
        }
    }

    private fun clickDocumentText(text: String) {
        val title = stableNode {
            nodes().firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
        }
        clickObservedNode(title)
    }

    private fun clickObservedNode(node: AccessibilityNodeInfo) {
        // API37 exposes the file card's click action on an ancestor. API29 cards lack it;
        // those use the observed node's settled touch bounds instead.
        var action: AccessibilityNodeInfo? = node
        while (action != null && action.actionList.none {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
        ) {
            action = action.parent
        }
        if (action != null) {
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } else {
            tap(node)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun realDocumentPickerExportImportAndSelectedQrPicture() {
        val source = TransferFixture()
        val target = TransferFixture()
        var selected = source.seed()
        val destination = target.partial()
        val resolver = instrumentation.targetContext.contentResolver
        val pictureName = "N10-setup-${SystemClock.uptimeMillis()}.png"
        val metadata = SetupMetadataCodec.create(
            "Photo bot",
            SetupEndpoint("Photo bot", TWO_URL),
            null,
            System.currentTimeMillis() / 1000
        )
        val picture =
            requireNotNull(
                resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, pictureName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            )
        val bitmap = SetupQr.render(SetupMetadataCodec.encode(metadata))
        resolver.openOutputStream(picture)!!.use { it.write(SetupQr.png(bitmap)) }
        bitmap.recycle()
        resolver.update(
            picture,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null
        )
        try {
            NativePhoneFixture().use { native ->
                native.show(mode, "settings", scroll = true) {
                    SetupTransferPage(native.context, {}, { selected })
                }
                native.scrollTo("Export configuration")
                native.click("Export configuration")
                waitUntil { native.find("Never included") != null || nodes().any { it.isEditable } }
                native.scrollTo("Passphrase")
                setField(native, String(PASSPHRASE), label = "Passphrase")
                native.scrollTo("Confirm passphrase")
                setField(native, String(PASSPHRASE), label = "Confirm passphrase")
                native.scrollTo("Choose where to save")
                clickReady(native, "Choose where to save")
                waitUntil {
                    nodes().any {
                        it.packageName?.toString()?.contains("documentsui") ==
                            true
                    }
                }
                capture("saf-create")
                val archiveName = "N10-transfer-${SystemClock.uptimeMillis()}.hgmigr"
                setField(native, archiveName, document = true)
                clickDocumentText("Save")
                waitUntil { native.find("Configuration exported") != null }
                selected = destination
                native.click("Done")
                native.scrollTo("Import configuration")
                native.click("Import configuration")
                clickDocumentText(archiveName)
                waitUntil { native.find("Unlock archive") != null }
                native.scrollTo("Passphrase")
                setField(native, String(PASSPHRASE), label = "Passphrase")
                native.scrollTo("Unlock and review")
                clickReady(native, "Unlock and review")
                waitUntil { native.find("Review import") != null }
                capture("saf-merge")
                native.scrollTo("Import selected items")
                clickReady(native, "Import selected items")
                waitUntil { native.find("Configuration imported") != null }
                assertEquals(7, destination.templates.templates().size)
                native.click("Done")
                native.scrollTo("Read a code from a photo")
                native.click("Read a code from a photo")
                waitUntil {
                    nodes().any {
                        val owner = it.packageName?.toString().orEmpty()
                        owner.contains("documentsui") || owner.contains("photopicker")
                    }
                }
                capture("photo-picker")
                if (nodes().any { it.packageName?.toString()?.contains("photopicker") == true }) {
                    // This fresh synthetic fixture has exactly one image, the QR just written.
                    // The native photo picker exposes date descriptions, not filenames; prove
                    // the MediaStore fixture and the unique visible photo before selecting it.
                    resolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )!!.use {
                        assertEquals(1, it.count)
                        assertTrue(it.moveToFirst())
                        assertEquals(pictureName, it.getString(0))
                    }
                    clickObservedNode(
                        stableNode {
                            nodes().filter {
                                it.contentDescription?.toString()?.startsWith("Photo taken on ") ==
                                    true
                            }.singleOrNull()
                        }
                    )
                    // API37's native picker confirms the selected photo with its Done button.
                    waitUntil {
                        native.find("Done") != null || native.find("Review setup code") != null
                    }
                    if (native.find("Done") != null) clickDocumentText("Done")
                } else {
                    // Document grids hide filenames; list view exposes the exact fixture name.
                    if (native.find("List view", description = true) != null) {
                        native.click("List view", description = true)
                    }
                    clickDocumentText(pictureName)
                }
                waitUntil { native.find("Review setup code") != null }
                native.scrollTo("Host: two.example.invalid")
                capture("selected-photo-review")
                native.scrollTo("Add reviewed address")
                native.click("Add reviewed address")
                waitUntil { native.find("Added for review") != null }
                assertTrue(destination.profiles.profiles().any { it.endpoint.url == TWO_URL })
            }
        } finally {
            resolver.delete(picture, null, null)
            source.quiesce()
            target.quiesce()
            source.close()
            target.close()
        }
    }

    @Test fun normalAndLargeTypeExposeEveryTransferReview() {
        val source = TransferFixture()
        val destination = TransferFixture()
        val stores = source.seed()
        val target = destination.partial()
        try {
            NativePhoneFixture().use { native ->
                if (suffix ==
                    "large"
                ) {
                    assertTrue(native.context.resources.configuration.fontScale >= 1.7f)
                }
                var generation = 0
                fun show(screen: TransferScreen, selected: SetupTransferStores = target) {
                    val version = ++generation
                    native.show(mode, "settings", scroll = true) {
                        key(version) {
                            SetupTransferPage(native.context, {}, { selected }, initial = screen)
                        }
                    }
                }
                show(TransferScreen.Home, stores)
                shown(native, "Show a setup code")
                capture("home")
                native.click("Show a setup code")
                native.await { native.find("Connection: Alpha") != null }
                native.click("Connection: Alpha")
                native.await { native.find("Setup code") != null }
                shown(native, "Save code as a picture")
                capture("qr-display")
                val now = System.currentTimeMillis() / 1000
                val review = SetupMetadataCodec.decode(
                    SetupMetadataCodec.encode(
                        SetupMetadataCodec.create(
                            "Reviewed host",
                            SetupEndpoint(
                                "Reviewed host",
                                "wss://review.example.invalid:9443/full/path"
                            ),
                            null,
                            now
                        )
                    ),
                    now
                )
                show(TransferScreen.Review(review))
                shown(native, "Host: review.example.invalid")
                capture("qr-review")
                shown(native, "Port: 9443")
                shown(native, "Path: /full/path")
                shown(native, "Full address: wss://review.example.invalid:9443/full/path")
                shown(native, "Add reviewed address")
                native.click("Add reviewed address")
                native.await { native.find("Added for review") != null }
                assertTrue(
                    target.profiles.profiles().any {
                        it.endpoint.url == review.endpoint!!.url
                    }
                )
                val beforeCancel = destination.state(target)
                show(TransferScreen.Export(SetupExport.build(stores)))
                shown(native, "Never included")
                capture("export-summary")
                shown(native, "Choose where to save")
                capture("passphrases")
                // The minimum and confirmation fields must be present; no passphrase is prefilled.
                native.scrollTo("Passphrase")
                assertNotNull(native.find("Passphrase"))
                native.scrollTo("Confirm passphrase")
                assertNotNull(native.find("Confirm passphrase"))
                val envelope = SetupExport.build(stores).use {
                    MigrationArchive.encrypt(it.snapshot, PASSPHRASE, it.artwork)
                }
                val preview = requireNotNull(openPreview(target, envelope, PASSPHRASE))
                show(TransferScreen.Preview(preview))
                shown(native, "Review import")
                capture("merge-preview")
                shown(native, "Keep this phone's name and transport")
                shown(native, "Widget layouts")
                capture("widget-layouts")
                shown(native, "Import selected items")
                capture("merge-confirm")
                show(TransferScreen.Home)
                assertEquals(beforeCancel, destination.state(target))
            }
        } finally {
            source.quiesce()
            destination.quiesce()
            source.close()
            destination.close()
        }
    }

    /** Each preset runs in its own instrumentation process; no repeated Activity host reuse. */
    @Test fun selectedTemplateFillsTheNativeWidgetDraft() {
        val source = TransferFixture()
        try {
            val stores = source.seed()
            val pages = stores.catalog.savedPages()
            val preset = HomeWidgetPreset.valueOf(
                InstrumentationRegistry.getArguments().getString("transfer_preset") ?: "FOCUS"
            )
            val template = WidgetTemplate(
                "00000000-0000-4000-8000-00000000f001",
                preset,
                List(preset.capacity) { if (it == 0) "home:one" else null },
                WidgetAppearance.MOONLIGHT
            )
            var saved: WidgetConfig? = null
            NativePhoneFixture().use { native ->
                native.show(mode, "settings") { modifier ->
                    HomeWidgetEditor(
                        WidgetConfig(preset), pages, false, "",
                        { saved = it }, {}, {}, modifier, listOf(template)
                    ) { _, _ -> }
                }
                val label = "Use saved layout 1 · 1 of ${preset.capacity} bots"
                shown(native, label)
                // Use the actual touch path after scrolling settles; API29's virtual-node
                // ACTION_CLICK was intermittent for a newly exposed 4x4-template control.
                tapText(native, label)
                native.scrollTo(WidgetAppearance.MOONLIGHT.label)
                waitUntil {
                    var node = native.find(WidgetAppearance.MOONLIGHT.label)
                    var selected = false
                    while (node != null) {
                        node.refresh()
                        selected = selected || checked(node) || node.isSelected
                        node = node.parent
                    }
                    selected
                }
                capture("template-${preset.name.lowercase()}")
                native.click("Save widget")
                waitUntil { saved != null }
                val chosen = requireNotNull(saved)
                assertEquals(template.slots, chosen.slots)
                assertEquals(WidgetAppearance.MOONLIGHT, chosen.appearance)
            }
        } finally {
            source.quiesce()
            source.close()
        }
    }
}
