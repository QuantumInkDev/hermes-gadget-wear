package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Separate bounded catalog/artwork work from conversation transport and enrollment storage. */
class WatchCatalogController(
    private val context: Context,
    private val pets: PetCache,
    private val allowed: (List<CatalogPage>) -> Boolean,
    private val loaded: (List<CatalogPage>, String) -> Unit,
    private val busy: (Boolean, String) -> Unit,
    private val artwork: (Map<String, PageArtwork>) -> Unit
) : AutoCloseable {
    private val promotion = Any()
    private val main = Handler(Looper.getMainLooper())
    private val vault = WatchCatalogVault(context)
    private val portraits = PortraitCache(File(context.filesDir, "portrait-cache"))
    private val animations = dev.quantumink.hermesgadget.client.AnimationCache(
        File(context.filesDir, "animation-cache")
    )
    private val animationIndex = AnimationIndex(File(context.filesDir, "animation-index"))

    @Volatile private var animationMetadata =
        emptyMap<String, dev.quantumink.hermesgadget.protocol.AnimationDescriptor>()
    private val remote = WatchCatalog(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val artWorker =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
    private val refreshing = AtomicBoolean()
    private val started = AtomicBoolean()
    private val restored = AtomicBoolean()
    private val requested = AtomicBoolean()
    private val requestedForce = AtomicBoolean()
    private val artEpoch = AtomicLong()
    private val closed = AtomicBoolean()
    private val pendingMetadata = java.util.concurrent.atomic.AtomicReference<String?>()
    private val metadataQueued = AtomicBoolean()
    private val pendingSelection = java.util.concurrent.atomic.AtomicReference<String?>()
    private val selectionQueued = AtomicBoolean()

    @Volatile private var node: String? = null

    @Volatile private var previews: List<ProfilePage> = emptyList()
    private var catalogKeys = emptySet<String>()
    private var catalogPortraits = emptyMap<String, PortraitDescriptor?>()
    private val listener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            val item = event.dataItem
            if (item.uri.host != node) continue
            if (item.uri.path == ProfileCatalog.DATA_PATH) {
                val text =
                    runCatching {
                        DataMapItem.fromDataItem(item).dataMap.getString("pages")
                    }.getOrNull()
                        ?: continue
                if (text.toByteArray().size <=
                    ProfileCatalog.MAX_BYTES
                ) {
                    pendingMetadata.set(text)
                    drainMetadata()
                }
            } else if (item.uri.path?.startsWith(ProfileCatalog.PORTRAIT_PATH) == true) {
                prepare(previews)
            } else if (item.uri.path ==
                dev.quantumink.hermesgadget.protocol.PortraitAnimations.DATA_PATH ||
                item.uri.path?.startsWith(
                    dev.quantumink.hermesgadget.protocol.PortraitAnimations.ASSET_PATH
                ) ==
                true
            ) {
                prepare(previews)
            }
        }
    }
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        worker.execute {
            animationMetadata = runCatching { animationIndex.read() }.getOrDefault(emptyMap())
            val saved = runCatching { vault.pages() to vault.selected() }
            saved.onSuccess { (pages, _) ->
                synchronized(promotion) {
                    if (!closed.get()) {
                        catalogKeys = pages.map { it.key }.toSet()
                        catalogPortraits = pages.associate { it.key to it.profile.portrait }
                        runCatching { portraits.retain(catalogKeys) }
                        artEpoch.incrementAndGet()
                    }
                }
            }
            main.post {
                if (closed.get()) return@post
                saved.fold({ (pages, selected) -> loaded(pages, selected) }, {
                    loaded(emptyList(), "")
                    busy(false, "Saved catalog needs attention.")
                })
                restored.set(true)
                Wearable.getDataClient(context).addListener(listener)
                if (requested.getAndSet(false)) refresh(requestedForce.getAndSet(false))
            }
        }
    }
    fun refresh(force: Boolean) {
        if (!restored.get()) {
            requested.set(true)
            if (force) requestedForce.set(true)
            return
        }
        if (closed.get() || !refreshing.compareAndSet(false, true)) return
        busy(true, "")
        worker.execute {
            try {
                val phone = requireNotNull(WatchRelay.reachablePhone(context))
                node = phone
                remote.cached(phone)?.let { accept(it) }
                accept(remote.refresh(phone, force))
                main.post { if (!closed.get()) busy(false, "") }
            } catch (_: Exception) {
                main.post {
                    if (!closed.get()) {
                        busy(
                            false,
                            "Phone catalog unavailable. Cached pages were kept."
                        )
                    }
                }
            } finally {
                refreshing.set(false)
            }
        }
    }
    private fun receive(text: String) {
        runCatching { accept(ProfileCatalog.decodePages(text)) }.onFailure {
            main.post {
                if (!closed.get()) busy(false, "Catalog exceeds limits or needs attention.")
            }
        }
    }
    private fun drainMetadata() {
        if (closed.get() || !metadataQueued.compareAndSet(false, true)) return
        worker.execute {
            pendingMetadata.getAndSet(null)?.let(::receive)
            metadataQueued.set(false)
            if (pendingMetadata.get() != null) drainMetadata()
        }
    }
    private fun accept(pages: List<CatalogPage>) {
        val selected = synchronized(promotion) {
            if (closed.get()) return
            require(allowed(pages)) { "Catalog exceeds the page limit." }
            vault.savePages(pages)
            catalogKeys = pages.map { it.key }.toSet()
            catalogPortraits = pages.associate { it.key to it.profile.portrait }
            artEpoch.incrementAndGet()
            runCatching { portraits.retain(catalogKeys) }
            vault.selected()
        }
        main.post { if (!closed.get()) loaded(pages, selected) }
    }
    fun rememberPage(key: String) {
        if (closed.get()) return
        pendingSelection.set(key)
        drainSelection()
    }
    private fun drainSelection() {
        if (closed.get() || !selectionQueued.compareAndSet(false, true)) return
        worker.execute {
            pendingSelection.getAndSet(null)?.let { runCatching { vault.select(it) } }
            selectionQueued.set(false)
            if (pendingSelection.get() != null) drainSelection()
        }
    }
    fun prepare(pages: List<ProfilePage>) {
        val (request, selected) = synchronized(promotion) {
            if (closed.get()) return
            previews = pages.take(3)
            artEpoch.incrementAndGet() to previews
        }
        artWorker.execute {
            node?.let { phone ->
                runCatching {
                    val records = remote.animations(phone).orEmpty()
                    synchronized(promotion) {
                        if (!closed.get() && request == artEpoch.get()) {
                            val current = records.filterKeys { it in catalogKeys }
                            animationIndex.save(current)
                            animationMetadata = current
                            animations.retain(current.values.map { it.sha256 }.toSet())
                        }
                    }
                }
            }
            animations.protect(animationMetadata.values.map { it.sha256 }.toSet())
            val result = selected.associate { page ->
                val descriptor = page.portrait
                if (descriptor != null &&
                    runCatching {
                        portraits.currentHash(page.key)
                    }.getOrNull() != descriptor.sha256
                ) {
                    node?.let { phone ->
                        runCatching {
                            val admission = synchronized(promotion) {
                                if (!closed.get() && request == artEpoch.get() &&
                                    catalogPortraits[page.key] == descriptor
                                ) {
                                    portraits.admission(page.key)
                                } else {
                                    null
                                }
                            }
                            if (admission != null) {
                                portraits.fetchAndStore(page.key, descriptor, admission) {
                                    remote.portrait(phone, descriptor)
                                }
                            }
                        }
                    }
                }
                val animation = animationMetadata[page.key]?.takeIf {
                    it.fallbackSha256 == descriptor?.sha256 &&
                        runCatching { portraits.currentHash(page.key) }.getOrNull() ==
                        it.fallbackSha256
                }?.let { metadata ->
                    animations.file(metadata) ?: node?.let { phone ->
                        runCatching {
                            val bytes = remote.animation(phone, metadata)
                            synchronized(promotion) {
                                if (bytes != null && !closed.get() && request == artEpoch.get() &&
                                    page.key in catalogKeys &&
                                    animationMetadata[page.key] == metadata
                                ) {
                                    animations.store(metadata, bytes)
                                }
                                animations.file(metadata)
                            }
                        }.getOrNull()
                    }
                }
                page.key to
                    PageArtwork(
                        portraits.load(page.key),
                        page.approved?.let {
                            runCatching { pets.load(it.endpoint.url) }.getOrNull()
                        },
                        animation
                    )
            }
            main.post { if (!closed.get() && request == artEpoch.get()) artwork(result) }
        }
    }
    fun reset() {
        synchronized(promotion) {
            catalogKeys = emptySet()
            catalogPortraits = emptyMap()
            previews = emptyList()
            animationMetadata = emptyMap()
            artEpoch.incrementAndGet()
            portraits.invalidateRequests()
        }
        worker.execute {
            synchronized(promotion) {
                artEpoch.incrementAndGet()
                catalogKeys = emptySet()
                catalogPortraits = emptyMap()
                previews = emptyList()
                animationMetadata = emptyMap()
                vault.reset()
                portraits.reset()
                animationIndex.reset()
                animations.reset()
            }
        }
    }
    override fun close() {
        synchronized(promotion) {
            closed.set(true)
            artEpoch.incrementAndGet()
            portraits.invalidateRequests()
        }
        Wearable.getDataClient(context).removeListener(listener)
        worker.shutdownNow()
        artWorker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
