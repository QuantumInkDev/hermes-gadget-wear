package dev.quantumink.hermesgadget.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text

internal fun ProfilePage.presentationKey(): PresentationKey = PresentationKey.of(
    key,
    approved?.endpoint?.url ?: discovered?.profile?.gadgetUrl
)

internal fun WatchState.withPresentationPet(endpoint: String, atlas: PetAtlas): WatchState =
    if (profile?.endpoint?.url == endpoint) copy(pet = atlas, petEndpoint = endpoint) else this

internal fun ProfilePage.presentationArtwork(watch: WatchState): PageArtwork? =
    watch.artwork[key]?.takeIf { watch.artworkIdentities[key] == presentationKey() }

/** Preserve the verified byte owner through publication, including ambient's raw projection. */
internal fun WatchState.withPublishedArtwork(incoming: Map<String, PageArtwork>): WatchState {
    val expected = ProfilePages.merge(profiles, catalog).associate {
        it.key to it.presentationKey()
    }
    val pictures = incoming.filter { (key, art) ->
        (art.portrait == null && art.animation == null) ||
            (art.portraitIdentity != null && art.portraitIdentity == expected[key])
    }.mapValues { (_, art) ->
        PageArtwork(art.portrait, null, art.animation, art.portraitIdentity)
    }
    val identities = pictures.mapNotNull { (key, art) ->
        art.portraitIdentity?.let { key to it }
    }.toMap()
    return copy(artwork = pictures, artworkIdentities = identities)
}

internal fun ProfilePage.presentationPet(watch: WatchState): PetAtlas? {
    // PageArtwork has no endpoint provenance. Use only the service's captured cache identity.
    val cached = watch.presentationPets[presentationKey()]
    val candidate = if (approved != null && approved.endpoint.url == watch.petEndpoint &&
        approved.endpoint.url == watch.profile?.endpoint?.url
    ) {
        watch.pet ?: cached
    } else {
        cached
    }
    return candidate?.takeIf { !it.rows["idle"].isNullOrEmpty() }
}

/** Each control is a separate scroll item, including at large font sizes. */
internal fun TransformingLazyColumnScope.presentationOptions(
    page: ProfilePage,
    watch: WatchState,
    select: (PresentationKey, PresentationMode) -> Unit
) {
    val key = page.presentationKey()
    val mode = watch.presentation[key]
    val artwork = page.presentationArtwork(watch)
    val pet = page.presentationPet(watch)
    item { Heading("Artwork") }
    if (mode == PresentationMode.PORTRAIT && artwork?.portrait == null) {
        item { Body("No portrait yet. You can choose Pet below.", muted = true) }
    } else if (mode == PresentationMode.PET && pet == null) {
        item {
            Body(
                "Pet unavailable or unsupported. Showing retained portrait or placeholder.",
                muted = true
            )
        }
    }
    if (watch.presentationNotice.isNotBlank()) {
        item { Body(watch.presentationNotice, muted = true) }
    }
    PresentationMode.entries.forEach { option ->
        item {
            RadioButton(
                selected = mode == option,
                onSelect = { select(key, option) },
                enabled = !watch.presentationSaving,
                modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
                label = { Text(if (option == PresentationMode.PORTRAIT) "Portrait" else "Pet") }
            )
        }
    }
}
