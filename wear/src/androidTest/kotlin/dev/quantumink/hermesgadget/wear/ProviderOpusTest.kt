package dev.quantumink.hermesgadget.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.OggOpus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderOpusTest {
    @Test fun native48kOggPacketsDecodeThroughTheWatchPlatformCodec() {
        var decoder: OpusDecoder? = null
        var decoded = 0
        var count = 0
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                "client-speech.ogg"
            ).use { input ->
                OggOpus.read(input, { header ->
                    assertEquals(
                        48000,
                        ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    )
                    decoder = OpusDecoder(header)
                }, { packet ->
                    count++
                    requireNotNull(decoder).push(packet) { decoded += it.size }
                })
            }
            requireNotNull(decoder).finish { decoded += it.size }
            assertEquals(31, count)
            assertTrue(decoded in 55000..63000)
        } finally {
            decoder?.close()
        }
    }
}
