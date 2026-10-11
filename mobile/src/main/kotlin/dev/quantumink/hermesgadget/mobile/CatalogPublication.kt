package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogPage
import dev.quantumink.hermesgadget.protocol.ProfileCatalog

internal data class CatalogDataItem(val owner: String, val path: String)

internal interface CatalogPublicationTransport {
    val owner: String
    fun portrait(hash: String, bytes: ByteArray)
    fun pages(json: String)
    fun items(): List<CatalogDataItem>
    fun delete(item: CatalogDataItem)
    fun finishCleanup() { }
}

internal object CatalogPublication {
    fun publish(
        pages: List<CatalogPage>,
        read: (String) -> ByteArray?,
        transport: CatalogPublicationTransport
    ) {
        val metadata = ProfileCatalog.encodePages(pages)
        val hashes = pages.mapNotNull { it.profile.portrait?.sha256 }.toSet()
        hashes.forEach { hash -> read(hash)?.let { transport.portrait(hash, it) } }
        transport.pages(metadata)
        val prefix = ProfileCatalog.PORTRAIT_PATH
        transport.items().filter { item ->
            item.owner == transport.owner && item.path.startsWith(prefix) &&
                item.path.removePrefix(prefix).matches(Regex("[a-f0-9]{64}")) &&
                item.path.removePrefix(prefix) !in hashes
        }.forEach(transport::delete)
        transport.finishCleanup()
    }
}
