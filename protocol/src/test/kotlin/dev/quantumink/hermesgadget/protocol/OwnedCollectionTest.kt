package dev.quantumink.hermesgadget.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A caller collection may return its own backing array from toArray(). Snapshot lists must still
 * be owned copies, independent of how the runtime's ArrayList(Collection) treats that array.
 */
class OwnedCollectionTest {
    private class ExposingList<T>(private val items: Array<Any?>) : java.util.AbstractList<T>() {
        override val size: Int get() = items.size

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): T = items[index] as T
        override fun toArray(): Array<Any?> = items
    }

    @Test fun widgetSlotsDoNotAliasAnArrayExposedByTheCallerCollection() {
        val backing = arrayOf<Any?>("box:one", null, null, null)
        val widget = MigrationWidget(
            "00000000-0000-4000-8000-000000000001",
            MigrationWidgetPreset.FOUR,
            ExposingList(backing)
        )
        backing[0] = "box:two"
        backing[1] = "box:two"
        assertEquals(listOf("box:one", null, null, null), widget.slots)
    }

    @Test fun snapshotListsDoNotAliasAnArrayExposedByTheCallerCollection() {
        val backing = arrayOf<Any?>("manual")
        val snapshot = MigrationSnapshot(sourceOrder = ExposingList(backing))
        backing[0] = "elsewhere"
        assertEquals(listOf("manual"), snapshot.sourceOrder)
        assertEquals(snapshot, MigrationSnapshot())
    }
}
