package dev.quantumink.hermesgadget.protocol.relay.state

import java.security.MessageDigest

/** Lowercase hex SHA-256 of the UTF-8 bytes of [text]. Used to compare fragments and cursors without keeping them. */
internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(64)
    for (byte in digest) {
        out.append(HEX[(byte.toInt() shr 4) and 0xf]).append(HEX[byte.toInt() and 0xf])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
