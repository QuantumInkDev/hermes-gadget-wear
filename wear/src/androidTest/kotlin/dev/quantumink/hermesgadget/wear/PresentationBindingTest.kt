package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationBindingTest {
    @Test fun lateAtlasForOldEndpointCannotReplaceMountedProfile() {
        val a =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8701/gadget", true, true),
                "Same name",
                ""
            )
        val b =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8702/gadget", true, true),
                "Same name",
                ""
            )
        val bitmap = Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888)
        val atlas = PetAtlas("Synthetic", 1100, mapOf("idle" to listOf(bitmap)))
        val current = WatchState(profile = b)
        val late = current.withPresentationPet(a.endpoint.url, atlas)
        assertNull(late.pet)
        val accepted = current.withPresentationPet(b.endpoint.url, atlas)
        assertSame(atlas, accepted.pet)
        assertEquals(b.endpoint.url, accepted.petEndpoint)
    }

    @Test fun cachedArtworkIsRejectedWhenAStablePageKeyGetsADifferentEndpoint() {
        val a =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8701/gadget", true, true),
                "Same name",
                ""
            )
        val b =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8702/gadget", true, true),
                "Same name",
                ""
            )
        val oldPage = ProfilePage("synthetic:reused", "Same name", "Fixture", a, null)
        val page = ProfilePage("synthetic:reused", "Same name", "Fixture", b, null)
        val bitmap = Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888)
        val art = PageArtwork(bitmap, PetAtlas("Synthetic", 1100, mapOf("idle" to listOf(bitmap))))
        val stale =
            WatchState(
                artwork = mapOf(page.key to art),
                artworkIdentities = mapOf(page.key to oldPage.presentationKey()),
                presentationPets = mapOf(oldPage.presentationKey() to requireNotNull(art.pet))
            )
        assertNull(page.presentationArtwork(stale))
        assertNull(page.presentationPet(stale))
        val current = stale.copy(
            artworkIdentities = mapOf(page.key to page.presentationKey()),
            presentationPets = mapOf(page.presentationKey() to requireNotNull(art.pet))
        )
        assertSame(art, page.presentationArtwork(current))
        assertSame(art.pet, page.presentationPet(current))
        bitmap.recycle()
    }

    @Test fun untaggedCachedAtlasCannotBorrowTheCurrentRequestIdentity() {
        val endpoint =
            EndpointProfile(
                Endpoint.parse("ws://localhost:8702/gadget", true, true),
                "Same name",
                ""
            )
        val page = ProfilePage("synthetic:reused", "Same name", "Fixture", endpoint, null)
        val bitmap = Bitmap.createBitmap(96, 104, Bitmap.Config.ARGB_8888)
        val oldArt =
            PageArtwork(null, PetAtlas("Old callback", 1100, mapOf("idle" to listOf(bitmap))))
        val watch = WatchState(artworkIdentities = mapOf(page.key to page.presentationKey()))
        assertNull(page.presentationPet(watch.copy(artwork = mapOf(page.key to oldArt))))
        bitmap.recycle()
    }

    @Test fun unsupportedAtlasCannotBePresentedAsARealPet() {
        val page = ProfilePage("synthetic:unsupported", "Synthetic", "Fixture", null, null)
        assertNull(
            page.presentationPet(
                WatchState(
                    presentationPets = mapOf(
                        page.presentationKey() to PetAtlas("Unsupported", 1100, emptyMap())
                    )
                )
            )
        )
    }

    @Test fun choicesPublishOnlyAfterPersistenceWithoutConnectingOrChangingTurn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = java.io.File(context.cacheDir, "presentation-binding-${System.nanoTime()}")
        val key = PresentationKey.of("synthetic:page", null)
        val watch = WatchState(selectedPage = "synthetic:page", captureActive = true)
        val before = watch.connection
        val store = PresentationStore(file)
        val changed = watch.copy(presentation = store.set(key, PresentationMode.PET))
        assertEquals(PresentationMode.PORTRAIT, watch.presentation[key])
        assertEquals(PresentationMode.PET, changed.presentation[key])
        assertSame(before, changed.connection)
        assertEquals(watch.selectedPage, changed.selectedPage)
        assertTrue(changed.captureActive)
        file.delete()
    }
}
