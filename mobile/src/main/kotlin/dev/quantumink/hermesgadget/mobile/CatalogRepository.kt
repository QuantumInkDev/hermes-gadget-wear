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
    private val mutableWatchPictures = MutableStateFlow<Set<String>>(emptySet())
    val watchPictures = mutableWatchPictures.asStateFlow()
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
        val old = combined(vault.pages(), false)
        portraits.protect(references(old))
        val result = CompletableFuture<List<CatalogPage>>()
        active = result
        val version = revision
        val future = discovery.refresh(sources, old, force) { incoming ->
            synchronized(this) {
                if (version != revision || result.isCancelled) return@refresh
                mutableHealth.value = discovery.statuses()
                val raw = combined(incoming, false)
                vault.savePages(raw)
                val saved = combined(raw)
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
                            val raw = combined(incoming, false)
                            vault.savePages(raw)
                            val saved = combined(raw)
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
        val previous = combined(vault.pages(), false)
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
        val raw = combined(
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
            },
            false
        )
        val keys = raw.filter {
            it.profile.gadgetUrl == profile.endpoint.url
        }.map { it.key }.toSet()
        vault.saveOverrides(
            vault.overrides().filterNot { it.key in keys } + raw.filter { it.key in keys }.map {
                withPortrait(it, portrait)
            }
        )
        vault.savePages(raw)
        val updated = combined(raw)
        mutablePages.value = updated
        portraits.protect(references(updated))
        portraits.retain(references(updated))
        publish(updated, portraits)
    }

    @Synchronized fun selectWatchPhoto(key: String, bytes: ByteArray) {
        val raw = combined(vault.pages(), false)
        val page = raw.single { it.key == key }
        portraits.protect(references(raw))
        val portrait = portraits.normalize(bytes)
        vault.saveOverrides(
            vault.overrides().filterNot {
                it.key == key
            } + withPortrait(page, portrait)
        )
        invalidate()
        publishPictures(raw)
    }

    @Synchronized fun useBoxPicture(key: String) {
        val raw = combined(vault.pages(), false)
        val page = raw.single { it.key == key }
        val fallbackId = page.profile.gadgetUrl?.let { url ->
            ClientSpeech.endpointId(dev.quantumink.hermesgadget.protocol.Endpoint.parse(url))
        } ?: page.profile.id.takeIf { page.sourceId == "manual" }
        vault.saveFallbacks(vault.fallbacks().filterNot { it.profile.id == fallbackId })
        vault.saveOverrides(vault.overrides().filterNot { it.key == key })
        vault.savePages(raw)
        invalidate()
        publishPictures(raw)
    }

    private fun publishPictures(raw: List<CatalogPage>) {
        val effective = combined(raw)
        mutablePages.value = effective
        portraits.protect(references(raw))
        portraits.retain(references(raw))
        publish(effective, portraits)
    }

    private fun withPortrait(page: CatalogPage, portrait: PortraitDescriptor): CatalogPage =
        CatalogPage(
            page.sourceId,
            page.sourceLabel,
            CatalogProfile(page.profile.id, page.profile.name, page.profile.gadgetUrl, portrait),
            page.status,
            page.checkedAt
        )

    @Synchronized fun retirePage(key: String) {
        val previous = vault.pages()
        require(previous.single { it.key == key }.status == CatalogStatus.WITHDRAWN)
        val remaining = previous.filterNot { it.key == key }
        vault.saveOverrides(vault.overrides().filterNot { it.key == key })
        retireFallbacks(remaining)
        val raw = combined(remaining, false)
        vault.savePages(raw)
        val pages = combined(raw)
        mutablePages.value = pages
        invalidate()
        portraits.protect(references(pages))
        portraits.retain(references(pages))
        publish(pages, portraits)
    }

    /** Called after owner-confirmed removal from the independent phone setup vault. */
    @Synchronized fun setupRemoved() {
        retireFallbacks(vault.pages())
        val raw = combined(vault.pages(), false)
        val retained = raw.map { it.key }.toSet()
        vault.saveOverrides(vault.overrides().filter { it.key in retained })
        vault.savePages(raw)
        val pages = combined(raw)
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
        val pages = combined(vault.pages(), false).toMutableList()
        val index = pages.indexOfFirst { it.key == key }
        require(index >= 0)
        val target = (index + delta).coerceIn(0, pages.lastIndex)
        pages.add(target, pages.removeAt(index))
        vault.savePages(pages)
        val effective = combined(pages)
        mutablePages.value = effective
        invalidate()
        publish(effective, portraits)
    }

    private fun combined(
        previous: List<CatalogPage>,
        applyOverrides: Boolean = true
    ): List<CatalogPage> {
        val overrides = vault.overrides().associateBy { it.key }
        mutableWatchPictures.value = overrides.keys
        val fallbacks = vault.fallbacks().associate { it.profile.id to it.profile.portrait }
        val discovered = previous.filter { it.sourceId != "manual" }.map { page ->
            val fallback = page.profile.gadgetUrl?.let { url ->
                fallbacks[
                    ClientSpeech.endpointId(
                        dev.quantumink.hermesgadget.protocol.Endpoint.parse(url)
                    )
                ]
            }
            if (applyOverrides && page.profile.portrait == null && fallback != null) {
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
                    fallbacks[id].takeIf { applyOverrides }
                ),
                CatalogStatus.AVAILABLE,
                0
            )
        }
        val order = previous.mapIndexed { index, page -> page.key to index }.toMap()
        return (discovered + manual).map { page ->
            val portrait = overrides[page.key]?.profile?.portrait
            if (applyOverrides && portrait != null) withPortrait(page, portrait) else page
        }.sortedBy {
            order[it.key] ?: Int.MAX_VALUE
        }.also { ProfileCatalog.encodePages(it) }
    }

    private fun references(pages: List<CatalogPage>): Set<String> =
        (pages + vault.pages() + vault.fallbacks() + vault.overrides())
            .mapNotNull { it.profile.portrait?.sha256 }.toSet()

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
