package dev.quantumink.hermesgadget.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Identity material must be encrypted by the platform storage layer and never logged. */
class DeviceIdentity(key: ByteArray) {
    private val key = key.copyOf().also { require(it.size == KEY_BYTES) }

    val deviceId: String = "hg-" + MessageDigest.getInstance("SHA-256").digest(this.key)
        .take(8).joinToString("") { "%02x".format(it) }

    fun enrollmentKey(): String = Base64.getEncoder().encodeToString(key)

    fun authMac(nonce: String): String = mac("hermes-gadget/v1|$deviceId|$nonce")

    fun otaMac(nonce: String, sha256: String, size: Long): String {
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(size >= 0)
        return mac("hermes-gadget/v1|ota|$deviceId|$nonce|$sha256|$size")
    }

    private fun mac(message: String): String {
        val algorithm = Mac.getInstance("HmacSHA256")
        algorithm.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            algorithm.doFinal(message.toByteArray(Charsets.UTF_8))
        )
    }

    override fun toString(): String = "DeviceIdentity(redacted)"

    companion object {
        const val KEY_BYTES = 32

        fun generate(): DeviceIdentity =
            DeviceIdentity(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes))

        fun fromBase64(encoded: String): DeviceIdentity? {
            if (!encoded.matches(Regex("[A-Za-z0-9+/]{43}="))) return null
            return try {
                val key = Base64.getDecoder().decode(encoded)
                if (key.size == KEY_BYTES) DeviceIdentity(key) else null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
