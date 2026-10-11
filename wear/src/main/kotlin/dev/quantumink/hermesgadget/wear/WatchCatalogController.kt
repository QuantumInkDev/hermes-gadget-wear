package dev.quantumink.hermesgadget.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.quantumink.hermesgadget.protocol.CatalogPage
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
    private val main = Handler(Looper.getMainLooper())
    private val vault = WatchCatalogVault(context)
    private val portraits = PortraitCache(File(context.filesDir, "portrait-cache"))
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
    private val artEpoch = AtomicLong()
    private val closed = AtomicBoolean()
    private val pendingMetadata = java.util.concurrent.atomic.AtomicReference<String?>()
    private val metadataQueued = AtomicBoolean()
    private val pendingSelection = java.util.concurrent.atomic.AtomicReference<String?>()
    private val selectionQueued = AtomicBoolean()

    @Volatile private var node: String? = null

    @Volatile private var previews: List<ProfilePage> = emptyList()
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
            }
        }
    }
    fun start() {
        Wearable.getDataClient(context).addListener(listener)
        worker.execute {
            runCatching { vault.pages() to vault.selected() }.fold({ (pages, selected) ->
                main.post { if (!closed.get()) loaded(pages, selected) }
            }, { main.post { if (!closed.get()) busy(false, "Saved catalog needs attention.") } })
        }
    }
    fun refresh(force: Boolean) {
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
        if (closed.get()) return
        require(allowed(pages)) { "Catalog exceeds the page limit." }
        vault.savePages(pages)
        runCatching { portraits.retain(pages.map { it.key }.toSet()) }
        val selected = vault.selected()
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
        if (closed.get()) return
        previews = pages.take(3)
        val request = artEpoch.incrementAndGet()
        val selected = previews
        artWorker.execute {
            val result = selected.associate { page ->
                val descriptor = page.portrait
                if (descriptor != null &&
                    runCatching {
                        portraits.currentHash(page.key)
                    }.getOrNull() != descriptor.sha256
                ) {
                    node?.let { phone ->
                        runCatching {
                            remote.portrait(phone, descriptor)?.let {
                                portraits.store(page.key, descriptor, it)
                            }
                        }
                    }
                }
                page.key to
                    PageArtwork(
                        portraits.load(page.key),
                        page.approved?.let {
                            runCatching { pets.load(it.endpoint.url) }.getOrNull()
                        }
                    )
            }
            main.post { if (!closed.get() && request == artEpoch.get()) artwork(result) }
        }
    }
    fun reset() {
        worker.execute {
            vault.reset()
            portraits.reset()
        }
    }
    override fun close() {
        closed.set(true)
        Wearable.getDataClient(context).removeListener(listener)
        worker.shutdownNow()
        artWorker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
