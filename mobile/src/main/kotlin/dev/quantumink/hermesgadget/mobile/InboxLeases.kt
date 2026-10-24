package dev.quantumink.hermesgadget.mobile

/**
 * The app's authority, not a document merge. Hooks run outside its monitor before replacement
 * admission.
 */
internal class InboxLeases {
    private val handoff = Any()
    private var generation = 0L
    private var foreground: Long? = null
    private var background: Long? = null
    private var transitioning = false
    private var withdraw: (() -> Unit)? = null
    fun foreground(withdraw: () -> Unit = {}): Long = synchronized(handoff) {
        val old: (() -> Unit)?
        val id: Long
        synchronized(this) {
            old = this.withdraw
            this.withdraw = null
            foreground = null
            background = null
            transitioning = true
            id = ++generation
        }
        old?.invoke()
        synchronized(this) {
            foreground = id
            this.withdraw = withdraw
            transitioning = false
        }
        id
    }

    @Synchronized fun background(withdraw: () -> Unit): Long? {
        if (transitioning || foreground != null || background != null) return null
        this.withdraw = withdraw
        return (++generation).also { background = it }
    }

    @Synchronized fun current(id: Long): Boolean = foreground == id || background == id

    @Synchronized fun isForeground(id: Long): Boolean = foreground == id
    fun cancelBackground() {
        val id = synchronized(this) { background }
        id?.let { release(it) }
    }
    fun release(id: Long) {
        val wasBackground = synchronized(this) {
            if (foreground == id) {
                foreground = null
                withdraw = null
                return
            }
            background == id
        }
        if (!wasBackground) return
        synchronized(handoff) {
            val end = synchronized(this) {
                if (background != id) {
                    null
                } else {
                    background = null
                    transitioning = true
                    withdraw.also { withdraw = null }
                }
            }
            try {
                end?.invoke()
            } finally {
                synchronized(this) { transitioning = false }
            }
        }
    }
}
