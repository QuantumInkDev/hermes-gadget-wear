package dev.quantumink.hermesgadget.protocol

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** An address is never printed by diagnostics. Cleartext always requires two explicit choices. */
class Endpoint private constructor(
    val url: String,
    val host: String,
    val privateNetwork: Boolean,
    val cleartextAccepted: Boolean
) {
    val isTls: Boolean get() = url.startsWith("wss:")

    override fun toString(): String = "Endpoint(address=redacted, tls=" + isTls + ")"

    companion object {
        fun parse(
            input: String,
            privateNetwork: Boolean = false,
            cleartextAccepted: Boolean = false
        ): Endpoint {
            require(input.length in 1..2048) { "Enter a WebSocket URL." }
            val uri = try {
                URI(input.trim())
            } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid WebSocket URL.")
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "ws" || scheme == "wss") { "Use ws or wss." }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Keep credentials outside the URL; query strings and fragments are unsupported."
            }
            val host = uri.host?.removeSurrounding("[", "]")?.lowercase(Locale.ROOT)
            require(!host.isNullOrBlank() && '%' !in host) { "Enter a DNS name or IP address." }
            require(uri.port == -1 || uri.port in 1..65535) { "Enter a valid port." }
            if (scheme == "ws") {
                require(privateNetwork && cleartextAccepted) {
                    "Cleartext requires LAN/tailnet declaration and warning acceptance."
                }
                if (host.matches(Regex("[0-9.]+")) || ':' in host) {
                    val address = try {
                        InetAddress.getByName(host)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("Enter a valid IP address.")
                    }
                    require(PrivateAddresses.contains(address)) { "Public endpoints require wss." }
                }
            }
            val port = uri.port.takeUnless { it == -1 || it == if (scheme == "wss") 443 else 80 }
            val authority = (if (':' in host) "[" + host + "]" else host) +
                (port?.let { ":" + it } ?: "")
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            val canonical = URI(scheme + "://" + authority + path).normalize().toASCIIString()
            return Endpoint(canonical, host, privateNetwork, cleartextAccepted)
        }
    }
}

object PrivateAddresses {
    fun contains(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        if (address.isLoopbackAddress) return true
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
        return bytes.size == 16 &&
            ((bytes[0] and 254) == 252 || (bytes[0] == 254 && (bytes[1] and 192) == 128))
    }
}
