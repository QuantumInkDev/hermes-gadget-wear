package dev.quantumink.hermesgadget.mobile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhonePictureSheetTest {
    @Test fun fullAppTitleLeadsTheSmallerDestinationHeading() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            ui.show(destination = "profiles") { modifier ->
                Column(modifier) {
                    Text("Your profiles", style = MaterialTheme.typography.titleMedium)
                }
            }
            val title = ui.find("Quantum Relay for Hermes Agents")
            assertTrue("Full app name is missing", title != null)
            val titleBounds = Rect().also(requireNotNull(title)::getBoundsInScreen)
            val heading = Rect().also(requireNotNull(ui.find("Your profiles"))::getBoundsInScreen)
            assertTrue(
                "Section heading still dominates the app name",
                titleBounds.height() > heading.height()
            )
        }
    }

    @Test fun bothPictureEntryPointsHaveAFullWidthCancelThatPreservesThePicture() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        NativePhoneFixture().use { ui ->
            val id = UUID.randomUUID().toString()
            val root = File(ui.context.cacheDir, "picture-sheet-$id").apply { mkdirs() }
            val aliases = listOf("hermes.test.picture.$id", "hermes.test.picture.setup.$id")
            val vault = CatalogVault(ui.context, File(root, "vault"), aliases[0])
            val setup = ProfileVault(ui.context, File(root, "setup"), aliases[1])
            vault.saveSources(
                listOf(
                    CatalogSource(
                        "studio",
                        "Studio",
                        "https://studio.example.invalid/catalog/v1",
                        "synthetic"
                    )
                )
            )
            vault.savePages(
                listOf(
                    CatalogPage(
                        "studio",
                        "Studio",
                        CatalogProfile(
                            "orbit",
                            "Orbit",
                            "wss://orbit.example.invalid/gadget",
                            null
                        ),
                        CatalogStatus.AVAILABLE,
                        1
                    )
                )
            )
            val repository =
                CatalogRepository(
                    ui.context,
                    vault,
                    setup,
                    PortraitStore(
                        File(root, "portraits")
                    ),
                    publish = {
                            _,
                            _
                        ->
                    }
                )
            try {
                repository.savedPages()
                for (catalog in listOf(false, true)) {
                    if (catalog) {
                        ui.show(destination = "settings", scroll = true) {
                            CatalogSettingsContent(ui.context, repository, {})
                        }
                        ui.scrollTo("Profile: Options")
                        ui.click("Profile: Options")
                        ui.click("Picture options")
                    } else {
                        ui.show(destination = "profiles") { modifier ->
                            DirectoryContent(ui.context, repository, {}, {}, {}, modifier)
                        }
                        ui.await { ui.find("Options for Orbit", true) != null }
                        ui.click("Options for Orbit", true)
                        ui.click("Watch picture")
                    }
                    ui.await { ui.find("Cancel") != null }
                    val cancel = clickTarget(requireNotNull(ui.find("Cancel")))
                    val bounds = Rect().also(cancel::getBoundsInScreen)
                    val width = ui.context.resources.displayMetrics.widthPixels
                    assertTrue(
                        "Cancel is still crowded into the dialog's button slot: $bounds",
                        bounds.width() > width * .7
                    )
                    assertTrue(bounds.bottom <= ui.context.resources.displayMetrics.heightPixels)
                    ui.capture(if (catalog) "picture-sheet-catalog" else "picture-sheet-directory")
                    val before = vault.overrides()
                    ui.click("Cancel")
                    ui.await { ui.find("Cancel") == null }
                    assertEquals(before, vault.overrides())
                }
            } finally {
                repository.close()
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                aliases.forEach { if (keys.containsAlias(it)) keys.deleteEntry(it) }
                root.deleteRecursively()
            }
        }
    }

    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
}
