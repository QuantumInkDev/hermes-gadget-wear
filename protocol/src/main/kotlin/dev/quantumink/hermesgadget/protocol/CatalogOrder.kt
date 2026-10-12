package dev.quantumink.hermesgadget.protocol

/** Owner order is independent of names, availability and conversation enrollment. */
object CatalogOrder {
    /** Migrates an interleaved roster using first source occurrence and existing local order. */
    fun group(pages: List<CatalogPage>): List<List<CatalogPage>> =
        pages.groupBy { it.sourceId }.values.toList()

    fun moveProfile(pages: List<CatalogPage>, key: String, delta: Int): List<CatalogPage> {
        val groups = group(pages).map { it.toMutableList() }
        val source = requireNotNull(groups.singleOrNull { group -> group.any { it.key == key } }) {
            "Unknown profile."
        }
        val index = source.indexOfFirst { it.key == key }
        val target = target(index, delta, source.lastIndex)
        source.add(target, source.removeAt(index))
        return groups.flatten()
    }

    fun moveSource(pages: List<CatalogPage>, id: String, delta: Int): List<CatalogPage> {
        val groups = group(pages).toMutableList()
        val index = groups.indexOfFirst { it.first().sourceId == id }
        require(index >= 0) {
            "Unknown box."
        }
        val target = target(index, delta, groups.lastIndex)
        groups.add(target, groups.removeAt(index))
        return groups.flatten()
    }

    private fun target(index: Int, delta: Int, lastIndex: Int): Int =
        (index.toLong() + delta).coerceIn(0, lastIndex.toLong()).toInt()
}
