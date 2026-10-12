package dev.quantumink.hermesgadget.wear

import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.TilePinEdit
import dev.quantumink.hermesgadget.protocol.TilePinResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePinStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recreationStaleAndDuplicateReceiptsPreserveDurableRevision() {
        val file = File(context.cacheDir, "tile-store-${System.nanoTime()}")
        try {
            val store = TilePinStore(context, file)
            val edit = TilePinEdit(0, "remote", "fixture:a", listOf("fixture:a", "fixture:b"))
            val accepted = store.apply(edit, setOf("fixture:a", "fixture:b"))
            val recreated = TilePinStore(context, file)
            assertEquals(accepted.pins, recreated.read())
            val local = recreated.editLocal("fixture:b", listOf(null, "fixture:a"))
            val duplicate = TilePinStore(context, file).apply(edit, emptySet())
            assertTrue(duplicate is TilePinResult.Current)
            assertEquals(accepted.pins, duplicate.pins)
            assertEquals(local, store.read())
            assertTrue(
                store.apply(
                    edit.copy(requestId = "stale"),
                    setOf("fixture:a")
                ) is TilePinResult.Conflict
            )
            assertEquals(local, TilePinStore(context, file).read())
            assertTrue(file.length() in 1..131072)
        } finally {
            file.delete()
        }
    }

    @Test fun independentInstancesSerializeConcurrentLocalEdits() {
        val file = File(context.cacheDir, "tile-store-${System.nanoTime()}")
        val worker = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(20)
        try {
            repeat(20) {
                worker.execute {
                    try {
                        TilePinStore(context, file).editLocal(null, emptyList())
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(20L, TilePinStore(context, file).read().revision)
        } finally {
            worker.shutdownNow()
            file.delete()
        }
    }

    @Test fun interruptedAtomicWriteRecoversTheLastCommittedRevision() {
        val file = File(context.cacheDir, "tile-store-${System.nanoTime()}")
        val backup = File(file.path + ".bak")
        try {
            val expected = TilePinStore(context, file).editLocal("fixture:a", listOf("fixture:a"))
            file.copyTo(backup)
            file.writeText("interrupted write")
            assertEquals(expected, TilePinStore(context, file).read())
            assertTrue(!backup.exists())
        } finally {
            file.delete()
            backup.delete()
        }
    }

    @Test fun malformedAndOversizedStoreFailClosedWithoutResettingRevision() {
        val file = File(context.cacheDir, "tile-store-${System.nanoTime()}")
        try {
            for (bad in listOf("{}", "x".repeat(131073))) {
                file.writeText(bad)
                assertThrows(Exception::class.java) {
                    TilePinStore(context, file).editLocal(null, emptyList())
                }
                assertEquals(bad, file.readText())
            }
        } finally {
            file.delete()
        }
    }
}
