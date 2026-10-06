package dev.quantumink.hermesgadget.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

sealed interface WatchAction {
    data class Vibrate(val durationMs: Int) : WatchAction
    class Notification(val title: String, val text: String) : WatchAction {
        override fun toString(): String = "Notification(content=redacted)"
    }
    data class Timer(val seconds: Int) : WatchAction
    data class Brightness(val level: Float) : WatchAction
}

object WatchActions {
    val names = setOf("watch.vibrate", "notification.show", "timer.start", "screen.brightness")

    fun validate(name: String, args: JsonObject): WatchAction {
        fun integer(key: String, default: Int? = null): Int {
            if (key !in args && default != null) return default
            return Message.create("args", args).int(key)
                ?: throw IllegalArgumentException("Expected an integer parameter.")
        }
        fun text(key: String, max: Int): String {
            val value = Message.create("args", args).string(key)
                ?: throw IllegalArgumentException("Expected a text parameter.")
            require(value.isNotBlank() && value.length <= max) {
                "Text parameter is too long or empty."
            }
            return value
        }
        fun keys(vararg allowed: String) {
            require(args.keys.all { it in allowed }) { "Unknown action parameter." }
        }
        return when (name) {
            "watch.vibrate" -> {
                keys("duration_ms")
                val duration = integer("duration_ms", 100)
                require(duration in 1..1000) { "Duration must be 1–1000 ms." }
                WatchAction.Vibrate(duration)
            }
            "notification.show" -> {
                keys("title", "text")
                WatchAction.Notification(text("title", 64), text("text", 512))
            }
            "timer.start" -> {
                keys("seconds")
                val seconds = integer("seconds")
                require(seconds in 1..3600) { "Timer must be 1–3600 seconds." }
                WatchAction.Timer(seconds)
            }
            "screen.brightness" -> {
                keys("level")
                val level = (args["level"] as? JsonPrimitive)?.takeUnless {
                    it.isString
                }?.doubleOrNull
                require(level != null && level.isFinite() && level in 0.05..1.0) {
                    "Brightness must be 0.05–1."
                }
                WatchAction.Brightness(level.toFloat())
            }
            else -> throw IllegalArgumentException("Unsupported device action.")
        }
    }

    fun manifest(available: Set<String> = names): JsonArray {
        fun property(
            type: String,
            minimum: Number? = null,
            maximum: Number? = null,
            maxLength: Int? = null
        ) = buildJsonObject {
            put("type", type)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
            maxLength?.let { put("maxLength", it) }
        }
        fun action(
            name: String,
            description: String,
            properties: JsonObject,
            required: List<String>
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            put(
                "params",
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JsonArray(required.map(::JsonPrimitive)))
                    put("additionalProperties", false)
                }
            )
        }
        return JsonArray(
            listOf(
                action(
                    "watch.vibrate",
                    "Give a short vibration on the watch, at most one second.",
                    buildJsonObject { put("duration_ms", property("integer", 1, 1000)) },
                    emptyList()
                ),
                action(
                    "notification.show",
                    "Show a local watch notification when the user permits notifications.",
                    buildJsonObject {
                        put("title", property("string", maxLength = 64))
                        put("text", property("string", maxLength = 512))
                    },
                    listOf("title", "text")
                ),
                action(
                    "timer.start",
                    "Request a system-clock timer, up to one hour, while this app is visible.",
                    buildJsonObject { put("seconds", property("integer", 1, 3600)) },
                    listOf("seconds")
                ),
                action(
                    "screen.brightness",
                    "Set this app's visible screen brightness; it resets on disconnect.",
                    buildJsonObject { put("level", property("number", 0.05, 1.0)) },
                    listOf("level")
                )
            ).filter { (it["name"] as JsonPrimitive).content in available }
        )
    }

    fun capabilities(): JsonObject = JsonObject(
        Protocol.pcmCapabilities() +
            buildJsonObject {
                put(
                    "display",
                    buildJsonObject {
                        put("width", 480)
                        put("height", 480)
                        put("charset", "utf8")
                        put("color", true)
                        put("text_cols", 24)
                        put("text_rows", 7)
                        put(
                            "image",
                            buildJsonObject {
                                put("width", 240)
                                put("height", 240)
                                put("format", "rgb565")
                            }
                        )
                    }
                )
                put("inputs", JsonArray(listOf("talk", "cancel").map(::JsonPrimitive)))
            }
    )
}
