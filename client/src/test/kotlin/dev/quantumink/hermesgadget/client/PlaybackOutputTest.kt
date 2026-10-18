package dev.quantumink.hermesgadget.client

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOutputTest {
    @Test fun missingObservationIsUnknownAndOnlyBuiltinSpeakerClaimsSpeaker() {
        assertEquals(PlaybackOutput.UNKNOWN, playbackOutputForDeviceType(null, 29))
        assertEquals(
            PlaybackOutput.SPEAKER,
            playbackOutputForDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 29)
        )
        assertEquals(
            PlaybackOutput.OTHER,
            playbackOutputForDeviceType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, 29)
        )
        assertEquals(PlaybackOutput.OTHER, playbackOutputForDeviceType(-1, 37))
    }

    @Test fun supportedHeadsetsAreClassifiedWithoutDeviceIdentity() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID
        )) {
            assertEquals(PlaybackOutput.HEADPHONES, playbackOutputForDeviceType(type, 29))
        }
    }

    @Test fun bleHeadsetRequiresItsRuntimeApiAndBleSpeakerDoesNotClaimHeadphones() {
        assertEquals(
            PlaybackOutput.OTHER,
            playbackOutputForDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET, 29)
        )
        assertEquals(
            PlaybackOutput.HEADPHONES,
            playbackOutputForDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET, 31)
        )
        assertEquals(
            PlaybackOutput.OTHER,
            playbackOutputForDeviceType(AudioDeviceInfo.TYPE_BLE_SPEAKER, 37)
        )
    }

    @Test fun callbackVocabularyIsFixed() {
        assertEquals(
            listOf("UNKNOWN", "HEADPHONES", "SPEAKER", "OTHER"),
            PlaybackOutput.entries.map {
                it.name
            }
        )
    }
}
