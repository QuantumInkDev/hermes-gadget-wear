package dev.quantumink.hermesgadget.mobile

/**
 * Actual notification-route owner. Deferral retains only the opaque hint and repeats lookup on
 * release.
 */
internal class PhoneInboxNavigation<T>(private val lookup: (String, (T?) -> Unit) -> Unit) {
    private var generation = 0L
    private var active: String? = null
    var pending: String? = null
        private set
    fun request(
        handle: String,
        ready: () -> Boolean,
        locked: () -> Boolean,
        complete: (T?) -> Unit
    ) {
        val version = ++generation
        active = handle
        pending = null
        if (!ready() || locked()) {
            pending = handle
            return
        }
        lookup(handle) { value ->
            if (version == generation) {
                if (!ready() || locked()) pending = handle else complete(value)
            }
        }
    }
    fun release(ready: () -> Boolean, locked: () -> Boolean, complete: (T?) -> Unit) {
        val handle = pending ?: return
        if (ready() && !locked()) request(handle, ready, locked, complete)
    }
    fun suspend() {
        generation++
        pending = pending ?: active
    }
    fun cancel() {
        generation++
        pending = null
        active = null
    }
}
