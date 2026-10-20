package dev.quantumink.hermesgadget.mobile

/** UI-thread lease; navigation and disposal invalidate delayed provider completions. */
internal class TransferReadLease {
    private var generation = 0L
    private var disposed = false
    fun begin(): Long = ++generation
    fun invalidate() {
        generation++
    }
    fun dispose() {
        disposed = true
        invalidate()
    }
    fun current(value: Long): Boolean = !disposed && value == generation
}
