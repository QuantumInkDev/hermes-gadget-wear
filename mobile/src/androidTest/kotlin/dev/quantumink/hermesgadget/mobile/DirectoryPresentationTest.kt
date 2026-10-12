package dev.quantumink.hermesgadget.mobile

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.Appearance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DirectoryPresentationTest {
    @Test fun fullRowOpensTheExactProfileAndOverflowKeepsPictureResetAndDetails() {
        directoryFixture { fixture ->
            fixture.show()
            fixture.clickDescription("Open Alpha Talk and Chat")
            assertEquals(listOf("a:alpha"), fixture.opened)
            fixture.clickDescription("Options for Alpha")
            fixture.clickText("Watch picture")
            fixture.awaitText("Picture for Alpha")
            fixture.awaitText("Choose picture")
            fixture.clickText("Use box picture")
            fixture.await { "a:alpha" !in fixture.repository.watchPictures.value }
            fixture.clickDescription("Options for Alpha")
            fixture.clickText("Endpoint details")
            fixture.awaitText("wss://alpha.example.invalid/gadget")
            fixture.clickText("Close")
            assertEquals(listOf("a:alpha"), fixture.opened)
        }
    }

    @Test fun searchExpandsMatchingCollapsedBoxesThenRestoresIndependentCollapse() {
        directoryFixture { fixture ->
            fixture.show()
            fixture.clickText("Box One")
            fixture.await { fixture.findDescription("Open Alpha Talk and Chat") == null }
            fixture.setSearch("Alpha")
            fixture.awaitDescription("Open Alpha Talk and Chat")
            assertNull(fixture.findDescription("Open Beta Talk and Chat"))
            fixture.setSearch("")
            fixture.awaitDescription("Open Beta Talk and Chat")
            assertNull(fixture.findDescription("Open Alpha Talk and Chat"))
            fixture.clickText("Box One")
            fixture.awaitDescription("Open Alpha Talk and Chat")
        }
    }

    @Test fun directoryOverflowReordersRealRepositoryAndKeepsSetupReachable() {
        directoryFixture { fixture ->
            fixture.show()
            fixture.clickDescription("Directory options")
            fixture.clickText("Manage boxes")
            assertEquals(1, fixture.boxes)
            fixture.clickDescription("Directory options")
            fixture.clickText("Manual setup")
            assertEquals(1, fixture.manual)
            fixture.clickDescription("Directory options")
            fixture.clickText("Reorder")
            fixture.clickDescription("Move Box Two earlier")
            fixture.await { fixture.repository.sourceOrder().first() == "b" }
            fixture.clickText("Done reordering")
            assertEquals("b", fixture.vault.sourceOrder()?.first())
            assertEquals(
                listOf("b:beta", "a:alpha"),
                fixture.repository.savedPages().map {
                    it.key
                }
            )
        }
    }

    @Test fun offlineRefreshKeepsPortraitAndWithdrawnRetirementRequiresConfirmation() {
        directoryFixture { fixture ->
            fixture.show()
            val picture = fixture.repository.savedPages().first().profile.portrait?.sha256
            fixture.clickDescription("Directory options")
            fixture.clickText("Refresh")
            fixture.await {
                fixture.repository.savedPages().all {
                    it.status == dev.quantumink.hermesgadget.protocol.CatalogStatus.OFFLINE
                }
            }
            assertEquals(picture, fixture.repository.savedPages().first().profile.portrait?.sha256)
            assertEquals(
                listOf("a:alpha", "b:beta"),
                fixture.repository.savedPages().map {
                    it.key
                }
            )
            fixture.awaitDescription("Open Alpha Talk and Chat")
            fixture.withdrawAlpha()
            fixture.clickDescription("Options for Alpha")
            fixture.clickText("Forget withdrawn card")
            fixture.awaitText("Forget withdrawn profile?")
            assertTrue(fixture.repository.savedPages().any { it.key == "a:alpha" })
            fixture.clickText("Cancel")
            assertTrue(fixture.repository.savedPages().any { it.key == "a:alpha" })
            fixture.clickDescription("Options for Alpha")
            fixture.clickText("Forget withdrawn card")
            fixture.clickText("Forget card")
            fixture.await { fixture.repository.savedPages().none { it.key == "a:alpha" } }
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun syntheticDirectoryRendersAllThemesAndLargeTypeWithNativePortraits() {
        Appearance.entries.forEach { mode ->
            directoryFixture { fixture ->
                fixture.show(mode)
                fixture.scrollToDescription("Watch picture for Alpha")
                fixture.capture(
                    "directory-${mode.name}" +
                        if (InstrumentationRegistry.getArguments().getString("task2_large") ==
                            "true"
                        ) {
                            "-large"
                        } else {
                            ""
                        }
                )
                fixture.scrollToDescription("Options for Alpha")
                fixture.clickDescription("Options for Alpha")
                fixture.awaitText("Watch picture")
                fixture.capture(
                    "profile-menu-${mode.name}" +
                        if (InstrumentationRegistry.getArguments().getString("task2_large") ==
                            "true"
                        ) {
                            "-large"
                        } else {
                            ""
                        }
                )
            }
        }
        if (InstrumentationRegistry.getArguments().getString("task2_large") == "true") {
            directoryFixture { fixture ->
                assertTrue(fixture.context.resources.configuration.fontScale >= 1.7f)
                assertTrue(fixture.context.resources.configuration.screenWidthDp <= 330)
                fixture.longLabel()
                fixture.show(Appearance.MOONLIGHT)
                fixture.capture("directory-large-narrow")
                fixture.scrollToDescription("Options for Alpha research writing companion")
                fixture.clickDescription("Options for Alpha research writing companion")
                fixture.awaitText("Endpoint details")
                fixture.capture("profile-menu-large-narrow")
                fixture.clickText("Endpoint details")
                fixture.awaitText("wss://alpha.example.invalid/gadget")
                fixture.capture("profile-details-large-narrow")
            }
        }
    }

    @Test fun topInstructionsHaveReadableContrastOutsideCards() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val rendered = CountDownLatch(1)
        val foreground = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        PhoneScaffold {
                            val color = LocalContentColor.current.toArgb()
                            SideEffect {
                                foreground.set(color)
                                rendered.countDown()
                            }
                            Text("Choose a profile to talk or chat.")
                        }
                    }
                }
            }
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            val contrast = ColorUtils.calculateContrast(foreground.get(), 0xff11170f.toInt())
            assertTrue("Top text contrast is only $contrast:1", contrast >= 4.5)
            instrumentation.sendStatus(
                0,
                android.os.Bundle().apply {
                    putFloat("top_text_contrast", contrast.toFloat())
                }
            )
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }

    @Test fun fullBoxHeadersExpandIndependentlyAndExposeTheirState() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(
                context,
                AppearanceTestActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as AppearanceTestActivity
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (node.isVisibleToUser &&
                    node.text?.toString()?.contains(label) == true
                ) {
                    return node
                }
                for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                return null
            }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun waitUntil(check: () -> Boolean) {
            repeat(50) {
                if (check()) return
                SystemClock.sleep(40)
            }
            error("Box presentation did not settle")
        }
        fun header(label: String): AccessibilityNodeInfo {
            var node = requireNotNull(find(label))
            while (!node.isClickable) node = node.parent ?: error("Tappable box header missing")
            return node
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        PhoneScaffold { padding ->
                            Column(Modifier.padding(padding)) {
                                DirectoryBoxHeader(
                                    "Box One",
                                    "12 profiles",
                                    "Catalog available",
                                    first.value,
                                    first.value,
                                    true,
                                    { first.value = !first.value }
                                )
                                if (first.value) Text("First box profile")
                                DirectoryBoxHeader(
                                    "Box Two",
                                    "4 profiles",
                                    "Catalog offline",
                                    second.value,
                                    second.value,
                                    true,
                                    { second.value = !second.value }
                                )
                                if (second.value) Text("Second box profile")
                            }
                        }
                    }
                }
            }
            waitUntil { find("Box One") != null }
            assertEquals("Collapsed", header("Box One").stateDescription?.toString())
            assertTrue(
                header("Box One").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("First box profile") != null }
            assertEquals("Expanded", header("Box One").stateDescription?.toString())
            assertEquals("Collapsed", header("Box Two").stateDescription?.toString())
            assertNull(find("Second box profile"))
            assertTrue(
                header("Box Two").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("Second box profile") != null }
            assertTrue(
                header("Box One").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
            waitUntil { find("First box profile") == null }
            assertNotNull(find("Second box profile"))
            assertEquals("Collapsed", header("Box One").stateDescription?.toString())
            assertEquals("Expanded", header("Box Two").stateDescription?.toString())
        } finally {
            instrumentation.runOnMainSync {
                activity.setContent { }
                activity.finish()
            }
        }
    }
}

/** Real encrypted stores isolated from the app's production setup and identity. */
private fun directoryFixture(block: (DirectoryFixture) -> Unit) {
    assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
    val fixture = DirectoryFixture()
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}

private class DirectoryFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val id = java.util.UUID.randomUUID().toString()
    private val directory = java.io.File(context.cacheDir, "directory-ui-$id")
    private val aliases = listOf("hermes.test.catalog.$id", "hermes.test.setup.$id")
    val vault = CatalogVault(context, java.io.File(directory, "vault"), aliases[0])
    private val setup = ProfileVault(context, java.io.File(directory, "setup"), aliases[1])
    private val portraits = PortraitStore(java.io.File(directory, "portraits"))
    val repository: CatalogRepository
    val opened = mutableListOf<String>()
    var boxes = 0
    var manual = 0
    private val activity = instrumentation.startActivitySync(
        Intent(context, AppearanceTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as AppearanceTestActivity

    init {
        vault.saveSources(
            listOf("a" to "Box One", "b" to "Box Two").map { (key, label) ->
                dev.quantumink.hermesgadget.protocol.CatalogSource(
                    key,
                    label,
                    "https://$key.example.invalid/catalog/v1",
                    "synthetic"
                )
            }
        )
        vault.savePages(
            listOf("a" to "Alpha", "b" to "Beta").map { (key, label) ->
                dev.quantumink.hermesgadget.protocol.CatalogPage(
                    key,
                    if (key == "a") "Box One" else "Box Two",
                    dev.quantumink.hermesgadget.protocol.CatalogProfile(
                        label.lowercase(),
                        label,
                        "wss://${label.lowercase()}.example.invalid/gadget",
                        null
                    ),
                    dev.quantumink.hermesgadget.protocol.CatalogStatus.AVAILABLE,
                    1
                )
            }
        )
        repository = CatalogRepository(
            context,
            vault,
            setup,
            portraits,
            animations = dev.quantumink.hermesgadget.client.AnimationCache(
                java.io.File(directory, "animations")
            ),
            fetchProfiles = {
                throw CatalogFailure(dev.quantumink.hermesgadget.protocol.CatalogStatus.OFFLINE)
            },
            publish = { _, _ -> }
        )
        repository.savedPages()
        val bitmap = android.graphics.Bitmap.createBitmap(
            64,
            64,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.rgb(50, 69, 112)
        canvas.drawCircle(32f, 32f, 32f, paint)
        paint.color = android.graphics.Color.rgb(150, 210, 230)
        canvas.drawRoundRect(15f, 14f, 49f, 50f, 12f, 12f, paint)
        paint.color = android.graphics.Color.rgb(30, 43, 70)
        canvas.drawCircle(25f, 29f, 3f, paint)
        canvas.drawCircle(39f, 29f, 3f, paint)
        canvas.drawRoundRect(25f, 39f, 39f, 42f, 2f, 2f, paint)
        val bytes = java.io.ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        repository.selectWatchPhoto("a:alpha", bytes)
    }

    fun withdrawAlpha() {
        vault.savePages(
            vault.pages().map {
                dev.quantumink.hermesgadget.protocol.CatalogPage(
                    it.sourceId,
                    it.sourceLabel,
                    it.profile,
                    if (it.key ==
                        "a:alpha"
                    ) {
                        dev.quantumink.hermesgadget.protocol.CatalogStatus.WITHDRAWN
                    } else {
                        it.status
                    },
                    1
                )
            }
        )
        repository.savedPages()
        awaitText("Withdrawn · setup retained")
    }

    fun longLabel() {
        vault.savePages(
            vault.pages().map {
                if (it.key ==
                    "a:alpha"
                ) {
                    dev.quantumink.hermesgadget.protocol.CatalogPage(
                        it.sourceId,
                        it.sourceLabel,
                        dev.quantumink.hermesgadget.protocol.CatalogProfile(
                            it.profile.id,
                            "Alpha research writing companion",
                            it.profile.gadgetUrl,
                            it.profile.portrait
                        ),
                        it.status,
                        1
                    )
                } else {
                    it
                }
            }
        )
        repository.savedPages()
    }

    fun show(mode: Appearance = Appearance.DARK) {
        instrumentation.runOnMainSync {
            activity.setContent {
                PhoneAppearance(mode) {
                    PhoneScaffold(
                        topBar = {
                            androidx.compose.foundation.layout.Box(Modifier.statusBarsPadding()) {
                                PhoneBrandHeader()
                            }
                        },
                        bottomBar = { PhoneNavigationBar("profiles") { } }
                    ) { padding ->
                        DirectoryContent(
                            context,
                            repository,
                            { opened.add(it.key) },
                            { boxes++ },
                            { manual++ },
                            Modifier.padding(padding)
                        )
                    }
                }
            }
        }
        awaitText("Box One")
        await { findText("Updating profiles…") == null }
    }

    fun await(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            SystemClock.sleep(50)
        }
        error("Directory did not settle")
    }

    private fun find(check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && check(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    fun findDescription(text: String) = find { it.contentDescription?.toString() == text }
    fun findText(text: String) = find { it.text?.toString()?.split('\n')?.contains(text) == true }
    fun awaitText(text: String) {
        await { findText(text) != null }
    }
    fun awaitDescription(text: String) {
        await { findDescription(text) != null }
    }
    fun clickText(text: String) {
        awaitText(text)
        click(requireNotNull(findText(text)))
    }
    fun clickDescription(text: String) {
        awaitDescription(text)
        click(requireNotNull(findDescription(text)))
    }
    private fun click(target: AccessibilityNodeInfo) {
        var node = target
        while (!node.isClickable) node = node.parent ?: error("No click target")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    fun setSearch(value: String) {
        val field = requireNotNull(find { it.isEditable })
        assertTrue(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
            )
        )
    }

    fun scrollToDescription(description: String) {
        repeat(8) {
            if (findDescription(description) != null) return
            val scroll = requireNotNull(find { it.isScrollable })
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(180)
        }
        awaitDescription(description)
    }

    fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(context.getExternalFilesDir(null), "task-2-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    override fun close() {
        instrumentation.runOnMainSync {
            activity.setContent { }
            activity.finish()
        }
        repository.close()
        directory.deleteRecursively()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
    }
}
