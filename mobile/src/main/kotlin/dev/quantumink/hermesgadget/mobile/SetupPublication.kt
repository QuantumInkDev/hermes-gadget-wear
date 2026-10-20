package dev.quantumink.hermesgadget.mobile

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Local commit is final; each independent metadata projection can be retried honestly. */
internal class SetupPublication(
    val catalog: CompletableFuture<Unit>,
    val setup: CompletableFuture<Unit>,
    val shortcuts: CompletableFuture<Unit>
) {
    val completion: CompletableFuture<Unit> = CompletableFuture.allOf(catalog, setup, shortcuts)
        .thenApply { Unit }

    fun pending(): Set<String> = channels().filterValues {
        !it.isDone || it.isCompletedExceptionally || it.isCancelled
    }.keys

    /** Bounded UI wait; a timeout is an incomplete projection, never a local rollback. */
    fun await(): Set<String> {
        runCatching { completion.get(10, TimeUnit.SECONDS) }
        return pending()
    }

    private fun channels() = linkedMapOf(
        "watch profiles and pictures" to catalog,
        "watch connections" to setup,
        "launcher shortcuts" to shortcuts
    )

    companion object {
        fun attempt(action: () -> Unit): CompletableFuture<Unit> = CompletableFuture<Unit>().also {
            try {
                action()
                it.complete(Unit)
            } catch (error: Exception) {
                it.completeExceptionally(error)
            }
        }
    }
}
