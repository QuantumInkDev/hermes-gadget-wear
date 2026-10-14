package dev.quantumink.hermesgadget.client

/** Startup and termination share one linearization point after slow recorder initialization. */
internal class CaptureStartGate {
    private var started = false
    private var ended = false

    @Synchronized fun start(action: () -> Unit): Boolean {
        if (ended || started) return false
        action()
        started = true
        return true
    }

    /** True only for the first transition from started to finishing. */
    @Synchronized fun finish(): Boolean {
        if (ended) return false
        ended = true
        return started
    }

    @Synchronized fun cancel() {
        ended = true
    }
}
