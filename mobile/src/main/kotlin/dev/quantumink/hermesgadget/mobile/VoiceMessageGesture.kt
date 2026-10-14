package dev.quantumink.hermesgadget.mobile

internal enum class VoiceMessageOutcome { SEND, CANCEL }

/** Owns one press independently of redraws, with irreversible left cancellation. */
internal class VoiceMessageGesture(private val cancelDistance: Float) {
    private var began: Long? = null
    private var cancelled = false

    fun begin(now: Long): Boolean {
        if (began != null) return false
        began = now
        cancelled = false
        return true
    }

    fun move(left: Float, vertical: Float): Boolean {
        if (began == null || cancelled || left < cancelDistance ||
            left <= kotlin.math.abs(vertical)
        ) {
            return false
        }
        cancelled = true
        return true
    }

    fun end(now: Long): VoiceMessageOutcome? {
        val start = began ?: return null
        began = null
        return if (cancelled || now - start < 250) {
            VoiceMessageOutcome.CANCEL
        } else {
            VoiceMessageOutcome.SEND
        }
    }

    fun cancel(): VoiceMessageOutcome? {
        if (began == null) return null
        began = null
        cancelled = true
        return VoiceMessageOutcome.CANCEL
    }
}
