package dev.quantumink.hermesgadget.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLevelHistoryTest {
    @Test fun historyRetainsOnlyTheNewestNormalizedLevels() {
        val history = AudioLevelHistory(3)
        listOf(.2f, .4f, 2f, Float.NaN, -.5f).forEach(history::add)
        assertEquals(listOf(1f, 0f, 0f), history.snapshot())
        history.clear()
        assertEquals(emptyList<Float>(), history.snapshot())
    }

    @Test fun silenceDoesNotFabricateAnActiveSignal() {
        val history = AudioLevelHistory(4)
        repeat(20) { history.add(0f) }
        assertEquals(listOf(0f, 0f, 0f, 0f), history.snapshot())
    }
}
