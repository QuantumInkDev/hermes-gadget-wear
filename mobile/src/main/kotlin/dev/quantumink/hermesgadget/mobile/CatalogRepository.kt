package dev.quantumink.hermesgadget.mobile

import android.content.Context
import dev.quantumink.hermesgadget.protocol.CatalogOrder
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.Endpoint
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
    private val animations: dev.quantumink.hermesgadget.client.AnimationCache =
        dev.quantumink.hermesgadget.client.AnimationCache(
            File(context.filesDir, "catalog-animations")
        ),
    private val client: CatalogClient = CatalogClient(),
    private val fetchProfiles: (CatalogSource) -> List<CatalogProfile> = client::fetch,
    private val fetchPortrait: (
        CatalogSource,
        PortraitDescriptor,
        Long
    ) -> ByteArray = client::portrait,
    private val publish: ((List<CatalogPage>, PortraitStore) -> Unit)? = null
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

    @Synchronized fun sources(): List<CatalogSource> {
        val order = sourceOrder()
        return vault.sources().sortedBy { order.indexOf(it.id) }
    }

    @Synchronized fun sourceOrder(): List<String> = sourceOrder(vault.pages())

    private fun sourceOrder(previous: List<CatalogPage>): List<String> {
        val configured = vault.sources().map { it.id } + "manual"
        val saved = vault.sourceOrder()
        val initial = saved ?: CatalogOrder.group(previous).map { it.first().sourceId }
        val order = (initial.filter { it in configured } + configured).distinct()
        if (saved != order) vault.saveSourceOrder(order)
        return order
    }

    /** Reads only normalized, hash-addressed local artwork; callers decode on an IO worker. */
    fun portraitFile(hash: String): File? = portraits.file(hash)

    /** Catalog credentials are deliberately absent from this conversation projection. */
    @Synchronized fun profileFor(page: CatalogPage): EndpointProfile? {
        val profiles = setup.profiles()
        val url = page.profile.gadgetUrl
        if (url == null) {
            return if (page.sourceId == "manual") {
                profiles.singleOrNull { ClientSpeech.endpointId(it.endpoint) == page.profile.id }
            } else {
                null
            }
        }
        val endpoint = Endpoint.parse(url)
        val saved = profiles.singleOrNull { it.endpoint.url == endpoint.url }
        return EndpointProfile(
            saved?.endpoint ?: endpoint,
            page.profile.name,
            saved?.accessToken.orEmpty()
        )
    }

    @Synchronized fun savedPages(): List<CatalogPage> {
        val previous = vault.pages()
        val raw = combined(previous, false)
        if (previous.map { it.key } != raw.map { it.key }) vault.savePages(raw)
        return combined(raw).also {
            mutablePages.value = it
            dev.quantumink.hermesgadget.mobile.widgets.HomeWidgets.publish(context, it)
        }
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
                publishCurrent(saved, version, result).whenComplete { _, error ->
                    synchronized(this) {
                        if (version == revision && !result.isCancelled && error != null) {
                            mutableNotice.value = "Sync needs attention. Cached profiles were kept."
                        }
                    }
                }
            }
        }
        future.whenComplete { incoming, failure ->
            synchronized(this) {
                if (version != revision || result.isCancelled) {
                    cleanup()
                    result.cancel(false)
                    return@whenComplete
                }
                if (failure != null) {
                    cleanup()
                    mutableNotice.value = "Catalog needs attention. Cached profiles were kept."
                    result.completeExceptionally(failure)
                } else {
                    try {
                        val raw = combined(incoming, false)
                        vault.savePages(raw)
                        val saved = combined(raw)
                        mutablePages.value = saved
                        portraits.protect(references(saved))
                        portraits.retain(references(saved))
                        publishCurrent(saved, version, result).whenComplete { _, error ->
                            synchronized(this) {
                                if (version == revision && !result.isCancelled) {
                                    if (error == null) {
                                        mutableNotice.value = if (discovery.statuses().values.any {
                                                it == CatalogStatus.ATTENTION
                                            }
                                        ) {
                                            "A catalog connection needs attention. Cached profiles were kept."
                                        } else {
                                            "Profiles refreshed. New connections need watch review."
                                        }
                                        result.complete(saved)
                                    } else {
                                        mutableNotice.value =
                                            "Sync needs attention. Cached profiles were kept."
                                        result.completeExceptionally(error)
                                    }
                                }
                            }
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

    fun selectPhoto(profile: EndpointProfile, bytes: ByteArray) = change {
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
        vault.saveAnimations(vault.animations().filterKeys { it !in keys && it != "manual:$id" })
        vault.savePages(raw)
        val updated = combined(raw)
        mutablePages.value = updated
        portraits.protect(references(updated))
        portraits.retain(references(updated))
        publishCurrent(updated)
    }

    fun selectWatchPhoto(key: String, bytes: ByteArray) = change {
        val raw = combined(vault.pages(), false)
        val page = raw.single { it.key == key }
        portraits.protect(references(raw))
        val previousAnimations = vault.animations()
        animations.protect(previousAnimations.values.map { it.sha256 }.toSet())
        val gif = if (bytes.size >= 6 &&
            bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF"
        ) {
            dev.quantumink.hermesgadget.protocol.GifPortrait.normalize(bytes)
        } else {
            null
        }
        val portrait = portraits.normalize(
            gif?.let { dev.quantumink.hermesgadget.client.GifDecoder.firstFrame(it.bytes) } ?: bytes
        )
        val animation = gif?.let {
            dev.quantumink.hermesgadget.protocol.AnimationDescriptor(
                dev.quantumink.hermesgadget.client.AnimationCache.sha256(it.bytes),
                it.bytes.size,
                portrait.sha256
            ).also { descriptor -> animations.stage(descriptor, it.bytes) }
        }
        vault.saveOverrides(
            vault.overrides().filterNot {
                it.key == key
            } + withPortrait(page, portrait)
        )
        vault.saveAnimations(
            previousAnimations - key +
                (animation?.let { mapOf(key to it) } ?: emptyMap())
        )
        invalidate()
        publishPictures(raw)
    }

    fun useBoxPicture(key: String) = change {
        val raw = combined(vault.pages(), false)
        val page = raw.single { it.key == key }
        val fallbackId = page.profile.gadgetUrl?.let { url ->
            ClientSpeech.endpointId(dev.quantumink.hermesgadget.protocol.Endpoint.parse(url))
        } ?: page.profile.id.takeIf { page.sourceId == "manual" }
        vault.saveFallbacks(vault.fallbacks().filterNot { it.profile.id == fallbackId })
        vault.saveOverrides(vault.overrides().filterNot { it.key == key })
        vault.saveAnimations(vault.animations() - key)
        vault.savePages(raw)
        invalidate()
        publishPictures(raw)
    }

    private fun publishPictures(raw: List<CatalogPage>) {
        val effective = combined(raw)
        mutablePages.value = effective
        portraits.protect(references(raw))
        portraits.retain(references(raw))
        publishCurrent(effective)
    }

    private var publishingPortraits = emptySet<String>()
    private var lastPublication: CompletableFuture<Unit>? = null

    /** Mutations enqueue under the state monitor, then wait without holding that monitor. */
    private fun change(action: () -> Unit) {
        val completion = synchronized(this) {
            val previous = lastPublication
            action()
            lastPublication.takeIf { it !== previous }
        }
        completion?.get()
    }

    // Called under the repository monitor. One executor preserves enqueue/publication order;
    // snapshots contain their own animation association, never a later vault revision.
    private fun publishCurrent(
        pages: List<CatalogPage>,
        version: Long = revision,
        refresh: CompletableFuture<List<CatalogPage>>? = null
    ): CompletableFuture<Unit> {
        dev.quantumink.hermesgadget.mobile.widgets.HomeWidgets.publish(context, pages)
        val pictureAnimations = vault.animations().toMap()
        val completion = CompletableFuture<Unit>()
        lastPublication = completion
        sync.execute {
            val current = synchronized(this) {
                val valid = version == revision && refresh?.isCancelled != true &&
                    lastPublication === completion
                if (valid) {
                    publishingPortraits = pages.mapNotNull { it.profile.portrait?.sha256 }.toSet()
                    portraits.protect(references(mutablePages.value))
                }
                valid
            }
            if (!current) {
                completion.cancel(false)
                return@execute
            }
            try {
                // Data Layer operations can block for seconds. No repository state lock here.
                publish?.invoke(pages, portraits)
                    ?: ProfileSync.publishCatalog(
                        context,
                        pages,
                        portraits,
                        pictureAnimations,
                        animations
                    )
                synchronized(this) {
                    publishingPortraits = emptySet()
                    cleanup()
                    if (version == revision && refresh?.isCancelled != true) {
                        animations.retain(pictureAnimations.values.map { it.sha256 }.toSet())
                    }
                }
                completion.complete(Unit)
            } catch (error: Exception) {
                synchronized(this) {
                    publishingPortraits = emptySet()
                    runCatching { cleanup() }
                }
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    /** Resolve a stable key and its endpoint together on the caller's IO worker. */
    @Synchronized fun currentProfile(key: String): Pair<CatalogPage, EndpointProfile>? {
        val page = mutablePages.value.singleOrNull { it.key == key } ?: return null
        return profileFor(page)?.let { page to it }
    }

    private fun withPortrait(page: CatalogPage, portrait: PortraitDescriptor): CatalogPage =
        CatalogPage(
            page.sourceId,
            page.sourceLabel,
            CatalogProfile(page.profile.id, page.profile.name, page.profile.gadgetUrl, portrait),
            page.status,
            page.checkedAt
        )

    fun retirePage(key: String) = change {
        val previous = vault.pages()
        require(previous.single { it.key == key }.status == CatalogStatus.WITHDRAWN)
        val remaining = previous.filterNot { it.key == key }
        vault.saveOverrides(vault.overrides().filterNot { it.key == key })
        vault.saveAnimations(vault.animations() - key)
        retireFallbacks(remaining)
        val raw = combined(remaining, false)
        vault.savePages(raw)
        val pages = combined(raw)
        mutablePages.value = pages
        invalidate()
        portraits.protect(references(pages))
        portraits.retain(references(pages))
        publishCurrent(pages)
    }

    /** Called after owner-confirmed removal from the independent phone setup vault. */
    fun setupRemoved() = change {
        retireFallbacks(vault.pages())
        val raw = combined(vault.pages(), false)
        val retained = raw.map { it.key }.toSet()
        vault.saveOverrides(vault.overrides().filter { it.key in retained })
        vault.saveAnimations(vault.animations().filterKeys { it in retained })
        vault.savePages(raw)
        val pages = combined(raw)
        mutablePages.value = pages
        invalidate()
        portraits.protect(references(pages))
        portraits.retain(references(pages))
        publishCurrent(pages)
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

    fun movePage(key: String, delta: Int) = change {
        val pages = CatalogOrder.moveProfile(combined(vault.pages(), false), key, delta)
        vault.savePages(pages)
        val effective = combined(pages)
        mutablePages.value = effective
        invalidate()
        publishCurrent(effective)
    }

    fun moveSource(id: String, delta: Int) = change {
        val raw = combined(vault.pages(), false)
        val order = sourceOrder(raw).toMutableList()
        val index = order.indexOf(id)
        require(index >= 0) { "Unknown box." }
        val target = (index.toLong() + delta).coerceIn(0, order.lastIndex.toLong()).toInt()
        order.add(target, order.removeAt(index))
        vault.saveSourceOrder(order)
        val pages = combined(raw, false)
        vault.savePages(pages)
        val effective = combined(pages)
        mutablePages.value = effective
        invalidate()
        publishCurrent(effective)
    }

    private fun combined(
        previous: List<CatalogPage>,
        applyOverrides: Boolean = true
    ): List<CatalogPage> {
        val overrides = vault.overrides().associateBy { it.key }
        val ownerPictures = mutableSetOf<String>()
        val fallbacks = vault.fallbacks().associate { it.profile.id to it.profile.portrait }
        val discovered = previous.filter { it.sourceId != "manual" }.map { page ->
            val fallback = page.profile.gadgetUrl?.let { url ->
                fallbacks[
                    ClientSpeech.endpointId(
                        dev.quantumink.hermesgadget.protocol.Endpoint.parse(url)
                    )
                ]
            }
            if (page.profile.portrait == null && fallback != null) ownerPictures += page.key
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
            if (fallbacks[id] != null) ownerPictures += "manual:$id"
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
        mutableWatchPictures.value = overrides.keys + ownerPictures
        val effective = (discovered + manual).map { page ->
            val portrait = overrides[page.key]?.profile?.portrait
            if (applyOverrides && portrait != null) withPortrait(page, portrait) else page
        }.sortedBy {
            order[it.key] ?: Int.MAX_VALUE
        }
        val sources = sourceOrder(previous)
        return CatalogOrder.group(effective).sortedBy { group ->
            sources.indexOf(group.first().sourceId).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }.flatten().also { ProfileCatalog.encodePages(it) }
    }

    private fun references(pages: List<CatalogPage>): Set<String> =
        (pages + vault.pages() + vault.fallbacks() + vault.overrides())
            .mapNotNull { it.profile.portrait?.sha256 }.toSet() + publishingPortraits

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
