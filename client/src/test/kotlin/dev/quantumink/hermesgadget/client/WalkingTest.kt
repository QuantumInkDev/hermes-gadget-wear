package dev.quantumink.hermesgadget.client

import android.media.AudioDeviceInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingTest {
    @Test fun firstPartIsAtMostTwoShortSentencesAndTheRestStaysInOrder() {
        val reply = "It is sunny. Take water. The trail is 3.5 km long. " +
            "Rain starts after 6 p.m. today. Enjoy it!"
        val parts = WalkingSpeech.parts(reply)
        assertEquals("It is sunny. Take water.", parts.first())
        assertEquals(
            "The trail is 3.5 km long. Rain starts after 6 p.m. today. Enjoy it!",
            parts.drop(1).joinToString(" ")
        )
        assertTrue(parts.first().length <= WalkingSpeech.FIRST_LIMIT)
        assertTrue(parts.drop(1).all { it.length <= WalkingSpeech.NEXT_LIMIT })
    }

    @Test fun abbreviationsDecimalsAndUrlsDoNotEndASentence() {
        assertEquals(
            listOf("Use e.g. the north path, see link for 2.5 hours."),
            WalkingSpeech.sentences(
                "Use e.g. the north path, see https://example.test/a.b for 2.5 hours."
            )
        )
    }

    @Test fun markdownAndCodeAreNotReadButListItemsAre() {
        val reply = "# Plan\n\n- **Buy** milk\n- Call [Sam](https://x.test)\n" +
            "```kotlin\nval secret = 1\n```\nDone."
        assertEquals(
            listOf("Plan.", "Buy milk.", "Call Sam.", "Done."),
            WalkingSpeech.sentences(reply)
        )
    }

    @Test fun codeOnlyOrEmptyReplyHasNothingToSpeak() {
        assertTrue(WalkingSpeech.parts("```\nprintln()\n```").isEmpty())
        assertTrue(WalkingSpeech.parts("  \n--- \n").isEmpty())
    }

    @Test fun overlongAndUnpunctuatedTextIsCutAtWordBoundaries() {
        val words = List(120) { "word$it" }.joinToString(" ")
        val parts = WalkingSpeech.parts(words)
        assertTrue(parts.size > 1)
        assertTrue(parts.first().length <= WalkingSpeech.FIRST_LIMIT)
        assertTrue(parts.all { it.length <= WalkingSpeech.NEXT_LIMIT })
        assertEquals(words + ".", parts.joinToString(" "))
    }

    @Test fun cjkPunctuationSplitsWithoutSpaces() {
        assertEquals(listOf("今日は晴れ。", "水を持って。"), WalkingSpeech.sentences("今日は晴れ。水を持って。"))
    }

    @Test fun longUnbrokenTextNeverSplitsASurrogatePair() {
        val text = "a😀".repeat(150)
        WalkingSpeech.parts(text).forEach { part ->
            assertFalse(Character.isHighSurrogate(part.last { it != '.' }))
        }
    }

    @Test fun walkingReplyAdvancesWithDistinctValidSpeechIds() {
        val id = "a".repeat(64)
        val reply = WalkingReply(id, "voice", listOf("One.", "Two.", "Three."))
        assertEquals(id to "One.", reply.next())
        val second = reply.next()!!
        assertEquals("Two.", second.second)
        assertTrue(second.first.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        assertTrue(reply.hasMore)
        assertEquals("Three.", reply.next()!!.second)
        assertFalse(reply.hasMore)
        assertNull(reply.next())
    }

    @Test fun recorderKeepsOnlyACompletedCopyOfTheCurrentStream() {
        val recorder = ReplayRecorder()
        val header = byteArrayOf(9)
        recorder.start(16000, header)
        val packet = byteArrayOf(1, 2)
        recorder.offer(packet)
        packet[0] = 7
        assertNull(recorder.clip)
        val clip = recorder.finish()!!
        assertSame(clip, recorder.clip)
        assertArrayEquals(byteArrayOf(1, 2), clip.packets.single())
        assertArrayEquals(header, clip.header)
        recorder.start(16000, null)
        assertNull("A new stream supersedes the old clip", recorder.clip)
        assertNull("An empty stream has nothing to replay", recorder.finish())
    }

    @Test fun recorderOverflowDiscardsThePartialClip() {
        val recorder = ReplayRecorder(maxBytes = 4, maxPackets = 8)
        recorder.start(16000, null)
        recorder.offer(ByteArray(3))
        recorder.offer(ByteArray(3))
        assertNull(recorder.finish())
        recorder.offer(ByteArray(1))
        assertNull("Late packets after overflow are not recorded", recorder.finish())
    }

    @Test fun clearForgetsTheClipAndIgnoresLatePackets() {
        val recorder = ReplayRecorder()
        recorder.start(16000, null)
        recorder.offer(ByteArray(2))
        recorder.finish()
        recorder.clear()
        assertNull(recorder.clip)
        recorder.offer(ByteArray(2))
        assertNull(recorder.finish())
    }

    @Test fun routePrefersBluetoothThenHeadphonesThenOptionalSpeaker() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val ble = AudioDeviceInfo.TYPE_BLE_HEADSET
        assertNull(preferredOutputIndex(PlaybackRoute.SYSTEM, listOf(speaker, a2dp), 37))
        assertEquals(
            2,
            preferredOutputIndex(PlaybackRoute.HEADPHONES_FIRST, listOf(speaker, wired, a2dp), 37)
        )
        assertEquals(
            1,
            preferredOutputIndex(PlaybackRoute.HEADPHONES_FIRST, listOf(speaker, wired), 37)
        )
        assertEquals(
            1,
            preferredOutputIndex(PlaybackRoute.HEADPHONES_THEN_SPEAKER, listOf(sco, ble), 33)
        )
        assertNull(
            "Phone keeps its normal route",
            preferredOutputIndex(PlaybackRoute.HEADPHONES_FIRST, listOf(speaker, sco), 37)
        )
        assertEquals(
            "Watch falls back to its speaker, never SCO",
            1,
            preferredOutputIndex(PlaybackRoute.HEADPHONES_THEN_SPEAKER, listOf(sco, speaker), 33)
        )
        assertNull(preferredOutputIndex(PlaybackRoute.HEADPHONES_THEN_SPEAKER, listOf(sco), 33))
    }
}
