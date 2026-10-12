package dev.quantumink.hermesgadget.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.quantumink.hermesgadget.client.Appearance
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.TilePinPhoneState
import dev.quantumink.hermesgadget.protocol.TilePinResponse
import dev.quantumink.hermesgadget.protocol.TilePinSnapshot
import dev.quantumink.hermesgadget.protocol.TilePins
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTilesTest {
    private val profiles =
        listOf(
            TileComposerProfile("box:a", "Alpha"),
            TileComposerProfile("box:b", "Beta", true),
            TileComposerProfile("box:c", "Gamma"),
            TileComposerProfile("box:d", "Delta")
        )
    private val keys = profiles.map { it.key }.toSet()
    private fun resetScroll(ui: NativePhoneFixture) {
        fun scroll(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            node ?: return false
            if (node.isScrollable &&
                node.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            ) {
                return true
            }
            for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
            return false
        }
        repeat(8) {
            scroll(ui.instrumentation.uiAutomation.rootInActiveWindow)
            android.os.SystemClock.sleep(80)
        }
    }
    private fun picture(ui: NativePhoneFixture): Pair<CatalogRepository, List<CatalogPage>> {
        val bitmap = androidx.core.graphics.createBitmap(64, 64)
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
        val portraits = PortraitStore(java.io.File(ui.context.filesDir, "catalog-portraits"))
        val descriptor = portraits.normalize(bytes)
        val pages = profiles.map {
            CatalogPage(
                "box",
                "Synthetic box",
                CatalogProfile(it.key.substringAfter(':'), it.label, null, descriptor),
                if (it.offline) CatalogStatus.OFFLINE else CatalogStatus.AVAILABLE,
                1
            )
        }
        return CatalogRepository(ui.context, portraits = portraits, publish = { _, _ -> }) to pages
    }

    @Test fun nativeFocusGridReplaceReorderRemoveAllThemesAndLargeText() {
        for (theme in Appearance.entries) {
            for (font in listOf(1f, 1.3f)) {
                NativePhoneFixture().use { ui ->
                    val (repository, pages) = picture(ui)
                    repository.use {
                        val state =
                            mutableStateOf(
                                TilePinPhoneState("watch", TilePinSnapshot(TilePins(), keys))
                            )
                        ui.show(theme, "watch", scroll = true) {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(density.density, font)
                            ) {
                                ProfileTilesContent(
                                    state.value,
                                    profiles,
                                    if (state.value.pending ==
                                        null
                                    ) {
                                        "Confirmed by watch"
                                    } else {
                                        "Waiting for watch confirmation"
                                    },
                                    {
                                            base,
                                            focus,
                                            grid
                                        ->
                                        state.value =
                                            state.value.queue("edit", focus, grid, base)
                                    },
                                    {},
                                    {}
                                ) { key, modifier ->
                                    DirectoryPortrait(
                                        repository,
                                        pages.single {
                                            it.key == key
                                        },
                                        modifier
                                    )
                                }
                            }
                        }
                        ui.scrollTo("Edit Focus", true)
                        ui.click("Edit Focus", true)
                        ui.click("Choose Alpha", true)
                        ui.scrollTo("Edit slot 1", true)
                        ui.click("Edit slot 1", true)
                        ui.click("Choose Beta", true)
                        ui.scrollTo("Edit slot 2", true)
                        ui.click("Edit slot 2", true)
                        ui.click("Choose Gamma", true)
                        ui.scrollTo("Edit slot 2", true)
                        ui.click("Edit slot 2", true)
                        ui.click("Move earlier")
                        ui.scrollTo("Edit slot 2", true)
                        ui.click("Edit slot 2", true)
                        ui.click("Remove pin")
                        for ((slot, name) in listOf(2 to "Beta", 3 to "Alpha", 4 to "Delta")) {
                            ui.scrollTo("Edit slot $slot", true)
                            ui.click("Edit slot $slot", true)
                            ui.scrollTo("Choose $name", true)
                            ui.click("Choose $name", true)
                        }
                        ui.scrollTo("Save to watch")
                        ui.click("Save to watch")
                        ui.await { state.value.pending != null }
                        assertEquals("box:a", state.value.pending!!.focus)
                        assertEquals(
                            listOf("box:c", "box:b", "box:a", "box:d"),
                            state.value.pending!!.grid
                        )
                        ui.instrumentation.waitForIdleSync()
                        resetScroll(ui)
                        ui.await { ui.find("Watch picture for Alpha", true) != null }
                        ui.capture("task6-${theme.name}-$font-pictures")
                        ui.scrollTo("Retry delivery")
                        ui.capture("task6-${theme.name}-$font-pending")
                    }
                }
            }
        }
    }

    @Test fun incomingWatchEditPreservesDraftBaseAndConflictRequiresReview() {
        NativePhoneFixture().use { ui ->
            val (repository, pages) = picture(ui)
            repository.use {
                val snapshot = TilePinSnapshot(TilePins(), keys)
                val state = mutableStateOf(TilePinPhoneState("watch", snapshot))
                var retries = 0
                var saves = 0
                ui.show(destination = "watch", scroll = true) {
                    ProfileTilesContent(state.value, profiles, "Review current watch settings", {
                            base,
                            focus,
                            grid
                        ->
                        saves++
                        if (saves > 1) state.value = state.value.queue("edit", focus, grid, base)
                    }, { retries++ }, {
                        state.value =
                            state.value.copy(pending = null, issue = null)
                    }) { key, modifier ->
                        DirectoryPortrait(
                            repository,
                            pages.single {
                                it.key ==
                                    key
                            },
                            modifier
                        )
                    }
                }
                ui.click("Edit Focus", true)
                ui.click("Choose Alpha", true)
                val changed = TilePins(1, "box:b")
                ui.instrumentation.runOnMainSync {
                    state.value =
                        state.value.copy(snapshot = snapshot.copy(pins = changed))
                }
                ui.scrollTo("Save to watch")
                ui.click("Save to watch")
                ui.await { saves == 1 }
                android.os.SystemClock.sleep(350)
                ui.click("Save to watch")
                ui.await { state.value.pending != null }
                assertEquals(0L, state.value.pending!!.baseRevision)
                ui.instrumentation.runOnMainSync {
                    state.value =
                        state.value.receive(
                            "watch",
                            TilePinResponse(
                                "phone",
                                "edit",
                                "conflict",
                                changed,
                                snapshot.copy(pins = changed)
                            )
                        )
                }
                resetScroll(ui)
                ui.scrollTo("On watch · Focus: Beta")
                ui.scrollTo("Apply reviewed edit")
                ui.click("Apply reviewed edit")
                ui.await { state.value.pending!!.baseRevision == 1L }
                assertEquals("box:a", state.value.pending!!.focus)
                ui.instrumentation.runOnMainSync {
                    state.value =
                        state.value.copy(issue = "rejected")
                }
                resetScroll(ui)
                ui.scrollTo("Keep watch version")
                ui.click("Keep watch version")
                ui.await { state.value.pending == null }
                assertEquals(changed, state.value.snapshot!!.pins)
                resetScroll(ui)
                ui.scrollTo("Refresh from watch")
                ui.click("Refresh from watch")
                assertEquals(1, retries)
            }
        }
    }
}
