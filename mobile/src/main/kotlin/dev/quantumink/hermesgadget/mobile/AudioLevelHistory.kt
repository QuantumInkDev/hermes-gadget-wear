package dev.quantumink.hermesgadget.mobile

/** Transient normalized amplitudes only, never audio samples. Confined to the UI coroutine. */
internal class AudioLevelHistory(private val capacity: Int = 24) {
    init {
        require(capacity in 1..64)
    }
    private val levels = ArrayDeque<Float>()
    fun add(level: Float) {
        if (levels.size == capacity) levels.removeFirst()
        levels.addLast(if (level.isFinite()) level.coerceIn(0f, 1f) else 0f)
    }
    fun snapshot(): List<Float> = levels.toList()
    fun clear() = levels.clear()
}
