package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor

class ProfilePage(
    val key: String,
    val label: String,
    val source: String,
    val approved: EndpointProfile?,
    val discovered: CatalogPage?
) {
    val portrait: PortraitDescriptor? get() = discovered?.profile?.portrait
    val status: CatalogStatus get() = discovered?.status ?: CatalogStatus.AVAILABLE
    override fun toString(): String = "ProfilePage(redacted)"
}

class PageArtwork(val portrait: Bitmap?, val pet: PetAtlas?)

object ProfilePages {
    fun merge(profiles: List<EndpointProfile>, catalog: List<CatalogPage>): List<ProfilePage> {
        val known = profiles.associateBy { it.endpoint.url }
        val discovered = catalog.map { page ->
            val profile = page.profile.gadgetUrl?.let { known[it] }
                ?: profiles.singleOrNull {
                    page.sourceId == "manual" &&
                        ClientSpeech.endpointId(it.endpoint) == page.profile.id
                }
            ProfilePage(page.key, page.profile.name, page.sourceLabel, profile, page)
        }
        val shown = discovered.mapNotNull { it.approved?.endpoint?.url }.toSet()
        return discovered + profiles.filter { it.endpoint.url !in shown }.map { profile ->
            ProfilePage(
                "watch:${ClientSpeech.endpointId(profile.endpoint)}",
                profile.label,
                "Saved on watch",
                profile,
                null
            )
        }
    }
}
