package dev.quantumink.hermesgadget.mobile

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded channel admission and shared foreground lifetime accounting. */
internal class RelayCapacity {
    private val conversations = Semaphore(2)
    private val catalogs = Semaphore(1)
    fun acquire(catalog: Boolean): AutoCloseable? {
        val slots = if (catalog) catalogs else conversations
        if (!slots.tryAcquire()) return null
        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) slots.release() }
    }
    val idle: Boolean get() = conversations.availablePermits() == 2 &&
        catalogs.availablePermits() == 1
}
