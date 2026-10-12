package dev.quantumink.hermesgadget.protocol

/** Joins recorder release and protocol outcome; callbacks never run under this monitor. */
class RecordingFeedback(private val onResult: (Boolean) -> Unit) {
    private class Capture(val token: String) {
        var stopped = false
        var outcome: Boolean? = null
        var delivered = false
    }

    private val monitor = Any()
    private var active: Capture? = null

    fun begin(token: String) {
        synchronized(monitor) {
            if (active?.token != token) active = Capture(token)
        }
    }

    fun stopped(token: String) {
        val result = synchronized(monitor) {
            val capture = active?.takeIf { it.token == token } ?: return@synchronized null
            capture.stopped = true
            result(capture)
        }
        result?.let(onResult)
    }

    fun outcome(token: String, finished: Boolean) {
        val result = synchronized(monitor) {
            val capture = active?.takeIf { it.token == token } ?: return@synchronized null
            if (capture.outcome == null) capture.outcome = finished
            result(capture)
        }
        result?.let(onResult)
    }

    fun invalidate() {
        synchronized(monitor) { active = null }
    }

    private fun result(capture: Capture): Boolean? {
        if (!capture.stopped || capture.delivered) return null
        val finished = capture.outcome ?: return null
        capture.delivered = true
        return finished
    }
}
