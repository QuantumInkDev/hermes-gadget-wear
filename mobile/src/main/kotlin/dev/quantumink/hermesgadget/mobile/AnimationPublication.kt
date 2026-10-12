package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.GifPortrait
import dev.quantumink.hermesgadget.protocol.PortraitAnimations
import java.security.MessageDigest

internal interface AnimationPublicationTransport {
    val owner: String
    fun asset(hash: String, bytes: ByteArray)
    fun manifest(json: String)
    fun items(): List<CatalogDataItem>
    fun delete(item: CatalogDataItem)
    fun finishCleanup() { }
}

internal object AnimationPublication {
    fun publish(
        pages: List<CatalogPage>,
        records: Map<String, AnimationDescriptor>,
        read: (AnimationDescriptor) -> ByteArray?,
        transport: AnimationPublicationTransport
    ) {
        val valid = linkedMapOf<String, AnimationDescriptor>()
        pages.forEach { page ->
            val descriptor = records[page.key] ?: return@forEach
            if (page.status == CatalogStatus.WITHDRAWN ||
                page.profile.portrait?.sha256 != descriptor.fallbackSha256
            ) {
                return@forEach
            }
            val bytes = read(descriptor) ?: return@forEach
            val checked = runCatching {
                require(bytes.size == descriptor.bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                require(hash == descriptor.sha256)
                GifPortrait.validate(bytes)
            }.isSuccess
            if (checked) {
                transport.asset(descriptor.sha256, bytes)
                valid[page.key] = descriptor
            }
        }
        transport.manifest(PortraitAnimations.encode(valid))
        val keep = valid.values.map { it.sha256 }.toSet()
        val prefix = PortraitAnimations.ASSET_PATH
        transport.items().filter { item ->
            item.owner == transport.owner && item.path.startsWith(prefix) &&
                item.path.removePrefix(prefix).matches(Regex("[a-f0-9]{64}")) &&
                item.path.removePrefix(prefix) !in keep
        }.forEach(transport::delete)
        transport.finishCleanup()
    }
}
