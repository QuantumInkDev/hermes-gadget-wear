package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogRoster
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** On-demand only. Failed sources never turn an authoritative roster into an empty list. */
class CatalogDiscovery(private val fetch: (CatalogSource) -> List<CatalogProfile>) : AutoCloseable {
    private val coordinator = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(3)
    private val health = java.util.concurrent.ConcurrentHashMap<String, CatalogStatus>()
    fun statuses(): Map<String, CatalogStatus> = health.toMap()

    @Synchronized fun invalidate() {
        snapshot = null
        refreshedAt = 0
    }
    private var pending: CompletableFuture<List<CatalogPage>>? = null
    private var snapshot: List<CatalogPage>? = null
    private var fingerprint = ""
    private var refreshedAt = 0L

    @Synchronized fun refresh(
        sources: List<CatalogSource>,
        previous: List<CatalogPage>,
        force: Boolean,
        changed: (List<CatalogPage>) -> Unit
    ): CompletableFuture<List<CatalogPage>> {
        pending?.takeIf { !it.isDone }?.let { return it }
        val configuration = ProfileCatalog.encodeSources(sources)
        val now = System.nanoTime()
        if (!force && fingerprint == configuration &&
            now - refreshedAt < TimeUnit.SECONDS.toNanos(60)
        ) {
            return CompletableFuture.completedFuture(snapshot ?: previous)
        }
        val future = CompletableFuture<List<CatalogPage>>()
        pending = future
        coordinator.execute {
            try {
                var pages = snapshot?.takeIf { fingerprint == configuration } ?: previous
                val completion =
                    ExecutorCompletionService<Pair<CatalogSource, Result<List<CatalogProfile>>>>(
                        workers
                    )
                val jobs = sources.map { source ->
                    completion.submit(
                        java.util.concurrent.Callable {
                            source to
                                runCatching { fetch(source) }
                        }
                    )
                }
                val remaining = sources.associateBy { it.id }.toMutableMap()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                try {
                    while (remaining.isNotEmpty()) {
                        val job =
                            completion.poll(
                                (deadline - System.nanoTime()).coerceAtLeast(0),
                                TimeUnit.NANOSECONDS
                            )
                                ?: break
                        val (source, result) = job.get()
                        remaining.remove(source.id)
                        val status = if (result.isSuccess) {
                            CatalogStatus.AVAILABLE
                        } else {
                            (result.exceptionOrNull() as? CatalogFailure)?.status
                                ?: CatalogStatus.ATTENTION
                        }
                        health[source.id] = status
                        val incomingUrls = result.getOrNull().orEmpty().mapNotNull {
                            it.gadgetUrl
                        }.toSet()
                        pages =
                            CatalogRoster.merge(
                                pages.filterNot {
                                    it.sourceId == "manual" && it.profile.gadgetUrl in incomingUrls
                                },
                                source,
                                result.getOrNull(),
                                status,
                                System.currentTimeMillis()
                            )
                        changed(pages)
                    }
                    for (source in remaining.values) {
                        health[source.id] = CatalogStatus.OFFLINE
                        pages =
                            CatalogRoster.merge(
                                pages,
                                source,
                                null,
                                CatalogStatus.OFFLINE,
                                System.currentTimeMillis()
                            )
                        changed(pages)
                    }
                } finally {
                    jobs.forEach { it.cancel(true) }
                }
                synchronized(this) {
                    snapshot = pages
                    fingerprint = configuration
                    refreshedAt = System.nanoTime()
                }
                future.complete(pages)
            } catch (failure: Exception) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }
    override fun close() {
        coordinator.shutdownNow()
        workers.shutdownNow()
        synchronized(this) { pending?.cancel(true) }
    }
}
