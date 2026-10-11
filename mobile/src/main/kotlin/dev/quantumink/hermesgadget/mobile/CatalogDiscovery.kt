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
        generation++
        pending?.cancel(false)
        pendingJob?.cancel(true)
        pending = null
        pendingJob = null
        snapshot = null
        refreshedAt = 0
        health.clear()
    }
    private var pending: CompletableFuture<List<CatalogPage>>? = null
    private var pendingJob: java.util.concurrent.Future<*>? = null
    private var pendingConfiguration = ""
    private var generation = 0L

    @Synchronized private fun current(version: Long): Boolean = version == generation
    private var snapshot: List<CatalogPage>? = null
    private var fingerprint = ""
    private var refreshedAt = 0L

    @Synchronized fun refresh(
        sources: List<CatalogSource>,
        previous: List<CatalogPage>,
        force: Boolean,
        changed: (List<CatalogPage>) -> Unit
    ): CompletableFuture<List<CatalogPage>> {
        val configuration = ProfileCatalog.encodeSources(sources)
        pending?.takeIf { !it.isDone }?.let {
            if (pendingConfiguration == configuration) return it
            invalidate()
        }
        val now = System.nanoTime()
        if (!force && fingerprint == configuration &&
            now - refreshedAt < TimeUnit.SECONDS.toNanos(60)
        ) {
            return CompletableFuture.completedFuture(snapshot ?: previous)
        }
        val future = CompletableFuture<List<CatalogPage>>()
        pending = future
        pendingConfiguration = configuration
        val version = generation
        val initial = snapshot?.takeIf { fingerprint == configuration } ?: previous
        pendingJob = coordinator.submit {
            try {
                var pages = initial
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
                        if (!current(version)) return@submit
                        remaining.remove(source.id)
                        var status = if (result.isSuccess) {
                            CatalogStatus.AVAILABLE
                        } else {
                            (result.exceptionOrNull() as? CatalogFailure)?.status
                                ?: CatalogStatus.ATTENTION
                        }
                        val incomingUrls = result.getOrNull().orEmpty().mapNotNull {
                            it.gadgetUrl
                        }.toSet()
                        val merged = runCatching {
                            CatalogRoster.merge(
                                pages.filterNot {
                                    it.sourceId == "manual" &&
                                        it.profile.gadgetUrl in incomingUrls
                                },
                                source,
                                result.getOrNull(),
                                status,
                                System.currentTimeMillis()
                            )
                        }
                        pages = merged.getOrElse {
                            status = CatalogStatus.ATTENTION
                            CatalogRoster.merge(
                                pages,
                                source,
                                null,
                                status,
                                System.currentTimeMillis()
                            )
                        }
                        health[source.id] = status
                        if (current(version)) changed(pages)
                    }
                    for (source in remaining.values) {
                        if (!current(version)) return@submit
                        health[source.id] = CatalogStatus.OFFLINE
                        pages =
                            CatalogRoster.merge(
                                pages,
                                source,
                                null,
                                CatalogStatus.OFFLINE,
                                System.currentTimeMillis()
                            )
                        if (current(version)) changed(pages)
                    }
                } finally {
                    jobs.forEach { it.cancel(true) }
                }
                synchronized(this) {
                    if (!current(version)) return@submit
                    snapshot = pages
                    fingerprint = configuration
                    refreshedAt = System.nanoTime()
                }
                future.complete(pages)
            } catch (failure: Exception) {
                if (current(version)) {
                    future.completeExceptionally(failure)
                } else {
                    future.cancel(false)
                }
            }
        }
        return future
    }
    override fun close() {
        invalidate()
        coordinator.shutdownNow()
        workers.shutdownNow()
    }
}
