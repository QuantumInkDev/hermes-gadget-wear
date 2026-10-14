package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AnimationDescriptor(val sha256: String, val bytes: Int, val fallbackSha256: String) {
    init {
        require(
            sha256.matches(Regex("[a-f0-9]{64}")) && fallbackSha256.matches(Regex("[a-f0-9]{64}"))
        ) {
            "Invalid animation or fallback hash."
        }
        require(bytes in 1..GifPortrait.MAX_BYTES) { "Invalid animation length." }
    }
}

/** Optional versioned metadata; the existing PNG catalog remains unchanged. */
object PortraitAnimations {
    const val DATA_PATH = "/hermes/animations/v1"
    const val ASSET_PATH = "/hermes/animation-assets/v1/"
    const val MAX_BYTES = 16384
    const val MAX_ANIMATIONS = 32

    fun encode(animations: Map<String, AnimationDescriptor>): String {
        require(animations.size <= MAX_ANIMATIONS) { "Too many animations." }
        animations.keys.forEach(::key)
        return buildJsonObject {
            put("version", 1)
            put(
                "animations",
                buildJsonObject {
                    animations.forEach { (page, asset) ->
                        put(
                            page,
                            buildJsonObject {
                                put("sha256", asset.sha256)
                                put("bytes", asset.bytes)
                                put("fallback_sha256", asset.fallbackSha256)
                            }
                        )
                    }
                }
            )
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(text: String): Map<String, AnimationDescriptor> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Animation manifest exceeds 16 KiB."
        }
        rejectDuplicates(text)
        val envelope = requireNotNull(Json.parseToJsonElement(text) as? JsonObject)
        require(envelope.keys == setOf("version", "animations")) { "Invalid animation envelope." }
        require(integer(envelope, "version") == 1) { "Unsupported animation version." }
        val assets = requireNotNull(envelope["animations"] as? JsonObject)
        require(assets.size <= MAX_ANIMATIONS) { "Too many animations." }
        return assets.mapValues { (page, value) ->
            key(page)
            val asset = requireNotNull(value as? JsonObject)
            require(asset.keys == setOf("sha256", "bytes", "fallback_sha256")) {
                "Invalid animation descriptor."
            }
            AnimationDescriptor(
                string(asset, "sha256"),
                integer(asset, "bytes"),
                string(asset, "fallback_sha256")
            )
        }
    }

    private fun string(item: JsonObject, field: String): String = requireNotNull(
        (item[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ) { "Invalid animation field." }

    private fun integer(item: JsonObject, field: String): Int {
        val value =
            requireNotNull((item[field] as? JsonPrimitive)?.takeUnless { it.isString }?.content)
        require(value.matches(Regex("0|[1-9][0-9]*"))) { "Invalid animation integer." }
        return requireNotNull(value.toIntOrNull()) { "Invalid animation integer." }
    }

    private fun key(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}"))) {
            "Invalid animation page key."
        }
    }

    /** JsonObject parsers otherwise overwrite duplicate members, including escaped aliases. */
    private fun rejectDuplicates(text: String) {
        val objects = ArrayDeque<MutableSet<String>>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> {
                    require(objects.size < 3) { "Animation manifest nesting is invalid." }
                    objects.addLast(mutableSetOf())
                }
                '}' -> {
                    require(objects.isNotEmpty()) { "Invalid animation JSON." }
                    objects.removeLast()
                }
                '[', ']' -> throw IllegalArgumentException("Animation arrays are unsupported.")
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length) { "Invalid animation JSON string." }
                    var after = index + 1
                    while (after < text.length && text[after].isWhitespace()) after++
                    if (after < text.length && text[after] == ':') {
                        require(objects.isNotEmpty()) { "Invalid animation JSON key." }
                        val key = (
                            Json.parseToJsonElement(
                                text.substring(start, index + 1)
                            ) as JsonPrimitive
                            ).content
                        require(objects.last().add(key)) { "Duplicate animation JSON key." }
                    }
                }
            }
            index++
        }
        require(objects.isEmpty()) { "Invalid animation JSON." }
    }
}
