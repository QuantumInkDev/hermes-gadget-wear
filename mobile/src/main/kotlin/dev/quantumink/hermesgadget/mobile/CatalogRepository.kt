package dev.quantumink.hermesgadget.mobile

import android.content.Context
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CatalogRepository internal constructor(private val context: CompanionApplication) {
    private val vault = CatalogVault(context)
    private val setup = ProfileVault(context)
    private val portraits = PortraitStore(File(context.filesDir, "catalog-portraits"))
    private val client = CatalogClient()
    private val sync = Executors.newSingleThreadExecutor()
    private val mutablePages = MutableStateFlow<List<CatalogPage>>(emptyList())
    val pages = mutablePages.asStateFlow()
    private val mutableNotice = MutableStateFlow("")
    val notice = mutableNotice.asStateFlow()
    private val mutableHealth = MutableStateFlow<Map<String, CatalogStatus>>(emptyMap())
    val health = mutableHealth.asStateFlow()
    private val discovery = CatalogDiscovery { source ->
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
        client.fetch(source).map { profile ->
            val asset = profile.portrait
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                deadline - System.nanoTime()
            )
            if (asset == null || portraits.file(asset.sha256) != null ||
                remaining <= 0
            ) {
                profile
            } else {
                val normalized = runCatching {
                    portraits.normalize(client.portrait(source, asset, remaining))
                }.getOrNull()
                if (normalized ==
                    null
                ) {
                    profile
                } else {
                    CatalogProfile(profile.id, profile.name, profile.gadgetUrl, normalized)
                }
            }
        }
    }
    private var active: CompletableFuture<List<CatalogPage>>? = null

    @Synchronized fun sources(): List<CatalogSource> = vault.sources()

    @Synchronized fun savedPages(): List<CatalogPage> = combined(vault.pages()).also {
        mutablePages.value =
            it
    }

    @Synchronized fun saveSource(source: CatalogSource) {
        val sources = vault.sources()
        vault.saveSources(
            if (sources.any { it.id == source.id }) {
                sources.map {
                    if (it.id ==
                        source.id
                    ) {
                        source
                    } else {
                        it
                    }
                }
            } else {
                sources + source
            }
        )
    }

    @Synchronized fun refresh(force: Boolean = false): CompletableFuture<List<CatalogPage>> {
        active?.takeIf { !it.isDone }?.let { return it }
        val sources = vault.sources()
        val old = combined(vault.pages())
        val result = CompletableFuture<List<CatalogPage>>()
        active = result
        val future = discovery.refresh(sources, old, force) { incoming ->
            synchronized(this) {
                mutableHealth.value = discovery.statuses()
                vault.savePages(combined(incoming))
                mutablePages.value = combined(incoming)
            }
        }
        future.whenComplete { incoming, failure ->
            if (failure != null) {
                mutableNotice.value = "Catalog needs attention. Cached profiles were kept."
                result.completeExceptionally(failure)
            } else {
                sync.execute {
                    try {
                        val saved =
                            synchronized(this) {
                                combined(incoming).also {
                                    vault.savePages(it)
                                    mutablePages.value =
                                        it
                                }
                            }
                        portraits.retain(saved.mapNotNull { it.profile.portrait?.sha256 }.toSet())
                        ProfileSync.publishCatalog(context, saved, portraits)
                        mutableNotice.value =
                            if (discovery.statuses().values.any { it == CatalogStatus.ATTENTION }) {
                                "A catalog connection needs attention. Cached profiles were kept."
                            } else {
                                "Profiles refreshed. New connections need watch review."
                            }
                        result.complete(saved)
                    } catch (error: Exception) {
                        mutableNotice.value = "Sync needs attention. Cached profiles were kept."
                        result.completeExceptionally(error)
                    }
                }
            }
        }
        return result
    }

    @Synchronized fun selectPhoto(profile: EndpointProfile, bytes: ByteArray) {
        val portrait = portraits.normalize(bytes)
        val previous = combined(vault.pages())
        val id = ClientSpeech.endpointId(profile.endpoint)
        val updated = previous.map { page ->
            if (page.profile.gadgetUrl == profile.endpoint.url ||
                (page.sourceId == "manual" && page.profile.id == id)
            ) {
                CatalogPage(
                    page.sourceId,
                    page.sourceLabel,
                    CatalogProfile(
                        page.profile.id,
                        page.profile.name,
                        page.profile.gadgetUrl,
                        portrait
                    ),
                    page.status,
                    page.checkedAt
                )
            } else {
                page
            }
        }
        vault.savePages(updated)
        mutablePages.value = updated
        portraits.retain(updated.mapNotNull { it.profile.portrait?.sha256 }.toSet())
        ProfileSync.publishCatalog(context, updated, portraits)
    }

    @Synchronized fun movePage(key: String, delta: Int) {
        val pages = combined(vault.pages()).toMutableList()
        val index = pages.indexOfFirst { it.key == key }
        require(index >= 0)
        val target = (index + delta).coerceIn(0, pages.lastIndex)
        pages.add(target, pages.removeAt(index))
        vault.savePages(pages)
        mutablePages.value = pages
        discovery.invalidate()
        ProfileSync.publishCatalog(context, pages, portraits)
    }

    private fun combined(previous: List<CatalogPage>): List<CatalogPage> {
        val discovered = previous.filter { it.sourceId != "manual" }
        val known = discovered.mapNotNull { it.profile.gadgetUrl }.toSet()
        val manual = setup.profiles().filter { it.endpoint.url !in known }.map { profile ->
            val id = ClientSpeech.endpointId(profile.endpoint)
            CatalogPage(
                "manual",
                "Saved on phone",
                CatalogProfile(
                    id,
                    profile.label,
                    profile.endpoint.url.takeIf {
                        profile.endpoint.isTls
                    },
                    previous.singleOrNull {
                        it.sourceId ==
                            "manual" &&
                            it.profile.id == id
                    }?.profile?.portrait
                ),
                CatalogStatus.AVAILABLE,
                0
            )
        }
        val order = previous.mapIndexed { index, page -> page.key to index }.toMap()
        return (discovered + manual).sortedBy {
            order[it.key] ?: Int.MAX_VALUE
        }.also { ProfileCatalog.encodePages(it) }
    }

    companion object {
        fun get(context: Context): CatalogRepository =
            (context.applicationContext as CompanionApplication).catalog
    }
}
