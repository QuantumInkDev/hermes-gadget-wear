package dev.quantumink.hermesgadget.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Cell-space replacement region; source poses and anchor remain immutable. */
class PetOverlay(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        // Establish ordered cell bounds before subtraction can overflow.
        require(0 <= left && left < right && right <= 192)
        require(0 <= top && top < bottom && bottom <= 208)
        require(right - left in 1..96 && bottom - top in 1..104)
    }

    companion object {
        private val integerToken = Regex("-?(0|[1-9][0-9]*)")

        fun parse(raw: kotlinx.serialization.json.JsonElement): PetOverlay {
            val value = raw as? JsonObject ?: throw IllegalArgumentException("Invalid overlay")
            require(value.keys == setOf("anchor_frame", "rect"))
            fun integer(raw: kotlinx.serialization.json.JsonElement?): Int {
                val number = raw as? JsonPrimitive
                require(number != null && !number.isString)
                require(integerToken.matches(number.content))
                return requireNotNull(number.content.toIntOrNull())
            }
            require(integer(value["anchor_frame"]) == 0)
            val rect = value["rect"] as? JsonArray
            require(rect != null && rect.size == 4)
            val coordinates = rect.map(::integer)
            return PetOverlay(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        }
    }
}

class PetRow(val name: String, val index: Int, val frames: Int, val overlay: PetOverlay? = null) {
    init {
        require(overlay == null || (name == "talking" && frames == 3))
    }
}
class PetSheet(
    val kind: String,
    val sha256: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val rows: List<PetRow>
) {
    init {
        require(kind == "companion" || rows.none { it.overlay != null })
    }
    override fun toString(): String = "PetSheet(redacted)"
}
class PetManifest(
    val name: String,
    val loopMs: Int,
    val sheets: List<PetSheet>,
    val encoded: String
) {
    override fun toString(): String = "PetManifest(redacted)"
    companion object {
        private val names =
            setOf(
                "idle", "wave", "waving", "run", "running", "running-left", "running-right",
                "jump", "jumping", "failed", "waiting", "review", "listening", "talking", "sleeping"
            )
        fun parse(message: Message): PetManifest {
            require(
                message.type == "pet.manifest" && message.int("cell_width") == 192 &&
                    message.int("cell_height") == 208
            )
            val name = requireNotNull(message.string("name"))
            require(name.isNotBlank() && name.length <= 64)
            val loop = requireNotNull(message.int("loop_ms"))
            require(loop in 300..10000)
            val assets = message.field("sheets") as? JsonArray ?: error("Invalid pet sheets")
            require(assets.size in 1..2)
            val sheets = assets.map { raw ->
                val sheet = Message.create(
                    "sheet",
                    raw as? JsonObject ?: error("Invalid pet sheet")
                )
                val kind = requireNotNull(sheet.string("kind"))
                val hash = requireNotNull(sheet.string("sha256"))
                val bytes = requireNotNull(sheet.int("bytes"))
                val width = requireNotNull(sheet.int("width"))
                val height = requireNotNull(sheet.int("height"))
                require(kind in setOf("base", "companion") && hash.matches(Regex("[a-f0-9]{64}")))
                require(bytes in 32..2097152 && width in 192..1728 && height in 208..2496)
                require(width % 192 == 0 && height % 208 == 0 && width.toLong() * height <= 3200000)
                val values = sheet.field("rows") as? JsonArray ?: error("Invalid pet rows")
                require(values.size in 1..12)
                val rows = values.map { value ->
                    val row = Message.create(
                        "row",
                        value as? JsonObject ?: error("Invalid pet row")
                    )
                    val label = requireNotNull(row.string("name"))
                    val index = requireNotNull(row.int("index"))
                    val frames = requireNotNull(row.int("frames"))
                    require(
                        label in names && index in 0 until height / 208 &&
                            frames in 1..minOf(6, width / 192)
                    )
                    if (kind ==
                        "companion"
                    ) {
                        require(label in setOf("listening", "talking", "sleeping"))
                    }
                    if (label == "talking") require(frames == 3)
                    PetRow(label, index, frames, row.field("overlay")?.let(PetOverlay::parse))
                }
                require(
                    rows.map { it.name }.distinct().size == rows.size &&
                        rows.map { it.index }.distinct().size == rows.size
                )
                if (kind == "base") require(rows.any { it.name == "idle" })
                PetSheet(kind, hash, bytes, width, height, rows)
            }
            require(
                sheets.map { it.kind }.distinct().size == sheets.size &&
                    sheets.any { it.kind == "base" }
            )
            return PetManifest(name, loop, sheets, message.encode())
        }
    }
}

sealed interface PetEffect {
    class Manifest(val value: PetManifest) : PetEffect
    class Asset(val sheet: PetSheet, bytes: ByteArray) : PetEffect {
        val bytes = bytes.copyOf()
        override fun toString(): String = "PetAsset(redacted)"
    }
}

class PetTransfers {
    private class Transfer(val sheet: PetSheet, var at: Long) {
        val bytes = ByteArrayOutputStream()
        var sequence = 0
    }
    private var manifest: PetManifest? = null
    private val transfers = HashMap<Int, Transfer>()
    fun message(message: Message, now: Long): List<PetEffect> {
        return try {
            when (message.type) {
                "pet.manifest" -> {
                    val parsed = PetManifest.parse(message)
                    clear()
                    manifest = parsed
                    listOf(PetEffect.Manifest(parsed))
                }
                "asset.start" -> {
                    val stream = requireNotNull(message.int("stream"))
                    require(stream in 1..255 && transfers.size < 2 && stream !in transfers)
                    val sheet = requireNotNull(
                        manifest?.sheets?.singleOrNull {
                            it.sha256 ==
                                message.string("sha256")
                        }
                    )
                    require(
                        message.int("bytes") == sheet.bytes && message.string("format") == "png"
                    )
                    transfers[stream] = Transfer(sheet, now)
                    emptyList()
                }
                "asset.end" -> {
                    val transfer = transfers.remove(message.int("stream")) ?: return emptyList()
                    val bytes = transfer.bytes.toByteArray()
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                    require(bytes.size == transfer.sheet.bytes && hash == transfer.sheet.sha256)
                    listOf(PetEffect.Asset(transfer.sheet, bytes))
                }
                "asset.abort" -> {
                    transfers.remove(message.int("stream"))
                    emptyList()
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            clear()
            emptyList()
        }
    }

    fun binary(frame: BinaryFrame, now: Long): List<PetEffect> {
        if (frame.channel != 4) return emptyList()
        val transfer = transfers[frame.stream] ?: return emptyList()
        val bytes = frame.payload
        if (frame.sequence != transfer.sequence || bytes.size !in 1..4096 ||
            transfer.bytes.size() + bytes.size > transfer.sheet.bytes
        ) {
            transfers.remove(frame.stream)
        } else {
            transfer.bytes.write(bytes)
            transfer.sequence = (transfer.sequence + 1) and 65535
            transfer.at = now
        }
        return emptyList()
    }
    fun tick(now: Long) {
        transfers.entries.removeAll { now - it.value.at >= 15000 }
    }
    fun clear() {
        transfers.clear()
        manifest = null
    }
}

object PetStates {
    fun row(mode: String, available: Set<String>): String {
        val choices = when (mode) {
            "listening" -> listOf("listening", "review", "idle")
            "talking" -> listOf("talking", "idle")
            "sleeping" -> listOf("sleeping", "idle")
            "waving" -> listOf("waving", "wave", "idle")
            "jumping" -> listOf("jumping", "jump", "idle")
            "running" -> listOf("running", "run", "idle")
            "waiting" -> listOf("waiting", "review", "idle")
            else -> listOf(mode, "idle")
        }
        return choices.firstOrNull { it in available } ?: "idle"
    }
    fun mouth(level: Float, previous: Int): Int = when {
        level >= if (previous == 2) 0.12f else 0.18f -> 2
        level >= if (previous >= 1) 0.03f else 0.06f -> 1
        else -> 0
    }
}
