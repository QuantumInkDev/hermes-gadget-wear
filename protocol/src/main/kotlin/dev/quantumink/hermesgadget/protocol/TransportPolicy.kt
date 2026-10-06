package dev.quantumink.hermesgadget.protocol

enum class TransportPreference { AUTO, RELAY, DIRECT }
enum class TransportPath { DIRECT, RELAY }

object TransportPolicy {
    fun select(endpoint: Endpoint, preference: TransportPreference, phone: Boolean): TransportPath =
        when (preference) {
            TransportPreference.DIRECT -> TransportPath.DIRECT
            TransportPreference.RELAY -> {
                require(endpoint.isTls) { "Phone relay requires a wss endpoint." }
                require(phone) { "Phone companion is unavailable." }
                TransportPath.RELAY
            }
            TransportPreference.AUTO -> if (endpoint.isTls && phone) {
                TransportPath.RELAY
            } else {
                TransportPath.DIRECT
            }
        }

    fun canFallBack(
        preference: TransportPreference,
        path: TransportPath,
        state: ConnectionState
    ): Boolean = preference == TransportPreference.AUTO && path == TransportPath.RELAY &&
        state.status == ConnectionStatus.RETRYING && state.error in setOf(
            "Cannot reach the configured endpoint.",
            "Connection closed.",
            "Connection timed out."
        )
}
