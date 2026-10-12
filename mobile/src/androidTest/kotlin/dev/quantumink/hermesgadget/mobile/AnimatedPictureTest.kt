package dev.quantumink.hermesgadget.mobile

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.client.AnimationCache
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedPictureTest {
    private fun instrumentationContext() = InstrumentationRegistry.getInstrumentation().context

    @Test fun gifOverrideKeepsPngContractSurvivesRestartAndStaticReplacementClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "animation-test-" + UUID.randomUUID())
        val alias = "test.animation." + UUID.randomUUID()
        val vault = CatalogVault(context, File(root, "vault"), alias)
        val setupAlias = alias + ".setup"
        val setup = ProfileVault(context, File(root, "setup"), setupAlias)
        val portraits = PortraitStore(File(root, "portraits"))
        val animations = AnimationCache(File(root, "animations"))
        val page = CatalogPage(
            "box",
            "Synthetic box",
            CatalogProfile("agent", "Agent", null, null),
            CatalogStatus.AVAILABLE,
            0
        )
        vault.savePages(listOf(page))
        fun repository() = CatalogRepository(
            context,
            vault,
            setup,
            portraits,
            animations = animations,
            publish = { _, _ -> }
        )
        var repository = repository()
        try {
            val gif = instrumentationContext().assets.open("original-two-frame.gif").use {
                it.readBytes()
            }
            repository.selectWatchPhoto(page.key, gif)
            val selected = repository.savedPages().single()
            val descriptor = vault.animations().getValue(page.key)
            assertEquals(descriptor.fallbackSha256, selected.profile.portrait!!.sha256)
            assertNotNull(portraits.file(descriptor.fallbackSha256))
            assertNotNull(animations.file(descriptor))
            assertEquals(2, GifPortrait.validate(animations.file(descriptor)!!.readBytes()).frames)
            repository.close()
            repository = repository()
            assertEquals(descriptor, vault.animations().getValue(page.key))
            assertEquals(
                descriptor.fallbackSha256,
                repository.savedPages().single().profile.portrait!!.sha256
            )
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val png = try {
                ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
            } finally {
                bitmap.recycle()
            }
            repository.selectWatchPhoto(page.key, png)
            assertTrue(vault.animations().isEmpty())
            assertFalse(
                repository.savedPages().single().profile.portrait!!.sha256 ==
                    descriptor.fallbackSha256
            )
            repository.useBoxPicture(page.key)
            assertTrue(vault.animations().isEmpty())
        } finally {
            repository.close()
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
                deleteEntry(setupAlias)
            }
        }
    }
}
