package dev.quantumink.hermesgadget.mobile

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Serializes configuration import against every normal read-modify-write of the stores it
 * touches. Normal mutators share the lock; import commit and journal recovery hold it alone.
 * Repository entry points establish recovery before taking their monitor. Normal operations
 * then acquire the repository monitor before the gate. Deferred recovery publication only
 * enqueues catalog work; it never waits on a worker that needs an enclosing monitor.
 *
 * Recovery of an interrupted import runs exclusively before the first gated mutation of the
 * process, so no normal write can observe or extend a half-applied import.
 */
internal object SetupTransferGate {
    private val lock = ReentrantReadWriteLock()

    @Volatile private var recovered = false

    @Volatile private var recovery: (() -> Unit)? = null
    private val publications = mutableListOf<() -> Unit>()

    /** Installed once by the application; tests may install their own owned recovery. */
    fun install(recover: () -> Unit) {
        recovery = recover
    }

    fun <T> mutation(block: () -> T): T {
        while (true) {
            ensureRecovered()
            lock.readLock().lock()
            if (recovered || lock.isWriteLockedByCurrentThread) break
            lock.readLock().unlock()
        }
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun <T> exclusive(block: () -> T): T {
        while (true) {
            ensureRecovered()
            lock.writeLock().lock()
            if (recovered || lock.writeHoldCount > 1) break
            lock.writeLock().unlock()
        }
        try {
            return block()
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** A failed recovery leaves its record in place for an explicit owner decision. */
    fun ensureRecovered() {
        if (recovered || lock.isWriteLockedByCurrentThread) return
        // A thread holding the shared side cannot upgrade; it already passed this point.
        if (lock.readHoldCount > 0) return
        lock.writeLock().lock()
        var ready = emptyList<() -> Unit>()
        try {
            if (!recovered) {
                recovery?.invoke()
                recovered = true
                ready = publications.toList()
                publications.clear()
            }
        } finally {
            lock.writeLock().unlock()
        }
        ready.forEach { runCatching(it) }
    }

    /** Repository publication happens after releasing the gate, preserving monitor lock order. */
    fun afterRecovery(publish: () -> Unit) {
        check(lock.isWriteLockedByCurrentThread)
        publications += publish
    }

    internal val isExclusiveHeld: Boolean get() = lock.isWriteLockedByCurrentThread

    /** A failed rollback or completion must be retried before another normal write. */
    fun requireRecovery(recover: () -> Unit) {
        check(lock.isWriteLockedByCurrentThread)
        recovered = false
        recovery = recover
    }

    /** Only explicit recovery management can enter while recovery is blocked. */
    fun <T> resolveRecovery(block: () -> T): T {
        lock.writeLock().lock()
        try {
            return block().also { recovered = false }
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** Test-only: model a new process for an interrupted-import fixture. */
    internal fun resetForProcessFixture(recover: (() -> Unit)?) {
        lock.writeLock().lock()
        try {
            recovered = false
            recovery = recover
            publications.clear()
        } finally {
            lock.writeLock().unlock()
        }
    }
}
