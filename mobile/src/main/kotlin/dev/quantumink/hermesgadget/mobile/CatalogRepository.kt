package dev.quantumink.hermesgadget.mobile

import android.content.Context
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.EndpointProfile
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CatalogRepository internal constructor(
    private val context: Context,
    private val vault: CatalogVault = CatalogVault(context),
    private val setup: ProfileVault = ProfileVault(context),
    private val portraits: PortraitStore =
        PortraitStore(File(context.filesDir, "catalog-portraits")),
    private val client: CatalogClient = CatalogClient(),
    private val fetchProfiles: (CatalogSource) -> List<CatalogProfile> = client::fetch,
    private val fetchPortrait: (
        CatalogSource,
        PortraitDescriptor,
        Long
    ) -> ByteArray = client::portrait,
    private val publish: (
        List<CatalogPage>,
        PortraitStore
    ) -> Unit = { pages, store -> ProfileSync.publishCatalog(context, pages, store) }
) : AutoCloseable {
    private val sync = Executors.newSingleThreadExecutor()
    private val mutablePages = MutableStateFlow<List<CatalogPage>>(emptyList())
    val pages = mutablePages.asStateFlow()
    private val mutableNotice = MutableStateFlow("")
    val notice = mutableNotice.asStateFlow()
    private val mutableHealth = MutableStateFlow<Map<String, CatalogStatus>>(emptyMap())
    val health = mutableHealth.asStateFlow()
    private val discovery = CatalogDiscovery { source ->
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
        fetchProfiles(source).map { profile ->
            val asset = profile.portrait
            val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                deadline - System.nanoTime()
            )
            if (asset == null || portraits.file(asset.sha256) != null) {
                profile
            } else {
                val normalized = if (remaining > 0) {
                    runCatching {
                        portraits.normalize(fetchPortrait(source, asset, remaining))
                    }.getOrNull()
                } else {
                    null
                }
                val fallback = normalized ?: synchronized(this) {
                    vault.pages().singleOrNull {
                        it.sourceId == source.id && it.profile.id == profile.id
                    }?.profile?.portrait?.takeIf { portraits.file(it.sha256) != null }
                }
                CatalogProfile(profile.id, profile.name, profile.gadgetUrl, fallback)
            }
        }
    }
    private var active: CompletableFuture<List<CatalogPage>>? = null
    private var revision = 0L
    private fun invalidate() {
        revision++
        active?.cancel(false)
        active = null
        discovery.invalidate()
    }

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
        invalidate()
    }

    @Synchronized fun refresh(force: Boolean = false): CompletableFuture<List<CatalogPage>> {
        active?.takeIf { !it.isDone }?.let { return it }
        val sources = vault.sources()
        val old = combined(vault.pages())
        portraits.protect(references(old))
        val result = CompletableFuture<List<CatalogPage>>()
        active = result
        val version = revision
        val future = discovery.refresh(sources, old, force) { incoming ->
            synchronized(this) {
                if (version != revision || result.isCancelled) return@refresh
                mutableHealth.value = discovery.statuses()
                vault.savePages(combined(incoming))
                val saved = combined(incoming)
                mutablePages.value = saved
                portraits.protect(references(saved))
                sync.execute {
                    synchronized(this) {
                        if (version == revision && !result.isCancelled) {
                            runCatching { publish(saved, portraits) }.onFailure {
                                mutableNotice.value =
                                    "Sync needs attention. Cached profiles were kept."
                            }
                        }
                    }
                }
            }
        }
        future.whenComplete { incoming, failure ->
            if (synchronized(this) { version != revision || result.isCancelled }) {
                cleanup()
                result.cancel(false)
                return@whenComplete
            }
            if (failure != null) {
                cleanup()
                mutableNotice.value = "Catalog needs attention. Cached profiles were kept."
                result.completeExceptionally(failure)
            } else {
                sync.execute {
                    try {
                        synchronized(this) {
                            if (version != revision || result.isCancelled) return@execute
                            val saved = combined(incoming)
                            vault.savePages(saved)
                            mutablePages.value = saved
                            portraits.protect(references(saved))
                            portraits.retain(references(saved))
                            publish(saved, portraits)
                            mutableNotice.value =
                                if (discovery.statuses().values.any {
                                        it == CatalogStatus.ATTENTION
                                    }
                                ) {
                                    "A catalog connection needs attention. Cached profiles were kept."
                                } else {
                                    "Profiles refreshed. New connections need watch review."
                                }
                            result.complete(saved)
                        }
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
        val previous = combined(vault.pages())
        portraits.protect(references(previous))
        val portrait = portraits.normalize(bytes)
        invalidate()
        val id = ClientSpeech.endpointId(profile.endpoint)
        val fallback = CatalogPage(
            "manual",
            "Phone picture",
            CatalogProfile(
                id,
                profile.label,
                profile.endpoint.url.takeIf {
                    profile.endpoint.isTls
                },
                portrait
            ),
            CatalogStatus.AVAILABLE,
            0
        )
        val oldFallback = vault.fallbacks().singleOrNull { it.profile.id == id }?.profile?.portrait
        vault.saveFallbacks(vault.fallbacks().filterNot { it.profile.id == id } + fallback)
        val updated = combined(
            previous.map { page ->
                if (page.profile.gadgetUrl == profile.endpoint.url && oldFallback != null &&
                    page.profile.portrait?.sha256 == oldFallback.sha256
                ) {
                    CatalogPage(
                        page.sourceId,
                        page.sourceLabel,
                        CatalogProfile(
                            page.profile.id,
                            page.profile.name,
                            page.profile.gadgetUrl,
                            null
                        ),
                        page.status,
                        page.checkedAt
                    )
                } else {
                    page
                }
            }
        )
        vault.savePages(updated)
        mutablePages.value = updated
        portraits.protect(references(updated))
        portraits.retain(references(updated))
        publish(updated, portraits)
    }

    @Synchronized fun retirePage(key: String) {
        val previous = vault.pages()
        require(previous.single { it.key == key }.status == CatalogStatus.WITHDRAWN)
        val remaining = previous.filterNot { it.key == key }
        retireFallbacks(remaining)
        val pages = combined(remaining)
        vault.savePages(pages)
        mutablePages.value = pages
        invalidate()
        portraits.protect(references(pages))
        portraits.retain(references(pages))
        publish(pages, portraits)
    }

    /** Called after owner-confirmed removal from the independent phone setup vault. */
    @Synchronized fun setupRemoved() {
        retireFallbacks(vault.pages())
        val pages = combined(vault.pages())
        vault.savePages(pages)
        mutablePages.value = pages
        invalidate()
        portraits.protect(references(pages))
        portraits.retain(references(pages))
        publish(pages, portraits)
    }

    private fun retireFallbacks(pages: List<CatalogPage>) {
        val retained = setup.profiles().map { ClientSpeech.endpointId(it.endpoint) }.toSet() +
            pages.filter { it.sourceId != "manual" }.mapNotNull { page ->
                page.profile.gadgetUrl?.let { url ->
                    ClientSpeech.endpointId(
                        dev.quantumink.hermesgadget.protocol.Endpoint.parse(url)
                    )
                }
            }
        vault.saveFallbacks(vault.fallbacks().filter { it.profile.id in retained })
    }

    @Synchronized fun movePage(key: String, delta: Int) {
        val pages = combined(vault.pages()).toMutableList()
        val index = pages.indexOfFirst { it.key == key }
        require(index >= 0)
        val target = (index + delta).coerceIn(0, pages.lastIndex)
        pages.add(target, pages.removeAt(index))
        vault.savePages(pages)
        mutablePages.value = pages
        invalidate()
        publish(pages, portraits)
    }

    private fun combined(previous: List<CatalogPage>): List<CatalogPage> {
        val fallbacks = vault.fallbacks().associate { it.profile.id to it.profile.portrait }
        val discovered = previous.filter { it.sourceId != "manual" }.map { page ->
            val fallback = page.profile.gadgetUrl?.let { url ->
                fallbacks[
                    ClientSpeech.endpointId(
                        dev.quantumink.hermesgadget.protocol.Endpoint.parse(url)
                    )
                ]
            }
            if (page.profile.portrait == null && fallback != null) {
                CatalogPage(
                    page.sourceId,
                    page.sourceLabel,
                    CatalogProfile(
                        page.profile.id,
                        page.profile.name,
                        page.profile.gadgetUrl,
                        fallback
                    ),
                    page.status,
                    page.checkedAt
                )
            } else {
                page
            }
        }
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
                    fallbacks[id] ?: previous.singleOrNull {
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

    private fun references(pages: List<CatalogPage>): Set<String> =
        (pages + vault.fallbacks()).mapNotNull { it.profile.portrait?.sha256 }.toSet()

    @Synchronized private fun cleanup() {
        val keep = references(combined(vault.pages()))
        portraits.protect(keep)
        portraits.retain(keep)
    }

    override fun close() {
        discovery.close()
        sync.shutdownNow()
    }

    companion object {
        fun get(context: Context): CatalogRepository =
            (context.applicationContext as CompanionApplication).catalog
    }
}
