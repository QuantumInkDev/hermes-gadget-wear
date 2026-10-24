package dev.quantumink.hermesgadget.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local walking mode, off by default. Never synced, migrated or sent to a server. */
class WalkingPreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("walking-v1", Context.MODE_PRIVATE)
    private val current = synchronized(states) {
        states.getOrPut(preferences) { MutableStateFlow(read()) }
    }
    val enabled: StateFlow<Boolean> = current.asStateFlow()

    @SuppressLint("UseKtx")
    fun set(enabled: Boolean) {
        synchronized(states) {
            check(preferences.edit().putBoolean("enabled", enabled).commit()) {
                "Could not save walking mode"
            }
            current.value = enabled
        }
    }

    private fun read(): Boolean = preferences.getBoolean("enabled", false)

    private companion object {
        val states = mutableMapOf<SharedPreferences, MutableStateFlow<Boolean>>()
    }
}

/**
 * Extractive spoken parts of one final reply. The displayed text is never changed: walking
 * speech reads the first part, and each Tell me more reads the next one. No model is called.
 */
object WalkingSpeech {
    const val FIRST_LIMIT = 220
    const val NEXT_LIMIT = 420
    private const val FIRST_SENTENCES = 2
    private const val NEXT_SENTENCES = 4
    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val marker = Regex("^\\s*(#{1,6}\\s+|[-*+]\\s+|\\d{1,3}[.)]\\s+|>\\s*)")
    private val emphasis = Regex("[*_`~|]+")
    private val abbreviations = setOf(
        "e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "st.", "no.", "approx."
    )

    fun parts(reply: String): List<String> {
        val sentences = sentences(reply)
        val parts = mutableListOf<String>()
        var index = 0
        while (index < sentences.size) {
            val first = parts.isEmpty()
            val limit = if (first) FIRST_LIMIT else NEXT_LIMIT
            val count = if (first) FIRST_SENTENCES else NEXT_SENTENCES
            val part = StringBuilder(sentences[index++])
            var used = 1
            while (index < sentences.size && used < count &&
                part.length + 1 + sentences[index].length <= limit
            ) {
                part.append(' ').append(sentences[index++])
                used++
            }
            parts += part.toString()
        }
        return parts
    }

    /** Readable sentences, each at most [FIRST_LIMIT] characters; code blocks are skipped. */
    internal fun sentences(reply: String): List<String> {
        val text = reply.take(32768).replace(fence, "\n")
        val sentences = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.replace(marker, "").replace(link, "$1").replace(url, "link")
                .replace(emphasis, "").replace(Regex("\\s+"), " ").trim()
            if (line.none { it.isLetterOrDigit() }) continue
            for (sentence in split(line)) {
                val spoken = if (sentence.last() in ".!?…。！？:;") sentence else "$sentence."
                sentences += bounded(spoken, FIRST_LIMIT)
            }
        }
        return sentences
    }

    private fun split(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < line.length) {
            val char = line[index]
            current.append(char)
            val end = when (char) {
                '。', '！', '？' -> true
                '.', '!', '?', '…' -> (index + 1 == line.length || line[index + 1] == ' ') &&
                    !abbreviation(current)
                else -> false
            }
            if (end) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(result::add)
                current.clear()
            }
            index++
        }
        current.toString().trim().takeIf { it.any(Char::isLetterOrDigit) }?.let(result::add)
        return result
    }

    private fun abbreviation(current: CharSequence): Boolean {
        val word = current.split(' ').last().lowercase()
        return word in abbreviations || word.matches(Regex("([a-z]\\.){2,3}"))
    }

    /** A sentence longer than [limit] is divided at word boundaries, never inside a pair. */
    private fun bounded(sentence: String, limit: Int): List<String> {
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > limit) {
            var cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
            if (Character.isHighSurrogate(rest[cut - 1])) cut--
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}

/** Spoken progress through one current client-speech reply. Callers own its lifetime. */
class WalkingReply(private val id: String, val voice: String, private val parts: List<String>) {
    private var next = 0
    val hasMore: Boolean get() = next < parts.size

    /** The speech id and text for the next part, or null once the whole reply was spoken. */
    fun next(): Pair<String, String>? {
        if (!hasMore) return null
        val index = next++
        return (if (index == 0) id else id.take(56) + "-m" + index) to parts[index]
    }
}

/** One completed, volatile clip of exactly the received response audio. */
class ReplayClip internal constructor(
    val rate: Int,
    header: ByteArray?,
    internal val packets: List<ByteArray>
) {
    private val opusHeader = header?.copyOf()
    val header: ByteArray? get() = opusHeader?.copyOf()
    override fun toString(): String = "ReplayClip(redacted)"
}

/** Records the current stream for Replay; overflow or a superseding stream discards it. */
class ReplayRecorder(
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val maxPackets: Int = 8192
) {
    private class Pending(val rate: Int, val header: ByteArray?) {
        val packets = mutableListOf<ByteArray>()
        var bytes = 0
    }
    private var pending: Pending? = null
    var clip: ReplayClip? = null
        @Synchronized get
        private set

    @Synchronized fun start(rate: Int, header: ByteArray?) {
        clip = null
        pending = Pending(rate, header?.copyOf())
    }

    @Synchronized fun offer(bytes: ByteArray) {
        val current = pending ?: return
        if (current.packets.size >= maxPackets || current.bytes + bytes.size > maxBytes) {
            pending = null
            return
        }
        current.packets += bytes.copyOf()
        current.bytes += bytes.size
    }

    @Synchronized fun finish(): ReplayClip? {
        val current = pending ?: return clip
        pending = null
        if (current.packets.isNotEmpty()) {
            clip = ReplayClip(current.rate, current.header, current.packets.toList())
        }
        return clip
    }

    @Synchronized fun clear() {
        pending = null
        clip = null
    }
}

/** Output preference for one stream. SYSTEM leaves Android's ordinary media route. */
enum class PlaybackRoute { SYSTEM, HEADPHONES_FIRST, HEADPHONES_THEN_SPEAKER }

/**
 * Chooses a connected media output by type: Bluetooth earbuds/headsets first, then other
 * headphones, then (only when asked) the built-in speaker. SCO/communication devices are
 * never chosen because response speech stays on the media stream.
 */
internal fun preferredOutputIndex(route: PlaybackRoute, types: List<Int>, api: Int): Int? {
    if (route == PlaybackRoute.SYSTEM) return null
    val wired = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET
    )
    fun headphones(type: Int) = playbackOutputForDeviceType(type, api) == PlaybackOutput.HEADPHONES
    types.indexOfFirst { headphones(it) && it !in wired }.takeIf { it >= 0 }?.let { return it }
    types.indexOfFirst(::headphones).takeIf { it >= 0 }?.let { return it }
    if (route != PlaybackRoute.HEADPHONES_THEN_SPEAKER) return null
    return types.indexOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).takeIf { it >= 0 }
}
