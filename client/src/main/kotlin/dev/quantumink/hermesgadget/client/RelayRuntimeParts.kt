package dev.quantumink.hermesgadget.client

import dev.quantumink.hermesgadget.protocol.relay.session.Cancelable
import dev.quantumink.hermesgadget.protocol.relay.session.InboxFailure
import dev.quantumink.hermesgadget.protocol.relay.session.InboxFile
import dev.quantumink.hermesgadget.protocol.relay.session.InboxProblem
import dev.quantumink.hermesgadget.protocol.relay.session.InboxSnapshot
import dev.quantumink.hermesgadget.protocol.relay.session.InboxWrite
import dev.quantumink.hermesgadget.protocol.relay.session.RelayDocument
import dev.quantumink.hermesgadget.protocol.relay.session.RelayScheduler
import dev.quantumink.hermesgadget.protocol.relay.session.RelayStore
import dev.quantumink.hermesgadget.protocol.relay.session.StoreCommit
import dev.quantumink.hermesgadget.protocol.relay.session.StoreLoad
import dev.quantumink.hermesgadget.protocol.relay.session.StoreProblem
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The controller's single thread: every Relay state change runs on it, off the main thread (disk and crypto live here). */
class ExecutorRelayScheduler :
    RelayScheduler,
    AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1) {
        Thread(it, "relay-controller").apply {
            isDaemon =
                true
        }
    }
        .apply { removeOnCancelPolicy = true }

    override fun post(task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed: the task belongs to a screen that is gone.
        }
    }

    override fun postDelayed(delayMs: Long, task: Runnable): Cancelable = try {
        val scheduled = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        Cancelable { scheduled.cancel(false) }
    } catch (_: RejectedExecutionException) {
        Cancelable { }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/** An unexpected platform exception becomes an explicit, untouched-bytes store result instead of a crash. */
fun RelayStore.failClosed(): RelayStore {
    val file = this
    return object : RelayStore {
        override fun load(): StoreLoad = try {
            file.load()
        } catch (_: Exception) {
            StoreLoad.Unreadable(StoreProblem.STORAGE_UNAVAILABLE)
        }
        override fun commit(document: RelayDocument, base: Long): StoreCommit = try {
            file.commit(document, base)
        } catch (_: Exception) {
            StoreCommit.Failed(StoreProblem.STORAGE_UNAVAILABLE, false)
        }
        override fun currentSequence(): Long = try {
            file.currentSequence()
        } catch (_: Exception) {
            -1
        }
    }
}

/** The inbox counterpart: typed failures pass through, anything else is storage trouble. */
fun InboxFile.failClosed(): InboxFile {
    val file = this
    return object : InboxFile {
        override fun load(): InboxSnapshot = try {
            file.load()
        } catch (failure: InboxFailure) {
            throw failure
        } catch (_: Exception) {
            throw InboxFailure(InboxProblem.STORAGE)
        }
        override fun commit(next: InboxSnapshot, base: Long): InboxWrite = try {
            file.commit(next, base)
        } catch (_: Exception) {
            InboxWrite.Failed(false)
        }
    }
}
