package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface PhoneIdentityLookup {
    class Known(val identity: DeviceIdentity) : PhoneIdentityLookup
    data object Absent : PhoneIdentityLookup
    data object Refused : PhoneIdentityLookup
    companion object {
        /** Non-mutating: no AtomicFile recovery, key generation, marker repair or enrollment. */
        fun read(directory: File, id: String, existingKey: () -> SecretKey): PhoneIdentityLookup {
            SetupTransferGate.ensureRecovered()
            require(id.matches(Regex("[0-9a-f]{64}")))
            if (!directory.exists()) return Absent
            return try {
                require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
                val names = listOf("identity-$id", "known-$id")
                require(
                    names.none { name ->
                        listOf(".bak", ".new").any { File(directory, name + it).exists() }
                    }
                )
                if (names.none { File(directory, it).exists() }) return Absent
                require(
                    names.all {
                        File(directory, it).isFile &&
                            !Files.isSymbolicLink(File(directory, it).toPath())
                    }
                )
                val key = existingKey()
                fun read(name: String): ByteArray {
                    val file = File(directory, name)
                    require(file.length() in 30..286)
                    val raw = file.inputStream().use { input ->
                        ByteArray(file.length().toInt()).also { bytes ->
                            var at = 0
                            while (at <
                                bytes.size
                            ) {
                                val n = input.read(bytes, at, bytes.size - at)
                                require(n > 0)
                                at += n
                            }
                            require(input.read() == -1)
                        }
                    }
                    return try {
                        require(raw[0] == 1.toByte() && raw[1] == 12.toByte())
                        Cipher.getInstance("AES/GCM/NoPadding").run {
                            init(
                                Cipher.DECRYPT_MODE,
                                key,
                                GCMParameterSpec(128, raw.copyOfRange(2, 14))
                            )
                            updateAAD((name + "|phone-v1").toByteArray())
                            doFinal(raw, 14, raw.size - 14)
                        }
                    } finally {
                        raw.fill(0)
                    }
                }
                read(names[1]).let {
                    try {
                        require(it.contentEquals(byteArrayOf(1)))
                    } finally {
                        it.fill(0)
                    }
                }
                val bytes = read(names[0])
                try {
                    Known(requireNotNull(DeviceIdentity.fromBase64(bytes.toString(Charsets.UTF_8))))
                } finally {
                    bytes.fill(0)
                }
            } catch (_: Exception) {
                Refused
            }
        }
    }
}
