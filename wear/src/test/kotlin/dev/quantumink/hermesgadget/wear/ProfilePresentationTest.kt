package dev.quantumink.hermesgadget.wear

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresentationTest {
    private fun file() = File(Files.createTempDirectory("presentation").toFile(), "modes")
    private val alpha = PresentationKey.of("box:alpha", "ws://localhost:8765")
    private val beta = PresentationKey.of("box:beta", "ws://localhost:8766")

    @Test fun choicesSurviveRestartWithoutCrossingProfileOrEndpoint() {
        val file = file()
        val store = PresentationStore(file)
        assertEquals(PresentationMode.PORTRAIT, store.read()[alpha])
        store.set(alpha, PresentationMode.PET)
        val restored = PresentationStore(file).read()
        assertEquals(PresentationMode.PET, restored[alpha])
        assertEquals(PresentationMode.PORTRAIT, restored[beta])
        assertEquals(
            PresentationMode.PORTRAIT,
            restored[PresentationKey.of("box:alpha", "ws://localhost:9999")]
        )
        assertEquals(PresentationMode.PORTRAIT, restored[PresentationKey.of("box:alpha", null)])
        store.set(beta, PresentationMode.PET)
        store.set(alpha, PresentationMode.PORTRAIT)
        assertEquals(PresentationMode.PET, PresentationStore(file).read()[beta])
    }

    @Test fun corruptAndUnreadableStoresPreserveBytesAndRefuseWrites() {
        val file = file()
        file.writeText("unreadable version or corrupt payload")
        val bytes = file.readBytes()
        val store = PresentationStore(file)
        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.set(alpha, PresentationMode.PET) }
        assertArrayEquals(bytes, file.readBytes())
        val directory = file()
        directory.mkdir()
        assertThrows(IOException::class.java) {
            PresentationStore(directory).set(alpha, PresentationMode.PET)
        }
        assertTrue(directory.isDirectory)
    }

    @Test fun failedAtomicReplacementDoesNotPublishOrDestroyLastSavedChoice() {
        val file = file()
        PresentationStore(file).set(alpha, PresentationMode.PET)
        val bytes = file.readBytes()
        val store = PresentationStore(file) { _, _ -> throw IOException("synthetic failure") }
        assertThrows(IOException::class.java) { store.set(alpha, PresentationMode.PORTRAIT) }
        assertEquals(PresentationMode.PET, store.read()[alpha])
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(
            PresentationMode.PORTRAIT,
            PresentationStore(file).set(alpha, PresentationMode.PORTRAIT)[alpha]
        )
    }

    @Test fun capacityRetainsOfflineChoicesAndAllowsExistingUpdates() {
        val file = file()
        val store = PresentationStore(file)
        repeat(64) { store.set(PresentationKey.of("page:$it", null), PresentationMode.PET) }
        val bytes = file.readBytes()
        assertThrows(IOException::class.java) { store.set(alpha, PresentationMode.PET) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(
            PresentationMode.PORTRAIT,
            store.set(
                PresentationKey.of("page:0", null),
                PresentationMode.PORTRAIT
            )[PresentationKey.of("page:0", null)]
        )
        assertEquals(
            PresentationMode.PET,
            PresentationStore(file).read()[PresentationKey.of("page:63", null)]
        )
    }

    @Test fun oversizedDuplicateAndUnknownStoredEntriesAreRejectedWithoutReplacement() {
        val file = file()
        val store = PresentationStore(file)
        store.set(alpha, PresentationMode.PET)
        val lines = file.readLines()
        val header = lines.first() + "\n"
        val row = lines.last() + "\n"
        for (invalid in listOf(
            header + row.repeat(65),
            header + row.repeat(2),
            header + row.replace("PET", "UNKNOWN"),
            "x".repeat(8193)
        )) {
            file.writeText(invalid)
            assertThrows(IOException::class.java) { store.set(beta, PresentationMode.PET) }
            assertEquals(invalid, file.readText())
        }
    }

    @Test fun abandonedStagingFileCannotAccumulateOrChangeCommittedChoices() {
        val file = file()
        File(file.path + ".new").writeText("incomplete synthetic write")
        val store = PresentationStore(file)
        assertEquals(PresentationMode.PORTRAIT, store.read()[alpha])
        store.set(alpha, PresentationMode.PET)
        assertEquals(PresentationMode.PET, PresentationStore(file).read()[alpha])
        assertEquals(listOf("modes"), requireNotNull(file.parentFile).list().orEmpty().toList())
    }

    @Test fun identityAndStorageNeverExposeProfileKeysOrEndpoints() {
        val file = file()
        PresentationStore(file).set(alpha, PresentationMode.PET)
        assertFalse(alpha.toString().contains("alpha"))
        assertFalse(file.readText().contains("localhost"))
        assertFalse(file.readText().contains("box:"))
    }
}
